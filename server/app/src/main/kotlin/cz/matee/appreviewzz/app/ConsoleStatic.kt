package cz.matee.appreviewzz.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private val logger = KotlinLogging.logger {}

/** Kde v jaru leží zbuilděná console. Sem ji kopíruje node stage v Dockerfile. */
private const val CONSOLE_ROOT = "console"
private const val INDEX = "index.html"

/**
 * Statické soubory console ze stejného image jako API (ADR 0008 — odpadá CloudFront i S3).
 *
 * Servírování je psané ručně místo `staticResources`, protože potřebuje dvě věci navíc:
 *
 * - **SPA fallback.** `/matee/recenze` je cesta routeru v prohlížeči, ne soubor; musí vrátit
 *   `index.html`, jinak by refresh stránky skončil na 404. Cesty API si přitom musí udržet
 *   svou JSON čtyřstovku, aby se chyba volání nepodobala HTML stránce.
 * - **Cache podle typu.** Assety mají v názvu otisk obsahu, takže se dají cacheovat na rok;
 *   `index.html` se cacheovat nesmí, jinak by prohlížeč po nasazení držel starou verzi
 *   s odkazy na assety, které už neexistují.
 *
 * Když console v image není (lokální běh serveru bez `npm run build`), routa se
 * nezaregistruje vůbec — API se tím nemění.
 */
fun Application.consoleStaticRoutes() {
    if (loadResource(INDEX) == null) {
        logger.info { "Console není v image (chybí $CONSOLE_ROOT/$INDEX) — servírují se jen API cesty" }
        return
    }
    logger.info { "Console se servíruje z classpath ($CONSOLE_ROOT/)" }

    routing {
        get("/{path...}") {
            val path =
                call.parameters
                    .getAll("path")
                    .orEmpty()
                    .joinToString("/")
            if (RESERVED_PREFIXES.any { path == it || path.startsWith("$it/") }) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", call.callId))
                return@get
            }

            val asset = path.takeIf { it.isNotEmpty() && !it.contains("..") }?.let { loadResource(it) }
            if (asset != null) {
                // Vite dává do jména souboru otisk obsahu, takže nová verze = nová adresa.
                call.response.header(HttpHeaders.CacheControl, IMMUTABLE_CACHE)
                call.respondBytes(asset, contentTypeOf(path))
            } else {
                call.response.header(HttpHeaders.CacheControl, CacheControl.NoCache(null).toString())
                call.respondBytes(loadResource(INDEX)!!, ContentType.Text.Html)
            }
        }
    }
}

/** Cesty, které patří API — ty musí i pro neznámou adresu vracet JSON, ne HTML. */
private val RESERVED_PREFIXES = listOf("api", "health", "webhooks", "slack", "metrics")

private val IMMUTABLE_CACHE =
    CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
        .cacheControl
        .toString() + ", immutable"

private fun loadResource(path: String): ByteArray? =
    Thread
        .currentThread()
        .contextClassLoader
        .getResourceAsStream("$CONSOLE_ROOT/$path")
        ?.use { it.readBytes() }

private fun contentTypeOf(path: String): ContentType =
    when (path.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.Text.JavaScript
        "css" -> ContentType.Text.CSS
        "json", "map" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "ico" -> ContentType.Image.XIcon
        "webmanifest" -> ContentType.Application.Json
        "woff2" -> ContentType.parse("font/woff2")
        "woff" -> ContentType.parse("font/woff")
        "txt" -> ContentType.Text.Plain
        else -> ContentType.Application.OctetStream
    }
