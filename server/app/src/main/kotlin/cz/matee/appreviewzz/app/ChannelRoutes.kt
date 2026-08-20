package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackApi
import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.CredentialStore
import cz.matee.appreviewzz.core.port.auditEntry
import cz.matee.appreviewzz.core.usecase.ChannelDraft
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.OrgActor
import cz.matee.appreviewzz.core.usecase.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class CreateChannelRequest(
    val type: ChannelType = ChannelType.SLACK,
    /** ID kanálu ze Slacku (`C…`), ne jeho jméno — jméno se mění, ID ne. */
    val targetRef: String,
    val credentialId: String,
    val label: String? = null,
    val locale: String? = null,
    val deliverReviews: Boolean = true,
    val deliverRatings: Boolean = true,
)

@Serializable
data class ChannelResponse(
    val id: String,
    val type: ChannelType,
    val targetRef: String,
    val targetLabel: String?,
    val credentialId: String?,
    val locale: MessageLocale,
    val deliverReviews: Boolean,
    val deliverRatings: Boolean,
    val enabled: Boolean,
)

@Serializable
data class UpdateChannelRequest(
    val enabled: Boolean,
)

@Serializable
data class ChannelTestRequest(
    /** `null` = otestovat všechny kanály aplikace. */
    val channelId: String? = null,
)

@Serializable
data class ChannelCheckResponse(
    val channelId: String,
    val targetRef: String,
    val ok: Boolean,
    val error: String? = null,
    val hint: String? = null,
)

@Serializable
data class ConnectSlackRequest(
    /** Bot token workspace (`xoxb-…`). */
    val token: String,
    val label: String? = null,
)

@Serializable
data class SlackConnectionResponse(
    val credentialId: String,
    val workspace: String,
    val botUserId: String?,
    val scopes: String?,
    /** Scopes, které Slack App nemá, ale bez kterých doručování nefunguje. */
    val missingScopes: List<String>,
)

@Serializable
data class SlackInstallUrlResponse(
    val url: String,
    val validFor: String,
)

/**
 * Připojení Slacku z console.
 *
 * Dvě cesty schválně: „Add to Slack" (OAuth) pro klienty a ruční vložení bot tokenu pro
 * self-host a pro vlastní workspace, kde se appka instaluje přímo z api.slack.com.
 * Výsledek je v obou případech tentýž credential, takže se pak nic nepřepisuje.
 */
class ConsoleSlack(
    private val api: SlackApi,
    private val vault: CredentialStore,
    private val audit: AuditLogRepository,
    private val installStates: SlackInstallStates?,
    private val publicBaseUrl: String?,
) {
    suspend fun connect(
        organization: Organization,
        actor: OrgActor,
        token: SecretPayload,
        label: String?,
    ): SlackConnectionResponse {
        requireRole(actor, OrgRole.ADMIN)
        if (!token.value.startsWith("xoxb-")) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Čekám bot token workspace (začíná xoxb-), ne app-level ani user token",
            )
        }

        // Ověření hned při vkládání: špatný token se má poznat tady, ne až první recenzí.
        val install =
            try {
                api.authTest(token)
            } catch (error: ChannelException) {
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Slack token neuznal: ${error.message}")
            }

        val meta =
            vault.store(
                organization.id,
                CredentialType.SLACK_INSTALL,
                label?.takeIf { it.isNotBlank() } ?: "Slack ${install.hint()}",
                install.payload(),
                install.hint(),
            )
        audit.append(
            auditEntry(
                orgId = organization.id,
                action = "slack.installed",
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "credential",
                targetId = meta.id.toString(),
                metadata = mapOf("team" to install.teamId, "scopes" to install.scopes.orEmpty()),
            ),
        )

        val scopes = install.scopes.orEmpty().split(',')
        return SlackConnectionResponse(
            credentialId = meta.id.toString(),
            workspace = install.hint(),
            botUserId = install.botUserId,
            scopes = install.scopes,
            missingScopes = if (install.scopes == null) emptyList() else REQUIRED_SCOPES.filterNot { it in scopes },
        )
    }

    fun installUrl(
        organization: Organization,
        actor: OrgActor,
    ): SlackInstallUrlResponse {
        requireRole(actor, OrgRole.ADMIN)
        val states =
            installStates
                ?: throw ConsoleException(
                    ConsoleFailure.INVALID_INPUT,
                    "Instalace odkazem není nastavená (chybí SLACK_SIGNING_SECRET) — vlož bot token ručně",
                )
        val baseUrl =
            publicBaseUrl
                ?: throw ConsoleException(
                    ConsoleFailure.INVALID_INPUT,
                    "Instalace odkazem není nastavená (chybí PUBLIC_BASE_URL) — vlož bot token ručně",
                )
        val state = states.issue(organization.id.toString())
        return SlackInstallUrlResponse(
            url = "${baseUrl.trimEnd('/')}$SLACK_INSTALL_PATH?state=$state",
            validFor = SlackInstallStates.DEFAULT_VALIDITY.toString(),
        )
    }

    private companion object {
        /** Bez těchhle scopes se zpráva buď neodešle, nebo nepůjde přepsat po odeslání odpovědi. */
        val REQUIRED_SCOPES = listOf("chat:write")
    }
}

/**
 * Kanály a připojení Slacku (F3.4) — poslední krok onboardingu.
 *
 * `POST …/channels/test` je tu ta důležitá cesta: pošle do kanálu zkušební zprávu a rovnou
 * pojmenuje, co je špatně. Bez ní se odvolaný token nebo nepozvaný bot pozná až tím,
 * že první recenze nikam nedorazí.
 */
fun Route.channelRoutes(console: ConsoleWiring) {
    val channels = console.channels

    route("/orgs/{org}/apps/{app}/channels") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { channels.list(context.organization.id, call.appIdParam()).map { it.toResponse() } })
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<CreateChannelRequest>()
            val channel =
                io {
                    channels.add(
                        organization = context.organization,
                        actor = context.actor,
                        draft =
                            ChannelDraft(
                                appId = call.appIdParam(),
                                type = request.type,
                                targetRef = request.targetRef,
                                credentialId = credentialIdOf(request.credentialId),
                                label = request.label,
                                locale = request.locale,
                                deliverReviews = request.deliverReviews,
                                deliverRatings = request.deliverRatings,
                            ),
                    )
                }
            call.respond(HttpStatusCode.Created, channel.toResponse())
        }

        patch("/{channel}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<UpdateChannelRequest>()
            io { channels.setEnabled(context.organization, context.actor, call.channelIdParam(), request.enabled) }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{channel}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { channels.delete(context.organization, context.actor, call.channelIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/test") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ChannelTestRequest>()
            val results =
                channels.test(
                    organization = context.organization,
                    actor = context.actor,
                    appId = call.appIdParam(),
                    only = request.channelId?.let { channelIdOf(it) },
                )
            // I samé chyby jsou platná odpověď: klient má vidět stav všech kanálů najednou.
            call.respond(
                results.map {
                    ChannelCheckResponse(it.channelId.toString(), it.targetRef, it.ok, it.error, it.hint)
                },
            )
        }
    }

    route("/orgs/{org}/slack") {
        post("/connect") {
            val context = call.orgContext(console.organizations, console.memberships)
            val slack = console.slack ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Slack není v téhle instalaci zapnutý")
            val request = call.receive<ConnectSlackRequest>()
            call.respond(slack.connect(context.organization, context.actor, SecretPayload(request.token), request.label))
        }

        get("/install-url") {
            val context = call.orgContext(console.organizations, console.memberships)
            val slack = console.slack ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Slack není v téhle instalaci zapnutý")
            call.respond(slack.installUrl(context.organization, context.actor))
        }
    }
}

private fun Channel.toResponse() =
    ChannelResponse(
        id = id.toString(),
        type = type,
        targetRef = targetRef,
        targetLabel = targetLabel,
        credentialId = credentialId?.toString(),
        locale = locale,
        deliverReviews = deliverReviews,
        deliverRatings = deliverRatings,
        enabled = enabled,
    )
