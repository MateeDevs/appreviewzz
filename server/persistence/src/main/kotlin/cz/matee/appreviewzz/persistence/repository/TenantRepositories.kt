package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.OrgDataKey
import cz.matee.appreviewzz.core.model.OrgMembership
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserAccount
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.DataKeyRepository
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.UserRepository
import cz.matee.appreviewzz.persistence.schema.OrgDataKeys
import cz.matee.appreviewzz.persistence.schema.OrgMembers
import cz.matee.appreviewzz.persistence.schema.Organizations
import cz.matee.appreviewzz.persistence.schema.Users
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class ExposedOrganizationRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : OrganizationRepository {
    override fun create(
        name: String,
        slug: String,
    ): Organization =
        transaction(database) {
            val organization =
                Organization(
                    id = OrganizationId(Uuid.random()),
                    name = name,
                    slug = slug,
                    createdAt = clock.now(),
                )
            Organizations.insert {
                it[id] = organization.id
                it[Organizations.name] = organization.name
                it[Organizations.slug] = organization.slug
                it[createdAt] = organization.createdAt
                it[updatedAt] = organization.createdAt
            }
            organization
        }

    override fun findById(id: OrganizationId): Organization? =
        transaction(database) {
            Organizations
                .selectAll()
                .where { Organizations.id eq id }
                .firstOrNull()
                ?.toOrganization()
        }

    override fun findBySlug(slug: String): Organization? =
        transaction(database) {
            Organizations
                .selectAll()
                .where { Organizations.slug eq slug }
                .firstOrNull()
                ?.toOrganization()
        }

    override fun list(): List<Organization> =
        transaction(database) {
            Organizations.selectAll().orderBy(Organizations.name to SortOrder.ASC).map { it.toOrganization() }
        }
}

class ExposedUserRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : UserRepository {
    override fun create(
        email: String,
        displayName: String?,
    ): User =
        transaction(database) {
            // Databáze má CHECK na lowercase; normalizujeme tady, ať to není past pro volajícího.
            val user =
                User(
                    id = UserId(Uuid.random()),
                    email = email.trim().lowercase(),
                    displayName = displayName,
                    createdAt = clock.now(),
                )
            Users.insert {
                it[id] = user.id
                it[Users.email] = user.email
                it[Users.displayName] = user.displayName
                it[createdAt] = user.createdAt
                it[updatedAt] = user.createdAt
            }
            user
        }

    override fun findById(id: UserId): User? =
        transaction(database) {
            Users
                .selectAll()
                .where { Users.id eq id }
                .firstOrNull()
                ?.toUser()
        }

    override fun findByEmail(email: String): User? =
        transaction(database) {
            Users
                .selectAll()
                .where { Users.email eq email.trim().lowercase() }
                .firstOrNull()
                ?.toUser()
        }

    override fun findAccountByEmail(email: String): UserAccount? =
        transaction(database) {
            Users
                .selectAll()
                .where { Users.email eq email.trim().lowercase() }
                .firstOrNull()
                ?.toUserAccount()
        }

    override fun findAccountById(id: UserId): UserAccount? =
        transaction(database) {
            Users
                .selectAll()
                .where { Users.id eq id }
                .firstOrNull()
                ?.toUserAccount()
        }

    override fun setPlatformRole(
        id: UserId,
        role: PlatformRole?,
    ): Boolean =
        transaction(database) {
            Users.update({ Users.id eq id }) {
                it[platformRole] = role
                it[updatedAt] = clock.now()
            } > 0
        }

    override fun listPlatformAdmins(): List<User> =
        transaction(database) {
            Users
                .selectAll()
                .where { Users.platformRole.isNotNull() }
                .orderBy(Users.email to SortOrder.ASC)
                .map { it.toUser() }
        }

    override fun setPassword(
        id: UserId,
        passwordHash: String,
        at: Instant,
    ): Boolean =
        transaction(database) {
            // Nastavení hesla je zároveň konec zamčení: kdo prošel resetem, prokázal se e-mailem.
            Users.update({ Users.id eq id }) {
                it[Users.passwordHash] = passwordHash
                it[failedLoginCount] = 0
                it[lockedUntil] = null
                it[updatedAt] = at
            } > 0
        }

    override fun markEmailVerified(
        id: UserId,
        at: Instant,
    ): Boolean =
        transaction(database) {
            Users.update({ (Users.id eq id) and (Users.emailVerifiedAt eq null) }) {
                it[emailVerifiedAt] = at
                it[updatedAt] = at
            } > 0
        }

    override fun recordLoginAttempt(
        id: UserId,
        failedLoginCount: Int,
        lockedUntil: Instant?,
        lastLoginAt: Instant?,
    ) {
        transaction(database) {
            Users.update({ Users.id eq id }) {
                it[Users.failedLoginCount] = failedLoginCount
                it[Users.lockedUntil] = lockedUntil
                // `null` = neúspěšný pokus; poslední úspěšné přihlášení se nepřepisuje.
                if (lastLoginAt != null) it[Users.lastLoginAt] = lastLoginAt
            }
        }
    }
}

class ExposedMembershipRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : MembershipRepository {
    override fun upsert(
        orgId: OrganizationId,
        userId: UserId,
        role: OrgRole,
    ): OrgMembership =
        transaction(database) {
            val existing =
                OrgMembers
                    .selectAll()
                    .where { (OrgMembers.orgId eq orgId) and (OrgMembers.userId eq userId) }
                    .firstOrNull()
                    ?.toMembership()
            if (existing == null) {
                val membership = OrgMembership(orgId, userId, role, clock.now())
                OrgMembers.insert {
                    it[OrgMembers.orgId] = membership.orgId
                    it[OrgMembers.userId] = membership.userId
                    it[OrgMembers.role] = membership.role
                    it[createdAt] = membership.createdAt
                }
                membership
            } else {
                OrgMembers.update({ (OrgMembers.orgId eq orgId) and (OrgMembers.userId eq userId) }) {
                    it[OrgMembers.role] = role
                }
                existing.copy(role = role)
            }
        }

    override fun listByOrg(orgId: OrganizationId): List<OrgMembership> =
        transaction(database) {
            OrgMembers.selectAll().where { OrgMembers.orgId eq orgId }.map { it.toMembership() }
        }

    override fun listByUser(userId: UserId): List<OrgMembership> =
        transaction(database) {
            OrgMembers
                .selectAll()
                .where { OrgMembers.userId eq userId }
                .orderBy(OrgMembers.createdAt to SortOrder.ASC)
                .map { it.toMembership() }
        }

    override fun roleOf(
        orgId: OrganizationId,
        userId: UserId,
    ): OrgRole? =
        transaction(database) {
            OrgMembers
                .selectAll()
                .where { (OrgMembers.orgId eq orgId) and (OrgMembers.userId eq userId) }
                .firstOrNull()
                ?.get(OrgMembers.role)
        }

    override fun remove(
        orgId: OrganizationId,
        userId: UserId,
    ): Boolean =
        transaction(database) {
            OrgMembers.deleteWhere { (OrgMembers.orgId eq orgId) and (OrgMembers.userId eq userId) } > 0
        }
}

class ExposedDataKeyRepository(
    private val database: ExposedDatabase,
) : DataKeyRepository {
    override fun findActive(orgId: OrganizationId): OrgDataKey? =
        transaction(database) {
            OrgDataKeys
                .selectAll()
                .where { (OrgDataKeys.orgId eq orgId) and (OrgDataKeys.active eq true) }
                .firstOrNull()
                ?.toDataKey()
        }

    override fun findById(
        orgId: OrganizationId,
        id: DataKeyId,
    ): OrgDataKey? =
        transaction(database) {
            OrgDataKeys
                .selectAll()
                .where { (OrgDataKeys.orgId eq orgId) and (OrgDataKeys.id eq id) }
                .firstOrNull()
                ?.toDataKey()
        }

    override fun create(
        orgId: OrganizationId,
        kekUri: String,
        wrappedDek: ByteArray,
        at: Instant,
    ): OrgDataKey =
        transaction(database) {
            // Nejdřív zneaktivnit starý klíč — parciální unikátní index na (org_id) WHERE active
            // jinak zápis odmítne. Rotace tak nikdy nenechá dva aktivní klíče.
            OrgDataKeys.update({ (OrgDataKeys.orgId eq orgId) and (OrgDataKeys.active eq true) }) {
                it[active] = false
                it[retiredAt] = at
            }
            val key =
                OrgDataKey(
                    id = DataKeyId(Uuid.random()),
                    orgId = orgId,
                    kekUri = kekUri,
                    wrappedDek = wrappedDek,
                    active = true,
                    createdAt = at,
                    retiredAt = null,
                )
            OrgDataKeys.insert {
                it[id] = key.id
                it[OrgDataKeys.orgId] = key.orgId
                it[OrgDataKeys.kekUri] = key.kekUri
                it[OrgDataKeys.wrappedDek] = key.wrappedDek
                it[active] = true
                it[createdAt] = key.createdAt
            }
            key
        }
}
