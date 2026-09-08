package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReviewArchiveContext
import cz.matee.appreviewzz.core.port.ReviewArchiveSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Historie Android recenzí z reportingu Play Console.
 *
 * Tentýž bucket a tentýž service account jako oficiální hodnocení
 * ([PlayReportingRatingsSource]), jen jiný prefix: `reviews/reviews_<package>_<YYYYMM>.csv`,
 * jeden soubor na měsíc. Soubor aktuálního měsíce Play přepisuje jednou denně, takže se
 * zdroj hodí i na průběžné čtení — a to je důležitější, než se zdá: **hodnocení bez textu**
 * přes API nepřijdou nikdy, takže kdyby se archiv četl jen jednou při onboardingu, měla by
 * appka historii s nimi a živé období bez nich. Objem i nálada by na té hranici skočily
 * a všechna srovnání „co se zlepšilo" by lhala.
 *
 * Chybějící export není chyba, ale prázdný výsledek — appka může být nová, klient nemusí mít
 * v Play Console práva na reporty a měsíc bez jediné recenze soubor vůbec nedostane.
 */
class PlayReportingReviewSource(
    httpClient: HttpClient,
    private val oauth: GoogleOAuth = GoogleOAuth(httpClient),
    baseUrl: String = PlayReportingRatingsSource.GCS_BASE_URL,
) : ReviewArchiveSource {
    private val bucketReader = ReportingBucketReader(httpClient, baseUrl)

    override val platform: Platform = Platform.ANDROID

    override suspend fun fetchArchive(context: ReviewArchiveContext): List<ObservedReview> {
        val bucket = normalizeBucket(context.reportingBucket) ?: return emptyList()
        if (context.until < context.since) return emptyList()

        val account = GoogleServiceAccount.parse(context.credential)
        val token = oauth.accessToken(account, PlayReportingRatingsSource.STORAGE_SCOPE)
        val months = months(context.since, context.until)

        val collected =
            months.flatMap { month ->
                download(bucket, "reviews/reviews_${context.appIdentifier}_$month", token)
            }
        // Soubor je měsíční, kdežto období skoro nikdy — okraje se ořežou až tady.
        val inPeriod = collected.filter { it.submittedAt >= context.since && it.submittedAt <= context.until }
        logger.info {
            "Archiv recenzí ${context.appIdentifier}: ${months.size} měsíců, ${collected.size} řádků, " +
                "${inPeriod.size} v období ${context.since}–${context.until}"
        }
        return inPeriod
    }

    private suspend fun download(
        bucket: String,
        prefix: String,
        token: String,
    ): List<ObservedReview> {
        val media = bucketReader.list(bucket, prefix, token).firstOrNull()?.mediaLink ?: return emptyList()
        return PlayReviewsCsv.parse(bucketReader.download(bucket, media, token))
    }

    /**
     * Měsíce `YYYYMM`, které období protíná, v UTC. Zóna appky by tu byla falešná přesnost:
     * exporty pojmenovává Google podle svého a okraje stejně ořezává filtr nad výsledkem.
     */
    private fun months(
        since: Instant,
        until: Instant,
    ): List<String> {
        val first = since.toLocalDateTime(TimeZone.UTC).date
        val last = until.toLocalDateTime(TimeZone.UTC).date
        var year = first.year
        var month = first.month.number
        val result = mutableListOf<String>()

        while (year < last.year || (year == last.year && month <= last.month.number)) {
            result += "%04d%02d".format(year, month)
            if (month == MONTHS_IN_YEAR) {
                month = 1
                year++
            } else {
                month++
            }
            // Pojistka proti nesmyslnému období: dvě stě volání do Cloud Storage kvůli
            // překlepu v datu nikomu nepomůže.
            if (result.size >= MAX_MONTHS) break
        }
        return result
    }

    companion object {
        private const val MONTHS_IN_YEAR = 12

        /** Strop jednoho běhu. Delší historii než dva roky nikdo nechce rozebírat. */
        const val MAX_MONTHS = 24
    }
}
