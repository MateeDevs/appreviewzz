package cz.matee.appreviewzz.core.model

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Base32 podle RFC 4648, bez výplně. Jediný důvod, proč tu je: **tímhle abecedním kódováním
 * se sdílí TOTP tajemství** — je to jediný formát, který zvládne přečíst každá autentizační
 * appka i člověk přepisující ho ručně z obrazovky.
 */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and 0xFF)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                out.append(ALPHABET[(buffer shr (bits - BITS_PER_CHAR)) and 0x1F])
                bits -= BITS_PER_CHAR
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and 0x1F])
        return out.toString()
    }

    /** @return `null`, když text obsahuje znak mimo abecedu — tedy překlep při ručním přepisu. */
    fun decode(text: String): ByteArray? {
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        // Mezery a výplň se ignorují: lidé tajemství přepisují po skupinách po čtyřech.
        text.uppercase().filterNot { it == '=' || it.isWhitespace() || it == '-' }.forEach { char ->
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl BITS_PER_CHAR) or value
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                out += ((buffer shr (bits - BITS_PER_BYTE)) and 0xFF).toByte()
                bits -= BITS_PER_BYTE
            }
        }
        return out.toByteArray()
    }
}

/**
 * Jednorázové kódy z autentizační appky (RFC 6238) — druhý faktor přihlášení do console (F5.3).
 *
 * Proč zrovna TOTP a ne SMS nebo magic link: nepotřebuje kanál navíc (SMS bránu, poštu, která
 * u nás dodnes visí na doplnění SMTP), funguje offline a klient si ho zapne sám v appce, kterou
 * už nejspíš má. Pro self-host je to navíc nulová provozní závislost.
 *
 * Parametry jsou schválně ty **výchozí** — SHA-1, šest číslic, třicetisekundový krok. Není to
 * z nevědomosti: Google Authenticator a spol. nic jiného spolehlivě nečtou a HMAC-SHA1 v roli
 * PRF žádnou známou slabinou netrpí.
 */
object Totp {
    const val DIGITS = 6

    /** Krok, po kterém se kód mění. */
    val PERIOD: Duration = 30.seconds

    /**
     * Tolerance k rozejitým hodinám: jeden krok dozadu i dopředu. Víc by prodlužovalo dobu,
     * po kterou je odposlechnutý kód použitelný — a to je proti smyslu druhého faktoru.
     */
    const val DRIFT_STEPS = 1

    /** RFC 4226 chce aspoň 128 bitů; 160 je to, co appky očekávají a co vyrábí každý generátor. */
    private const val SECRET_BYTES = 20

    private const val HMAC_ALGORITHM = "HmacSHA1"
    private val random = SecureRandom()

    fun generateSecret(): SecretPayload {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return SecretPayload(Base32.encode(bytes))
    }

    fun stepAt(instant: Instant): Long = Math.floorDiv(instant.epochSeconds, PERIOD.inWholeSeconds)

    /**
     * Kód pro daný časový krok. Vrací se jako řetězec včetně vedoucích nul — `042173` není
     * číslo 42173 a porovnávat je jako čísla je klasická chyba.
     */
    fun code(
        secret: SecretPayload,
        step: Long,
    ): String {
        val key = Base32.decode(secret.value) ?: return ""
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        val digest = mac.doFinal(step.toByteArray())

        // Dynamic truncation podle RFC 4226 §5.3.
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary =
            ((digest[offset].toInt() and 0x7F) shl 24) or
                ((digest[offset + 1].toInt() and 0xFF) shl 16) or
                ((digest[offset + 2].toInt() and 0xFF) shl 8) or
                (digest[offset + 3].toInt() and 0xFF)
        return (binary % POWERS_OF_TEN[DIGITS]).toString().padStart(DIGITS, '0')
    }

    /**
     * Ověření kódu.
     *
     * @param usedStep poslední krok, který už tenhle účet uplatnil. Bez něj by odposlechnutý
     *   kód šel do konce svého okna použít znovu — třicet sekund je na přehrání spousta času.
     * @return krok, který kód potvrdil, nebo `null`.
     */
    fun matchingStep(
        secret: SecretPayload,
        code: String,
        at: Instant,
        usedStep: Long? = null,
    ): Long? {
        val trimmed = code.filterNot { it.isWhitespace() }
        if (trimmed.length != DIGITS || trimmed.any { !it.isDigit() }) return null
        val current = stepAt(at)
        return (current - DRIFT_STEPS..current + DRIFT_STEPS)
            .firstOrNull { step ->
                if (usedStep != null && step <= usedStep) return@firstOrNull false
                constantTimeEquals(code(secret, step), trimmed)
            }
    }

    /**
     * `otpauth://` odkaz pro QR kód. Vydavatel je v cestě i v parametru schválně: starší appky
     * čtou jedno, novější druhé, a bez něj by v seznamu zbyl jen e-mail bez kontextu.
     */
    fun provisioningUri(
        issuer: String,
        account: String,
        secret: SecretPayload,
    ): String {
        val label = "${issuer.encode()}:${account.encode()}"
        return "otpauth://totp/$label" +
            "?secret=${secret.value}" +
            "&issuer=${issuer.encode()}" +
            "&algorithm=SHA1" +
            "&digits=$DIGITS" +
            "&period=${PERIOD.inWholeSeconds}"
    }

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    private fun Long.toByteArray(): ByteArray = ByteArray(Long.SIZE_BYTES) { i -> (this shr ((7 - i) * 8)).toByte() }

    private fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)
}

/**
 * Záchranné kódy pro případ ztraceného telefonu. Jsou jediná cesta zpátky do účtu, na kterém
 * je zapnutý druhý faktor — bez nich by ztráta telefonu znamenala ruční zásah v databázi.
 *
 * Do databáze jde jen otisk, stejně jako u tokenů z e-mailu ([OpaqueTokens]). Kód má dost
 * entropie na to, aby se nedal hádat, takže sůl ani pomalá funkce nemají co dělat.
 */
object RecoveryCodes {
    const val COUNT = 10
    private const val GROUPS = 2
    private const val GROUP_LENGTH = 5

    /** Bez `0/O/1/I/L` — kódy se opisují z papíru a tyhle znaky se pletou. */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    private val random = SecureRandom()

    fun generate(): List<String> = List(COUNT) { single() }

    /** Normalizace před porovnáním: člověk kód přepíše s pomlčkou i bez ní, velkými i malými. */
    fun normalize(code: String): String = code.lowercase().filter { it in ALPHABET }

    fun hash(code: String): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(normalize(code).toByteArray(Charsets.UTF_8))

    private fun single(): String =
        (1..GROUPS).joinToString(separator = "-") {
            (1..GROUP_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(separator = "")
        }
}
