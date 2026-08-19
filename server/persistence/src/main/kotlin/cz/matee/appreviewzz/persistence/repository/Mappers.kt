package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.BackupRun
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.FailedJob
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrgDataKey
import cz.matee.appreviewzz.core.model.OrgMembership
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.persistence.schema.AppCredentials
import cz.matee.appreviewzz.persistence.schema.Apps
import cz.matee.appreviewzz.persistence.schema.AuditLogs
import cz.matee.appreviewzz.persistence.schema.BackupRuns
import cz.matee.appreviewzz.persistence.schema.Channels
import cz.matee.appreviewzz.persistence.schema.Credentials
import cz.matee.appreviewzz.persistence.schema.FailedJobs
import cz.matee.appreviewzz.persistence.schema.OrgDataKeys
import cz.matee.appreviewzz.persistence.schema.OrgMembers
import cz.matee.appreviewzz.persistence.schema.Organizations
import cz.matee.appreviewzz.persistence.schema.RatingSnapshots
import cz.matee.appreviewzz.persistence.schema.Replies
import cz.matee.appreviewzz.persistence.schema.ReviewMessages
import cz.matee.appreviewzz.persistence.schema.Reviews
import cz.matee.appreviewzz.persistence.schema.Users
import org.jetbrains.exposed.v1.core.ResultRow

internal fun ResultRow.toOrganization(): Organization =
    Organization(
        id = this[Organizations.id],
        name = this[Organizations.name],
        slug = this[Organizations.slug],
        createdAt = this[Organizations.createdAt],
    )

internal fun ResultRow.toUser(): User =
    User(
        id = this[Users.id],
        email = this[Users.email],
        displayName = this[Users.displayName],
        createdAt = this[Users.createdAt],
    )

internal fun ResultRow.toMembership(): OrgMembership =
    OrgMembership(
        orgId = this[OrgMembers.orgId],
        userId = this[OrgMembers.userId],
        role = this[OrgMembers.role],
        createdAt = this[OrgMembers.createdAt],
    )

internal fun ResultRow.toDataKey(): OrgDataKey =
    OrgDataKey(
        id = this[OrgDataKeys.id],
        orgId = this[OrgDataKeys.orgId],
        kekUri = this[OrgDataKeys.kekUri],
        wrappedDek = this[OrgDataKeys.wrappedDek],
        active = this[OrgDataKeys.active],
        createdAt = this[OrgDataKeys.createdAt],
        retiredAt = this[OrgDataKeys.retiredAt],
    )

internal fun ResultRow.toCredentialMeta(): CredentialMeta =
    CredentialMeta(
        id = this[Credentials.id],
        orgId = this[Credentials.orgId],
        type = this[Credentials.type],
        label = this[Credentials.label],
        fingerprint = this[Credentials.fingerprint],
        hint = this[Credentials.hint],
        validationStatus = this[Credentials.validationStatus],
        validationError = this[Credentials.validationError],
        validatedAt = this[Credentials.validatedAt],
        createdAt = this[Credentials.createdAt],
    )

internal fun ResultRow.toApp(): App =
    App(
        id = this[Apps.id],
        orgId = this[Apps.orgId],
        name = this[Apps.name],
        gpPackageName = this[Apps.gpPackageName],
        ascAppId = this[Apps.ascAppId],
        locale = MessageLocale.ofCode(this[Apps.locale]),
        timezone = this[Apps.timezone],
        notifyFrom = this[Apps.notifyFrom],
        aiInstructions = this[Apps.aiInstructions],
        ingestIntervalMinutes = this[Apps.ingestIntervalMinutes],
        dailyDigestAt = this[Apps.dailyDigestAt],
        enabled = this[Apps.enabled],
        createdAt = this[Apps.createdAt],
    )

internal fun ResultRow.toChannel(): Channel =
    Channel(
        id = this[Channels.id],
        orgId = this[Channels.orgId],
        appId = this[Channels.appId],
        type = this[Channels.type],
        credentialId = this[Channels.credentialId],
        targetRef = this[Channels.targetRef],
        targetLabel = this[Channels.targetLabel],
        locale = MessageLocale.ofCode(this[Channels.locale]),
        deliverReviews = this[Channels.deliverReviews],
        deliverRatings = this[Channels.deliverRatings],
        enabled = this[Channels.enabled],
    )

internal fun ResultRow.toReview(): Review =
    Review(
        id = this[Reviews.id],
        orgId = this[Reviews.orgId],
        appId = this[Reviews.appId],
        platform = this[Reviews.platform],
        storeReviewId = this[Reviews.storeReviewId],
        authorName = this[Reviews.authorName],
        starRating = this[Reviews.starRating].toInt(),
        title = this[Reviews.title],
        body = this[Reviews.body],
        locale = this[Reviews.locale],
        territory = this[Reviews.territory],
        appVersion = this[Reviews.appVersion],
        device = this[Reviews.device],
        submittedAt = this[Reviews.submittedAt],
        storeUpdatedAt = this[Reviews.storeUpdatedAt],
        contentHash = this[Reviews.contentHash],
        developerResponseBody = this[Reviews.developerResponseBody],
        developerResponseAt = this[Reviews.developerResponseAt],
        state = this[Reviews.state],
        firstSeenAt = this[Reviews.firstSeenAt],
        lastSeenAt = this[Reviews.lastSeenAt],
    )

internal fun ResultRow.toReviewMessage(): ReviewMessage =
    ReviewMessage(
        id = this[ReviewMessages.id],
        orgId = this[ReviewMessages.orgId],
        reviewId = this[ReviewMessages.reviewId],
        channelId = this[ReviewMessages.channelId],
        providerConversationId = this[ReviewMessages.providerConversationId],
        providerMessageId = this[ReviewMessages.providerMessageId],
        status = this[ReviewMessages.status],
        error = this[ReviewMessages.error],
        sentAt = this[ReviewMessages.sentAt],
        contentHash = this[ReviewMessages.contentHash],
        createdAt = this[ReviewMessages.createdAt],
    )

internal fun ResultRow.toReply(): Reply =
    Reply(
        id = this[Replies.id],
        orgId = this[Replies.orgId],
        reviewId = this[Replies.reviewId],
        body = this[Replies.body],
        bodyHash = this[Replies.bodyHash],
        authorUserId = this[Replies.authorUserId],
        authorExternalId = this[Replies.authorExternalId],
        authorDisplayName = this[Replies.authorDisplayName],
        source = this[Replies.replySource],
        status = this[Replies.status],
        error = this[Replies.error],
        publishedAt = this[Replies.publishedAt],
        createdAt = this[Replies.createdAt],
    )

internal fun ResultRow.toRatingSnapshot(): RatingSnapshot =
    RatingSnapshot(
        id = this[RatingSnapshots.id],
        orgId = this[RatingSnapshots.orgId],
        appId = this[RatingSnapshots.appId],
        platform = this[RatingSnapshots.platform],
        date = this[RatingSnapshots.snapshotDate],
        territory = this[RatingSnapshots.territory],
        average = this[RatingSnapshots.average]?.toDouble(),
        totalCount = this[RatingSnapshots.totalCount],
        histogram = this[RatingSnapshots.histogram].orEmpty(),
        source = this[RatingSnapshots.ratingSource],
        collectedAt = this[RatingSnapshots.collectedAt],
    )

internal fun ResultRow.toAuditEntry(): AuditEntry =
    AuditEntry(
        orgId = this[AuditLogs.orgId],
        actorType = this[AuditLogs.actorType],
        actorUserId = this[AuditLogs.actorUserId],
        actorLabel = this[AuditLogs.actorLabel],
        action = this[AuditLogs.action],
        targetType = this[AuditLogs.targetType],
        targetId = this[AuditLogs.targetId],
        metadata = this[AuditLogs.metadata],
        createdAt = this[AuditLogs.createdAt],
    )

internal fun ResultRow.toFailedJob(): FailedJob =
    FailedJob(
        id = this[FailedJobs.id],
        orgId = this[FailedJobs.orgId],
        taskName = this[FailedJobs.taskName],
        taskInstance = this[FailedJobs.taskInstance],
        payload = this[FailedJobs.payload],
        errorClass = this[FailedJobs.errorClass],
        errorMessage = this[FailedJobs.errorMessage],
        attempts = this[FailedJobs.attempts],
        firstFailedAt = this[FailedJobs.firstFailedAt],
        lastFailedAt = this[FailedJobs.lastFailedAt],
        resolvedAt = this[FailedJobs.resolvedAt],
    )

internal fun ResultRow.toBackupRun(): BackupRun =
    BackupRun(
        id = this[BackupRuns.id],
        startedAt = this[BackupRuns.startedAt],
        finishedAt = this[BackupRuns.finishedAt],
        status = this[BackupRuns.status],
        location = this[BackupRuns.location],
        sizeBytes = this[BackupRuns.sizeBytes],
        checksum = this[BackupRuns.checksum],
        error = this[BackupRuns.error],
    )

/** Vazba credentialu na appku se čte často spolu s appkou, proto malý pomocník. */
internal val appCredentialColumns = listOf(AppCredentials.appId, AppCredentials.credentialId, AppCredentials.purpose)
