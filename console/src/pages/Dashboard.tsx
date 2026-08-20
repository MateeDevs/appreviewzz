import { Link, useParams } from 'react-router-dom'
import { useHealth } from '../api/hooks'
import { Badge, Card, ErrorBox, Loading, When } from '../components/ui'
import type { AppHealth } from '../api/types'

/**
 * „Proč nic nechodí" na jedné obrazovce. Console neradí, co dělat — ukáže fakta
 * (neověřený klíč, kanál bez instalace, úloha v DLQ) a nechá závěr na člověku.
 */
export function DashboardPage() {
  const { org = '' } = useParams()
  const health = useHealth(org)

  if (health.isPending) return <Loading />
  if (health.error) return <ErrorBox error={health.error} />

  const apps = health.data?.apps ?? []
  const failedJobs = health.data?.failedJobs ?? []

  return (
    <div className="stack">
      <div>
        <h1>Přehled</h1>
        <p className="muted">Stav doručování recenzí do kanálů.</p>
      </div>

      {apps.length === 0 ? (
        <Card>
          <p>Zatím tu není žádná aplikace.</p>
          <Link to={`/${org}/onboarding`}>Projít průvodce nastavením</Link>
        </Card>
      ) : (
        apps.map((app) => <AppHealthCard key={app.appId} org={org} app={app} />)
      )}

      <Card title="Úlohy, které se nepovedly">
        {failedJobs.length === 0 ? (
          <p className="muted">Nic nevázne.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Úloha</th>
                <th>Pokusů</th>
                <th>Naposled</th>
                <th>Důvod</th>
              </tr>
            </thead>
            <tbody>
              {failedJobs.map((job) => (
                <tr key={`${job.task}-${job.firstFailedAt}`}>
                  <td>{job.task}</td>
                  <td>{job.attempts}</td>
                  <td>
                    <When iso={job.lastFailedAt} />
                  </td>
                  <td className="small">{job.error ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}

function AppHealthCard({ org, app }: { org: string; app: AppHealth }) {
  const channelsOk = app.channels.filter((channel) => channel.enabled && channel.hasCredential).length
  const invalidKeys = app.credentials.filter((credential) => credential.validationStatus === 'INVALID')
  const uncheckedKeys = app.credentials.filter((credential) => credential.validationStatus === 'UNKNOWN')

  return (
    <Card>
      <div className="spread">
        <h2 style={{ margin: 0 }}>
          <Link to={`/${org}/aplikace/${app.appId}`}>{app.name}</Link>
        </h2>
        {app.enabled ? <Badge tone="ok">zapnutá</Badge> : <Badge tone="warn">vypnutá</Badge>}
      </div>
      <table style={{ marginTop: '0.75rem' }}>
        <tbody>
          <tr>
            <th>Poslední recenze</th>
            <td>
              <When iso={app.lastReviewAt} />
            </td>
          </tr>
          <tr>
            <th>Čeká na odpověď</th>
            <td>{app.pendingReviews}</td>
          </tr>
          <tr>
            <th>Kanály</th>
            <td>
              {app.channels.length === 0 ? (
                <Badge tone="warn">žádný kanál — recenze nemají kam chodit</Badge>
              ) : (
                <span>
                  {channelsOk} z {app.channels.length} připravených
                  {app.channels
                    .filter((channel) => !channel.hasCredential)
                    .map((channel) => (
                      <span key={channel.id}>
                        {' '}
                        <Badge tone="bad">{channel.targetRef} bez instalace</Badge>
                      </span>
                    ))}
                </span>
              )}
            </td>
          </tr>
          <tr>
            <th>Klíče</th>
            <td>
              {app.credentials.length === 0 ? (
                <Badge tone="warn">žádný klíč — nemáme čím stáhnout recenze</Badge>
              ) : (
                <>
                  {invalidKeys.map((credential) => (
                    <span key={credential.id}>
                      <Badge tone="bad">{credential.label}: {credential.validationError ?? 'neplatný'}</Badge>{' '}
                    </span>
                  ))}
                  {uncheckedKeys.map((credential) => (
                    <span key={credential.id}>
                      <Badge tone="warn">{credential.label}: neověřený</Badge>{' '}
                    </span>
                  ))}
                  {invalidKeys.length === 0 && uncheckedKeys.length === 0 ? <Badge tone="ok">v pořádku</Badge> : null}
                </>
              )}
            </td>
          </tr>
        </tbody>
      </table>
    </Card>
  )
}
