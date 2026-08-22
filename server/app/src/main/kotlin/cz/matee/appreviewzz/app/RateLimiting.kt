package cz.matee.appreviewzz.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Kolik požadavků a za jak dlouho. `burst` je zároveň velikost kbelíku i počet, který se do
 * něj za `window` doleje — takže „60 za minutu" znamená minutový nádech na začátku a pak
 * jeden požadavek za sekundu, ne šedesát a pak ticho.
 */
data class RateLimitRule(
    val name: String,
    val burst: Int,
    val window: Duration,
) {
    init {
        require(burst > 0) { "Limit '$name' musí dovolit aspoň jeden požadavek" }
        require(window.isPositive()) { "Okno limitu '$name' musí být kladné" }
    }

    internal val tokensPerSecond: Double get() = burst / window.inWholeMilliseconds.toDouble() * 1000.0
}

sealed interface RateLimitDecision {
    data class Allowed(
        val remaining: Int,
    ) : RateLimitDecision

    data class Rejected(
        val retryAfter: Duration,
    ) : RateLimitDecision
}

/**
 * Token bucket v paměti procesu (F5.1).
 *
 * **Platí per instance, ne per cluster.** S jedním API kontejnerem je to totéž; kdyby jich
 * jednou běželo víc, limit se násobí jejich počtem a je potřeba sdílený stav (Postgres,
 * nebo limity na reverzní proxy). Napsané schválně tak, aby to bylo vidět v jedné třídě —
 * s Redisem „pro jistotu" by přibyla závislost, kterou self-host nepotřebuje.
 *
 * Limit **nenahrazuje** zamykání účtu po sérii špatných hesel (to je v `AuthenticationService`
 * a přežije restart). Řeší jinou věc: cizí zdroj, který zkouší tisíce hesel nebo jen zahltí
 * argon2, se nedostane ani k prvnímu hashi.
 */
class RateLimiter(
    val rule: RateLimitRule,
    private val clock: Clock = Clock.System,
    /** Strop na počet sledovaných klíčů — sám kbelík nesmí být cesta, jak sníst paměť. */
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    private val onReject: (String) -> Unit = {},
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    val trackedKeys: Int get() = buckets.size

    fun check(key: String): RateLimitDecision {
        val now = clock.now()
        if (buckets.size >= maxKeys) evictIdle(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(rule.burst.toDouble(), now) }
        val decision =
            synchronized(bucket) {
                bucket.refill(now, rule)
                bucket.take(rule)
            }
        if (decision is RateLimitDecision.Rejected) onReject(key)
        return decision
    }

    /**
     * Úklid. Kbelík, na který se dvě okna nesáhlo, je zaručeně plný — a plný kbelík se od
     * nově založeného ničím neliší, takže se dá zahodit. Je to jediné místo, kde mapa ubývá;
     * vlastní vlákno kvůli tomuhle nestojí za to.
     */
    private fun evictIdle(now: Instant) {
        val idleSince = now - rule.window * IDLE_WINDOWS
        buckets.entries.removeIf { (_, bucket) ->
            synchronized(bucket) { bucket.updatedAt < idleSince }
        }
        if (buckets.size >= maxKeys) {
            logger.warn { "Limit '${rule.name}' sleduje ${buckets.size} klíčů — nejspíš rozprostřený útok" }
        }
    }

    private class Bucket(
        var tokens: Double,
        var updatedAt: Instant,
    ) {
        fun refill(
            now: Instant,
            rule: RateLimitRule,
        ) {
            val elapsed = (now - updatedAt).inWholeMilliseconds.coerceAtLeast(0) / 1000.0
            tokens = min(rule.burst.toDouble(), tokens + elapsed * rule.tokensPerSecond)
            updatedAt = now
        }

        fun take(rule: RateLimitRule): RateLimitDecision {
            if (tokens >= 1.0) {
                tokens -= 1.0
                return RateLimitDecision.Allowed(floor(tokens).toInt())
            }
            val seconds = ceil((1.0 - tokens) / rule.tokensPerSecond).toLong().coerceAtLeast(1)
            return RateLimitDecision.Rejected(seconds.seconds)
        }
    }

    private companion object {
        const val DEFAULT_MAX_KEYS = 50_000
        const val IDLE_WINDOWS = 2
    }
}

/**
 * Limity, které API používá. Pohromadě ze stejného důvodu jako [cz.matee.appreviewzz.core.usecase.AuthPolicy] —
 * ať je na jednom místě vidět, co všechno se posune, když se čísla zvednou.
 */
class RateLimits(
    config: RateLimitConfig,
    registry: MeterRegistry? = null,
    clock: Clock = Clock.System,
) {
    val enabled: Boolean = config.enabled

    /** Všechno pod `/api` — strop proti scrapování console cizím skriptem. */
    val api: RateLimiter? = limiter("api", config.apiPerMinute, 1.minutes, registry, clock)

    /** Přihlašovací endpointy per adresa. Přísnější: tudy se hádají hesla. */
    val auth: RateLimiter? = limiter("auth", config.authPerFiveMinutes, AUTH_WINDOW, registry, clock)

    /**
     * Přihlašovací endpointy per e-mail. Adresu si útočník snadno vymění, cílový účet ne —
     * bez tohohle by distribuovaný pokus o jedno konkrétní heslo prošel pod IP limitem.
     */
    val authIdentity: RateLimiter? = limiter("auth-identity", config.authPerIdentity, AUTH_WINDOW, registry, clock)

    /** Webhooky. Ověření podpisu stojí procesor, takže se limituje ještě před ním. */
    val webhook: RateLimiter? = limiter("webhook", config.webhookPerMinute, 1.minutes, registry, clock)

    private fun limiter(
        name: String,
        burst: Int,
        window: Duration,
        registry: MeterRegistry?,
        clock: Clock,
    ): RateLimiter? {
        if (!enabled) return null
        val counter = registry?.counter("appreviewzz.rate_limit.rejected", "rule", name)
        return RateLimiter(
            rule = RateLimitRule(name, burst, window),
            clock = clock,
            onReject = { counter?.increment() },
        )
    }

    companion object {
        val AUTH_WINDOW = 5.minutes

        /** Testy a self-host, který si limity řeší na proxy. */
        fun disabled(): RateLimits = RateLimits(RateLimitConfig(enabled = false))
    }
}

/**
 * Limit na celý podstrom cest. Nasazuje se stejně jako [requireCsrf] — na stromě, ne
 * v handlerech, aby ho nová sekce API nemohla vynechat.
 */
fun Route.rateLimited(
    limiter: RateLimiter?,
    keyOf: (ApplicationCall) -> String = { it.clientIp() ?: UNKNOWN_CLIENT },
) {
    if (limiter == null) return
    (this as RoutingNode).intercept(ApplicationCallPipeline.Plugins) {
        val decision = limiter.check(keyOf(call))
        if (decision is RateLimitDecision.Rejected) {
            call.respondRateLimited(limiter.rule, decision)
            return@intercept finish()
        }
    }
}

/**
 * Limit na vyjmenovanou skupinu cest. Používá se tam, kde limitovaný podstrom nekopíruje
 * celou sekci — přihlašovací endpointy ano, ale vydání CSRF tokenu nad nimi ne.
 */
fun Route.rateLimitedGroup(
    limiter: RateLimiter?,
    keyOf: (ApplicationCall) -> String = { it.clientIp() ?: UNKNOWN_CLIENT },
    build: Route.() -> Unit,
): Route {
    val child = (this as RoutingNode).createChild(TransparentSelector())
    child.rateLimited(limiter, keyOf)
    child.build()
    return child
}

/**
 * Limit uvnitř handleru — pro klíč, který je až v těle požadavku (e-mail u přihlášení).
 * Vrací `true`, když se má pokračovat.
 */
suspend fun ApplicationCall.allowedBy(
    limiter: RateLimiter?,
    key: String,
): Boolean {
    val decision = limiter?.check(key) ?: return true
    if (decision is RateLimitDecision.Rejected) {
        respondRateLimited(limiter.rule, decision)
        return false
    }
    return true
}

private suspend fun ApplicationCall.respondRateLimited(
    rule: RateLimitRule,
    decision: RateLimitDecision.Rejected,
) {
    logger.warn { "Limit '${rule.name}' odmítl ${request.local.uri} od ${clientIp()}" }
    response.header("Retry-After", decision.retryAfter.inWholeSeconds.toString())
    respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse(
            error = "rate_limited",
            requestId = callId,
            message = "Moc požadavků za sebou, zkus to za ${decision.retryAfter.inWholeSeconds} s",
        ),
    )
}

/** Když se adresa klienta nedá zjistit, sdílí všichni takoví jeden kbelík. */
const val UNKNOWN_CLIENT = "unknown"
