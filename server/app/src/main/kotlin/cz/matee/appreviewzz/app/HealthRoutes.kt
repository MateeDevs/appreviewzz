package cz.matee.appreviewzz.app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class LivenessResponse(
    val status: String,
    val version: String,
    val gitSha: String,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val checks: Map<String, String>,
)

/**
 * `live` = proces běží (nezávisí na DB, jinak by restart smyčka po výpadku DB byla zbytečná).
 * `ready` = umíme obsloužit provoz, tedy i sáhnout do databáze.
 */
fun Application.healthRoutes(readiness: () -> Boolean) {
    routing {
        get("/health/live") {
            call.respond(
                LivenessResponse(status = "UP", version = BuildInfo.version, gitSha = BuildInfo.gitSha),
            )
        }

        get("/health/ready") {
            val databaseUp = readiness()
            call.respond(
                if (databaseUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadinessResponse(
                    status = if (databaseUp) "UP" else "DOWN",
                    checks = mapOf("database" to if (databaseUp) "UP" else "DOWN"),
                ),
            )
        }
    }
}
