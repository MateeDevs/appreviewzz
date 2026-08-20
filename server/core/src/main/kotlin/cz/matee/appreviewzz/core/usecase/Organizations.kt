package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.InvitationMails
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.Invitation
import cz.matee.appreviewzz.core.model.InvitationId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OpaqueTokens
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Slugs
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserAccount
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.InvitationRepository
import cz.matee.appreviewzz.core.port.MailException
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.UserRepository
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Kdo operaci provádí a s jakou rolí. Roli zjišťuje vrstva nad tímhle, ne use-case sám. */
data class OrgActor(
    val userId: UserId,
    val role: OrgRole,
    val displayName: String?,
)

/**
 * Výsledek pozvání. `delivered = false` znamená, že pozvánka vznikla, ale pošta ji nepřevzala —
 * console to musí říct nahlas, jinak by se čekalo na e-mail, který nikdy nedorazí.
 */
data class InvitationResult(
    val invitation: Invitation,
    val delivered: Boolean,
)

/** Člen organizace tak, jak ho ukazuje console — uživatel, role a odkdy. */
data class OrgMember(
    val user: User,
    val role: OrgRole,
    val since: Instant,
)

/**
 * Organizace, členové a pozvánky (F3.2). Tohle je vrstva, po které se klient onboarduje
 * bez našeho zásahu: založí si organizaci a přizve kolegy sám.
 *
 * Dvě pravidla se tu drží napříč vším:
 *
 * - **Roli nikdy neurčuje vstup z požadavku, ale záznam v `org_member`.** Volající sem
 *   posílá [OrgActor], který vznikl dohledáním členství, ne z těla požadavku.
 * - **Organizace nikdy nezůstane bez OWNERa.** Poslední se nedá odebrat ani degradovat,
 *   ani sám sebou — jinak by se dala „ztratit" a musel bych do ní lézt přes databázi.
 */
class OrganizationService(
    private val organizations: OrganizationRepository,
    private val memberships: MembershipRepository,
    private val users: UserRepository,
    private val invitations: InvitationRepository,
    private val audit: AuditLogRepository,
    private val mailer: Mailer,
    private val links: ConsoleLinks,
    private val clock: Clock = Clock.System,
    private val invitationLifetime: Duration = 7.days,
) {
    /**
     * Založení organizace. Vyžaduje ověřený e-mail: bez toho by šlo obsadit slug (a začít
     * zvát lidi) z adresy, ke které se zakládající vůbec nedostane.
     */
    fun create(
        founder: UserAccount,
        name: String,
        slug: String?,
    ): Organization {
        if (!founder.emailVerified) {
            throw ConsoleException(
                ConsoleFailure.EMAIL_NOT_VERIFIED,
                "Nejdřív potvrď e-mail — poslali jsme ti odkaz na ${founder.user.email}",
            )
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Organizace potřebuje název")

        val candidate = (slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: Slugs.of(trimmed))
        if (!Slugs.isValid(candidate)) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Adresa '$candidate' neprojde: ${Slugs.RULE}")
        }
        organizations.findBySlug(candidate)?.let {
            throw ConsoleException(ConsoleFailure.SLUG_TAKEN, "Adresu '$candidate' už někdo používá, zvol jinou")
        }

        val organization = organizations.create(trimmed, candidate)
        memberships.upsert(organization.id, founder.user.id, OrgRole.OWNER)
        audit(organization.id, founder.user, "org.created", "organization", organization.id.toString())
        logger.info { "Organizace ${organization.slug} založená uživatelem ${founder.user.id}" }
        return organization
    }

    fun listMembers(orgId: OrganizationId): List<OrgMember> =
        memberships.listByOrg(orgId).mapNotNull { membership ->
            users.findById(membership.userId)?.let { OrgMember(it, membership.role, membership.createdAt) }
        }

    fun listInvitations(orgId: OrganizationId): List<Invitation> = invitations.listPending(orgId, clock.now())

    /**
     * Pozvání kolegy. ADMIN může zvát ADMINy a MEMBERy, OWNERa jen jiný OWNER — jinak by si
     * admin uměl povýšit spoluhráče a přes něj sebe.
     */
    fun invite(
        organization: Organization,
        actor: OrgActor,
        email: String,
        role: OrgRole,
        locale: MessageLocale = MessageLocale.CS,
    ): InvitationResult {
        requireRole(actor, OrgRole.ADMIN)
        if (role == OrgRole.OWNER) requireRole(actor, OrgRole.OWNER)

        val normalized = email.trim().lowercase()
        if (!normalized.contains('@')) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "'$email' nevypadá jako e-mail")
        }

        val now = clock.now()
        users.findByEmail(normalized)?.let { existing ->
            if (memberships.roleOf(organization.id, existing.id) != null) {
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "$normalized už v organizaci je")
            }
        }

        val token = OpaqueTokens.generate()
        val invitation =
            invitations.create(
                orgId = organization.id,
                email = normalized,
                role = role,
                invitedBy = actor.userId,
                tokenHash = OpaqueTokens.hash(token),
                expiresAt = now + invitationLifetime,
                at = now,
            )
        // Pozvánka platí i s nefunkční poštou; console pak nabídne odkaz zkopírovat ručně.
        val delivered =
            try {
                mailer.send(
                    InvitationMails.invitation(
                        to = normalized,
                        organization = organization.name,
                        invitedBy = actor.displayName,
                        role = role,
                        link = links.invitation(token),
                        validFor = invitationLifetime,
                        locale = locale,
                    ),
                )
                true
            } catch (error: MailException) {
                logger.error(error) { "Pozvánku pro $normalized se nepodařilo odeslat" }
                false
            }

        audit(
            organization.id,
            actorUserId = actor.userId,
            action = "invitation.sent",
            targetType = "invitation",
            targetId = invitation.id.toString(),
            metadata = mapOf("email" to normalized, "role" to role.name, "delivered" to delivered.toString()),
        )
        return InvitationResult(invitation, delivered)
    }

    fun revokeInvitation(
        organization: Organization,
        actor: OrgActor,
        id: InvitationId,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val revoked = invitations.revoke(organization.id, id, clock.now())
        if (!revoked) throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková pozvánka tu není")
        audit(
            organization.id,
            actorUserId = actor.userId,
            action = "invitation.revoked",
            targetType = "invitation",
            targetId = id.toString(),
        )
    }

    /**
     * Přijetí pozvánky přihlášeným člověkem.
     *
     * Adresa v pozvánce musí sedět s adresou účtu: jinak by přeposlaný odkaz pustil do
     * organizace kohokoli. Zároveň je kliknutí na odkaz z e-mailu důkaz, že adresa patří
     * jemu — tak se rovnou označí za ověřenou a nemusí potvrzovat dvakrát.
     */
    fun acceptInvitation(
        account: UserAccount,
        token: SecretPayload,
    ): Organization {
        val now = clock.now()
        val invitation =
            invitations.findPendingByToken(OpaqueTokens.hash(token), now)
                ?: throw ConsoleException(
                    ConsoleFailure.INVITATION_INVALID,
                    "Pozvánka je neplatná, už použitá, nebo jí vypršela platnost",
                )
        if (!invitation.email.equals(account.user.email, ignoreCase = true)) {
            throw ConsoleException(
                ConsoleFailure.INVITATION_INVALID,
                "Pozvánka je pro ${invitation.email}, ale jsi přihlášený jako ${account.user.email}",
            )
        }

        val organization =
            organizations.findById(invitation.orgId)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Organizace mezitím zanikla")

        memberships.upsert(organization.id, account.user.id, invitation.role)
        invitations.markAccepted(invitation.id, now)
        if (!account.emailVerified) users.markEmailVerified(account.user.id, now)

        audit(
            organization.id,
            account.user,
            "member.joined",
            "user",
            account.user.id.toString(),
            mapOf("role" to invitation.role.name),
        )
        logger.info { "Uživatel ${account.user.id} přijal pozvánku do ${organization.slug}" }
        return organization
    }

    fun changeRole(
        organization: Organization,
        actor: OrgActor,
        target: UserId,
        role: OrgRole,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val current =
            memberships.roleOf(organization.id, target)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Ten člověk v organizaci není")
        // Sáhnout na OWNERa (povýšit na něj i sundat ho z něj) smí zase jen OWNER.
        if (role == OrgRole.OWNER || current == OrgRole.OWNER) requireRole(actor, OrgRole.OWNER)
        if (current == OrgRole.OWNER && role != OrgRole.OWNER) requireAnotherOwner(organization.id, target)

        memberships.upsert(organization.id, target, role)
        audit(
            organization.id,
            actorUserId = actor.userId,
            action = "member.role_changed",
            targetType = "user",
            targetId = target.toString(),
            metadata = mapOf("from" to current.name, "to" to role.name),
        )
    }

    fun removeMember(
        organization: Organization,
        actor: OrgActor,
        target: UserId,
    ) {
        val current =
            memberships.roleOf(organization.id, target)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Ten člověk v organizaci není")
        // Odejít smí každý sám za sebe; vyhazovat můžou admini a OWNERa jen OWNER.
        if (actor.userId != target) {
            requireRole(actor, OrgRole.ADMIN)
            if (current == OrgRole.OWNER) requireRole(actor, OrgRole.OWNER)
        }
        if (current == OrgRole.OWNER) requireAnotherOwner(organization.id, target)

        memberships.remove(organization.id, target)
        audit(
            organization.id,
            actorUserId = actor.userId,
            action = if (actor.userId == target) "member.left" else "member.removed",
            targetType = "user",
            targetId = target.toString(),
            metadata = mapOf("role" to current.name),
        )
    }

    private fun requireRole(
        actor: OrgActor,
        required: OrgRole,
    ) {
        if (!actor.role.atLeast(required)) {
            throw ConsoleException(
                ConsoleFailure.FORBIDDEN,
                "Na tohle potřebuješ roli ${required.name.lowercase()} a vyšší",
            )
        }
    }

    private fun requireAnotherOwner(
        orgId: OrganizationId,
        except: UserId,
    ) {
        val owners = memberships.listByOrg(orgId).filter { it.role == OrgRole.OWNER && it.userId != except }
        if (owners.isEmpty()) {
            throw ConsoleException(
                ConsoleFailure.LAST_OWNER,
                "Tohle je poslední vlastník organizace — napřed udělej vlastníkem někoho dalšího",
            )
        }
    }

    private fun audit(
        orgId: OrganizationId,
        actor: User,
        action: String,
        targetType: String,
        targetId: String,
        metadata: Map<String, String> = emptyMap(),
    ) = audit(orgId, actor.id, action, targetType, targetId, metadata, actor.displayName ?: actor.email)

    private fun audit(
        orgId: OrganizationId,
        actorUserId: UserId,
        action: String,
        targetType: String,
        targetId: String,
        metadata: Map<String, String> = emptyMap(),
        actorLabel: String? = null,
    ) {
        audit.append(
            auditEntry(
                orgId = orgId,
                action = action,
                actorType = ActorType.USER,
                actorUserId = actorUserId,
                actorLabel = actorLabel,
                targetType = targetType,
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }
}
