package cz.matee.appreviewzz.app

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

fun Application.installObservability(metrics: PrometheusMeterRegistry) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
    }
    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.length in 1..128 }
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        // ANSI barvy patří do terminálu, ne do JSON logu v CloudWatchi.
        disableDefaultColors()
        // Health probe každých pár sekund by log jinak utopil.
        filter { call -> !call.request.path().startsWith("/health") }
    }
    install(io.ktor.server.metrics.micrometer.MicrometerMetrics) {
        registry = metrics
    }
}

fun Application.installSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
}
