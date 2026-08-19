package cz.matee.appreviewzz.crypto

import java.util.concurrent.atomic.AtomicLong

/**
 * Počítadla práce se správcem klíčů. V našem provozu nese tutéž informaci CloudTrail a stojí
 * na ní alarm na objem rozbalování (F1.9) — jenže self-host s lokálním keysetem žádný
 * CloudTrail nemá a signál „někdo hromadně odemyká credentials" potřebuje stejně.
 *
 * Čísla jsou od startu procesu, tedy monotónní; alarm se staví na jejich přírůstku.
 */
class KekUsage {
    private val unwraps = AtomicLong()
    private val generated = AtomicLong()
    private val failures = AtomicLong()

    /** Kolikrát se rozbalil datový klíč. V našem provozu odpovídá jednomu `kms:Decrypt`. */
    val unwrapCount: Long get() = unwraps.get()

    /** Kolikrát vznikl nový datový klíč — první credential organizace nebo rotace. */
    val generateCount: Long get() = generated.get()

    /** Kolik volání skončilo chybou správce klíčů (nedostupné KMS, chybějící právo). */
    val failureCount: Long get() = failures.get()

    internal fun recordUnwrap() {
        unwraps.incrementAndGet()
    }

    internal fun recordGenerate() {
        generated.incrementAndGet()
    }

    internal fun recordFailure() {
        failures.incrementAndGet()
    }
}

/**
 * Obal kolem [KekProvider], který počítá volání do [usage]. Záměrně nic nelogguje: řádek
 * ke každému rozbalení by v logu jen šuměl a informaci o objemu už nese metrika.
 */
class MeteredKekProvider(
    private val delegate: KekProvider,
    private val usage: KekUsage,
) : KekProvider {
    override val uri: String get() = delegate.uri

    override fun generateDataKey(): DataKeyMaterial =
        try {
            delegate.generateDataKey().also { usage.recordGenerate() }
        } catch (error: KeyManagementException) {
            usage.recordFailure()
            throw error
        }

    override fun unwrap(wrapped: ByteArray): ByteArray =
        try {
            delegate.unwrap(wrapped).also { usage.recordUnwrap() }
        } catch (error: KeyManagementException) {
            usage.recordFailure()
            throw error
        }
}
