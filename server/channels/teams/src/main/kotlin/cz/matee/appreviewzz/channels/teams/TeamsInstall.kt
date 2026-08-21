package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val installJson = Json { ignoreUnknownKeys = true }

/**
 * Instalace našeho bota v klientově Microsoft 365 tenantu — teamsový protějšek
 * [cz.matee.appreviewzz.channels.slack.SlackInstall].
 *
 * Zásadní rozdíl proti Slacku: **tajemství bota tady není.** `client_id`/`client_secret` jsou
 * app-level (jeden Azure Bot pro celý deployment, stejně jako je jeden Slack signing secret)
 * a berou se z konfigurace; ve vaultu leží jen to, co je per klient — do kterého tenantu je
 * bot nainstalovaný a přes který regionální endpoint se s ním mluví.
 *
 * Dnešní n8n má naproti tomu `client_secret` v plaintextu ve čtyřech nodech a tenant natvrdo
 * v URL, takže „přidat klienta" znamená editovat workflow.
 */
@Serializable
data class TeamsInstall(
    /** Microsoft Entra tenant ID klienta (`eeffd5e3-…`). */
    val tenantId: String,
    val tenantName: String? = null,
    /**
     * Regionální Bot Connector, který klientovi patří. Bere se z příchozí aktivity (`serviceUrl`)
     * — natvrdo `…/emea` jako v n8n by mimo Evropu tiše nefungovalo.
     */
    val serviceUrl: String = DEFAULT_SERVICE_URL,
    /** Team (skupina), do které je bot přidaný; jen pro popis v consoli. */
    val teamId: String? = null,
    val teamName: String? = null,
) {
    fun payload(): SecretPayload = SecretPayload(installJson.encodeToString(serializer(), this))

    /** Co se smí ukázat v consoli. Tenant ID tajemství není, ale patří k identitě instalace. */
    fun hint(): String =
        listOfNotNull(
            teamName?.takeIf { it.isNotBlank() },
            tenantName?.takeIf { it.isNotBlank() } ?: tenantId,
        ).joinToString(" · ")

    /** Adresa bez koncového lomítka — Bot Framework ho v cestě nesnáší. */
    fun connectorBaseUrl(): String = serviceUrl.trimEnd('/')

    companion object {
        /** Evropský Bot Connector; stejný default, jaký má dnešní n8n natvrdo v URL. */
        const val DEFAULT_SERVICE_URL = "https://smba.trafficmanager.net/emea"

        fun parse(payload: SecretPayload): TeamsInstall =
            try {
                installJson.decodeFromString(serializer(), payload.value)
            } catch (error: Exception) {
                // Payload se dá opravit jedině novým připojením — retry by opakoval totéž.
                throw ChannelException(
                    ChannelErrorKind.AUTH,
                    "Uložené připojení Teams nejde přečíst; klient musí bota připojit znovu",
                    error,
                )
            }
    }
}
