package cz.matee.appreviewzz.core.model

import java.security.SecureRandom
import java.text.Normalizer

/**
 * Slug organizace — kus adresy v consoli, takže musí přežít kopírování do e-mailu i do
 * terminálu. Databáze má na tenhle tvar `CHECK`, tady je stejné pravidlo pro validaci.
 */
object Slugs {
    private const val ASCII_LIMIT = 128
    private const val MAX_LENGTH = 63
    private const val SUFFIX_LENGTH = 6

    /** Bez `l`, `o` a `1`/`0` — přípona se přepisuje z e-mailu, tak ať se nedá splést. */
    private const val SUFFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,62}$")
    private val DIACRITICS = Regex("\\p{M}+")
    private val random = SecureRandom()

    /**
     * „Matee interní" → `matee-interni`. Diakritika se převádí, ne zahazuje: `intern-`
     * místo `interni` by vypadalo jako překlep, kterého si nikdo nevšimne včas.
     */
    fun of(name: String): String =
        Normalizer
            .normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .map { if (it.isLetterOrDigit() && it.code < ASCII_LIMIT) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trim('-')

    /**
     * `matee` → `matee-k3fqz7`. Odliší druhou organizaci stejného jména, aniž by se člověk
     * musel zabývat adresou; přípona je náhodná, ne odvozená z názvu — jinak by druhý pokus
     * o stejné jméno spadl na tu samou kolizi.
     */
    fun withSuffix(base: String): String {
        val trimmed = base.take(MAX_LENGTH - SUFFIX_LENGTH - 1).trim('-')
        val suffix = (1..SUFFIX_LENGTH).map { SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)] }.joinToString("")
        return "$trimmed-$suffix"
    }

    fun isValid(slug: String): Boolean = PATTERN.matches(slug)

    /** Věta, kterou uvidí člověk v consoli i v CLI — ať se pravidlo neopisuje dvakrát. */
    const val RULE =
        "2–63 znaků, jen malá písmena bez diakritiky, číslice a pomlčky, " +
            "a musí začínat písmenem nebo číslicí"
}
