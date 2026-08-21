package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.RatingSnapshotRepository

/** Jeden bod v grafu vývoje hodnocení. */
data class RatingsPoint(
    val snapshot: RatingSnapshot,
    /** Kolik hodnocení přibylo proti předchozímu bodu; `null` u nejstaršího. */
    val newCount: Long?,
)

/** Vývoj hodnocení jedné platformy. Prázdná řada je legitimní — appka může být čerstvá. */
data class RatingsSeries(
    val platform: Platform,
    val territory: String,
    /** Od nejstaršího k nejnovějšímu, ať se graf kreslí zleva doprava. */
    val points: List<RatingsPoint>,
) {
    val latest: RatingSnapshot? get() = points.lastOrNull()?.snapshot

    /** Změna průměru za celé zobrazené období; `null`, když není co srovnat. */
    val change: Double?
        get() {
            val first = points.firstOrNull()?.snapshot?.average ?: return null
            val last = points.lastOrNull()?.snapshot?.average ?: return null
            return if (points.size < 2) null else last - first
        }
}

/**
 * Vývoj hodnocení pro graf v consoli (F4.5).
 *
 * Čte se výhradně `GLOBAL` řada: rozpad po storefrontech v databázi je, ale v grafu by
 * dvacet čar znamenalo, že není vidět nic. Kdo ho potřebuje, sáhne si do dat přímo.
 */
class RatingsInsights(
    private val apps: AppRepository,
    private val snapshots: RatingSnapshotRepository,
) {
    fun history(
        orgId: OrganizationId,
        appId: AppId,
        days: Int = DEFAULT_DAYS,
    ): List<RatingsSeries> {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val limit = days.coerceIn(MIN_DAYS, MAX_DAYS)

        return app
            .platforms()
            .sortedBy { it.ordinal }
            .map { platform ->
                val ordered =
                    snapshots
                        .listRecent(orgId, app.id, platform, ObservedRatings.GLOBAL, limit)
                        .sortedBy { it.date }
                RatingsSeries(platform, ObservedRatings.GLOBAL, points(ordered))
            }
    }

    /**
     * Přírůstek počtu hodnocení mezi body. Záporný rozdíl se zahodí: store hodnocení maže
     * a „−12 nových" by v grafu vypadalo jako chyba dat, i když je to normální stav.
     */
    private fun points(ordered: List<RatingSnapshot>): List<RatingsPoint> =
        ordered.mapIndexed { index, snapshot ->
            val previous = ordered.getOrNull(index - 1)
            val newCount =
                if (previous?.totalCount != null && snapshot.totalCount != null) {
                    (snapshot.totalCount - previous.totalCount).coerceAtLeast(0)
                } else {
                    null
                }
            RatingsPoint(snapshot, newCount)
        }

    private companion object {
        const val DEFAULT_DAYS = 30
        const val MIN_DAYS = 2
        const val MAX_DAYS = 180
    }
}
