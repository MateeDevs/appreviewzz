import { useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  useAudit,
  useChangePlan,
  useChangeRole,
  useInvitations,
  useInvite,
  useMe,
  useMembers,
  useOrganization,
  useRemoveMember,
  useRevokeInvitation,
} from '../api/hooks'
import type { OrgPlan } from '../api/types'
import { Badge, Card, ErrorBox, Field, Loading, When } from '../components/ui'
import { Select } from '../components/Select'

/**
 * Tarify tak, jak je vidí klient. `unlocks` je záměrně o tom, co tarif **dneska** umí:
 * slibovat v selectu funkce, které nejsou hotové, je nejrychlejší cesta ke zklamání.
 */
const PLANS: { value: OrgPlan; label: string; unlocks: string }[] = [
  { value: 'STARTER', label: 'Starter', unlocks: 'recenze, odpovědi, týdenní rozbory — bez měsíčního reportu' },
  { value: 'REGULAR', label: 'Regular', unlocks: 'navíc měsíční report pro klienta (odkaz i PDF)' },
  { value: 'ENTERPRISE', label: 'Enterprise', unlocks: 'totéž co Regular; místo pro limity, které přijdou s fakturací' },
]

function planLabel(plan: OrgPlan): string {
  return PLANS.find((item) => item.value === plan)?.label ?? plan
}

export function OrganizationPage() {
  const { org = '' } = useParams()
  const me = useMe()
  const organization = useOrganization(org)
  const members = useMembers(org)
  const invitations = useInvitations(org)
  const invite = useInvite(org)
  const revoke = useRevokeInvitation(org)
  const changeRole = useChangeRole(org)
  const remove = useRemoveMember(org)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('MEMBER')
  const [notice, setNotice] = useState<string | null>(null)

  const myRole = me.data?.organizations.find((item) => item.slug === org)?.role
  const canManage = myRole === 'OWNER' || myRole === 'ADMIN'

  return (
    <div className="stack">
      <div>
        <h1>{organization.data?.name ?? 'Organizace'}</h1>
        <p className="muted">Tarif, kdo do organizace vidí a co smí.</p>
      </div>

      <PlanCard org={org} canManage={myRole === 'OWNER'} />

      <Card title="Členové">
        {members.isPending ? <Loading /> : null}
        <ErrorBox error={members.error ?? changeRole.error ?? remove.error} />
        <table>
          <thead>
            <tr>
              <th>Člověk</th>
              <th>Role</th>
              <th>V organizaci od</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {members.data?.map((member) => (
              <tr key={member.userId}>
                <td>
                  {member.displayName ?? member.email}
                  <div className="small muted">{member.email}</div>
                </td>
                <td>
                  {canManage ? (
                    <Select
                      value={member.role}
                      options={[
                        { value: 'OWNER', label: 'vlastník' },
                        { value: 'ADMIN', label: 'správce' },
                        { value: 'MEMBER', label: 'člen' },
                      ]}
                      onChange={(role) => changeRole.mutate({ userId: member.userId, role })}
                      ariaLabel={`Role uživatele ${member.displayName ?? member.email}`}
                    />
                  ) : (
                    <Badge>{member.role.toLowerCase()}</Badge>
                  )}
                </td>
                <td className="small">
                  <When iso={member.since} />
                </td>
                <td>
                  {canManage ? (
                    <button type="button" className="danger" onClick={() => remove.mutate(member.userId)}>
                      Odebrat
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {canManage ? (
        <Card title="Pozvánky">
          {invitations.data?.length === 0 ? (
            <p className="muted">Na nikoho se nečeká.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>E-mail</th>
                  <th>Role</th>
                  <th>Platí do</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {invitations.data?.map((invitation) => (
                  <tr key={invitation.id}>
                    <td>{invitation.email}</td>
                    <td className="small">{invitation.role.toLowerCase()}</td>
                    <td className="small">
                      <When iso={invitation.expiresAt} />
                    </td>
                    <td>
                      <button type="button" className="danger" onClick={() => revoke.mutate(invitation.id)}>
                        Zrušit
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 style={{ marginTop: '1.25rem' }}>Pozvat kolegu</h3>
          <form
            onSubmit={(event) => {
              event.preventDefault()
              invite.mutate(
                { email, role },
                {
                  onSuccess: (invitation) => {
                    setEmail('')
                    setNotice(
                      invitation.delivered === false
                        ? 'Pozvánka platí, ale e-mail se nepodařilo odeslat — zkontroluj nastavení pošty.'
                        : 'Pozvánka odeslaná.',
                    )
                  },
                },
              )
            }}
          >
            <Field label="E-mail">
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </Field>
            <Field label="Role" hint="Člen vidí recenze a odpovídá; správce navíc spravuje appky, klíče a kanály.">
              <Select
                value={role}
                options={[
                  { value: 'MEMBER', label: 'člen' },
                  { value: 'ADMIN', label: 'správce' },
                  { value: 'OWNER', label: 'vlastník' },
                ]}
                onChange={setRole}
                ariaLabel="Role pozvaného uživatele"
              />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={invite.error} />
              {notice ? <div className="notice">{notice}</div> : null}
              <button type="submit" disabled={invite.isPending}>
                Poslat pozvánku
              </button>
            </div>
          </form>
        </Card>
      ) : null}
    </div>
  )
}

/**
 * Tarif organizace. Přepnout ho smí vlastník: dokud se tarify nefakturují, je to přepínač
 * funkcí, ne platba, a čekání na e-mail od nás by bylo horší než záznam v auditu. Ostatní
 * ho tu mají proto, aby věděli, proč něco nevidí, a koho o změnu požádat.
 */
function PlanCard({ org, canManage }: { org: string; canManage: boolean }) {
  const organization = useOrganization(org)
  const changePlan = useChangePlan(org)
  const plan = organization.data?.plan

  return (
    <Card title="Tarif">
      {organization.isPending ? <Loading /> : null}
      <ErrorBox error={organization.error ?? changePlan.error} />
      {plan ? (
        canManage ? (
          <Field
            label="Tarif organizace"
            hint={PLANS.find((item) => item.value === plan)?.unlocks}
          >
            <Select
              value={plan}
              disabled={changePlan.isPending}
              options={PLANS.map((item) => ({ value: item.value, label: item.label }))}
              onChange={(value) => changePlan.mutate(value as OrgPlan)}
              ariaLabel="Tarif organizace"
            />
          </Field>
        ) : (
          <p>
            <Badge>{planLabel(plan)}</Badge>{' '}
            <span className="muted">{PLANS.find((item) => item.value === plan)?.unlocks}</span>
          </p>
        )
      ) : null}
      <p className="small muted">
        Tarify se zatím nefakturují a nic jiného neomezují — jediné, co tarif dnes rozhoduje, je
        měsíční report pro klienta. Ten se generuje na Regularu a výš.
        {canManage ? '' : ' Změnit ho může vlastník organizace.'}
      </p>
    </Card>
  )
}

export function AuditPage() {
  const { org = '' } = useParams()
  const audit = useAudit(org)

  return (
    <div className="stack">
      <div>
        <h1>Audit</h1>
        <p className="muted">Kdo co v organizaci udělal — včetně toho, co udělal systém.</p>
      </div>
      <Card>
        {audit.isPending ? <Loading /> : null}
        <ErrorBox error={audit.error} />
        <table>
          <thead>
            <tr>
              <th>Kdy</th>
              <th>Kdo</th>
              <th>Co</th>
              <th>Podrobnosti</th>
            </tr>
          </thead>
          <tbody>
            {audit.data?.map((entry, index) => (
              <tr key={`${entry.action}-${entry.at}-${index}`}>
                <td className="small">
                  <When iso={entry.at} />
                </td>
                <td className="small">{entry.actor ?? 'systém'}</td>
                <td className="small">{entry.action}</td>
                <td className="small muted">
                  {Object.entries(entry.metadata)
                    .map(([key, value]) => `${key}: ${value}`)
                    .join(', ')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  )
}
