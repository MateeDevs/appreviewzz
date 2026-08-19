package cz.matee.appreviewzz.core.model

/**
 * Otevřený obsah credentialu — service account JSON, `.p8` klíč, Slack token.
 *
 * Existuje jen mezi vaultem a konektorem, který ho zrovna používá. Obalení do vlastního
 * typu má dva důvody: `toString()` je redigovaný (redakční filtr logů se nedá obejít
 * nedopatřením) a v signaturách je na první pohled vidět, kudy tajemství teče.
 */
@JvmInline
value class SecretPayload(
    val value: String,
) {
    override fun toString(): String = "SecretPayload(**redacted**, ${value.length} znaků)"

    /** Otisk pro zobrazení v consoli — pozná změnu klíče, obsah neprozradí. */
    fun fingerprint(): String = "sha256:" + sha256Hex(value).take(FINGERPRINT_LENGTH)

    companion object {
        private const val FINGERPRINT_LENGTH = 16
    }
}
