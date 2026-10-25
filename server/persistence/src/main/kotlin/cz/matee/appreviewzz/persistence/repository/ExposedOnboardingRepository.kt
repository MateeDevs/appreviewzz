package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.OnboardingProgress
import cz.matee.appreviewzz.core.port.OnboardingRepository
import cz.matee.appreviewzz.core.port.OnboardingSync
import cz.matee.appreviewzz.persistence.schema.appId
import cz.matee.appreviewzz.persistence.schema.instant
import cz.matee.appreviewzz.persistence.schema.organizationId
import cz.matee.appreviewzz.persistence.schema.userId
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private object Progress : Table("onboarding_progress") {
    val org = organizationId("org_id")
    val user = userId("user_id")
    val app = appId("app_id").nullable()
    val step = text("step")
    val preferences = bool("preferences_confirmed")
    val completed = bool("completed")
    override val primaryKey = PrimaryKey(org, user)
}

private object Syncs : Table("onboarding_sync") {
    val org = organizationId("org_id")
    val app = appId("app_id")
    val platform = enumerationByName<Platform>("platform", 32)
    val status = text("status")
    val started = instant("started_at")
    val finished = instant("finished_at").nullable()
    val fetched = integer("fetched").nullable()
    val message = text("message").nullable()
    override val primaryKey = PrimaryKey(org, app, platform)
}

class ExposedOnboardingRepository(
    private val database: Database,
) : OnboardingRepository {
    override fun find(
        orgId: OrganizationId,
        userId: UserId,
    ): OnboardingProgress? =
        transaction(database) {
            Progress.selectAll().where { (Progress.org eq orgId) and (Progress.user eq userId) }.firstOrNull()?.let {
                OnboardingProgress(it[Progress.app], it[Progress.step], it[Progress.preferences], it[Progress.completed])
            }
        }

    override fun save(
        orgId: OrganizationId,
        userId: UserId,
        progress: OnboardingProgress,
    ) {
        transaction(database) {
            Progress.upsert {
                it[org] = orgId
                it[user] = userId
                it[app] = progress.appId
                it[step] = progress.step
                it[preferences] = progress.preferencesConfirmed
                it[completed] = progress.completed
            }
        }
    }

    override fun syncs(
        orgId: OrganizationId,
        appId: AppId,
    ): List<OnboardingSync> =
        transaction(database) {
            Syncs.selectAll().where { (Syncs.org eq orgId) and (Syncs.app eq appId) }.map {
                OnboardingSync(
                    it[Syncs.platform],
                    it[Syncs.status],
                    it[Syncs.started],
                    it[Syncs.finished],
                    it[Syncs.fetched],
                    it[Syncs.message],
                )
            }
        }

    override fun startSync(
        orgId: OrganizationId,
        appId: AppId,
        platforms: Set<Platform>,
        now: Instant,
    ): Boolean =
        transaction(database) {
            // Zamykáme aplikaci i při prvním běhu, kdy ještě neexistuje žádný řádek synchronizace.
            cz.matee.appreviewzz.persistence.schema.Apps
                .selectAll()
                .where {
                    (cz.matee.appreviewzz.persistence.schema.Apps.orgId eq orgId) and
                        (cz.matee.appreviewzz.persistence.schema.Apps.id eq appId)
                }.forUpdate()
                .firstOrNull() ?: return@transaction false
            if (syncs(orgId, appId).any { it.status == "RUNNING" && now - it.startedAt < 5.minutes }) return@transaction false
            platforms.forEach { target ->
                Syncs.upsert {
                    it[org] = orgId
                    it[app] = appId
                    it[platform] = target
                    it[status] = "RUNNING"
                    it[started] = now
                    it[finished] = null
                    it[fetched] = null
                    it[message] = null
                }
            }
            true
        }

    override fun finishSync(
        orgId: OrganizationId,
        appId: AppId,
        sync: OnboardingSync,
    ) {
        transaction(database) {
            // Starý pomalý běh nesmí přepsat nový lease po jeho vypršení.
            Syncs.update({
                (Syncs.org eq orgId) and (Syncs.app eq appId) and (Syncs.platform eq sync.platform) and
                    (Syncs.started eq sync.startedAt)
            }) {
                it[status] = sync.status
                it[started] = sync.startedAt
                it[finished] = sync.finishedAt
                it[fetched] = sync.fetched
                it[message] = sync.message
            }
        }
    }
}
