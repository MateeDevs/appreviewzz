import { useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  useApps,
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
        <table className="row-cards">
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
                <td className="lead">
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
                <td className="small" data-label="v organizaci od">
                  <When iso={member.since} />
                </td>
                <td className="end">
                  {canManage && member.userId !== me.data?.id ? (
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
            <table className="row-cards">
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
                    <td className="lead">{invitation.email}</td>
                    <td className="small">{invitation.role.toLowerCase()}</td>
                    <td className="small" data-label="platí do">
                      <When iso={invitation.expiresAt} />
                    </td>
                    <td className="end">
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
              // Enterprise se zatím nedá zvolit — nastavuje ho provozovatel. V nabídce zůstává jen
              // tehdy, když ho organizace už má, aby select měl co ukázat.
              options={PLANS.filter((item) => item.value !== 'ENTERPRISE' || plan === 'ENTERPRISE').map((item) => ({
                value: item.value,
                label: item.label,
              }))}
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

/**
 * Klíče akcí z auditu přeložené pro člověka. Neznámý klíč se ukáže tak, jak přišel —
 * lepší syrový `credential.rotated` než prázdné místo.
 */
const AUDIT_ACTIONS: Record<string, string> = {
  'org.created': 'založení organizace',
  'org.plan_changed': 'změna tarifu',
  'member.added': 'přidání člena',
  'member.role_changed': 'změna role',
  'member.removed': 'odebrání člena',
  'invitation.sent': 'odeslání pozvánky',
  'invitation.revoked': 'zrušení pozvánky',
  'invitation.accepted': 'přijetí pozvánky',
  'app.created': 'přidání aplikace',
  'app.updated': 'úprava nastavení aplikace',
  'credential.created': 'nahrání klíče',
  'credential.provisioned': 'vytvoření spravovaného účtu',
  'credential.attached': 'přiřazení klíče k appce',
  'credential.validated': 'ověření klíče',
  'credential.validation_failed': 'klíč neprošel ověřením',
  'credential.validation_recovered': 'klíč znovu funguje',
  'credential.revoked': 'odebrání klíče',
  'slack.installed': 'připojení Slacku',
  'teams.connected': 'připojení Teams',
  'channel.created': 'připojení kanálu',
  'channel.deleted': 'odpojení kanálu',
  'channel.tested': 'zkušební zpráva',
  'channel.locale_changed': 'změna jazyka kanálu',
  'channel.enabled_changed': 'zapnutí/vypnutí kanálu',
  'channel.deliveries_changed': 'změna obsahu kanálu',
  'review.state_changed': 'změna stavu recenze',
  'reply.published': 'publikování odpovědi',
  'reply_template.created': 'přidání šablony odpovědi',
  'reply_template.updated': 'úprava šablony odpovědi',
  'reply_template.deleted': 'smazání šablony odpovědi',
  'analysis.manual': 'ruční odeslání rozboru',
  'analysis.backfill': 'doplnění výkladů za historii',
  'history.import': 'import historie recenzí',
  'topic.created': 'přidání vlastního tématu',
  'topic.deleted': 'smazání vlastního tématu',
  'app_topic.created': 'přidání vlastního tématu',
  'app_topic.updated': 'úprava vlastního tématu',
  'app_topic.deleted': 'smazání vlastního tématu',
  'report.generated': 'vygenerování reportu',
  'report.shared': 'sdílení reportu',
  'report.unshared': 'zrušení sdílení reportu',
}

/** Skupiny akcí podle prefixu klíče — filtr „jen klíče" nebo „jen kanály" bez výčtu všech akcí. */
const AUDIT_GROUPS: Record<string, string> = {
  org: 'organizace',
  member: 'členové',
  invitation: 'pozvánky',
  app: 'aplikace',
  credential: 'klíče',
  channel: 'kanály',
  slack: 'Slack',
  teams: 'Teams',
  review: 'recenze',
  reply: 'odpovědi',
  reply_template: 'šablony odpovědí',
  analysis: 'rozbory',
  history: 'historie recenzí',
  topic: 'vlastní témata',
  app_topic: 'vlastní témata',
  report: 'reporty',
}

const AUDIT_PAGE = 50

/** UUID poznáme podle tvaru; v podrobnostech se za ně dosazuje jméno appky, ostatní zůstávají. */
const isUuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

export function AuditPage() {
  const { org = '' } = useParams()
  const [offset, setOffset] = useState(0)
  const audit = useAudit(org, AUDIT_PAGE, offset)
  const apps = useApps(org)
  const [group, setGroup] = useState('')
  const [actor, setActor] = useState('')

  // Audit nese jen UUID appky; jméno se doplňuje z katalogu, ať v řádku stojí „Můj Up", ne hash.
  // Smazaná appka v katalogu není — té zůstane UUID, což je pořád lepší než nic.
  const appName = (id: string) => apps.data?.find((app) => app.id === id)?.name ?? id
  const describe = (key: string, value: string) =>
    key === 'app' && isUuid(value) ? `${key}: ${appName(value)}` : `${key}: ${value}`

  const entries = audit.data ?? []
  // Zvolená hodnota musí v nabídce zůstat i na stránce, kde se nevyskytuje — jinak by se
  // filtr po přechodu na další stránku potichu „ztratil" ze selectu.
  const groups = [...new Set([...entries.map((entry) => entry.action.split('.')[0] ?? ''), group])]
    .filter(Boolean)
    .sort()
  const actors = [...new Set([...entries.map((entry) => entry.actor ?? 'systém'), actor])].filter(Boolean).sort()
  // Filtruje se nad načtenou stránkou: server stránkuje bez filtru a padesátka je přehledná
  // i bez dalšího dotazu. Prázdný výsledek tedy znamená „na téhle stránce nic", ne „nikdy".
  const visible = entries.filter(
    (entry) =>
      (group === '' || entry.action.startsWith(`${group}.`)) && (actor === '' || (entry.actor ?? 'systém') === actor),
  )

  return (
    <div className="stack">
      <div>
        <h1>Audit</h1>
        <p className="muted">Kdo co v organizaci udělal — včetně toho, co udělal systém.</p>
      </div>
      <Card>
        <div className="row" style={{ marginBottom: '0.75rem' }}>
          <Select
            value={group}
            options={[
              { value: '', label: 'Všechny akce' },
              ...groups.map((item) => ({ value: item, label: AUDIT_GROUPS[item] ?? item })),
            ]}
            onChange={setGroup}
            ariaLabel="Druh akce"
            fitContent
          />
          <Select
            value={actor}
            options={[{ value: '', label: 'Kdokoli' }, ...actors.map((item) => ({ value: item, label: item }))]}
            onChange={setActor}
            ariaLabel="Kdo"
            fitContent
          />
        </div>
        {audit.isPending ? <Loading /> : null}
        <ErrorBox error={audit.error} />
        <table className="row-cards">
          <thead>
            <tr>
              <th>Kdy</th>
              <th>Kdo</th>
              <th>Co</th>
              <th>Podrobnosti</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((entry, index) => (
              <tr key={`${entry.action}-${entry.at}-${index}`}>
                <td className="small nowrap">
                  <When iso={entry.at} />
                </td>
                <td className="small nowrap">{entry.actor ?? 'systém'}</td>
                <td className="small nowrap lead" title={entry.action}>
                  {AUDIT_ACTIONS[entry.action] ?? entry.action}
                </td>
                <td className="small muted wrap full">
                  {[
                    ...(entry.targetType === 'app' && entry.targetId && !('app' in entry.metadata)
                      ? [`app: ${appName(entry.targetId)}`]
                      : []),
                    ...Object.entries(entry.metadata).map(([key, value]) => describe(key, value)),
                  ].join(', ')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!audit.isPending && entries.length > 0 && visible.length === 0 ? (
          <p className="small muted">Na téhle stránce auditu filtru nic neodpovídá — zkus další stránku.</p>
        ) : null}
        <div className="row audit-pager">
          <button
            type="button"
            className="secondary"
            disabled={offset === 0 || audit.isFetching}
            onClick={() => setOffset(Math.max(0, offset - AUDIT_PAGE))}
          >
            Předchozí
          </button>
          <span className="small muted">
            {entries.length > 0 ? `${offset + 1}–${offset + entries.length}` : '—'}
          </span>
          <button
            type="button"
            className="secondary"
            // Kratší stránka než plná = poslední. Server počet celkem neposílá, a tenhle test stačí.
            disabled={entries.length < AUDIT_PAGE || audit.isFetching}
            onClick={() => setOffset(offset + AUDIT_PAGE)}
          >
            Další
          </button>
        </div>
      </Card>
    </div>
  )
}
