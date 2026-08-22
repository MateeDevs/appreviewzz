package cz.matee.appreviewzz.app

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.ktor.server.response.header

/**
 * Hlavičky, kterými prohlížeči říkáme, co si k naší stránce smí dovolit (F5.4).
 *
 * Do F5 tu byly tři nejlevnější (`nosniff`, `DENY`, `no-referrer`). Chybělo to podstatné:
 * **CSP**, tedy jediná obrana, která platí i ve chvíli, kdy se do stránky dostane cizí skript.
 *
 * Console je čistá SPA ze stejného původu — žádné CDN, žádné externí fonty, žádný inline
 * `<script>` — takže `script-src 'self'` nic nerozbije a je to skutečná ochrana, ne kosmetika.
 * `style-src` má `'unsafe-inline'` schválně: React nastavuje styly přes CSSOM a rozdíl mezi
 * povoleným a zakázaným inline stylem je natolik nespolehlivý napříč prohlížeči, že by
 * přísnější hodnota znamenala rozbité obrazovky výměnou za skoro nic.
 */
fun Application.installSecurityHeaders(https: Boolean) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        // Nic z toho console nepoužívá; vypnuté je to pro případ, že by se na stránku
        // dostalo něco cizího.
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
        header("Cross-Origin-Opener-Policy", "same-origin")
        header("Cross-Origin-Resource-Policy", "same-origin")
        // Jen na https: na http ji prohlížeč ignoruje a v lokálním běhu by akorát mátla.
        if (https) header("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
    }

    // Odpovědi API nesou recenze, klíče (jejich otisky) a profil — nemají co ležet
    // v cache prohlížeče ani proxy. Statické soubory console si svoje `Cache-Control`
    // nastavují samy a tenhle interceptor je nechává být.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path().startsWith("/api")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }
}

private val CONTENT_SECURITY_POLICY =
    listOf(
        "default-src 'self'",
        "base-uri 'self'",
        "form-action 'self'",
        // Doplněk k X-Frame-Options pro prohlížeče, které ho už neznají.
        "frame-ancestors 'none'",
        "object-src 'none'",
        "img-src 'self' data:",
        "font-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "connect-src 'self'",
    ).joinToString(separator = "; ")
