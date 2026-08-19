package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import kotlin.time.Instant

internal object Fixtures {
    val seenAt: Instant = Instant.parse("2026-08-19T10:00:00Z")

    fun observedReview(
        storeReviewId: String = "gp:12345",
        platform: Platform = Platform.ANDROID,
        starRating: Int = 4,
        body: String? = "Funguje dobře, jen notifikace chodí pozdě.",
        title: String? = null,
        appVersion: String? = "3.2.1",
        developerResponseBody: String? = null,
        submittedAt: Instant = Instant.parse("2026-08-19T09:30:00Z"),
        storeUpdatedAt: Instant? = null,
    ): ObservedReview =
        ObservedReview(
            platform = platform,
            storeReviewId = storeReviewId,
            authorName = "Jana N.",
            starRating = starRating,
            title = title,
            body = body,
            locale = "cs",
            territory = "CZ",
            appVersion = appVersion,
            device = "Pixel 8",
            submittedAt = submittedAt,
            storeUpdatedAt = storeUpdatedAt,
            developerResponseBody = developerResponseBody,
            developerResponseAt = null,
        )
}
