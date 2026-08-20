package cz.matee.appreviewzz.core.model

import java.text.Normalizer

/**
 * Slug organizace — kus adresy v consoli, takže musí přežít kopírování do e-mailu i do
 * terminálu. Databáze má na tenhle tvar `CHECK`, tady je stejné pravidlo pro validaci.
 */
object Slugs {
    private const val ASCII_LIMIT = 128
    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,62}$")
    private val DIACRITICS = Regex("\\p{M}+")

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

    fun isValid(slug: String): Boolean = PATTERN.matches(slug)

    /** Věta, kterou uvidí člověk v consoli i v CLI — ať se pravidlo neopisuje dvakrát. */
    const val RULE =
        "2–63 znaků, jen malá písmena bez diakritiky, číslice a pomlčky, " +
            "a musí začínat písmenem nebo číslicí"
}
