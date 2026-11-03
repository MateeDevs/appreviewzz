package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SLACK_OAUTH_CALLBACK_PATH
import cz.matee.appreviewzz.channels.slack.SlackInstall
import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.slack.SlackOAuth
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.auditEntry
import cz.matee.appreviewzz.crypto.CredentialVault
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private val logger = KotlinLogging.logger {}

/**
 * Uložení instalace do vaultu. Druhá instalace téhož workspace přepíše payload existujícího
 * credentialu místo zakládání dalšího — jinak by po každém „Add to Slack" přibyl mrtvý token
 * a nikdo by nevěděl, který z nich platí.
 */
class SlackInstallStore(
    private val vault: CredentialVault,
    private val credentials: CredentialRepository,
    private val audit: AuditLogRepository,
) {
    fun save(
        orgId: OrganizationId,
        install: SlackInstall,
    ): CredentialMeta {
        val label = "Slack ${install.hint()}"
        val existing =
            credentials
                .listByOrg(orgId, CredentialType.SLACK_INSTALL)
                .firstOrNull { it.hint == install.hint() || it.hint == install.teamId || it.hint?.endsWith("(${install.teamId})") == true }

        val meta =
            if (existing == null) {
                vault.store(orgId, CredentialType.SLACK_INSTALL, label, install.payload(), install.hint())
            } else {
                vault.replace(orgId, existing.id, install.payload(), install.hint())
                    ?: vault.store(orgId, CredentialType.SLACK_INSTALL, label, install.payload(), install.hint())
            }

        audit.append(
            auditEntry(
                orgId = orgId,
                action = if (existing == null) "slack.installed" else "slack.reinstalled",
                actorType = ActorType.CHAT,
                actorLabel = install.hint(),
                targetType = "credential",
                targetId = meta.id.toString(),
                metadata = mapOf("team" to install.teamId, "scopes" to install.scopes.orEmpty()),
            ),
        )
        logger.info { "Slack App nainstalovaná pro organizaci $orgId do workspace ${install.teamId}" }
        return meta
    }
}

/**
 * „Add to Slack" a návrat z Slacku. Odkaz na `/slack/install` vydává seed CLI (ve F3 console)
 * a nese **podepsaný `state`** — jinak by šlo appku nainstalovat jménem cizí organizace.
 */
fun Application.slackInstallRoutes(
    oauth: SlackOAuth,
    states: SlackInstallStates,
    store: SlackInstallStore,
    redirectUri: String,
    replay: ReplayGuard = ReplayGuard("slack-install", ReplayGuard.INSTALL_RETENTION),
    consoleBaseUrl: String? = null,
) {
    routing {
        get(SLACK_INSTALL_PATH) {
            val state = call.request.queryParameters["state"]
            if (states.verify(state) == null) {
                // Prošlý odkaz je běžný případ (klient si ho otevřel za týden) — proto věta, ne chyba.
                call.respondText(
                    "Instalační odkaz je neplatný nebo mu vypršela platnost. Vygeneruj si prosím nový.",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            call.respondRedirect(oauth.authorizeUrl(state = state!!, redirectUri = redirectUri))
        }

        get(SLACK_OAUTH_CALLBACK_PATH) {
            val parameters = call.request.queryParameters
            val verified = states.verify(parameters["state"])
            val returnPath = verified?.returnPath
            if (verified != null && parameters["error"] != null) {
                if (returnPath != null) {
                    call.respondRedirect(slackReturnUrl(consoleBaseUrl, returnPath, "cancelled"))
                } else {
                    call.respondText("Instalace byla zrušena. Můžete ji spustit znovu v Appreviewzz.")
                }
                return@get
            }

            val code = parameters["code"]
            if (verified == null || code.isNullOrBlank()) {
                call.respondText("Instalaci se nepodařilo dokončit — zkus to prosím znovu.", status = HttpStatusCode.BadRequest)
                return@get
            }

            // `state` je podepsaný a má expiraci, ale sám o sobě je použitelný opakovaně:
            // dvě hodiny by stačily na to, aby ho někdo z historie prohlížeče použil ještě
            // jednou a připojil k cizí organizaci svůj workspace. Dokončit instalaci tedy jde
            // jen jednou; klient si pro druhý pokus vygeneruje nový odkaz.
            if (!replay.firstSighting(parameters["state"].orEmpty())) {
                logger.warn { "Instalace Slacku: state už byl jednou uplatněný" }
                call.respondText(
                    "Tenhle instalační odkaz už byl použitý. Vygeneruj si prosím nový.",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }

            try {
                val install = oauth.exchange(code, redirectUri)
                val credential = store.save(OrganizationId.parse(verified.orgId), install)
                if (returnPath != null) {
                    call.respondRedirect(slackReturnUrl(consoleBaseUrl, returnPath, "connected", credential.id.toString()))
                    return@get
                }
                call.respondText(
                    successPage(install.teamName ?: install.teamId),
                    contentType = ContentType.Text.Html,
                )
            } catch (error: ChannelException) {
                logger.warn { "Instalace Slacku selhala: ${error.message}" }
                if (returnPath != null) {
                    call.respondRedirect(slackReturnUrl(consoleBaseUrl, returnPath, "failed"))
                    return@get
                }
                call.respondText(
                    "Instalaci se nepodařilo dokončit. Zkuste ji znovu z Appreviewzz.",
                    status = HttpStatusCode.BadGateway,
                )
            }
        }
    }
}

internal fun slackReturnUrl(
    baseUrl: String?,
    path: String,
    status: String,
    credentialId: String? = null,
): String {
    require(SlackInstallStates.safeReturnPath(path))
    // Cíl pochází výhradně z podepsaného state a nakonfigurované adresy konzole.
    val url =
        URLBuilder("https://console.invalid$path")
            .apply {
                parameters.remove("slack")
                parameters.remove("slackCredential")
                parameters.append("slack", status)
                credentialId?.let { parameters.append("slackCredential", it) }
                fragment = "kanaly"
            }.buildString()
            .removePrefix("https://console.invalid")
    return baseUrl?.trimEnd('/')?.let { it + url } ?: url
}

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/** Prostá stránka bez závislostí — po instalaci se klient vrací do console (F3). */
private fun successPage(workspace: String): String =
    """
    <!doctype html>
    <html lang="cs">
      <head><meta charset="utf-8"><title>appreviewz — hotovo</title></head>
      <body style="font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto;">
        <h1>Hotovo</h1>
        <p>appreviewz je nainstalovaný ve workspace <strong>${escapeHtml(workspace)}</strong>.</p>
        <p>Teď zbývá vybrat kanál, do kterého mají recenze chodit.</p>
      </body>
    </html>
    """.trimIndent()

const val SLACK_INSTALL_PATH = "/slack/install"
