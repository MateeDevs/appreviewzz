package cz.matee.appreviewzz.persistence.schema

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.BackupRunId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.FailedJobId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.RatingSnapshotId
import cz.matee.appreviewzz.core.model.ReplyId
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessageId
import cz.matee.appreviewzz.core.model.UserId
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Sloupcové typy, které drží doménové typy až k databázi.
 *
 * Časy jsou v Postgresu `timestamptz` (Exposed je mapuje na `OffsetDateTime`), doména
 * pracuje s `Instant`. Převod je tady na jednom místě a vždy přes UTC — ať se nikdy
 * nestane, že se offset serveru propíše do dat.
 */
internal fun Table.instant(name: String): Column<Instant> =
    timestampWithTimeZone(name).transform(
        { offsetDateTime -> offsetDateTime.toInstant().toKotlinInstant() },
        { value -> OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC) },
    )

internal fun Table.organizationId(name: String = "org_id"): Column<OrganizationId> =
    uuid(name).transform({ OrganizationId(it) }, { it.value })

internal fun Table.userId(name: String): Column<UserId> = uuid(name).transform({ UserId(it) }, { it.value })

internal fun Table.appId(name: String = "app_id"): Column<AppId> = uuid(name).transform({ AppId(it) }, { it.value })

internal fun Table.credentialId(name: String = "credential_id"): Column<CredentialId> =
    uuid(name).transform({ CredentialId(it) }, { it.value })

internal fun Table.dataKeyId(name: String = "data_key_id"): Column<DataKeyId> = uuid(name).transform({ DataKeyId(it) }, { it.value })

internal fun Table.channelId(name: String = "channel_id"): Column<ChannelId> = uuid(name).transform({ ChannelId(it) }, { it.value })

internal fun Table.reviewId(name: String = "review_id"): Column<ReviewId> = uuid(name).transform({ ReviewId(it) }, { it.value })

internal fun Table.reviewMessageId(name: String): Column<ReviewMessageId> = uuid(name).transform({ ReviewMessageId(it) }, { it.value })

internal fun Table.replyId(name: String): Column<ReplyId> = uuid(name).transform({ ReplyId(it) }, { it.value })

internal fun Table.ratingSnapshotId(name: String): Column<RatingSnapshotId> = uuid(name).transform({ RatingSnapshotId(it) }, { it.value })

internal fun Table.failedJobId(name: String): Column<FailedJobId> = uuid(name).transform({ FailedJobId(it) }, { it.value })

internal fun Table.backupRunId(name: String): Column<BackupRunId> = uuid(name).transform({ BackupRunId(it) }, { it.value })
