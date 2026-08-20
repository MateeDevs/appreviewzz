package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.OrgDataKey
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.CredentialStore
import cz.matee.appreviewzz.core.port.DataKeyRepository
import cz.matee.appreviewzz.core.port.NewCredential
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Credential v databázi není, nebo patří jiné organizaci — z pohledu volajícího totéž. */
class CredentialNotFoundException(
    orgId: OrganizationId,
    id: CredentialId,
) : RuntimeException("Credential $id v organizaci $orgId neexistuje")

/**
 * Srdce produktu ([ADR 0005]): credentials klientů zašifrované tak, že dump databáze
 * bez přístupu ke KEK je bezcenný.
 *
 * - **DEK per organizace**, uložený jen zabalený; kompromitace jednoho rozbaleného klíče
 *   neodemyká data jiné organizace.
 * - **AAD = `org_id:credential_id:type`** — ciphertext je svázaný s řádkem, do kterého patří,
 *   takže ho nejde podstrčit jinam ani v rámci téže databáze.
 * - **Rozbalený DEK žije jen v paměti** a jen [dekCacheTtl]; cache existuje proto, aby ingest
 *   každých 30 minut per appka nedělal z KMS volání (a z CloudTrailu peklo).
 */
class CredentialVault(
    private val dataKeys: DataKeyRepository,
    private val credentials: CredentialRepository,
    private val kek: KekProvider,
    private val clock: Clock = Clock.System,
    private val dekCacheTtl: Duration = 5.minutes,
) : CredentialStore {
    private val cache = ConcurrentHashMap<DataKeyId, CachedDataKey>()

    /** Zašifruje a uloží nový credential. Vrací jen metadata — payload už z vaultu nevyjde. */
    override fun store(
        orgId: OrganizationId,
        type: CredentialType,
        label: String,
        payload: SecretPayload,
        hint: String?,
    ): CredentialMeta {
        val credentialId = CredentialId(Uuid.random())
        val dataKey = activeDataKey(orgId)
        val ciphertext =
            Aead.encrypt(
                key = unwrapped(dataKey),
                plaintext = payload.value.toByteArray(Charsets.UTF_8),
                associatedData = associatedData(orgId, credentialId, type),
            )
        return credentials.create(
            orgId,
            NewCredential(
                id = credentialId,
                type = type,
                label = label,
                dataKeyId = dataKey.id,
                ciphertext = ciphertext,
                fingerprint = payload.fingerprint(),
                hint = hint,
            ),
        )
    }

    /** Rotace obsahu (klient nahrál nový klíč). Vrací `null`, když credential neexistuje. */
    override fun replace(
        orgId: OrganizationId,
        credentialId: CredentialId,
        payload: SecretPayload,
        label: String?,
        hint: String?,
    ): CredentialMeta? {
        val current = credentials.findMeta(orgId, credentialId) ?: return null
        val dataKey = activeDataKey(orgId)
        val ciphertext =
            Aead.encrypt(
                key = unwrapped(dataKey),
                plaintext = payload.value.toByteArray(Charsets.UTF_8),
                associatedData = associatedData(orgId, credentialId, current.type),
            )
        return credentials.replacePayload(
            orgId,
            credentialId,
            NewCredential(
                id = credentialId,
                type = current.type,
                label = label ?: current.label,
                dataKeyId = dataKey.id,
                ciphertext = ciphertext,
                fingerprint = payload.fingerprint(),
                hint = hint ?: current.hint,
            ),
        )
    }

    /**
     * Dešifruje credential. Volá se výhradně ve workeru v okamžiku použití — návratovou
     * hodnotu nikam neukládej a nelogguj (proto je [SecretPayload] redigovaný).
     */
    override fun load(
        orgId: OrganizationId,
        credentialId: CredentialId,
    ): SecretPayload {
        val stored =
            credentials.loadForDecryption(orgId, credentialId)
                ?: throw CredentialNotFoundException(orgId, credentialId)
        val dataKey =
            dataKeys.findById(orgId, stored.dataKeyId)
                ?: throw KeyManagementException("Datový klíč ${stored.dataKeyId} organizace $orgId chybí")

        val plaintext =
            try {
                Aead.decrypt(
                    key = unwrapped(dataKey),
                    ciphertext = stored.ciphertext,
                    associatedData = associatedData(orgId, credentialId, stored.meta.type),
                )
            } catch (error: GeneralSecurityException) {
                // Sem se dostaneme, když ciphertext nepatří k téhle organizaci, credentialu
                // nebo typu — přesně to, proti čemu je AAD binding. Detail ven nepatří.
                throw KeyManagementException("Credential $credentialId nejde dešifrovat", error)
            }
        return SecretPayload(plaintext.toString(Charsets.UTF_8))
    }

    /** Port pro jádro: use-casy vidí jen „dej mi obsah credentialu", ne vault ani KMS. */
    override fun resolve(
        orgId: OrganizationId,
        credentialId: CredentialId,
    ): SecretPayload = load(orgId, credentialId)

    /**
     * Rotace datového klíče organizace: vyrobí nový DEK a přešifruje pod něj všechny
     * credentials. Dotýká se jedné organizace, ostatní běží dál beze změny.
     *
     * @return počet přešifrovaných credentials
     */
    fun rotateDataKey(orgId: OrganizationId): Int {
        val previous = dataKeys.findActive(orgId)
        val payloads =
            credentials.listByOrg(orgId).map { meta -> meta to load(orgId, meta.id) }

        val material = kek.generateDataKey()
        val newKey = dataKeys.create(orgId, kek.uri, material.wrapped, clock.now())
        cache[newKey.id] = CachedDataKey(material.plaintext, clock.now() + dekCacheTtl)

        payloads.forEach { (meta, payload) ->
            val ciphertext =
                Aead.encrypt(
                    key = material.plaintext,
                    plaintext = payload.value.toByteArray(Charsets.UTF_8),
                    associatedData = associatedData(orgId, meta.id, meta.type),
                )
            credentials.reencrypt(orgId, meta.id, newKey.id, ciphertext)
        }

        previous?.let { cache.remove(it.id) }
        logger.info { "Rotace DEK organizace $orgId: přešifrováno ${payloads.size} credentials" }
        return payloads.size
    }

    /** Vyhodí rozbalené klíče z paměti — pro testy a pro reakci na incident. */
    fun clearCache() = cache.clear()

    private fun activeDataKey(orgId: OrganizationId): OrgDataKey =
        dataKeys.findActive(orgId) ?: run {
            // První credential organizace: DEK vzniká až když je pro co, ne při založení org.
            val material = kek.generateDataKey()
            val created = dataKeys.create(orgId, kek.uri, material.wrapped, clock.now())
            cache[created.id] = CachedDataKey(material.plaintext, clock.now() + dekCacheTtl)
            logger.info { "Organizace $orgId dostala datový klíč ${created.id} (KEK ${kek.uri})" }
            created
        }

    private fun unwrapped(dataKey: OrgDataKey): ByteArray {
        val now = clock.now()
        val cached = cache[dataKey.id]
        if (cached != null && cached.expiresAt > now) return cached.key

        val plaintext = kek.unwrap(dataKey.wrappedDek)
        cache[dataKey.id] = CachedDataKey(plaintext, now + dekCacheTtl)
        return plaintext
    }

    private fun associatedData(
        orgId: OrganizationId,
        credentialId: CredentialId,
        type: CredentialType,
    ): ByteArray = "$orgId:$credentialId:$type".toByteArray(Charsets.UTF_8)

    private class CachedDataKey(
        val key: ByteArray,
        val expiresAt: Instant,
    )
}
