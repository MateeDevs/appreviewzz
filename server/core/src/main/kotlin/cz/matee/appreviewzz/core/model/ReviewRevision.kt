package cz.matee.appreviewzz.core.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Uložené znění; observedAt je čas našeho načtení, nikoli čas úpravy ve storu. */
data class ReviewRevision(
    val id: Uuid,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val appVersion: String?,
    val observedAt: Instant,
)
