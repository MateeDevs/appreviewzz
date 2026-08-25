package cz.matee.appreviewzz.app

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.util.AttributeKey

/**
 * Adresa, na které klient konzoli vidí — z ní se skládají odkazy v e-mailech (potvrzení
 * adresy, obnova hesla, pozvánky). Bez toho by odkaz vedl tam, co je zrovna nastavené
 * v konfiguraci; typicky na `localhost:8080` na stroji, kam se adresát nedostane.
 *
 * Hlavičce se věří **jen** ve spojení s allowlistem v `ConsoleLinks` — hostitele si do
 * požadavku píše klient, takže bez filtru by šlo poslat odkaz na obnovu hesla mířící na
 * cizí server. Tady se řeší jen to, *co* klient tvrdí; jestli tomu věříme, rozhoduje
 * konfigurace domén.
 *
 * `X-Forwarded-Host` se čte stejnou logikou jako `X-Forwarded-For` u [ClientAddress]:
 * bere se `hops`-tá položka od konce, tedy ta, kterou tam napsala naše proxy. Bez proxy
 * (`trustedProxyHops <= 0`) se forwarded hlavičky ignorují úplně a platí `Host`.
 */
class ConsoleOrigin(
    private val trustedProxyHops: Int,
    /** Čím se odkaz začíná, když proxy neřekne jinak. Lokální běh je jediné http. */
    private val defaultHttps: Boolean,
) {
    fun of(call: ApplicationCall): String? {
        val host = forwarded(call, FORWARDED_HOST) ?: call.request.header(HttpHeaders.Host) ?: hostOf(call)
        if (!HOST.matches(host)) return null
        val scheme =
            forwarded(call, FORWARDED_PROTO)?.lowercase()?.takeIf { it == "http" || it == "https" }
                ?: if (defaultHttps) "https" else "http"
        return "$scheme://$host"
    }

    /** Když `Host` chybí (HTTP/2 posílá `:authority`), poskládá se z toho, co Ktor rozparsoval. */
    private fun hostOf(call: ApplicationCall): String {
        val origin = call.request.origin
        val port = origin.serverPort
        return if (port == 80 || port == 443) origin.serverHost else "${origin.serverHost}:$port"
    }

    private fun forwarded(
        call: ApplicationCall,
        name: String,
    ): String? {
        if (trustedProxyHops <= 0) return null
        val chain =
            call.request
                .header(name)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        if (chain.isEmpty()) return null
        return chain.getOrNull((chain.size - trustedProxyHops).coerceAtLeast(0))
    }

    private companion object {
        const val FORWARDED_HOST = "X-Forwarded-Host"
        const val FORWARDED_PROTO = "X-Forwarded-Proto"

        /** Co se sem nevejde, do URL v e-mailu stejně nepatří — a hlavně tam nesmí projít CRLF. */
        val HOST = Regex("""^[A-Za-z0-9._-]+(:\d{1,5})?$""")
    }
}

private val ConsoleOriginKey = AttributeKey<ConsoleOrigin>("appreviewzz.consoleOrigin")

fun Application.installConsoleOrigin(
    trustedProxyHops: Int,
    https: Boolean,
) {
    attributes.put(ConsoleOriginKey, ConsoleOrigin(trustedProxyHops, https))
}

/** Adresa konzole podle požadavku; `null` znamená „ber, co je v konfiguraci". */
fun ApplicationCall.consoleOrigin(): String? = application.attributes.getOrNull(ConsoleOriginKey)?.of(this)
