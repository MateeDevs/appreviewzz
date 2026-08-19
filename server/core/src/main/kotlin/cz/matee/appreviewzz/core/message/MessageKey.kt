package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale

/**
 * Katalog produktových textů, které vidí zákazníkův tým ve Slacku a v Teams.
 *
 * Znění je **převzaté 1:1 z dnešního n8n řešení** (i18n workflow „Get Localized Strings"),
 * aby klienti po migraci nepoznali rozdíl. Obě jazykové varianty jsou schválně vedle sebe
 * v jednom řádku — překlad, který se rozejde, je pak vidět na první pohled.
 *
 * Proti n8n jsou tři rozdíly, všechny záměrné:
 *
 * - **Chybí fallback na neznámý jazyk?** Nemůže nastat: jazyk je enum [MessageLocale], ne
 *   textové pole v tabulce. V n8n neznámá hodnota tiše zabila celou pipeline dané appky.
 * - **Texty chybějící v n8n** (chyba publikace odpovědi, aktualizovaná recenze) tam byly
 *   natvrdo anglicky v kódu uzlu; tady mají klíč a překlad.
 * - **Placeholder** je jednotný `{limit}`; n8n hledal `[[limit]]` i `{{limit}}` podle toho,
 *   kdo text zrovna psal.
 */
enum class MessageKey(
    private val cs: String,
    private val en: String,
) {
    SEND("Odeslat", "Send"),
    SUGGESTED_REPLY_LABEL("Návrh odpovědi (max {limit} znaků)", "Suggested reply (max {limit} characters)"),
    ALREADY_REPLIED_WARNING_ANDROID(
        "V minulosti jste již odpověděli. Další odpověď bude přidána do konverzace.",
        "You’ve already replied in the past. Another reply will be added to the thread.",
    ),
    ALREADY_REPLIED_WARNING_IOS(
        "V minulosti jste již odpověděli. Nová odpověď přepíše tu předchozí.",
        "You’ve already replied in the past. The new reply will replace the previous one.",
    ),
    YOU_ALREADY_REPLIED("Už jste na tuto recenzi odpověděli:", "You already replied to this review:"),
    YOU_REPLIED("Odpověděli jste", "You replied"),
    REVIEW_PROCESSED("Recenze byla zpracována", "Review was processed"),
    RATINGS_SUMMARY_TITLE("Souhrn hodnocení", "Ratings Summary"),
    RATINGS_SUMMARY_FOR_WORD("pro", "for"),
    DATE_LABEL("Datum", "Date"),
    TODAY_LABEL("Dnes", "Today"),
    TOTAL_LABEL("Celkem", "Total"),
    DELTA_LABEL("Δ vůči celkem", "Δ vs Total"),
    NEW_RATINGS_TODAY_LABEL("Nová hodnocení", "New ratings"),
    APP_HAS_NEW_REVIEW("Nová recenze aplikace {app}!", "New review for {app}!"),
    REVIEW("recenze", "review"),
    NEW("nová", "new"),
    USER_FALLBACK("Uživatel", "User"),
    APP_FALLBACK("Aplikace", "App"),
    VERSION_LABEL("verze", "version"),
    DEVELOPER_LABEL("Vývojář", "Developer"),
    MISSING_REVIEW_TEXT("Chybí text recenze.", "No review text."),
    IOS_ONLY_LABEL("Jen iOS", "iOS Only"),
    RATINGS_OVERVIEW_TITLE("Přehled hodnocení pro aplikaci", "New Ratings Overview delivered for"),

    /** Recenzi autor po doručení přepsal — v n8n neexistovalo, protože editace zapadla. */
    REVIEW_UPDATED("Aktualizovaná recenze", "Updated review"),
    REPLY_FAILED_TITLE("Odpověď se nepodařilo odeslat", "Your reply could not be posted"),
    ERROR_LABEL("Chyba", "Error"),
    ;

    internal fun text(locale: MessageLocale): String =
        when (locale) {
            MessageLocale.CS -> cs
            MessageLocale.EN -> en
        }
}
