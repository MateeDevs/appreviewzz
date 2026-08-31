package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.PlatformSettingSource
import cz.matee.appreviewzz.core.model.PlatformSettingType
import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.PlatformSecretMeta
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.PlatformActor
import cz.matee.appreviewzz.core.usecase.PlatformAppSummary
import cz.matee.appreviewzz.core.usecase.ResolvedSetting
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Jedna položka katalogu i s hodnotou, která právě platí. `source` je tu proto, aby bylo
 * v consoli vidět, že hodnota jde z prostředí — jinak se dlouho hledá, proč se uložením
 * nic nezměnilo.
 */
@Serializable
data class PlatformSettingResponse(
    val key: String,
    val type: PlatformSettingType,
    val section: String,
    val label: String,
    val help: String,
    /** U tajemství vždy `null`. */
    val value: String?,
    val source: PlatformSettingSource,
    val default: String?,
    val envName: String?,
    val options: List<String>,
    val min: Int?,
    val max: Int?,
)

@Serializable
data class UpdatePlatformSettingsRequest(
    /** `null` u klíče znamená „zruš uložené" a spadni zpátky na prostředí. */
    val values: Map<String, String?>,
)

/** Co se o platformním tajemství smí říct. Hodnota tu není a nikdy nebude. */
@Serializable
data class PlatformSecretResponse(
    val key: String,
    val label: String,
    val fingerprint: String,
    val hint: String?,
    val updatedAt: String,
)

@Serializable
data class SetPlatformSecretRequest(
    val value: String,
)

@Serializable
data class PlatformStatsResponse(
    val organizations: Long,
    val users: Long,
    val apps: Long,
    val enabledApps: Long,
    val failedJobs: Long,
    val appsWithIntervalOverride: Long,
    /** Interval, který právě platí pro appky bez výjimky — přehled bez něj nedává smysl. */
    val defaultIntervalMinutes: Int,
    val minIntervalMinutes: Int,
)

@Serializable
data class PlatformAuditResponse(
    val actorLabel: String?,
    val action: String,
    val targetKey: String?,
    val metadata: Map<String, String>,
    val createdAt: String?,
)

@Serializable
data class PlatformAppResponse(
    val id: String,
    val name: String,
    val orgId: String,
    val intervalMinutes: Int,
    /** `null` znamená, že appka jede na platformní výchozí hodnotě. */
    val overrideMinutes: Int?,
    val enabled: Boolean,
)

@Serializable
data class SetAppIntervalRequest(
    /** `null` ruší výjimku a vrací appku k platformní výchozí hodnotě. */
    val minutes: Int? = null,
)

/**
 * Správa platformy (F7.3, [ADR 0018]).
 *
 * Celý strom visí pod `requirePlatformAdmin`, takže tady žádná kontrola role není — a taky
 * proto tu **nejsou žádná data organizací**. Superadmin spravuje konfiguraci; kdo chce vidět
 * recenze klienta, musí být jeho členem.
 */
fun Route.platformRoutes(console: ConsoleWiring) {
    val platform = console.platform ?: return

    route("/platform") {
        get("/settings") {
            call.respond(io { platform.settings().map { it.toResponse() } })
        }

        put("/settings") {
            val request = call.receive<UpdatePlatformSettingsRequest>()
            val updated = io { platform.updateSettings(call.platformActor(), request.values) }
            call.respond(updated.map { it.toResponse() })
        }

        get("/secrets") {
            call.respond(io { platform.secrets().map { it.toResponse() } })
        }

        put("/secrets/{key}") {
            val key = call.secretKeyParam()
            val request = call.receive<SetPlatformSecretRequest>()
            if (request.value.isBlank()) {
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Hodnota je prázdná — na zrušení klíče je DELETE")
            }
            io { platform.setSecret(call.platformActor(), key, SecretPayload(request.value)) }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/secrets/{key}") {
            io { platform.removeSecret(call.platformActor(), call.secretKeyParam()) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/overview") {
            call.respond(
                io {
                    val stats = platform.stats()
                    PlatformStatsResponse(
                        organizations = stats.organizations,
                        users = stats.users,
                        apps = stats.apps,
                        enabledApps = stats.enabledApps,
                        failedJobs = stats.failedJobs,
                        appsWithIntervalOverride = stats.appsWithIntervalOverride,
                        defaultIntervalMinutes = console.ingest.defaultIntervalMinutes(),
                        minIntervalMinutes = console.ingest.minIntervalMinutes(),
                    )
                },
            )
        }

        get("/audit") {
            call.respond(
                io {
                    platform.auditTrail().map {
                        PlatformAuditResponse(
                            actorLabel = it.actorLabel,
                            action = it.action,
                            targetKey = it.targetKey,
                            metadata = it.metadata,
                            createdAt = it.createdAt?.toString(),
                        )
                    }
                },
            )
        }

        get("/apps") {
            call.respond(io { platform.appsWithIntervalOverride().map { it.toResponse() } })
        }

        patch("/apps/{app}") {
            val request = call.receive<SetAppIntervalRequest>()
            val updated = io { platform.setAppInterval(call.platformActor(), call.appIdParam(), request.minutes) }
            call.respond(updated.toResponse())
        }
    }
}

private fun ResolvedSetting.toResponse() =
    PlatformSettingResponse(
        key = definition.key,
        type = definition.type,
        section = definition.section,
        label = definition.label,
        help = definition.help,
        value = value,
        source = source,
        default = definition.default,
        envName = definition.envName,
        options = definition.options,
        min = definition.min,
        max = definition.max,
    )

private fun PlatformSecretMeta.toResponse() =
    PlatformSecretResponse(
        key = key,
        label = PlatformSettings.find(key)?.label ?: key,
        fingerprint = fingerprint,
        hint = hint,
        updatedAt = updatedAt.toString(),
    )

private fun PlatformAppSummary.toResponse() =
    PlatformAppResponse(
        id = app.id.toString(),
        name = app.name,
        orgId = app.orgId.toString(),
        intervalMinutes = effectiveIntervalMinutes,
        overrideMinutes = app.ingestIntervalMinutes,
        enabled = app.enabled,
    )

private fun ApplicationCall.platformActor(): PlatformActor = PlatformActor.of(authenticatedUser.account.user)

/**
 * Klíč tajemství z adresy. Ověřuje se proti katalogu hned tady, aby se do `PUT` na neznámý
 * klíč vůbec nedošlo — v cestě je to část URL, kterou si volající vymyslí.
 */
private fun ApplicationCall.secretKeyParam(): String {
    val key = parameters["key"].orEmpty()
    val definition =
        PlatformSettings.find(key)
            ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takové nastavení tu není")
    if (!definition.secret) {
        throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Nastavení '$key' není tajemství")
    }
    return key
}
