package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Kanál, do kterého se má připojit. Hodnoty přicházejí z formuláře, takže syrové. */
data class ChannelDraft(
    val appId: AppId,
    val type: ChannelType,
    /** Slack channel ID (`C0123…`), resp. Teams conversation ID. */
    val targetRef: String,
    val credentialId: CredentialId,
    val label: String? = null,
    val locale: String? = null,
    val deliverReviews: Boolean = true,
    val deliverRatings: Boolean = true,
)

/** Výsledek ověření jednoho kanálu. `hint` je věta, co s tím — ta je na tom to cenné. */
data class ChannelCheck(
    val channelId: ChannelId,
    val targetRef: String,
    val ok: Boolean,
    val error: String? = null,
    val hint: String? = null,
)

/**
 * Kanály (F3.4) — poslední krok onboardingu, po kterém začnou chodit recenze.
 *
 * Nejcennější věc tady je [test]: pošle do kanálu krátkou zprávu a tím rovnou odhalí tři
 * nejčastější chyby (odvolaný token, chybějící scope, bot nepozvaný do privátního kanálu).
 * Bez něj se na ně přijde až tím, že první recenze nikam nedorazí — a to je ta nejdražší chvíle.
 */
class ChannelService(
    private val channels: ChannelRepository,
    private val apps: AppRepository,
    private val credentials: CredentialRepository,
    private val secrets: SecretResolver,
    private val implementations: List<NotificationChannel>,
    private val audit: AuditLogRepository,
) {
    fun list(
        orgId: OrganizationId,
        appId: AppId,
    ): List<Channel> = channels.listByApp(orgId, appId)

    fun add(
        organization: Organization,
        actor: OrgActor,
        draft: ChannelDraft,
    ): Channel {
        requireRole(actor, OrgRole.ADMIN)
        val app = app(organization.id, draft.appId)
        val meta =
            credentials.findMeta(organization.id, draft.credentialId)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková instalace tu není")

        val expected = credentialTypeFor(draft.type)
        if (meta.type != expected) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Kanál typu ${draft.type.name} potřebuje instalaci ${expected.name}, ne ${meta.type.name}",
            )
        }
        requireTargetRef(draft.type, draft.targetRef)

        val channel =
            channels.create(
                organization.id,
                NewChannel(
                    appId = app.id,
                    type = draft.type,
                    targetRef = draft.targetRef.trim(),
                    targetLabel = draft.label?.trim()?.takeIf { it.isNotEmpty() },
                    credentialId = meta.id,
                    // Jazyk se dědí z appky: jeden klient může mít český tým na jedné a anglický na druhé.
                    locale = draft.locale?.let { AppInputs.locale(it, "locale") } ?: app.locale,
                    deliverReviews = draft.deliverReviews,
                    deliverRatings = draft.deliverRatings,
                ),
            )
        audit(
            organization.id,
            actor,
            "channel.created",
            channel.id.toString(),
            mapOf("app" to app.id.toString(), "type" to channel.type.name, "target" to channel.targetRef),
        )
        logger.info { "Kanál ${channel.id} (${channel.targetRef}) připojený k aplikaci ${app.id}" }
        return channel
    }

    fun setEnabled(
        organization: Organization,
        actor: OrgActor,
        id: ChannelId,
        enabled: Boolean,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        if (!channels.setEnabled(organization.id, id, enabled)) {
            throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový kanál tu není")
        }
        audit(organization.id, actor, "channel.enabled_changed", id.toString(), mapOf("enabled" to enabled.toString()))
    }

    fun delete(
        organization: Organization,
        actor: OrgActor,
        id: ChannelId,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        if (!channels.delete(organization.id, id)) {
            throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový kanál tu není")
        }
        audit(organization.id, actor, "channel.deleted", id.toString(), emptyMap())
    }

    /**
     * Zkušební zpráva do kanálů aplikace. Nevyhazuje na první chybě: klient má vidět stav
     * všech kanálů najednou, ne opravovat je po jednom.
     */
    suspend fun test(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        only: ChannelId? = null,
    ): List<ChannelCheck> {
        requireRole(actor, OrgRole.ADMIN)
        val app = app(organization.id, appId)
        val targets =
            channels.listByApp(organization.id, appId).filter { only == null || it.id == only }
        if (targets.isEmpty()) {
            throw ConsoleException(ConsoleFailure.NOT_FOUND, "Aplikace ${app.name} zatím nemá kanál")
        }

        val results =
            targets.map { channel ->
                val implementation = implementations.firstOrNull { it.type == channel.type }
                val credentialId = channel.credentialId
                when {
                    implementation == null ->
                        ChannelCheck(
                            channel.id,
                            channel.targetRef,
                            ok = false,
                            error = "Kanál typu ${channel.type.name} tenhle proces neumí",
                        )

                    credentialId == null ->
                        ChannelCheck(
                            channel.id,
                            channel.targetRef,
                            ok = false,
                            error = "Chybí připojená instalace",
                            hint = "Připoj workspace znovu a kanál založ s ním",
                        )

                    else ->
                        try {
                            implementation.postConnectivityCheck(
                                ChannelTarget(channel.targetRef, secrets.resolve(organization.id, credentialId)),
                                ConnectivityNotice(appName = app.name, locale = channel.locale),
                            )
                            ChannelCheck(channel.id, channel.targetRef, ok = true)
                        } catch (error: ChannelException) {
                            ChannelCheck(
                                channel.id,
                                channel.targetRef,
                                ok = false,
                                error = error.message,
                                hint = hintFor(error, channel.type),
                            )
                        }
                }
            }

        audit(
            organization.id,
            actor,
            "channel.tested",
            appId.toString(),
            mapOf("kanálů" to results.size.toString(), "chyb" to results.count { !it.ok }.toString()),
        )
        return results
    }

    private fun app(
        orgId: OrganizationId,
        id: AppId,
    ): App = apps.findById(orgId, id) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")

    private fun credentialTypeFor(type: ChannelType): CredentialType =
        when (type) {
            ChannelType.SLACK -> CredentialType.SLACK_INSTALL
            ChannelType.TEAMS -> CredentialType.TEAMS_BOT_REF
        }

    /**
     * Obě platformy chtějí **ID** kanálu, ne jeho jméno. Je to nejčastější překlep při
     * onboardingu a bez téhle kontroly se projeví až tím, že zpráva nikam nedorazí.
     */
    private fun requireTargetRef(
        type: ChannelType,
        targetRef: String,
    ) {
        val value = targetRef.trim()
        if (value.isEmpty()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Kanál potřebuje cíl")
        when (type) {
            ChannelType.SLACK ->
                if (!value.startsWith("C") && !value.startsWith("G")) {
                    throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Čekám ID kanálu ze Slacku (začíná C nebo G), ne jeho jméno — najdeš ho dole ve 'View channel details'",
                    )
                }

            // `19:…@thread.tacv2` je ID teamsového kanálu; klient ho vyzobne z odkazu na kanál.
            ChannelType.TEAMS ->
                if (!value.startsWith("19:")) {
                    throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Čekám ID kanálu v Teams (začíná 19: a končí @thread.tacv2), ne jeho jméno — " +
                            "je v odkazu na kanál z 'Kopírovat odkaz'",
                    )
                }
        }
    }

    private fun audit(
        orgId: OrganizationId,
        actor: OrgActor,
        action: String,
        targetId: String,
        metadata: Map<String, String>,
    ) {
        audit.append(
            auditEntry(
                orgId = orgId,
                action = action,
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "channel",
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }
}

/**
 * Co s chybou kanálu udělat. Tohle je na ověření to cenné — „nepovedlo se" samo o sobě
 * nikomu nepomůže a přesně kvůli tomuhle dnes chodí dotazy na podporu.
 */
fun hintFor(
    error: ChannelException,
    type: ChannelType = ChannelType.SLACK,
): String =
    when (error.kind) {
        ChannelErrorKind.AUTH ->
            when (type) {
                ChannelType.SLACK -> "token je odvolaný nebo appce chybí scope — připoj workspace znovu"
                ChannelType.TEAMS -> "bot nemá platné heslo nebo ho tenant odebral — zkontroluj registraci a připoj Teams znovu"
            }

        ChannelErrorKind.NOT_FOUND ->
            when (type) {
                ChannelType.SLACK -> "kanál neexistuje, nebo v něm bot není — pozvi ho přes /invite @appreviewzz"
                ChannelType.TEAMS -> "kanál neexistuje, nebo v týmu není naše aplikace — přidej ji v Teams přes 'Aplikace'"
            }

        ChannelErrorKind.INVALID_REQUEST -> "zprávu odmítl kvůli obsahu; tohle patří do issue, ne do nastavení"
        ChannelErrorKind.RATE_LIMITED -> "protistrana teď omezuje volání, zkus to za chvíli"
        ChannelErrorKind.TRANSIENT -> "výpadek sítě nebo protistrany, zkus to znovu"
    }
