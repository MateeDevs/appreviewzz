package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import kotlin.time.Instant

/**
 * Měsíční export recenzí z Play Console (`reviews/reviews_<package>_<YYYYMM>.csv`).
 *
 * Tohle je jediný způsob, jak se dostat k **historii Android recenzí**: `reviews.list` vrací
 * jen zhruba týden zpět a jen recenze s textem. Export má obojí — roky zpátky i hodnocení
 * bez textu, která přes API nikdy nepřijdou.
 *
 * Dvě věci, které z něj plynou a jsou ověřené na reálných exportech:
 *
 * - **ID je až v odkazu.** Poslední sloupec je URL do Play Console s parametrem `reviewId`
 *   (UUID). Je to **jiný jmenný prostor** než `gp:AOqpTO…` z API, takže se samo o sobě
 *   s ničím nespáruje — proto prefix `csv:` a párování přes čas odeslání v [ObservedReview].
 * - **Hodnocení bez textu odkaz nemají vůbec**, takže ani ID. Klíčem je pro ně čas odeslání
 *   v milisekundách: dvě hodnocení téže appky ve stejné milisekundě se nestanou.
 *
 * Co export nenese: jméno autora (u historie zůstane prázdné) a trh — je tu jen jazyk recenzenta.
 */
internal object PlayReviewsCsv {
    /** Prefix ID recenzí z exportu. Odpovědět na ně nejde, tak ať je to v datech vidět. */
    const val ID_PREFIX = "csv:"

    fun parse(bytes: ByteArray): List<ObservedReview> {
        val rows = PlayCsv.rows(PlayCsv.decode(bytes))
        if (rows.size < 2) return emptyList()

        val header = rows.first()
        val columns = Columns(header)
        if (!columns.usable) return emptyList()

        return rows.drop(1).mapNotNull { columns.toObservedReview(it) }
    }

    private class Columns(
        header: List<String>,
    ) {
        val submitMillis = PlayCsv.column(header, "Review Submit Millis Since Epoch")
        val submitAt = PlayCsv.column(header, "Review Submit Date and Time")
        val updateMillis = PlayCsv.column(header, "Review Last Update Millis Since Epoch")
        val updateAt = PlayCsv.column(header, "Review Last Update Date and Time")
        val stars = PlayCsv.column(header, "Star Rating")
        val title = PlayCsv.column(header, "Review Title")
        val body = PlayCsv.column(header, "Review Text")
        val language = PlayCsv.column(header, "Reviewer Language")
        val device = PlayCsv.column(header, "Device")
        val versionName = PlayCsv.column(header, "App Version Name")
        val versionCode = PlayCsv.column(header, "App Version Code")
        val replyMillis = PlayCsv.column(header, "Developer Reply Millis Since Epoch")
        val replyAt = PlayCsv.column(header, "Developer Reply Date and Time")
        val replyText = PlayCsv.column(header, "Developer Reply Text")
        val link = PlayCsv.column(header, "Review Link")

        /** Bez hvězd a času odeslání není z čeho recenzi poskládat — to je jiný soubor. */
        val usable = stars >= 0 && (submitMillis >= 0 || submitAt >= 0)

        fun toObservedReview(cells: List<String>): ObservedReview? {
            val submittedAt = instant(cells, submitMillis, submitAt) ?: return null
            val starRating = PlayCsv.cell(cells, stars)?.toIntOrNull()?.takeIf { it in 1..MAX_STARS } ?: return null
            val updatedAt = instant(cells, updateMillis, updateAt)

            return ObservedReview(
                platform = Platform.ANDROID,
                storeReviewId = ID_PREFIX + (reviewId(cells) ?: submittedAt.toEpochMilliseconds().toString()),
                // Export jméno recenzenta nenese; historie ho tedy mít nebude a konzole
                // to musí unést stejně jako u recenze bez jména z API.
                authorName = null,
                starRating = starRating,
                title = PlayCsv.cell(cells, title),
                body = PlayCsv.cell(cells, body),
                locale = PlayCsv.cell(cells, language),
                // Trh export nezná; jazyk recenzenta není země a míchat je by rozbilo tabulku trhů.
                territory = null,
                appVersion = PlayCsv.cell(cells, versionName) ?: PlayCsv.cell(cells, versionCode),
                device = PlayCsv.cell(cells, device),
                submittedAt = submittedAt,
                storeUpdatedAt = updatedAt?.takeIf { it != submittedAt },
                developerResponseBody = PlayCsv.cell(cells, replyText),
                developerResponseAt = instant(cells, replyMillis, replyAt),
            )
        }

        /** `reviewId` z odkazu do Play Console. Chybí u hodnocení bez textu — ta odkaz nemají. */
        private fun reviewId(cells: List<String>): String? =
            PlayCsv
                .cell(cells, link)
                ?.substringAfter("reviewId=", "")
                ?.substringBefore('&')
                ?.takeIf { it.isNotEmpty() }

        /**
         * Čas přednostně z milisekund: textová podoba má jen sekundy a párování historie
         * s recenzemi z API stojí právě na tom, jak přesně se časy potkají.
         */
        private fun instant(
            cells: List<String>,
            millisColumn: Int,
            textColumn: Int,
        ): Instant? =
            PlayCsv.cell(cells, millisColumn)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
                ?: PlayCsv.cell(cells, textColumn)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    private const val MAX_STARS = 5
}
