package cz.matee.appreviewzz.app.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter
import net.logstash.logback.mask.ValueMasker
import tools.jackson.core.TokenStreamContext

/** Co se v logu nahrazuje. */
private const val MASK = "**redacted**"

/**
 * Redakce logů (F5.4).
 *
 * [cz.matee.appreviewzz.core.model.SecretPayload] hlídá, aby tajemství neproteklo *naším*
 * kódem. Tohle je druhá vrstva pro to, co si nekontrolujeme: text výjimky z cizí knihovny,
 * odpověď storu vypsaná při chybě, tělo požadavku ve stacku. Obojí je potřeba — filtr sám
 * o sobě je jen regulární výraz a ten se dá obejít, typový obal zase neplatí mimo náš kód.
 *
 * Vzory jsou schválně **konzervativní**: raději nechat projít něco, co tajemství není, než
 * začernit půlku logu a udělat z něj nepoužitelnou věc. Každý z nich odpovídá formátu, který
 * se v tomhle systému opravdu vyskytuje.
 */
object LogRedaction {
    private val rules: List<Pair<Regex, String>> =
        listOf(
            // Privátní klíč v PEM (App Store Connect .p8, service account). Nejdůležitější
            // vzor ze všech: jeden takový řádek v logu je celý klíč ke storu klienta.
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""") to
                "-----BEGIN PRIVATE KEY-----$MASK-----END PRIVATE KEY-----",
            // `"private_key": "..."` ze service account JSONu — než se stihne stát PEMem.
            Regex(""""private_key"\s*:\s*"(?:[^"\\]|\\.)*"""") to """"private_key":"$MASK"""",
            // Tokeny Slacku: bot (xoxb), user (xoxp), app (xapp), refresh (xoxe).
            Regex("""\bxox[bpasre]-[A-Za-z0-9-]{8,}""") to MASK,
            // JWT — tokeny Bot Connectoru, Googlu i App Store Connectu. Tři base64url části.
            Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""") to MASK,
            // Hlavička Authorization, ať už v dumpu požadavku nebo ve výjimce klienta.
            Regex("""(?i)\b(authorization)\s*[:=]\s*("?)(?:bearer\s+|basic\s+)?[^\s",;)]+""") to "$1: $MASK",
            // Přiřazení, kde je tajemství v názvu: `client_secret=…`, `"apiKey": "…"`.
            Regex(
                """(?i)\b(password|passwd|client_secret|signing_secret|api[_-]?key|access_token|refresh_token)""" +
                    """"?\s*[:=]\s*("?)[^\s",;&)]+""",
            ) to "$1=$MASK",
        )

    fun redact(text: String): String =
        if (text.isEmpty()) {
            text
        } else {
            rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
        }
}

/**
 * Redakce pro textový formát logu. Registruje se jako `%redact(...)` v `logback.xml`.
 *
 * Musí obalit i `%ex`: text výjimky ze storu bývá delší a upovídanější než naše hláška.
 */
class RedactingConverter : CompositeConverter<ILoggingEvent>() {
    override fun transform(
        event: ILoggingEvent,
        input: String,
    ): String = LogRedaction.redact(input)
}

/**
 * Redakce pro JSON formát. Logstash encoder pouští každou hodnotu tudy — což je přesně to,
 * co chceme: pokrývá zprávu, stack trace i vlastní pole, aniž bychom je museli vyjmenovat.
 */
class RedactingValueMasker : ValueMasker {
    override fun mask(
        context: TokenStreamContext,
        value: Any?,
    ): Any? {
        if (value !is CharSequence) return null
        val text = value.toString()
        val redacted = LogRedaction.redact(text)
        // `null` znamená „nemaskuj" — vracet nezměněný řetězec by encoder nutil ho přepisovat.
        return if (redacted == text) null else redacted
    }
}
