package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.AppListingSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.usecase.AppDraft
import cz.matee.appreviewzz.core.usecase.AppInputs
import cz.matee.appreviewzz.core.usecase.AppSetup
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.OrgActor
import cz.matee.appreviewzz.core.usecase.SetupGap
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
data class CreateAppRequest(
    val name: String,
    val gpPackageName: String? = null,
    /** Bucket s reportingem Play Console (`pubsite_prod_…`) — bez něj se Android čísla scrapují. */
    val gpReportingBucket: String? = null,
    val ascAppId: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /** `now` nebo ISO-8601. Bez hodnoty je watermarkem čas přidání appky — historie do kanálu nejde. */
    val notifyFrom: String? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int? = null,
    val dailyDigestAt: String? = null,
)

@Serializable
data class UpdateAppRequest(
    val name: String,
    val gpReportingBucket: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /** `null` znamená „nech, jak je" — watermark se nedá zrušit, jen posunout. */
    val notifyFrom: String? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int? = null,
    val dailyDigestAt: String? = null,
    val enabled: Boolean? = null,
)

/** Odkazy ze storu, jak je klient zkopíruje z prohlížeče. Aspoň jeden musí být vyplněný. */
@Serializable
data class ResolveStoreLinksRequest(
    val googlePlayUrl: String? = null,
    val appStoreUrl: String? = null,
)

/**
 * Co se z odkazu povedlo vyčíst. `name` je `null`, když store neodpověděl — přidání appky
 * to nebrání, jen si klient název napíše sám.
 */
@Serializable
data class ResolvedStore(
    val platform: Platform,
    val identifier: String,
    val name: String?,
    /** Věta pro člověka, když se odkaz nepovedlo rozluštit nebo store mlčel. */
    val error: String?,
)

@Serializable
data class ResolveStoreLinksResponse(
    val googlePlay: ResolvedStore?,
    val appStore: ResolvedStore?,
)

@Serializable
data class AppResponse(
    val id: String,
    val name: String,
    val gpPackageName: String?,
    val gpReportingBucket: String?,
    val ascAppId: String?,
    val platforms: List<Platform>,
    val locale: MessageLocale,
    val timezone: String,
    val notifyFrom: String?,
    val aiInstructions: String?,
    /**
     * Efektivní interval, tedy co doopravdy platí. Console ho ukazuje jako větu, ne jako
     * pole — nastavuje ho provozovatel platformy (F7.4, ADR 0018).
     */
    val ingestIntervalMinutes: Int,
    val ingestIntervalSource: IngestIntervalSource,
    val dailyDigestAt: String,
    val enabled: Boolean,
    /** Co appce chybí, aby recenze tekly. Console podle toho odliší „sledujeme" od „čeká na nastavení". */
    val setup: AppSetupResponse,
)

@Serializable
data class AppSetupResponse(
    val ready: Boolean,
    val gaps: List<SetupGap>,
    val platformsWithoutKey: List<Platform>,
)

/** Odkud se vzal interval stahování: platformní výchozí hodnota, nebo výjimka pro tuhle appku. */
@Serializable
enum class IngestIntervalSource {
    PLATFORM,
    APP,
}

/**
 * Sledované aplikace (F3.3) — druhý krok onboardingu, hned po založení organizace.
 *
 * Store identifikátory jde zadat jen při zakládání. Přepnutí appky na jiný balíček by
 * zdědilo cizí recenze i watermark, takže od toho je nová appka; `PATCH` proto mění
 * jen nastavení.
 */
fun Route.appRoutes(console: ConsoleWiring) {
    val apps = console.apps
    val setup = console.appSetup

    route("/orgs/{org}/apps") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(
                io { apps.list(context.organization.id).map { it.toResponse(apps.effectiveInterval(it), setup.of(it)) } },
            )
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<CreateAppRequest>()
            val app =
                io {
                    apps.create(
                        organization = context.organization,
                        actor = context.actor,
                        draft =
                            AppDraft(
                                name = request.name,
                                gpPackageName = request.gpPackageName,
                                gpReportingBucket = request.gpReportingBucket,
                                ascAppId = request.ascAppId,
                                locale = request.locale,
                                timezone = request.timezone,
                                notifyFrom = request.notifyFrom,
                                aiInstructions = request.aiInstructions,
                                ingestIntervalMinutes = request.ingestIntervalMinutes,
                                dailyDigestAt = request.dailyDigestAt,
                            ),
                    )
                }
            call.respond(HttpStatusCode.Created, app.toResponse(apps.effectiveInterval(app), io { setup.of(app) }))
        }

        /**
         * Rozluštění odkazů ze storu. Odděleně od zakládání schválně: dialog ho volá ve chvíli,
         * kdy klient odkaz vloží, aby mu mohl nabídnout název dřív, než appku potvrdí.
         */
        post("/resolve") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ResolveStoreLinksRequest>()
            call.respond(console.storeLookup.resolve(context.actor, request))
        }

        get("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(
                io {
                    apps.get(context.organization.id, call.appIdParam()).let {
                        it.toResponse(apps.effectiveInterval(it), setup.of(it))
                    }
                },
            )
        }

        patch("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<UpdateAppRequest>()
            val app =
                io {
                    apps.update(
                        organization = context.organization,
                        actor = context.actor,
                        id = call.appIdParam(),
                        draft =
                            AppDraft(
                                name = request.name,
                                gpReportingBucket = request.gpReportingBucket,
                                locale = request.locale,
                                timezone = request.timezone,
                                notifyFrom = request.notifyFrom,
                                aiInstructions = request.aiInstructions,
                                ingestIntervalMinutes = request.ingestIntervalMinutes,
                                dailyDigestAt = request.dailyDigestAt,
                                enabled = request.enabled,
                            ),
                    )
                }
            call.respond(app.toResponse(apps.effectiveInterval(app), io { setup.of(app) }))
        }

        delete("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { apps.delete(context.organization, context.actor, call.appIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun App.toResponse(
    effectiveInterval: Int,
    setup: AppSetup,
) = AppResponse(
    id = id.toString(),
    name = name,
    gpPackageName = gpPackageName,
    gpReportingBucket = gpReportingBucket,
    ascAppId = ascAppId,
    platforms = platforms().toList(),
    locale = locale,
    timezone = timezone,
    notifyFrom = notifyFrom?.toString(),
    aiInstructions = aiInstructions,
    ingestIntervalMinutes = effectiveInterval,
    ingestIntervalSource =
        if (ingestIntervalMinutes == null) IngestIntervalSource.PLATFORM else IngestIntervalSource.APP,
    dailyDigestAt = dailyDigestAt.toString(),
    enabled = enabled,
    setup =
        AppSetupResponse(
            ready = setup.ready,
            gaps = setup.gaps,
            platformsWithoutKey = setup.platformsWithoutKey,
        ),
)

/**
 * Vyčtení appky z odkazu na store (F3.3).
 *
 * Klient přidává aplikaci tak, že vloží odkaz z Google Play a z App Storu; z nich se vezme
 * package name, číselné App ID a název, který store uvádí. Sedí v `app`, ne v doméně:
 * čtení veřejného listingu umí jen konektory.
 *
 * Selhání storu **není chyba požadavku** — vrátí se jako věta u konkrétního odkazu a dialog
 * nechá klienta pokračovat s vlastním názvem. Jinak by nedostupný Play Store znamenal, že se
 * appka nedá přidat vůbec.
 */
class StoreLookup(
    private val sources: List<AppListingSource> = emptyList(),
) {
    suspend fun resolve(
        actor: OrgActor,
        request: ResolveStoreLinksRequest,
    ): ResolveStoreLinksResponse {
        requireRole(actor, OrgRole.ADMIN)
        val googlePlay = request.googlePlayUrl?.takeIf { it.isNotBlank() }
        val appStore = request.appStoreUrl?.takeIf { it.isNotBlank() }
        if (googlePlay == null && appStore == null) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Vlož aspoň jeden odkaz — na Google Play, nebo na App Store",
            )
        }

        // Jménem pole je popisek, který má klient u vstupu před sebou — hláška se ukazuje
        // rovnou pod ním a „googlePlayUrl" by tam bylo cizí slovo.
        return ResolveStoreLinksResponse(
            googlePlay = googlePlay?.let { resolve(Platform.ANDROID, it) { raw -> AppInputs.playPackage(raw, "Odkaz na Google Play") } },
            appStore = appStore?.let { resolve(Platform.IOS, it) { raw -> AppInputs.appStoreId(raw, "Odkaz na App Store") } },
        )
    }

    private suspend fun resolve(
        platform: Platform,
        raw: String,
        identify: (String) -> String,
    ): ResolvedStore {
        val identifier =
            try {
                identify(raw)
            } catch (error: ConsoleException) {
                return ResolvedStore(platform, identifier = "", name = null, error = error.message)
            }

        val source = sources.firstOrNull { it.platform == platform }
        val name =
            try {
                source?.fetchName(identifier)
            } catch (error: StoreConnectorException) {
                // Věta z konektoru je psaná pro člověka („Aplikace … v Play Storu není"),
                // takže se předává, jak je — vlastní obal by ji jen zamlžil.
                return ResolvedStore(platform, identifier, name = null, error = error.message)
            }
        return ResolvedStore(
            platform = platform,
            identifier = identifier,
            name = name,
            error = if (name == null) "Název se ze storu nepovedlo přečíst, napiš ho ručně" else null,
        )
    }
}
