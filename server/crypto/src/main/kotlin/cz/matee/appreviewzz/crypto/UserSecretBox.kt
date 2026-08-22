package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.AppDataKey
import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AppDataKeyRepository
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
 * Malý bratr [CredentialVault] pro tajemství, která patří uživateli, ne organizaci (F5.3).
 *
 * Stejná stavba a stejné důvody — DEK zabalený KEKem, otevřený jen v paměti a jen na chvíli,
 * AAD svazující ciphertext s řádkem. Rozdíl je v rozsahu klíče: **jeden na deployment**, ne
 * jeden na organizaci. Dělit ho po uživatelích by nic nepřidalo (na rozdíl od klientů si tu
 * data nemáme před kým chránit navzájem) a znamenalo by KMS volání na každé přihlášení.
 *
 * AAD je `user_id:purpose`, takže se zapečetěné tajemství nedá přesunout k jinému uživateli
 * ani použít v jiné roli — a to i v případě, že by někdo uměl zapisovat přímo do databáze.
 */
class UserSecretBox(
    private val keys: AppDataKeyRepository,
    private val kek: KekProvider,
    /**
     * Potřebné jen kvůli rotaci — bez uložených tajemství se přešifrovat nedá nic.
     * `null` znamená instalaci, která rotaci neumí (a nepotřebuje: nikdo si ji nezapnul).
     */
    private val secrets: UserMfaRepository? = null,
    private val clock: Clock = Clock.System,
    private val dekCacheTtl: Duration = 5.minutes,
) : UserSecretVault {
    private val cache = ConcurrentHashMap<Uuid, CachedKey>()

    override fun seal(
        userId: UserId,
        purpose: String,
        secret: SecretPayload,
    ): SealedSecret {
        val key = activeKey()
        val ciphertext =
            Aead.encrypt(
                key = unwrapped(key),
                plaintext = secret.value.toByteArray(Charsets.UTF_8),
                associatedData = associatedData(userId, purpose),
            )
        return SealedSecret(key.id, ciphertext)
    }

    override fun open(
        userId: UserId,
        purpose: String,
        sealed: SealedSecret,
    ): SecretPayload {
        val key =
            keys.findById(sealed.dataKeyId)
                ?: throw KeyManagementException("Datový klíč ${sealed.dataKeyId} chybí")
        val plaintext =
            try {
                Aead.decrypt(
                    key = unwrapped(key),
                    ciphertext = sealed.ciphertext,
                    associatedData = associatedData(userId, purpose),
                )
            } catch (error: GeneralSecurityException) {
                // Sem se dojde, když ciphertext patří jinému uživateli nebo jiné roli —
                // přesně to, proti čemu je AAD binding. Detail ven nepatří.
                throw KeyManagementException("Tajemství uživatele $userId nejde dešifrovat", error)
            }
        return SecretPayload(plaintext.toString(Charsets.UTF_8))
    }

    /**
     * Rotace datového klíče: nový DEK a přešifrování všech uložených tajemství pod něj.
     * Protějšek [CredentialVault.rotateDataKey] — bez něj by TOTP tajemství zůstala navěky
     * pod prvním klíčem, který kdy vznikl.
     *
     * @return počet přešifrovaných tajemství
     */
    fun rotateDataKey(): Int {
        val repository =
            secrets ?: throw KeyManagementException("Rotace uživatelských tajemství potřebuje přístup k jejich úložišti")
        // Otevřít se musí **před** výrobou nového klíče: potom už by starý nebyl aktivní.
        val opened = repository.listSealed().map { (userId, sealed) -> userId to open(userId, TOTP_SECRET_PURPOSE, sealed) }

        val material = kek.generateDataKey()
        val created = keys.create(kek.uri, material.wrapped, clock.now())
        cache[created.id] = CachedKey(material.plaintext, clock.now() + dekCacheTtl)

        opened.forEach { (userId, secret) ->
            val ciphertext =
                Aead.encrypt(
                    key = material.plaintext,
                    plaintext = secret.value.toByteArray(Charsets.UTF_8),
                    associatedData = associatedData(userId, TOTP_SECRET_PURPOSE),
                )
            repository.reseal(userId, SealedSecret(created.id, ciphertext))
        }
        logger.info { "Rotace klíče uživatelských tajemství: přešifrováno ${opened.size} položek" }
        return opened.size
    }

    fun clearCache() = cache.clear()

    private fun activeKey(): AppDataKey =
        keys.findActive() ?: run {
            // Líně: instalace, kde si nikdo druhý faktor nezapne, žádný klíč nemá.
            val material = kek.generateDataKey()
            val created = keys.create(kek.uri, material.wrapped, clock.now())
            cache[created.id] = CachedKey(material.plaintext, clock.now() + dekCacheTtl)
            logger.info { "Vznikl datový klíč ${created.id} pro uživatelská tajemství (KEK ${kek.uri})" }
            created
        }

    private fun unwrapped(key: AppDataKey): ByteArray {
        val now = clock.now()
        cache[key.id]?.takeIf { it.expiresAt > now }?.let { return it.key }
        val plaintext = kek.unwrap(key.wrappedDek)
        cache[key.id] = CachedKey(plaintext, now + dekCacheTtl)
        return plaintext
    }

    private fun associatedData(
        userId: UserId,
        purpose: String,
    ): ByteArray = "$userId:$purpose".toByteArray(Charsets.UTF_8)

    private class CachedKey(
        val key: ByteArray,
        val expiresAt: Instant,
    )
}
