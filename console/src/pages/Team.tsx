import { useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  useAudit,
  useChangeRole,
  useInvitations,
  useInvite,
  useMe,
  useMembers,
  useRemoveMember,
  useRevokeInvitation,
} from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, When } from '../components/ui'

export function TeamPage() {
  const { org = '' } = useParams()
  const me = useMe()
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
        <h1>Tým</h1>
        <p className="muted">Kdo do organizace vidí a co smí.</p>
      </div>

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
                    <select
                      value={member.role}
                      onChange={(e) => changeRole.mutate({ userId: member.userId, role: e.target.value })}
                    >
                      <option value="OWNER">vlastník</option>
                      <option value="ADMIN">správce</option>
                      <option value="MEMBER">člen</option>
                    </select>
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
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="MEMBER">člen</option>
                <option value="ADMIN">správce</option>
                <option value="OWNER">vlastník</option>
              </select>
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
