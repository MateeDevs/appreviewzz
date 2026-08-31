package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.AppDataKey
import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AppDataKeyRepository
import cz.matee.appreviewzz.core.port.PlatformSecretRepository
import cz.matee.appreviewzz.core.port.PlatformSecretVault
import cz.matee.appreviewzz.core.port.UserMfaRepository
import cz.matee.appreviewzz.core.port.UserSecretVault
import cz.matee.appreviewzz.core.usecase.TOTP_SECRET_PURPOSE
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Malý bratr [CredentialVault] pro tajemství, která nepatří žádné organizaci — TOTP seed
 * uživatele (F5.3) a konfigurační klíče platformy (F7.2).
 *
 * Stejná stavba a stejné důvody — DEK zabalený KEKem, otevřený jen v paměti a jen na chvíli,
 * AAD svazující ciphertext s řádkem. Rozdíl je v rozsahu klíče: **jeden na deployment**, ne
 * jeden na organizaci. Dělit ho po uživatelích by nic nepřidalo (na rozdíl od klientů si tu
 * data nemáme před kým chránit navzájem) a znamenalo by KMS volání na každé přihlášení.
 *
 * AAD je `user_id:purpose`, resp. `platform:klíč` — zapečetěné tajemství se tak nedá přesunout
 * k jinému uživateli, na jiný konfigurační klíč ani použít v jiné roli, a to i v případě, že
 * by někdo uměl zapisovat přímo do databáze.
 *
 * **Obě role v jedné třídě schválně:** sdílejí jeden aktivní DEK, takže rotace musí přešifrovat
 * obojí naráz. Dvě třídy by znamenaly dvě rotace, dva nové klíče a otázku, který z nich je
 * ten aktivní.
 */
class AppSecretBox(
    private val keys: AppDataKeyRepository,
    private val kek: KekProvider,
    /**
     * Potřebné jen kvůli rotaci — bez uložených tajemství se přešifrovat nedá nic.
     * `null` znamená instalaci, která rotaci neumí (a nepotřebuje: nikdo si ji nezapnul).
     */
    private val secrets: UserMfaRepository? = null,
    private val platformSecrets: PlatformSecretRepository? = null,
    private val clock: Clock = Clock.System,
    private val dekCacheTtl: Duration = 5.minutes,
) : UserSecretVault,
    PlatformSecretVault {
    private val cache = ConcurrentHashMap<Uuid, CachedKey>()

    override fun seal(
        userId: UserId,
        purpose: String,
        secret: SecretPayload,
    ): SealedSecret = seal(userAad(userId, purpose), secret)

    override fun open(
        userId: UserId,
        purpose: String,
        sealed: SealedSecret,
    ): SecretPayload = open(userAad(userId, purpose), sealed) { "Tajemství uživatele $userId nejde dešifrovat" }

    override fun seal(
        key: String,
        secret: SecretPayload,
    ): SealedSecret = seal(platformAad(key), secret)

    override fun open(
        key: String,
        sealed: SealedSecret,
    ): SecretPayload = open(platformAad(key), sealed) { "Platformní tajemství '$key' nejde dešifrovat" }

    /**
     * Rotace datového klíče: nový DEK a přešifrování **všech** uložených tajemství pod něj —
     * uživatelských i platformních. Protějšek [CredentialVault.rotateDataKey]; bez něj by
     * zůstala navěky pod prvním klíčem, který kdy vznikl.
     *
     * @return počet přešifrovaných tajemství
     */
    fun rotateDataKey(): Int {
        if (secrets == null && platformSecrets == null) {
            throw KeyManagementException("Rotace potřebuje přístup k úložišti tajemství")
        }
        // Otevřít se musí **před** výrobou nového klíče: potom už by starý nebyl aktivní.
        val openedUsers =
            secrets
                ?.listSealed()
                .orEmpty()
                .map { (userId, sealed) -> userId to open(userId, TOTP_SECRET_PURPOSE, sealed) }
        val openedPlatform =
            platformSecrets
                ?.listSealed()
                .orEmpty()
                .map { (key, sealed) -> key to open(key, sealed) }

        val material = kek.generateDataKey()
        val created = keys.create(kek.uri, material.wrapped, clock.now())
        cache[created.id] = CachedKey(material.plaintext, clock.now() + dekCacheTtl)

        openedUsers.forEach { (userId, secret) ->
            val ciphertext = encrypt(material.plaintext, userAad(userId, TOTP_SECRET_PURPOSE), secret)
            secrets?.reseal(userId, SealedSecret(created.id, ciphertext))
        }
        openedPlatform.forEach { (key, secret) ->
            val ciphertext = encrypt(material.plaintext, platformAad(key), secret)
            platformSecrets?.reseal(key, SealedSecret(created.id, ciphertext))
        }

        val total = openedUsers.size + openedPlatform.size
        logger.info {
            "Rotace klíče: přešifrováno ${openedUsers.size} uživatelských a ${openedPlatform.size} platformních tajemství"
        }
        return total
    }

    fun clearCache() = cache.clear()

    private fun seal(
        associatedData: ByteArray,
        secret: SecretPayload,
    ): SealedSecret {
        val key = activeKey()
        return SealedSecret(key.id, encrypt(unwrapped(key), associatedData, secret))
    }

    private fun open(
        associatedData: ByteArray,
        sealed: SealedSecret,
        onFailure: () -> String,
    ): SecretPayload {
        val key =
            keys.findById(sealed.dataKeyId)
                ?: throw KeyManagementException("Datový klíč ${sealed.dataKeyId} chybí")
        val plaintext =
            try {
                Aead.decrypt(
                    key = unwrapped(key),
                    ciphertext = sealed.ciphertext,
                    associatedData = associatedData,
                )
            } catch (error: GeneralSecurityException) {
                // Sem se dojde, když ciphertext patří jinému uživateli nebo jiné roli —
                // přesně to, proti čemu je AAD binding. Detail ven nepatří.
                throw KeyManagementException(onFailure(), error)
            }
        return SecretPayload(plaintext.toString(Charsets.UTF_8))
    }

    private fun encrypt(
        key: ByteArray,
        associatedData: ByteArray,
        secret: SecretPayload,
    ): ByteArray =
        Aead.encrypt(
            key = key,
            plaintext = secret.value.toByteArray(Charsets.UTF_8),
            associatedData = associatedData,
        )

    private fun activeKey(): AppDataKey =
        keys.findActive() ?: run {
            // Líně: instalace, kde si nikdo druhý faktor nezapne a nic nenastaví, klíč nemá.
            val material = kek.generateDataKey()
            val created = keys.create(kek.uri, material.wrapped, clock.now())
            cache[created.id] = CachedKey(material.plaintext, clock.now() + dekCacheTtl)
            logger.info { "Vznikl datový klíč ${created.id} pro tajemství mimo organizace (KEK ${kek.uri})" }
            created
        }

    private fun unwrapped(key: AppDataKey): ByteArray {
        val now = clock.now()
        cache[key.id]?.takeIf { it.expiresAt > now }?.let { return it.key }
        val plaintext = kek.unwrap(key.wrappedDek)
        cache[key.id] = CachedKey(plaintext, now + dekCacheTtl)
        return plaintext
    }

    private fun userAad(
        userId: UserId,
        purpose: String,
    ): ByteArray = "$userId:$purpose".toByteArray(Charsets.UTF_8)

    /**
     * Prefix `platform:` odděluje konfigurační klíče od uživatelských tajemství. Bez něj by
     * `user_id` a název klíče žily ve stejném prostoru a shoda by znamenala záměnu.
     */
    private fun platformAad(key: String): ByteArray = "platform:$key".toByteArray(Charsets.UTF_8)

    private class CachedKey(
        val key: ByteArray,
        val expiresAt: Instant,
    )
}
