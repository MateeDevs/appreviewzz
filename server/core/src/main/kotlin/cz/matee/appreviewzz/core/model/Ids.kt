package cz.matee.appreviewzz.core.model

import kotlin.uuid.Uuid

/**
 * Typované identifikátory. Kompilátor tak neprojde záměnu `appId` za `orgId` —
 * což je u multi-tenant aplikace ta nejlevnější pojistka proti prosakování dat mezi organizacemi.
 */
@JvmInline
value class OrganizationId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): OrganizationId = OrganizationId(Uuid.parse(raw))
    }
}

@JvmInline
value class UserId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class AppId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): AppId = AppId(Uuid.parse(raw))
    }
}

@JvmInline
value class CredentialId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class DataKeyId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ChannelId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ReviewId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ReviewMessageId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ReplyId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class RatingSnapshotId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class FailedJobId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class BackupRunId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}
