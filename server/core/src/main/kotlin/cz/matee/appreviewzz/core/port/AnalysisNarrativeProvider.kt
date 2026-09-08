package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.MessageLocale

/** Kandidát na citaci ve shrnutí. Model smí citovat jen z těchhle — a jen doslova. */
data class NarrativeQuote(
    val reviewId: String,
    val quote: String,
)

/**
 * Co má model shrnout. Dostává **hotová čísla**, ne recenze: shrnutí je jazyková úloha nad
 * agregáty, ne druhý výpočet. Kdyby si model počítal sám, nešlo by ověřit, že se nespletl.
 */
data class NarrativeRequest(
    val appName: String,
    val locale: MessageLocale,
    /** Agregáty jako JSON — přesně ta čísla, která jdou i do šablonové části zprávy. */
    val aggregatesJson: String,
    val quotes: List<NarrativeQuote>,
)

/**
 * Shrnutí i s tím, co model tvrdí, že cituje. `citedReviewIds` slouží k ověření: id mimo
 * seznam kandidátů znamená, že si model citaci vymyslel, a celé shrnutí se zahodí.
 */
data class Narrative(
    val summary: String,
    val citedReviewIds: List<String>,
)

sealed interface NarrativeResult {
    data class Written(
        val narrative: Narrative,
    ) : NarrativeResult

    /** Instalace bez AI, nebo vypnuté shrnutí. Zpráva odejde v šablonové podobě. */
    data object Unavailable : NarrativeResult

    data class Failed(
        val message: String,
    ) : NarrativeResult
}

/**
 * Slovní shrnutí rozboru (B6). Jediné místo, kde do zprávy sahá jazykový model — a i tam
 * jen do **prvního odstavce**; čísla, témata i citáty pod ním zůstávají šablonové.
 *
 * Implementace nesmí vyhodit výjimku; stejný kontrakt jako [SuggestReplyProvider].
 */
fun interface AnalysisNarrativeProvider {
    suspend fun narrate(request: NarrativeRequest): NarrativeResult
}
