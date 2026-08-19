package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import java.util.Locale

/**
 * Texty jednoho jazyka. Kanál si katalog vytáhne podle jazyka kanálu (ne organizace) —
 * jeden klient může mít český tým na jedné appce a anglický na druhé.
 */
class MessageCatalog private constructor(
    val locale: MessageLocale,
) {
    /** Jazyk pro formátování data a čísel; odpovídá dnešnímu `date_locale` z n8n. */
    val dateLocale: Locale =
        when (locale) {
            MessageLocale.CS -> Locale.of("cs", "CZ")
            MessageLocale.EN -> Locale.of("en", "US")
        }

    operator fun get(key: MessageKey): String = key.text(locale)

    /**
     * Text s doplněnými placeholdery: `format(SUGGESTED_REPLY_LABEL, "limit" to 350)`.
     *
     * Nedosazený placeholder je chyba programátora, ne provozní stav — kdyby text propadl
     * do Slacku s `{limit}` uvnitř, nikdo si toho v provozu nevšimne, tak to spadne rovnou.
     */
    fun format(
        key: MessageKey,
        vararg values: Pair<String, Any>,
    ): String {
        val text = values.fold(get(key)) { acc, (name, value) -> acc.replace("{$name}", value.toString()) }
        require(!PLACEHOLDER.containsMatchIn(text)) {
            "Text $key v jazyce $locale má nedosazený placeholder: $text"
        }
        return text
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{[a-z_]+}""")
        private val catalogs = MessageLocale.entries.associateWith { MessageCatalog(it) }

        fun of(locale: MessageLocale): MessageCatalog = catalogs.getValue(locale)
    }
}
