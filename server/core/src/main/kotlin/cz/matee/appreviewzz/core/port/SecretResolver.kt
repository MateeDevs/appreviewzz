package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialOrigin
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.SecretPayload

/**
 * Rozbalení credentialu v okamžiku použití. Use-casy v jádru díky tomuhle portu nevědí nic
 * o KMS ani o šifrování — implementaci dodává vault (`server/crypto`).
 */
fun interface SecretResolver {
    fun resolve(
        orgId: OrganizationId,
        credentialId: CredentialId,
    ): SecretPayload
}

/**
 * Uložení a rotace credentialu. Proti [SecretResolver] navíc zapisuje, takže ho potřebuje
 * jen onboarding v consoli — worker si vystačí s rozbalením.
 *
 * Šifrování zůstává celé ve vaultu: jádro sem podá otevřený [SecretPayload] a zpátky dostane
 * metadata. **Payload z vaultu ven vyjde jedině přes [SecretResolver.resolve]**, aby se
 * nedopatřením nedal vrátit v odpovědi API.
 */
interface CredentialStore : SecretResolver {
    fun store(
        orgId: OrganizationId,
        type: CredentialType,
        label: String,
        payload: SecretPayload,
        hint: String? = null,
        origin: CredentialOrigin = CredentialOrigin.UPLOADED,
    ): CredentialMeta

    /** `null`, když credential v organizaci není. */
    fun replace(
        orgId: OrganizationId,
        credentialId: CredentialId,
        payload: SecretPayload,
        label: String? = null,
        hint: String? = null,
    ): CredentialMeta?

    fun load(
        orgId: OrganizationId,
        credentialId: CredentialId,
    ): SecretPayload
}
