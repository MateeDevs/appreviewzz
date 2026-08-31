package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult

/** Stavy, ze kterých dává smysl přejít do REPLIED. IGNORED i SUPPRESSED zůstávají, kde jsou. */
private val REPLYABLE_STATES = setOf(ReviewState.NEW, ReviewState.NOTIFIED, ReviewState.UPDATED)

/**
 * Odpověď, která se ve storu objevila mimo náš systém (Play Console, App Store Connect).
 * Recenze je tím pádem vyřízená a nemá cenu ji posílat do kanálu jako novou.
 *
 * Pozor na záměnu s opačným případem: když autor po naší odpovědi recenzi přepíše, přijde
 * změna textu nebo hvězdiček **spolu** s odpovědí, kterou už známe — a to je věc, kterou tým
 * vidět chce. Proto se sem počítá jen běh, ve kterém je odpověď jedinou změnou.
 *
 * Pravidlo je společné pro ingest i pro dohledávání ([RefreshStoreRepliesUseCase]) schválně:
 * kdyby se rozešla, tatáž odpověď by podle cesty, kterou dorazila, skončila jednou v REPLIED
 * a jednou v UPDATED.
 *
 * @return true, když se recenze právě překlopila do [ReviewState.REPLIED]
 */
internal fun markAnsweredInStore(
    reviews: ReviewRepository,
    result: ReviewUpsertResult,
): Boolean {
    val review = result.review
    if (review.developerResponseBody == null) return false
    val responseIsTheNews =
        when (result.outcome) {
            ReviewUpsertOutcome.CREATED -> true
            ReviewUpsertOutcome.UPDATED -> result.changes == setOf(ReviewChange.DEVELOPER_RESPONSE)
            ReviewUpsertOutcome.UNCHANGED -> false
        }
    if (!responseIsTheNews || review.state !in REPLYABLE_STATES) return false

    reviews.updateState(review.orgId, review.id, ReviewState.REPLIED)
    return true
}
