package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.CredentialId
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
