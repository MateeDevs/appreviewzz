package cz.matee.appreviewzz.core.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Datový klíč organizace — v databázi leží **jen zabalený** (wrapped) KEKem z KMS.
 * Rozbalený DEK existuje výhradně v paměti workeru v okamžiku šifrování či dešifrování.
 */
class OrgDataKey(
    val id: DataKeyId,
    val orgId: OrganizationId,
    val kekUri: String,
    val wrappedDek: ByteArray,
    val active: Boolean,
    val createdAt: Instant,
    val retiredAt: Instant?,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is OrgDataKey &&
                    id == other.id &&
                    orgId == other.orgId &&
                    kekUri == other.kekUri &&
                    wrappedDek.contentEquals(other.wrappedDek) &&
                    active == other.active &&
                    createdAt == other.createdAt &&
                    retiredAt == other.retiredAt
            )

    override fun hashCode(): Int =
        listOf(id, orgId, kekUri, wrappedDek.contentHashCode(), active, createdAt, retiredAt)
            .fold(7) { acc, part -> 31 * acc + part.hashCode() }

    /** Bez ByteArray v textové podobě — zabalený klíč nemá co dělat v logu. */
    override fun toString(): String = "OrgDataKey(id=$id, orgId=$orgId, kekUri=$kekUri, active=$active)"
}

/**
 * Datový klíč pro tajemství, která nepatří žádné organizaci (TOTP seed uživatele).
 * Jinak úplně stejná úvaha jako u [OrgDataKey] — v databázi leží jen zabalený.
 */
class AppDataKey(
    val id: Uuid,
    val kekUri: String,
    val wrappedDek: ByteArray,
    val active: Boolean,
    val createdAt: Instant,
) {
    override fun toString(): String = "AppDataKey(id=$id, kekUri=$kekUri, active=$active)"
}
