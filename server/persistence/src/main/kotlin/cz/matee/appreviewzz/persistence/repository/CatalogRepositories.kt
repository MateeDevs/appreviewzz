package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppSettings
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.persistence.schema.Apps
import cz.matee.appreviewzz.persistence.schema.Channels
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class ExposedAppRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : AppRepository {
    override fun create(
        orgId: OrganizationId,
        app: NewApp,
    ): App =
        transaction(database) {
            val created =
                App(
                    id = AppId(Uuid.random()),
                    orgId = orgId,
                    name = app.name,
                    gpPackageName = app.gpPackageName,
                    gpReportingBucket = app.gpReportingBucket,
                    ascAppId = app.ascAppId,
                    locale = app.locale,
                    timezone = app.timezone,
                    notifyFrom = app.notifyFrom,
                    aiInstructions = app.aiInstructions,
                    ingestIntervalMinutes = app.ingestIntervalMinutes,
                    dailyDigestAt = app.dailyDigestAt,
                    enabled = true,
                    createdAt = clock.now(),
                )
            Apps.insert {
                it[id] = created.id
                it[Apps.orgId] = created.orgId
                it[name] = created.name
                it[gpPackageName] = created.gpPackageName
                it[gpReportingBucket] = created.gpReportingBucket
                it[ascAppId] = created.ascAppId
                it[locale] = created.locale.code
                it[timezone] = created.timezone
                it[notifyFrom] = created.notifyFrom
                it[aiInstructions] = created.aiInstructions
                it[ingestIntervalMinutes] = created.ingestIntervalMinutes
                it[dailyDigestAt] = created.dailyDigestAt
                it[enabled] = true
                it[createdAt] = created.createdAt
                it[updatedAt] = created.createdAt
            }
            created
        }

    override fun findById(
        orgId: OrganizationId,
        id: AppId,
    ): App? =
        transaction(database) {
            Apps
                .selectAll()
                .where { (Apps.orgId eq orgId) and (Apps.id eq id) }
                .firstOrNull()
                ?.toApp()
        }

    override fun listByOrg(orgId: OrganizationId): List<App> =
        transaction(database) {
            Apps
                .selectAll()
                .where { Apps.orgId eq orgId }
                .orderBy(Apps.name to SortOrder.ASC)
                .map { it.toApp() }
        }

    override fun listEnabled(): List<App> =
        transaction(database) {
            Apps.selectAll().where { Apps.enabled eq true }.map { it.toApp() }
        }

    override fun updateSettings(
        orgId: OrganizationId,
        id: AppId,
        settings: AppSettings,
    ): App? =
        transaction(database) {
            val updated =
                Apps.update({ (Apps.orgId eq orgId) and (Apps.id eq id) }) {
                    it[name] = settings.name
                    it[gpReportingBucket] = settings.gpReportingBucket
                    it[locale] = settings.locale.code
                    it[timezone] = settings.timezone
                    it[notifyFrom] = settings.notifyFrom
                    it[aiInstructions] = settings.aiInstructions
                    it[ingestIntervalMinutes] = settings.ingestIntervalMinutes
                    it[dailyDigestAt] = settings.dailyDigestAt
                    it[enabled] = settings.enabled
                }
            if (updated == 0) null else findById(orgId, id)
        }

    override fun delete(
        orgId: OrganizationId,
        id: AppId,
    ): Boolean =
        transaction(database) {
            Apps.deleteWhere { (Apps.orgId eq orgId) and (Apps.id eq id) } > 0
        }
}

class ExposedChannelRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : ChannelRepository {
    override fun create(
        orgId: OrganizationId,
        channel: NewChannel,
    ): Channel =
        transaction(database) {
            // Kanál se věší na appku, ne na organizaci — appka z cizí org by tiše propašovala
            // doručování mimo tenant, proto kontrola dřív než zápis.
            val appBelongs =
                Apps.selectAll().where { (Apps.orgId eq orgId) and (Apps.id eq channel.appId) }.firstOrNull() != null
            require(appBelongs) { "App ${channel.appId} nepatří organizaci $orgId" }

            val created =
                Channel(
                    id = ChannelId(Uuid.random()),
                    orgId = orgId,
                    appId = channel.appId,
                    type = channel.type,
                    credentialId = channel.credentialId,
                    targetRef = channel.targetRef,
                    targetLabel = channel.targetLabel,
                    locale = channel.locale,
                    deliverReviews = channel.deliverReviews,
                    deliverRatings = channel.deliverRatings,
                    enabled = true,
                )
            val now = clock.now()
            Channels.insert {
                it[id] = created.id
                it[Channels.orgId] = created.orgId
                it[appId] = created.appId
                it[type] = created.type
                it[credentialId] = created.credentialId
                it[targetRef] = created.targetRef
                it[targetLabel] = created.targetLabel
                it[locale] = created.locale.code
                it[deliverReviews] = created.deliverReviews
                it[deliverRatings] = created.deliverRatings
                it[enabled] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
            created
        }

    override fun findById(
        orgId: OrganizationId,
        id: ChannelId,
    ): Channel? =
        transaction(database) {
            Channels
                .selectAll()
                .where { (Channels.orgId eq orgId) and (Channels.id eq id) }
                .firstOrNull()
                ?.toChannel()
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<Channel> =
        transaction(database) {
            Channels
                .selectAll()
                .where { (Channels.orgId eq orgId) and (Channels.appId eq appId) }
                .map { it.toChannel() }
        }

    override fun setEnabled(
        orgId: OrganizationId,
        id: ChannelId,
        enabled: Boolean,
    ): Boolean =
        transaction(database) {
            Channels.update({ (Channels.orgId eq orgId) and (Channels.id eq id) }) {
                it[Channels.enabled] = enabled
            } > 0
        }

    override fun delete(
        orgId: OrganizationId,
        id: ChannelId,
    ): Boolean =
        transaction(database) {
            Channels.deleteWhere { (Channels.orgId eq orgId) and (Channels.id eq id) } > 0
        }
}
