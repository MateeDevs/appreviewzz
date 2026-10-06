package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.storeReplyMaxLength
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.SuggestReplyProvider

/**
 * AI k ruce při odpovídání z konzole (C1, C8). Stejný provider a stejný prompt jako
 * u zprávy do Slacku — člověk v konzoli dostane totéž, co by dostal v kanálu, jen na
 * vyžádání: návrh se počítá, až o něj někdo stojí, ne u každé recenze.
 *
 * Výsledek se nikam neukládá. Je to koncept do formuláře, ne odpověď; odpovědí se stane
 * až tím, že ji člověk odešle.
 */
class ReplyAssistant(
    private val apps: AppRepository,
    private val reviews: ReviewRepository,
    private val suggestions: SuggestReplyProvider,
) {
    suspend fun suggest(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): ReplySuggestion {
        val (app, review) = load(orgId, reviewId)
        return suggestions.suggest(ReplySuggestionRequest.of(app, review))
    }

    /** Překlad konceptu do jazyka recenze. Prázdný koncept nemá co překládat. */
    suspend fun translate(
        orgId: OrganizationId,
        reviewId: ReviewId,
        draft: String,
    ): ReplySuggestion {
        val text = draft.trim()
        if (text.isEmpty()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Není co překládat — napiš odpověď")
        val (app, review) = load(orgId, reviewId)
        if (text.length > review.platform.storeReplyMaxLength) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Odpověď je delší, než store přijme; zkrať ji před překladem")
        }
        return suggestions.suggest(ReplySuggestionRequest.of(app, review).copy(draftToTranslate = text))
    }

    private fun load(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ) = run {
        val review = reviews.findById(orgId, reviewId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková recenze tu není")
        val app = apps.findById(orgId, review.appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        app to review
    }
}
