package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.SessionId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.UserSession
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.core.port.SessionRepository
import cz.matee.appreviewzz.core.port.UserTokenRepository
import cz.matee.appreviewzz.persistence.schema.UserSessions
import cz.matee.appreviewzz.persistence.schema.UserTokens
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
