package cz.matee.appreviewzz.crypto

import java.nio.file.Path

/**
 * Výběr správce klíčů podle URI z konfigurace (`VAULT_KEK_URI`):
 *
 * - `aws-kms://arn:aws:kms:eu-central-1:…` — náš provoz
 * - `local://var/lib/appreviewzz/keyset` — self-host bez cloudu
 * - `vault://transit/appreviewzz` — zatím neimplementováno, hlásí to hned při startu
 */
object KekProviders {
    private const val LOCAL_SCHEME = "local://"
    private const val VAULT_SCHEME = "vault://"

    fun fromUri(uri: String): KekProvider =
        when {
            uri.startsWith(AwsKmsKekProvider.SCHEME) -> AwsKmsKekProvider.fromUri(uri)
            uri.startsWith(LOCAL_SCHEME) -> LocalKeysetKekProvider.openOrCreate(Path.of(uri.removePrefix(LOCAL_SCHEME)))
            uri.startsWith(VAULT_SCHEME) ->
                throw KeyManagementException("Vault transit provider zatím není implementovaný (URI '$uri')")
            else ->
                throw KeyManagementException(
                    "Neznámé VAULT_KEK_URI '$uri'; podporováno: ${AwsKmsKekProvider.SCHEME}, $LOCAL_SCHEME",
                )
        }
}
