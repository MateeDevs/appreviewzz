package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AppTopicId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.usecase.AnalysisInsights
import cz.matee.appreviewzz.core.usecase.AppService
import cz.matee.appreviewzz.core.usecase.AppSetupCheck
import cz.matee.appreviewzz.core.usecase.AppTopicService
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ChannelService
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.ConsoleLinks
import cz.matee.appreviewzz.core.usecase.CredentialService
import cz.matee.appreviewzz.core.usecase.DailyRatingsUseCase
import cz.matee.appreviewzz.core.usecase.IngestPolicy
import cz.matee.appreviewzz.core.usecase.MfaService
import cz.matee.appreviewzz.core.usecase.MonthlyReportUseCase
import cz.matee.appreviewzz.core.usecase.OrgActor
import cz.matee.appreviewzz.core.usecase.OrganizationService
import cz.matee.appreviewzz.core.usecase.PlatformAdminService
import cz.matee.appreviewzz.core.usecase.RatingsInsights
import cz.matee.appreviewzz.core.usecase.ReviewInbox
import cz.matee.appreviewzz.core.usecase.ScheduledAnalysisUseCase
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Co console potřebuje. Pohromadě schválně: routy si dokládají závislosti odsud, takže
 * `apiModule` nemá deset volitelných parametrů a přibývání sekcí nemění jeho podpis.
 */
class ConsoleWiring(
    val auth: AuthenticationService,
    val orgs: OrganizationService,
    val apps: AppService,
    /** Čeká appka ještě na klíč nebo kanál? Console podle toho odliší nastavenou appku od nové. */
    val appSetup: AppSetupCheck,
    val credentials: CredentialService,
    val channels: ChannelService,
    val reviews: ReviewInbox,
    /** Vlastní témata aplikace (F8) — základní taxonomii klient nemění. */
    val appTopics: AppTopicService,
    /** Agregace pro stránku *Rozbory* a dopad verzí (F8, B1/B3). */
    val analysis: AnalysisInsights,
    /** Odkazy do konzole a na veřejný report — sdílený odkaz se skládá tady. */
    val links: ConsoleLinks,
    val ratings: RatingsInsights,
    val dailyRatings: DailyRatingsUseCase,
    val audit: AuditLogRepository,
    val cookies: SessionCookies,
    val organizations: OrganizationRepository,
    val memberships: MembershipRepository,
    /**
     * Čtení názvu appky z odkazu na store při jejím přidávání. Výchozí instance je bez
     * konektorů: identifikátory z odkazu vytáhne, název nechá na klientovi.
     */
    val storeLookup: StoreLookup = StoreLookup(),
    /**
     * Druhý faktor (F5.3). `null` = instalace bez správce klíčů, kde nemáme tajemství kam
     * bezpečně uložit; console pak nabídku zabezpečení schová a přihlášení končí heslem.
     */
    val mfa: MfaService? = null,
    /** `null` = instalace bez Slacku (self-host, který používá jen Teams). */
    val slack: ConsoleSlack? = null,
    /** `null` = instalace bez Teams (výchozí stav, dokud provozovatel nezaloží Azure Bota). */
    val teams: ConsoleTeams? = null,
    /**
     * Zařazení odpovědi z console do fronty. `null` znamená proces bez přístupu k plánovači —
     * console pak recenze ukazuje, ale odpovídat z ní nejde (a řekne to větou).
     */
    val enqueueReply: ((ConsoleReply) -> Boolean)? = null,
    /**
     * Správa platformy (F7). `null` = strom `/api/platform` se vůbec nezaregistruje —
     * self-host, který si nikoho nepovýšil, o té sekci nemusí vědět.
     */
    val platform: PlatformAdminService? = null,
    /** Jak často se stahují recenze. Sem se ptá i přehled v platformní sekci. */
    val ingest: IngestPolicy = IngestPolicy.fixed(),
    /**
     * Výroba service accountů pro Google Play. `null` = instalace bez provisioneru; dialog
     * pak řekne, že automatické napojení není nastavené, a nabídne ruční nahrání klíče.
     */
    val googlePlayProvisioning: GooglePlayProvisioning? = null,
    /**
     * Zařazení doplnění rozborů do fronty (org, app). `null` = proces bez přístupu
     * k plánovači; konzole pak tlačítko „Doplnit za historii" nenabídne.
     */
    val enqueueAnalysis: ((String, String) -> Boolean)? = null,
    /** Týdenní rozbor pro tlačítko „Poslat teď"; `null` = proces bez kanálů. */
    val weeklyAnalysis: ScheduledAnalysisUseCase? = null,
    /**
     * Zařazení dotažení historie recenzí (org, app, měsíce). `null` = proces bez plánovače;
     * historii pak dotáhne až denní běh workeru, jen o den později.
     */
    val enqueueHistoryImport: ((String, String, Int) -> Boolean)? = null,
    /**
     * Měsíční reporty (C1). `null` = strom `/reports` se nezaregistruje; instalace, která
     * reporty nepoužívá, o nich nemusí vědět.
     */
    val reports: MonthlyReportUseCase? = null,
    val clock: Clock = Clock.System,
) {
    /**
     * Dotažení historie recenzí pro appku, pokud na archiv vůbec dosáhne: u Androidu jen
     * s reportingovým bucketem (bez něj není odkud číst), u iOS vždycky — App Store Connect
     * historii vrací sám. Appka jen s Androidem a bez bucketu úlohu nedostane, jinak by
     * každé přidání zakládalo běh, který nemá co stáhnout.
     */
    internal fun requestHistoryImport(app: App) {
        val enqueue = enqueueHistoryImport ?: return
        val android = app.gpPackageName != null && app.gpReportingBucket != null
        val ios = app.ascAppId != null
        if (!android && !ios) return
        enqueue(app.orgId.toString(), app.id.toString(), app.historyMonths)
    }

    /**
     * Názvy vlastních témat aplikace pro překlad klíčů ve výkladu. Jeden dotaz na požadavek,
     * ne jeden na recenzi — inbox jich vypisuje padesát.
     */
    internal fun topicNames(
        orgId: OrganizationId,
        appId: AppId,
    ): Map<String, String> = appTopics.list(orgId, appId).associate { it.key to it.name }
}

/**
 * Celé API console pod `/api`.
 *
 * Ochrana je nasazená **na stromě cest**, ne v jednotlivých handlerech: limit požadavků
 * a CSRF na všem, co mění stav, a session na všem kromě přihlašovacích endpointů. Nová
 * sekce tak nemůže zapomenout zeptat se, kdo volá.
 */
fun Application.consoleRoutes(
    console: ConsoleWiring,
    limits: RateLimits = RateLimits.disabled(),
) {
    routing {
        route("/api") {
            rateLimited(limits.api)
            requireCsrf()
            authRoutes(console, limits)

            requireSession(console.auth) {
                orgRoutes(console)
                appRoutes(console)
                credentialRoutes(console)
                channelRoutes(console)
                reviewRoutes(console)
                analysisRoutes(console)
                reportRoutes(console)
                ratingsRoutes(console)

                // Vlastní podstrom s vlastní ochranou: role SUPERADMIN a zapnutý druhý faktor.
                requirePlatformAdmin(console.mfa) {
                    platformRoutes(console)
                }
            }
        }
    }
}

/** Organizace z adresy plus role přihlášeného v ní. Nečlen tu končí na 404. */
class OrgContext(
    val organization: Organization,
    val actor: OrgActor,
) {
    val role: OrgRole get() = actor.role
}

/**
 * Organizace se v adrese identifikuje **slugem**, ne UUID — je to část odkazu, kterou lidé
 * posílají mezi sebou. Kdo v organizaci není, dostane 404, ne 403: jinak by se dalo hádáním
 * adres zjistit, kdo je náš zákazník.
 */
suspend fun ApplicationCall.orgContext(
    organizations: OrganizationRepository,
    memberships: MembershipRepository,
): OrgContext {
    val slug = parameters["org"].orEmpty()
    val user = authenticatedUser.account.user
    return io {
        val organization =
            organizations.findBySlug(slug)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Organizace '$slug' neexistuje")
        val role =
            memberships.roleOf(organization.id, user.id)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Organizace '$slug' neexistuje")
        OrgContext(
            organization,
            OrgActor(
                userId = user.id,
                role = role,
                displayName = user.displayName ?: user.email,
                // Nepřidává práva v organizaci — jen pár polí, která jsou knobem na náš
                // provoz, ne nastavením klienta (interval stahování recenzí).
                platformRole = user.platformRole,
            ),
        )
    }
}

fun ApplicationCall.userIdParam(): UserId = UserId(uuidParam("userId", "Takový člen tu není"))

fun ApplicationCall.appIdParam(): AppId = AppId(uuidParam("app", "Taková aplikace tu není"))

fun ApplicationCall.appTopicIdParam(): AppTopicId = AppTopicId(uuidParam("topic", "Takové téma tu není"))

fun ApplicationCall.credentialIdParam(): CredentialId = credentialIdOf(parameters["credential"].orEmpty())

fun ApplicationCall.channelIdParam(): ChannelId = channelIdOf(parameters["channel"].orEmpty())

fun credentialIdOf(raw: String): CredentialId =
    runCatching { CredentialId(Uuid.parse(raw)) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový klíč tu není") }

fun channelIdOf(raw: String): ChannelId =
    runCatching { ChannelId(Uuid.parse(raw)) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový kanál tu není") }

private fun ApplicationCall.uuidParam(
    name: String,
    message: String,
): Uuid =
    runCatching { Uuid.parse(parameters[name].orEmpty()) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, message) }

/** Sdílené tělo požadavku, které nese jen jednorázový token z e-mailu. */
@Serializable
data class TokenRequest(
    val token: String,
)
