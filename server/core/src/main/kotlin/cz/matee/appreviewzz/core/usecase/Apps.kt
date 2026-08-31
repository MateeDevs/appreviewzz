package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppSettings
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Sledovaná appka, jak ji zadává člověk. Hodnoty jsou syrové texty schválně — přicházejí
 * stejně z JSON těla i z parametrů CLI a kontrola je pro obojí jedna ([AppInputs]).
 */
data class AppDraft(
    val name: String,
    val gpPackageName: String? = null,
    /** Bucket s reportingem Play Console; bez něj se Android hodnocení berou z veřejného listingu. */
    val gpReportingBucket: String? = null,
    val ascAppId: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /** `now`, ISO-8601, nebo nic — u nové appky se z prázdna stane čas jejího přidání. */
    val notifyFrom: String? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int? = null,
    val dailyDigestAt: String? = null,
    val enabled: Boolean? = null,
)

/**
 * Sledované aplikace (F3.3). Zakládat a měnit je smí ADMIN a výš; MEMBER je vidí,
 * protože bez appky nemá k čemu vztáhnout recenze.
 *
 * Store identifikátory (`gp_package_name`, `asc_app_id`) musí být v organizaci unikátní.
 * Není to kosmetika: dvě appky nad týmž balíčkem by stahovaly tytéž recenze a doručovaly
 * je dvakrát — dnešní n8n přesně tímhle trpí, když se zapomene stará větev dispatcheru.
 */
class AppService(
    private val apps: AppRepository,
    private val audit: AuditLogRepository,
    /**
     * Jak často se stahují recenze. Není to nastavení appky, ale knob na náš provoz —
     * klient ho nevidí a `AppService` si ho odsud jen přečte (F7.4, ADR 0018).
     */
    private val ingest: IngestPolicy = IngestPolicy.fixed(),
    private val clock: Clock = Clock.System,
) {
    /** Efektivní interval appky: vlastní výjimka, jinak platformní výchozí hodnota. */
    fun effectiveInterval(app: App): Int = app.ingestIntervalMinutes ?: ingest.defaultIntervalMinutes()

    fun list(orgId: OrganizationId): List<App> = apps.listByOrg(orgId)

    fun get(
        orgId: OrganizationId,
        id: AppId,
    ): App = apps.findById(orgId, id) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")

    fun create(
        organization: Organization,
        actor: OrgActor,
        draft: AppDraft,
    ): App {
        requireRole(actor, OrgRole.ADMIN)
        val name = draft.name.trim()
        if (name.isEmpty()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Aplikace potřebuje název")

        // Identifikátory se berou i z odkazu na store — console je tak, jak je klient
        // zkopíruje z prohlížeče, posílá rovnou (viz [AppInputs.playPackage]).
        val gpPackage =
            draft.gpPackageName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AppInputs.playPackage(it, "gpPackageName") }
        val ascAppId =
            draft.ascAppId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AppInputs.appStoreId(it, "ascAppId") }
        if (gpPackage == null && ascAppId == null) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Aplikace potřebuje aspoň jeden store — package name z Google Play nebo číselné App ID",
            )
        }
        requireIdentifiersFree(organization.id, gpPackage, ascAppId, except = null)
        requireRoomForApp(organization.id)

        // Výchozí hodnoty drží doména (NewApp), ne tahle vrstva — jinak by se obojí rozešlo.
        val defaults =
            NewApp(
                name = name,
                gpPackageName = gpPackage,
                gpReportingBucket =
                    draft.gpReportingBucket?.takeIf { it.isNotBlank() }?.let {
                        AppInputs.reportingBucket(it, "gpReportingBucket")
                    },
                ascAppId = ascAppId,
            )
        val app =
            apps.create(
                organization.id,
                defaults.copy(
                    locale = draft.locale?.let { AppInputs.locale(it, "locale") } ?: defaults.locale,
                    timezone = draft.timezone?.let { AppInputs.timezone(it, "timezone") } ?: defaults.timezone,
                    // Bez zadané hodnoty je watermark „teď": recenze starší než dnešní přidání
                    // appky patří do historie, ne do kanálu.
                    notifyFrom = AppInputs.newAppNotifyFrom(draft.notifyFrom, "notifyFrom", clock),
                    aiInstructions = draft.aiInstructions?.takeIf { it.isNotBlank() },
                    ingestIntervalMinutes = intervalOverride(actor, draft),
                    dailyDigestAt =
                        draft.dailyDigestAt?.let { AppInputs.digestAt(it, "dailyDigestAt") } ?: defaults.dailyDigestAt,
                ),
            )
        audit(organization.id, actor, "app.created", app.id.toString(), mapOf("name" to app.name))
        logger.info { "Aplikace ${app.id} (${app.name}) založená v organizaci ${organization.slug}" }
        return app
    }

    /**
     * Změna nastavení. Store identifikátory se tu **nemění**: přepnutí appky na jiný balíček
     * by zdědilo cizí recenze i watermark. Od toho je nová appka.
     */
    fun update(
        organization: Organization,
        actor: OrgActor,
        id: AppId,
        draft: AppDraft,
    ): App {
        requireRole(actor, OrgRole.ADMIN)
        val current = get(organization.id, id)
        val name = draft.name.trim().ifEmpty { throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Aplikace potřebuje název") }

        val settings =
            AppSettings(
                name = name,
                gpReportingBucket =
                    draft.gpReportingBucket?.takeIf { it.isNotBlank() }?.let { AppInputs.reportingBucket(it, "gpReportingBucket") }
                        ?: current.gpReportingBucket,
                locale = draft.locale?.let { AppInputs.locale(it, "locale") } ?: current.locale,
                timezone = draft.timezone?.let { AppInputs.timezone(it, "timezone") } ?: current.timezone,
                notifyFrom = AppInputs.notifyFrom(draft.notifyFrom, "notifyFrom", clock) ?: current.notifyFrom,
                aiInstructions = draft.aiInstructions?.takeIf { it.isNotBlank() },
                ingestIntervalMinutes = intervalOverride(actor, draft) ?: current.ingestIntervalMinutes,
                dailyDigestAt = draft.dailyDigestAt?.let { AppInputs.digestAt(it, "dailyDigestAt") } ?: current.dailyDigestAt,
                enabled = draft.enabled ?: current.enabled,
            )
        val updated =
            apps.updateSettings(organization.id, id, settings)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        audit(organization.id, actor, "app.updated", id.toString(), changes(current, updated))
        return updated
    }

    /**
     * Smazání appky. Bere s sebou recenze, zprávy i kanály (kaskáda v databázi) — proto
     * jen OWNER: je to jediná operace v consoli, po které nezůstane co obnovit bez zálohy.
     */
    fun delete(
        organization: Organization,
        actor: OrgActor,
        id: AppId,
    ) {
        requireRole(actor, OrgRole.OWNER)
        val app = get(organization.id, id)
        apps.delete(organization.id, id)
        audit(organization.id, actor, "app.deleted", id.toString(), mapOf("name" to app.name))
        logger.info { "Aplikace ${app.id} (${app.name}) smazaná z organizace ${organization.slug}" }
    }

    /**
     * Strop počtu aplikací (platformní nastavení). Kontroluje se **jen při zakládání** —
     * snížení stropu nemá klientovi zneviditelnit appky, které už sleduje.
     */
    private fun requireRoomForApp(orgId: OrganizationId) {
        val limit = ingest.maxAppsPerOrg()
        if (limit <= 0) return
        val current = apps.listByOrg(orgId).size
        if (current >= limit) {
            throw ConsoleException(
                ConsoleFailure.FORBIDDEN,
                "Organizace má maximum sledovaných aplikací ($limit) — napiš nám, když potřebuješ víc",
            )
        }
    }

    private fun requireIdentifiersFree(
        orgId: OrganizationId,
        gpPackage: String?,
        ascAppId: String?,
        except: AppId?,
    ) {
        apps.listByOrg(orgId).filter { it.id != except }.forEach { existing ->
            val collision =
                when {
                    gpPackage != null && existing.gpPackageName == gpPackage -> gpPackage
                    ascAppId != null && existing.ascAppId == ascAppId -> ascAppId
                    else -> null
                }
            if (collision != null) {
                throw ConsoleException(
                    ConsoleFailure.INVALID_INPUT,
                    "'$collision' už sleduje aplikace ${existing.name} — dvě appky nad týmž storem by recenze doručovaly dvakrát",
                )
            }
        }
    }

    /**
     * Interval stahování z požadavku. Pro klienta je to `403`, ne tiché ignorování: console
     * to pole neposílá, takže sem se dostane jen vlastní klient nad API — a ten si zaslouží
     * vědět, že se jeho hodnota neuložila.
     */
    private fun intervalOverride(
        actor: OrgActor,
        draft: AppDraft,
    ): Int? {
        val requested = draft.ingestIntervalMinutes ?: return null
        if (actor.platformRole != PlatformRole.SUPERADMIN) {
            throw ConsoleException(
                ConsoleFailure.FORBIDDEN,
                "Jak často se stahují recenze, nastavuje provozovatel platformy — napiš nám, když ti interval nesedí",
            )
        }
        val floor = ingest.minIntervalMinutes()
        if (requested < floor) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Interval nesmí být kratší než platformní podlaha $floor minut",
            )
        }
        return AppInputs.ingestInterval(requested, "ingestIntervalMinutes")
    }

    private fun changes(
        before: App,
        after: App,
    ): Map<String, String> =
        buildMap {
            if (before.name != after.name) put("name", after.name)
            if (before.enabled != after.enabled) put("enabled", after.enabled.toString())
            if (before.locale != after.locale) put("locale", after.locale.code)
            if (before.ingestIntervalMinutes != after.ingestIntervalMinutes) {
                put("ingestIntervalMinutes", after.ingestIntervalMinutes?.toString() ?: "platformní")
            }
            if (before.notifyFrom != after.notifyFrom) put("notifyFrom", after.notifyFrom.toString())
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
                targetType = "app",
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }
}

/**
 * Role se kontroluje na jednom místě pro všechny console use-case — včetně těch, které kvůli
 * závislosti na konektoru sedí až v aplikační vrstvě (připojení Slacku).
 */
fun requireRole(
    actor: OrgActor,
    required: OrgRole,
) {
    if (!actor.role.atLeast(required)) {
        throw ConsoleException(ConsoleFailure.FORBIDDEN, "Na tohle potřebuješ roli ${required.name.lowercase()} a vyšší")
    }
}
