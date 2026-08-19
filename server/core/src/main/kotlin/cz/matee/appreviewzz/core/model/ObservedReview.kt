package cz.matee.appreviewzz.core.model

import kotlin.time.Instant

/**
 * Recenze tak, jak ji právě vidí store — výstup konektoru před uložením.
 * Nezná ID v naší databázi; identitu drží dvojice (platform, storeReviewId).
 */
data class ObservedReview(
    val platform: Platform,
    val storeReviewId: String,
    val authorName: String?,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val locale: String?,
    val territory: String?,
    val appVersion: String?,
    val device: String?,
    val submittedAt: Instant,
    val storeUpdatedAt: Instant?,
    val developerResponseBody: String?,
    val developerResponseAt: Instant?,
) {
    init {
        require(starRating in 1..5) { "Hvězdičky mimo rozsah: $starRating (recenze $storeReviewId)" }
        require(storeReviewId.isNotBlank()) { "Recenze bez ID ze storu" }
    }

    /**
     * Otisk toho, co člověk v recenzi uvidí. Změna otisku znamená, že recenzi někdo editoval
     * (umí to obě platformy) — dnešní n8n dedup nad seznamem zpracovaných ID tohle prošvihne.
     * Vlastní odpověď je součástí otisku, aby se poznalo i její doplnění mimo náš systém.
     */
    fun contentHash(): String {
        // Délkový prefix u každého pole: bez něj mají "ab"+"c" a "a"+"bc" stejný otisk
        // a editace, která jen přesune znak mezi titulkem a tělem, by prošla nepovšimnuta.
        val canonical =
            listOf(
                starRating.toString(),
                title.orEmpty(),
                body.orEmpty(),
                appVersion.orEmpty(),
                developerResponseBody.orEmpty(),
            ).joinToString(separator = "") { field -> field.length.toString() + ":" + field }
        return sha256Hex(canonical)
    }
}
