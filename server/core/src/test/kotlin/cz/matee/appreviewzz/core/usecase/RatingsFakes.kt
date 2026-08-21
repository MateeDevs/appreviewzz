package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.model.RatingSnapshotId
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.core.port.RatingSnapshotRepository
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsDigestRepository
import cz.matee.appreviewzz.core.port.RatingsSource
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Snapshoty v paměti se stejnou sémantikou jako v databázi: klíč je (app, platforma, den, země). */
internal class FakeRatingSnapshotRepository : RatingSnapshotRepository {
    private val stored = mutableMapOf<Key, RatingSnapshot>()

    fun seed(
        orgId: OrganizationId,
        snapshot: NewRatingSnapshot,
        collectedAt: Instant,
    ) = upsert(orgId, snapshot, collectedAt)

    override fun upsert(
        orgId: OrganizationId,
        snapshot: NewRatingSnapshot,
        collectedAt: Instant,
    ): RatingSnapshot {
        val key = Key(snapshot.appId, snapshot.platform, snapshot.date, snapshot.territory)
        val row =
            RatingSnapshot(
                id = stored[key]?.id ?: RatingSnapshotId(Uuid.random()),
                orgId = orgId,
                appId = snapshot.appId,
                platform = snapshot.platform,
                date = snapshot.date,
                territory = snapshot.territory,
                average = snapshot.average,
                totalCount = snapshot.totalCount,
                histogram = snapshot.histogram,
                source = snapshot.source,
                collectedAt = collectedAt,
            )
        stored[key] = row
        return row
    }

    override fun findByDate(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        date: LocalDate,
        territory: String,
    ): RatingSnapshot? = stored[Key(appId, platform, date, territory)]?.takeIf { it.orgId == orgId }

    override fun listRecent(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        territory: String,
        limit: Int,
    ): List<RatingSnapshot> =
        stored.values
            .filter { it.orgId == orgId && it.appId == appId && it.platform == platform && it.territory == territory }
            .sortedByDescending { it.date }
            .take(limit)

    fun all(): List<RatingSnapshot> = stored.values.toList()

    private data class Key(
        val appId: AppId,
        val platform: Platform,
        val date: LocalDate,
        val territory: String,
    )
}

internal class FakeRatingsDigestRepository : RatingsDigestRepository {
    private val claimed = mutableSetOf<Pair<ChannelId, LocalDate>>()

    override fun claim(
        orgId: OrganizationId,
        appId: AppId,
        channelId: ChannelId,
        date: LocalDate,
        sentAt: Instant,
    ): Boolean = claimed.add(channelId to date)

    override fun lastSent(
        orgId: OrganizationId,
        channelId: ChannelId,
    ): LocalDate? = claimed.filter { it.first == channelId }.maxOfOrNull { it.second }
}

/** Zdroj hodnocení, který vrátí, co mu test nastaví — nebo předvede selhání storu. */
internal class FakeRatingsSource(
    override val platform: Platform,
    override val priority: Int,
    private val result: List<ObservedRatings> = emptyList(),
    private val failWith: Throwable? = null,
) : RatingsSource {
    val calls = mutableListOf<RatingsContext>()

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        calls += context
        failWith?.let { throw it }
        return result
    }
}
