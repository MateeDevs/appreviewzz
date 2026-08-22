package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppDataKey
import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.SessionId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.UserSession
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.core.model.UserTotp
import cz.matee.appreviewzz.core.port.AppDataKeyRepository
import cz.matee.appreviewzz.core.port.SessionRepository
import cz.matee.appreviewzz.core.port.UserMfaRepository
import cz.matee.appreviewzz.core.port.UserTokenRepository
import cz.matee.appreviewzz.persistence.schema.AppDataKeys
import cz.matee.appreviewzz.persistence.schema.UserRecoveryCodes
import cz.matee.appreviewzz.persistence.schema.UserSessions
import cz.matee.appreviewzz.persistence.schema.UserTokens
import cz.matee.appreviewzz.persistence.schema.UserTotps
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Relace console. Do tabulky se ukládá jen otisk tokenu, takže vyhledání je přesná shoda
 * na `token_hash` — žádné procházení a porovnávání, žádný časový únik.
 */
class ExposedSessionRepository(
    private val database: ExposedDatabase,
) : SessionRepository {
    override fun create(
        userId: UserId,
        tokenHash: ByteArray,
        createdAt: Instant,
        expiresAt: Instant,
        userAgent: String?,
        clientIp: String?,
    ): UserSession =
        transaction(database) {
            val session =
                UserSession(
                    id = SessionId(Uuid.random()),
                    userId = userId,
                    createdAt = createdAt,
                    lastSeenAt = createdAt,
                    expiresAt = expiresAt,
                )
            UserSessions.insert {
                it[id] = session.id
                it[UserSessions.userId] = session.userId
                it[UserSessions.tokenHash] = tokenHash
                // User agent bereme zkrácený: je to nápověda pro člověka ve výpisu relací,
                // ne datový sklad prohlížečů.
                it[UserSessions.userAgent] = userAgent?.take(USER_AGENT_LIMIT)
                it[UserSessions.clientIp] = clientIp
                it[UserSessions.createdAt] = session.createdAt
                it[lastSeenAt] = session.lastSeenAt
                it[UserSessions.expiresAt] = session.expiresAt
            }
            session
        }

    override fun findValid(
        tokenHash: ByteArray,
        at: Instant,
    ): UserSession? =
        transaction(database) {
            UserSessions
                .selectAll()
                .where {
                    (UserSessions.tokenHash eq tokenHash) and
                        (UserSessions.revokedAt eq null) and
                        (UserSessions.expiresAt greater at)
                }.firstOrNull()
                ?.toUserSession()
        }

    override fun touch(
        id: SessionId,
        at: Instant,
    ) {
        transaction(database) {
            UserSessions.update({ UserSessions.id eq id }) { it[lastSeenAt] = at }
        }
    }

    override fun revoke(
        id: SessionId,
        at: Instant,
    ): Boolean =
        transaction(database) {
            UserSessions.update({ (UserSessions.id eq id) and (UserSessions.revokedAt eq null) }) {
                it[revokedAt] = at
            } > 0
        }

    override fun revokeAllOfUser(
        userId: UserId,
        at: Instant,
    ): Int =
        transaction(database) {
            UserSessions.update({ (UserSessions.userId eq userId) and (UserSessions.revokedAt eq null) }) {
                it[revokedAt] = at
            }
        }

    override fun revokeAllOfUserExcept(
        userId: UserId,
        keep: SessionId,
        at: Instant,
    ): Int =
        transaction(database) {
            UserSessions.update({
                (UserSessions.userId eq userId) and
                    (UserSessions.id neq keep) and
                    (UserSessions.revokedAt eq null)
            }) {
                it[revokedAt] = at
            }
        }

    override fun listActive(
        userId: UserId,
        at: Instant,
    ): List<UserSession> =
        transaction(database) {
            UserSessions
                .selectAll()
                .where {
                    (UserSessions.userId eq userId) and
                        (UserSessions.revokedAt eq null) and
                        (UserSessions.expiresAt greater at)
                }.orderBy(UserSessions.lastSeenAt to SortOrder.DESC)
                .map { it.toUserSession() }
        }

    override fun deleteExpired(before: Instant): Int =
        transaction(database) {
            UserSessions.deleteWhere { expiresAt less before }
        }

    private companion object {
        const val USER_AGENT_LIMIT = 200
    }
}

/**
 * Jednorázové odkazy z e-mailu. `consume` je jediná cesta ven a rovnou token zneplatní —
 * dvě souběžná kliknutí na týž odkaz tak uspějí nejvýš jednou.
 */
class ExposedUserTokenRepository(
    private val database: ExposedDatabase,
) : UserTokenRepository {
    override fun create(
        userId: UserId,
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        expiresAt: Instant,
        at: Instant,
    ) {
        transaction(database) {
            UserTokens.insert {
                it[id] = Uuid.random()
                it[UserTokens.userId] = userId
                it[UserTokens.purpose] = purpose
                it[UserTokens.tokenHash] = tokenHash
                it[UserTokens.expiresAt] = expiresAt
                it[createdAt] = at
            }
        }
    }

    override fun findValid(
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        at: Instant,
    ): UserId? =
        transaction(database) {
            UserTokens
                .selectAll()
                .where {
                    (UserTokens.tokenHash eq tokenHash) and
                        (UserTokens.purpose eq purpose) and
                        (UserTokens.consumedAt eq null) and
                        (UserTokens.expiresAt greater at)
                }.firstOrNull()
                ?.get(UserTokens.userId)
        }

    override fun consume(
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        at: Instant,
    ): UserId? =
        transaction(database) {
            val row =
                UserTokens
                    .selectAll()
                    .where {
                        (UserTokens.tokenHash eq tokenHash) and
                            (UserTokens.purpose eq purpose) and
                            (UserTokens.consumedAt eq null) and
                            (UserTokens.expiresAt greater at)
                    }.firstOrNull() ?: return@transaction null

            val consumed =
                UserTokens.update({ (UserTokens.id eq row[UserTokens.id]) and (UserTokens.consumedAt eq null) }) {
                    it[consumedAt] = at
                }
            if (consumed > 0) row[UserTokens.userId] else null
        }

    override fun invalidateAll(
        userId: UserId,
        purpose: UserTokenPurpose,
        at: Instant,
    ): Int =
        transaction(database) {
            UserTokens.update({
                (UserTokens.userId eq userId) and
                    (UserTokens.purpose eq purpose) and
                    (UserTokens.consumedAt eq null)
            }) {
                it[consumedAt] = at
            }
        }
}

/**
 * Druhý faktor (F5.3). Tajemství jde dovnitř i ven jen zapečetěné — dešifruje ho vault,
 * ne repozitář, takže se z databázové vrstvy nedá omylem vytáhnout v otevřené podobě.
 */
class ExposedUserMfaRepository(
    private val database: ExposedDatabase,
) : UserMfaRepository {
    override fun find(userId: UserId): UserTotp? =
        transaction(database) {
            UserTotps
                .selectAll()
                .where { UserTotps.userId eq userId }
                .firstOrNull()
                ?.let { row ->
                    UserTotp(
                        userId = row[UserTotps.userId],
                        secret = SealedSecret(row[UserTotps.dataKeyId], row[UserTotps.ciphertext]),
                        createdAt = row[UserTotps.createdAt],
                        confirmedAt = row[UserTotps.confirmedAt],
                        lastStep = row[UserTotps.lastStep],
                    )
                }
        }

    override fun startSetup(
        userId: UserId,
        secret: SealedSecret,
        at: Instant,
    ) {
        transaction(database) {
            // Rozdělané nastavení se přepisuje, potvrzené chrání volající (a `delete`).
            UserTotps.deleteWhere { UserTotps.userId eq userId }
            UserTotps.insert {
                it[UserTotps.userId] = userId
                it[dataKeyId] = secret.dataKeyId
                it[ciphertext] = secret.ciphertext
                it[createdAt] = at
            }
        }
    }

    override fun confirm(
        userId: UserId,
        at: Instant,
        step: Long,
    ) {
        transaction(database) {
            UserTotps.update({ UserTotps.userId eq userId }) {
                it[confirmedAt] = at
                it[lastStep] = step
            }
        }
    }

    override fun recordStep(
        userId: UserId,
        step: Long,
    ) {
        transaction(database) {
            UserTotps.update({ UserTotps.userId eq userId }) {
                it[lastStep] = step
            }
        }
    }

    override fun delete(userId: UserId) {
        transaction(database) {
            UserRecoveryCodes.deleteWhere { UserRecoveryCodes.userId eq userId }
            UserTotps.deleteWhere { UserTotps.userId eq userId }
        }
    }

    override fun replaceRecoveryCodes(
        userId: UserId,
        hashes: List<ByteArray>,
        at: Instant,
    ) {
        transaction(database) {
            // Nová sada ruší starou celou, včetně už použitých řádků — jinak by v tabulce
            // zůstávaly otisky, ke kterým nikdo nemá papír.
            UserRecoveryCodes.deleteWhere { UserRecoveryCodes.userId eq userId }
            hashes.forEach { hash ->
                UserRecoveryCodes.insert {
                    it[id] = Uuid.random()
                    it[UserRecoveryCodes.userId] = userId
                    it[codeHash] = hash
                    it[createdAt] = at
                }
            }
        }
    }

    override fun consumeRecoveryCode(
        userId: UserId,
        hash: ByteArray,
        at: Instant,
    ): Boolean =
        transaction(database) {
            // Jedno UPDATE s podmínkou na `used_at IS NULL`: dvě souběžná odeslání téhož
            // kódu tak uspějí nejvýš jednou, bez zamykání.
            UserRecoveryCodes.update({
                (UserRecoveryCodes.userId eq userId) and
                    (UserRecoveryCodes.codeHash eq hash) and
                    (UserRecoveryCodes.usedAt eq null)
            }) {
                it[usedAt] = at
            } > 0
        }

    override fun remainingRecoveryCodes(userId: UserId): Int =
        transaction(database) {
            UserRecoveryCodes
                .selectAll()
                .where { (UserRecoveryCodes.userId eq userId) and (UserRecoveryCodes.usedAt eq null) }
                .count()
                .toInt()
        }
}

/**
 * DEK pro uživatelská tajemství. Jeden aktivní na celý deployment; parciální unikátní index
 * `(active) WHERE active` hlídá, že se z rotace nikdy nevyklubou dva.
 */
class ExposedAppDataKeyRepository(
    private val database: ExposedDatabase,
) : AppDataKeyRepository {
    override fun findActive(): AppDataKey? =
        transaction(database) {
            AppDataKeys
                .selectAll()
                .where { AppDataKeys.active eq true }
                .firstOrNull()
                ?.toAppDataKey()
        }

    override fun findById(id: Uuid): AppDataKey? =
        transaction(database) {
            AppDataKeys
                .selectAll()
                .where { AppDataKeys.id eq id }
                .firstOrNull()
                ?.toAppDataKey()
        }

    override fun create(
        kekUri: String,
        wrappedDek: ByteArray,
        at: Instant,
    ): AppDataKey =
        transaction(database) {
            AppDataKeys.update({ AppDataKeys.active eq true }) {
                it[active] = false
                it[retiredAt] = at
            }
            val key = AppDataKey(Uuid.random(), kekUri, wrappedDek, active = true, createdAt = at)
            AppDataKeys.insert {
                it[id] = key.id
                it[AppDataKeys.kekUri] = key.kekUri
                it[AppDataKeys.wrappedDek] = key.wrappedDek
                it[active] = true
                it[createdAt] = key.createdAt
            }
            key
        }

    private fun ResultRow.toAppDataKey(): AppDataKey =
        AppDataKey(
            id = this[AppDataKeys.id],
            kekUri = this[AppDataKeys.kekUri],
            wrappedDek = this[AppDataKeys.wrappedDek],
            active = this[AppDataKeys.active],
            createdAt = this[AppDataKeys.createdAt],
        )
}
