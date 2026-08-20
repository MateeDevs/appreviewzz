package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * argon2id nad Bouncy Castle. Čistá Java schválně: nativní argon2 přes JNI by z multi-arch
 * image (amd64 + arm64) udělal dvě různé starosti.
 *
 * Výsledek je standardní PHC řetězec
 * `$argon2id$v=19$m=19456,t=2,p=1$<sůl>$<hash>`, takže se dá ověřit i mimo aplikaci
 * a parametry se dají zvednout bez migrace — [verify] čte ty, které jsou v zápisu.
 *
 * Parametry odpovídají doporučení OWASP (19 MiB / 2 iterace / 1 vlákno). Vyšší paměť je
 * lákavá, ale přihlášení běží ve stejném kontejneru jako zbytek API — tohle je hranice,
 * za kterou by pár souběžných loginů začalo brát paměť ingestu.
 */
class Argon2PasswordHasher(
    private val memoryKib: Int = DEFAULT_MEMORY_KIB,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) : PasswordHasher {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    override fun hash(password: SecretPayload): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(password, salt, memoryKib, iterations, parallelism)
        return "\$argon2id\$v=${Argon2Parameters.ARGON2_VERSION_13}" +
            "\$m=$memoryKib,t=$iterations,p=$parallelism" +
            "\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(digest)}"
    }

    override fun verify(
        password: SecretPayload,
        hash: String,
    ): Boolean {
        val parsed = parse(hash) ?: return false
        val computed = derive(password, parsed.salt, parsed.memoryKib, parsed.iterations, parsed.parallelism)
        // MessageDigest.isEqual porovnává v konstantním čase — jinak by délka shody unikala časem.
        return MessageDigest.isEqual(computed, parsed.digest)
    }

    private fun derive(
        password: SecretPayload,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val output = ByteArray(HASH_BYTES)
        generator.generateBytes(password.value.toByteArray(Charsets.UTF_8), output)
        return output
    }

    /** `null` pro cokoli, co není náš zápis — poškozený řádek znamená „heslo nesedí", ne výjimku. */
    private fun parse(hash: String): ParsedHash? {
        val parts = hash.split('$')
        if (parts.size != PHC_PARTS || parts[1] != "argon2id") return null
        if (parts[2] != "v=${Argon2Parameters.ARGON2_VERSION_13}") return null

        val options =
            parts[3]
                .split(',')
                .mapNotNull { option ->
                    val (key, value) = option.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    key to (value.toIntOrNull() ?: return null)
                }.toMap()

        return runCatching {
            ParsedHash(
                memoryKib = options["m"] ?: return null,
                iterations = options["t"] ?: return null,
                parallelism = options["p"] ?: return null,
                salt = decoder.decode(parts[4]),
                digest = decoder.decode(parts[5]),
            )
        }.getOrNull()
    }

    private class ParsedHash(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val digest: ByteArray,
    )

    private companion object {
        const val DEFAULT_MEMORY_KIB = 19_456
        const val DEFAULT_ITERATIONS = 2
        const val DEFAULT_PARALLELISM = 1
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32

        /** `["", "argon2id", "v=19", "m=…,t=…,p=…", "sůl", "hash"]` */
        const val PHC_PARTS = 6
    }
}
