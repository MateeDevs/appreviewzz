package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.storeReplyMaxLength

/**
 * Zadání pro AI: co se stalo v recenzi, jak si to klient přeje odpovídat a kolik znaků
 * store spolkne. Provider nedostává nic z vaultu ani z jiné organizace — jen tohle.
 */
data class ReplySuggestionRequest(
    val platform: Platform,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val appName: String,
    /** Jazyk, ve kterém recenze přišla (`cs`, `en-GB`…); `null`, když ho store neuvádí. */
    val reviewLocale: String?,
    /** Jazyk týmu — použije se, když jazyk recenze neznáme. */
    val teamLocale: MessageLocale,
    /** Per-app instrukce z console: tón, podpis, co nikdy neslibovat. */
    val instructions: String?,
    val maxLength: Int,
) {
    companion object {
        fun of(
            app: App,
            review: Review,
        ): ReplySuggestionRequest =
            ReplySuggestionRequest(
                platform = review.platform,
                starRating = review.starRating,
                title = review.title,
                body = review.body,
                appName = app.name,
                reviewLocale = review.locale,
                teamLocale = app.locale,
                instructions = app.aiInstructions,
                maxLength = review.platform.storeReplyMaxLength,
            )
    }
}

/**
 * Výsledek jednoho pokusu o návrh. Selhání AI **není chyba doručení** — zpráva do kanálu
 * odejde i s prázdným vstupem a člověk odpověď napíše sám. Proto se stavy rozlišují:
 * [Unavailable] je normální konfigurace, [Failed] patří do logu a delivery health.
 */
sealed interface ReplySuggestion {
    data class Suggested(
        val text: String,
        /** Model, který návrh vyrobil — kvůli porovnávání kvality mezi verzemi promptu. */
        val model: String,
    ) : ReplySuggestion

    /** Provider není nastavený (self-host bez AI, `AI_PROVIDER=none`). */
    data object Unavailable : ReplySuggestion

    data class Failed(
        val message: String,
    ) : ReplySuggestion
}

/**
 * Pluggable AI vrstva (plán §5.5). Implementace **nesmí vyhodit výjimku** — návrh odpovědi
 * je pohodlí navíc, ne podmínka doručení recenze.
 */
fun interface SuggestReplyProvider {
    suspend fun suggest(request: ReplySuggestionRequest): ReplySuggestion
}
