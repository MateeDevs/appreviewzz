package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val installJson = Json { ignoreUnknownKeys = true }

/**
 * Instalace naší Slack Appky v klientově workspace — to, co po OAuth zůstane a co se ukládá
 * zašifrované do vaultu.
 *
 * Proti dnešku je to zásadní změna provozu: n8n má pro každého klienta vlastního bota
 * s tokenem nalepeným v credential store, takže každý nový klient znamená ruční založení
 * appky. Tady je Slack App jedna a klient si ji nainstaluje sám.
 */
@Serializable
data class SlackInstall(
    /** `xoxb-…` token workspace. Jediná věc, kvůli které je celý payload tajemství. */
    val botToken: String,
    val teamId: String,
    val teamName: String? = null,
    val botUserId: String? = null,
    /** Scopes, které workspace opravdu schválil — podle nich se pozná, že chybí oprávnění. */
    val scopes: String? = null,
) {
    fun payload(): SecretPayload = SecretPayload(installJson.encodeToString(serializer(), this))

    /** Co se smí ukázat v consoli: název workspace, nikdy token. */
    fun hint(): String = teamName?.takeIf { it.isNotBlank() }?.let { "$it ($teamId)" } ?: teamId

    companion object {
        fun parse(payload: SecretPayload): SlackInstall =
            try {
                installJson.decodeFromString(serializer(), payload.value)
            } catch (error: Exception) {
                // Payload se dá opravit jedině novou instalací — retry by opakoval totéž.
                throw ChannelException(
                    ChannelErrorKind.AUTH,
                    "Uložená instalace Slacku nejde přečíst; klient musí appku nainstalovat znovu",
                    error,
                )
            }
    }
}
