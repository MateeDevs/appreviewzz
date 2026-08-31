package cz.matee.appreviewzz.persistence.schema

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.core.model.ValidationStatus
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.time
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Exposed pohled na schéma z migrace `V2__domain.sql`. Definice tabulek **negenerují DDL** —
 * pravda o schématu je Flyway migrace, tohle je jen typovaný přístup k ní. Když se rozejdou,
 * spadne integrační test nad Testcontainers.
 */
private val schemaJson = Json { encodeDefaults = true }

private const val ENUM_LENGTH = 32

internal object Organizations : Table("organization") {
    val id = organizationId("id")
    val name = text("name")
    val slug = text("slug")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Users : Table("app_user") {
    val id = userId("id")
    val email = text("email")
    val displayName = text("display_name").nullable()
    val passwordHash = text("password_hash").nullable()
    val emailVerifiedAt = instant("email_verified_at").nullable()
    val lastLoginAt = instant("last_login_at").nullable()
    val failedLoginCount = integer("failed_login_count")
    val lockedUntil = instant("locked_until").nullable()
    val platformRole = enumerationByName<PlatformRole>("platform_role", ENUM_LENGTH).nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object UserSessions : Table("user_session") {
    val id = sessionId("id")
    val userId = userId("user_id")
    val tokenHash = binary("token_hash")
    val userAgent = text("user_agent").nullable()
    val clientIp = text("client_ip").nullable()
    val createdAt = instant("created_at")
    val lastSeenAt = instant("last_seen_at")
    val expiresAt = instant("expires_at")
    val revokedAt = instant("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object UserTokens : Table("user_token") {
    val id = uuid("id")
    val userId = userId("user_id")
    val purpose = enumerationByName<UserTokenPurpose>("purpose", ENUM_LENGTH)
    val tokenHash = binary("token_hash")
    val expiresAt = instant("expires_at")
    val consumedAt = instant("consumed_at").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Druhý faktor (F5.3). Řádek na uživatele; `confirmedAt == null` je rozdělané nastavení. */
internal object UserTotps : Table("user_totp") {
    val userId = userId("user_id")
    val dataKeyId = uuid("data_key_id")
    val ciphertext = binary("ciphertext")
    val createdAt = instant("created_at")
    val confirmedAt = instant("confirmed_at").nullable()
    val lastStep = long("last_step").nullable()

    override val primaryKey = PrimaryKey(userId)
}

internal object UserRecoveryCodes : Table("user_recovery_code") {
    val id = uuid("id")
    val userId = userId("user_id")
    val codeHash = binary("code_hash")
    val createdAt = instant("created_at")
    val usedAt = instant("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** DEK pro tajemství vázaná na uživatele. Deployment-scoped, ne per organizace. */
internal object AppDataKeys : Table("app_data_key") {
    val id = uuid("id")
    val kekUri = text("kek_uri")
    val wrappedDek = binary("wrapped_dek")
    val active = bool("active")
    val createdAt = instant("created_at")
    val retiredAt = instant("retired_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object OrgMembers : Table("org_member") {
    val orgId = organizationId()
    val userId = userId("user_id")
    val role = enumerationByName<OrgRole>("role", ENUM_LENGTH)
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(orgId, userId)
}

internal object OrgInvitations : Table("org_invitation") {
    val id = invitationId("id")
    val orgId = organizationId()
    val email = text("email")
    val role = enumerationByName<OrgRole>("role", ENUM_LENGTH)
    val invitedBy = userId("invited_by").nullable()
    val tokenHash = binary("token_hash")
    val expiresAt = instant("expires_at")
    val acceptedAt = instant("accepted_at").nullable()
    val revokedAt = instant("revoked_at").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal object OrgDataKeys : Table("org_data_key") {
    val id = dataKeyId("id")
    val orgId = organizationId()
    val kekUri = text("kek_uri")
    val wrappedDek = binary("wrapped_dek")
    val active = bool("active")
    val createdAt = instant("created_at")
    val retiredAt = instant("retired_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object Credentials : Table("credential") {
    val id = credentialId("id")
    val orgId = organizationId()
    val type = enumerationByName<CredentialType>("type", ENUM_LENGTH)
    val label = text("label")
    val dataKeyId = dataKeyId()
    val ciphertext = binary("ciphertext")
    val fingerprint = text("fingerprint")
    val hint = text("hint").nullable()
    val validationStatus = enumerationByName<ValidationStatus>("validation_status", ENUM_LENGTH)
    val validationError = text("validation_error").nullable()
    val validatedAt = instant("validated_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Apps : Table("app") {
    val id = appId("id")
    val orgId = organizationId()
    val name = text("name")
    val gpPackageName = text("gp_package_name").nullable()
    val gpReportingBucket = text("gp_reporting_bucket").nullable()
    val ascAppId = text("asc_app_id").nullable()
    val locale = text("locale")
    val timezone = text("timezone")
    val notifyFrom = instant("notify_from").nullable()
    val aiInstructions = text("ai_instructions").nullable()
    val ingestIntervalMinutes = integer("ingest_interval_minutes").nullable()
    val dailyDigestAt = time("daily_digest_at")
    val enabled = bool("enabled")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object AppCredentials : Table("app_credential") {
    val appId = appId()
    val credentialId = credentialId()
    val purpose = enumerationByName<CredentialPurpose>("purpose", ENUM_LENGTH)

    override val primaryKey = PrimaryKey(appId, credentialId, purpose)
}

internal object Channels : Table("channel") {
    val id = channelId("id")
    val orgId = organizationId()
    val appId = appId()
    val type = enumerationByName<ChannelType>("type", ENUM_LENGTH)
    val credentialId = credentialId().nullable()
    val targetRef = text("target_ref")
    val targetLabel = text("target_label").nullable()
    val locale = text("locale")
    val deliverReviews = bool("deliver_reviews")
    val deliverRatings = bool("deliver_ratings")
    val enabled = bool("enabled")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Reviews : Table("review") {
    val id = reviewId("id")
    val orgId = organizationId()
    val appId = appId()
    val platform = enumerationByName<Platform>("platform", ENUM_LENGTH)
    val storeReviewId = text("store_review_id")
    val authorName = text("author_name").nullable()
    val starRating = short("star_rating")
    val title = text("title").nullable()
    val body = text("body").nullable()
    val locale = text("locale").nullable()
    val territory = text("territory").nullable()
    val appVersion = text("app_version").nullable()
    val device = text("device").nullable()
    val submittedAt = instant("submitted_at")
    val storeUpdatedAt = instant("store_updated_at").nullable()
    val contentHash = text("content_hash")
    val developerResponseBody = text("developer_response_body").nullable()
    val developerResponseAt = instant("developer_response_at").nullable()
    val state = enumerationByName<ReviewState>("state", ENUM_LENGTH)
    val firstSeenAt = instant("first_seen_at")
    val lastSeenAt = instant("last_seen_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object ReviewRevisions : Table("review_revision") {
    val id = uuid("id")
    val reviewId = reviewId()
    val contentHash = text("content_hash")
    val starRating = short("star_rating")
    val title = text("title").nullable()
    val body = text("body").nullable()
    val appVersion = text("app_version").nullable()
    val developerResponseBody = text("developer_response_body").nullable()
    val observedAt = instant("observed_at")

    override val primaryKey = PrimaryKey(id)
}

internal object ReviewMessages : Table("review_message") {
    val id = reviewMessageId("id")
    val orgId = organizationId()
    val reviewId = reviewId()
    val channelId = channelId()
    val providerConversationId = text("provider_conversation_id").nullable()
    val providerMessageId = text("provider_message_id").nullable()
    val status = enumerationByName<MessageStatus>("status", ENUM_LENGTH)
    val error = text("error").nullable()
    val sentAt = instant("sent_at").nullable()
    val contentHash = text("content_hash")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Replies : Table("reply") {
    val id = replyId("id")
    val orgId = organizationId()
    val reviewId = reviewId()
    val body = text("body")
    val bodyHash = text("body_hash")
    val authorUserId = userId("author_user_id").nullable()
    val authorExternalId = text("author_external_id").nullable()
    val authorDisplayName = text("author_display_name").nullable()
    val replySource = enumerationByName<ReplySource>("source", ENUM_LENGTH)
    val status = enumerationByName<ReplyStatus>("status", ENUM_LENGTH)
    val error = text("error").nullable()
    val publishedAt = instant("published_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object RatingsDigests : Table("ratings_digest") {
    val orgId = organizationId()
    val appId = appId()
    val channelId = channelId()
    val digestDate = date("digest_date")
    val sentAt = instant("sent_at")

    override val primaryKey = PrimaryKey(channelId, digestDate)
}

internal object RatingSnapshots : Table("rating_snapshot") {
    val id = ratingSnapshotId("id")
    val orgId = organizationId()
    val appId = appId()
    val platform = enumerationByName<Platform>("platform", ENUM_LENGTH)
    val snapshotDate = date("snapshot_date")
    val territory = text("territory")
    val average = decimal("average", precision = 4, scale = 3).nullable()
    val totalCount = long("total_count").nullable()
    val histogram =
        jsonb("histogram", schemaJson, MapSerializer(Int.serializer(), Long.serializer())).nullable()

    // `source` je zabraný v Exposed ColumnSet, proto jiné jméno property (sloupec zůstává "source").
    val ratingSource = enumerationByName<RatingSource>("source", ENUM_LENGTH)
    val collectedAt = instant("collected_at")

    override val primaryKey = PrimaryKey(id)
}

internal object AuditLogs : Table("audit_log") {
    val id = long("id").autoIncrement()
    val orgId = organizationId()
    val actorType = enumerationByName<ActorType>("actor_type", ENUM_LENGTH)
    val actorUserId = userId("actor_user_id").nullable()
    val actorLabel = text("actor_label").nullable()
    val action = text("action")
    val targetType = text("target_type").nullable()
    val targetId = text("target_id").nullable()
    val metadata = jsonb("metadata", schemaJson, MapSerializer(String.serializer(), String.serializer()))
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal object FailedJobs : Table("failed_job") {
    val id = failedJobId("id")
    val orgId = organizationId().nullable()
    val taskName = text("task_name")
    val taskInstance = text("task_instance")
    val payload = text("payload").nullable()
    val errorClass = text("error_class").nullable()
    val errorMessage = text("error_message").nullable()
    val attempts = integer("attempts")
    val firstFailedAt = instant("first_failed_at")
    val lastFailedAt = instant("last_failed_at")
    val resolvedAt = instant("resolved_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object BackupRuns : Table("backup_run") {
    val id = backupRunId("id")
    val startedAt = instant("started_at")
    val finishedAt = instant("finished_at")
    val status = enumerationByName<BackupStatus>("status", ENUM_LENGTH)
    val location = text("location").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val checksum = text("checksum").nullable()
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Platformní konfigurace (F7.2). Bez `org_id` schválně — je to jediná část schématu, která
 * k žádné organizaci nepatří, a nesmí se do ní dát zamíchat.
 */
internal object PlatformSettingsTable : Table("platform_setting") {
    val key = text("key")
    val value = text("value")
    val updatedAt = instant("updated_at")
    val updatedBy = userId("updated_by").nullable()

    override val primaryKey = PrimaryKey(key)
}

/** Write-only tajemství platformy. `ciphertext` z týhle tabulky nikdy nejde do API odpovědi. */
internal object PlatformSecrets : Table("platform_secret") {
    val key = text("key")
    val dataKeyId = uuid("data_key_id")
    val ciphertext = binary("ciphertext")
    val fingerprint = text("fingerprint")
    val hint = text("hint").nullable()
    val updatedAt = instant("updated_at")
    val updatedBy = userId("updated_by").nullable()

    override val primaryKey = PrimaryKey(key)
}

internal object PlatformAuditLogs : Table("platform_audit_log") {
    val id = long("id").autoIncrement()
    val actorUserId = userId("actor_user_id").nullable()
    val actorLabel = text("actor_label").nullable()
    val action = text("action")
    val targetKey = text("target_key").nullable()
    val metadata = jsonb("metadata", schemaJson, MapSerializer(String.serializer(), String.serializer()))
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}
