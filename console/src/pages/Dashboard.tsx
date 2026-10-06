import { Link, useParams } from 'react-router-dom'
import { useAnalysis, useAnalysisAlerts, useHealth, useRatings } from '../api/hooks'
import { Badge, Card, ErrorBox, Loading, PlatformBadge, When } from '../components/ui'
import type { AnalysisAlert, AppHealth, Health, RatingsSeries } from '../api/types'

/**
 * „Co mám dnes udělat" na jedné obrazovce: kolik recenzí čeká, které hoří, jak se appkám
 * daří. Diagnostika (klíče, kanály, úlohy) se ukazuje jen tehdy, když něco nefunguje —
 * správně nastavená appka nemá proč zabírat místo řádkem „v pořádku“ u každé položky.
 */
export function DashboardPage() {
  const { org = '' } = useParams()
  const health = useHealth(org)

  if (health.isPending) return <Loading />
  if (health.error) return <ErrorBox error={health.error} />

  const apps = health.data?.apps ?? []
  const failedJobs = health.data?.failedJobs ?? []
  // Konkurence klíč ani kanál nemá schválně — v „Co nefunguje" nemá co dělat.
  const issues = apps.filter((app) => !app.competitor).flatMap((app) => appIssues(org, app))
  const somethingBroken = issues.length > 0 || failedJobs.length > 0

  return (
    <div className="stack">
      <div>
        <h1>Přehled</h1>
        <p className="muted">Co dnes čeká na odpověď a jak se appkám daří.</p>
      </div>

      {apps.length === 0 ? (
        <Card>
          <p>Zatím tu není žádná aplikace.</p>
          <Link to={`/${org}/onboarding`}>Projít průvodce nastavením</Link>
        </Card>
      ) : (
        <>
          <Summary org={org} apps={apps} />
          <div className="overview-grid">
            {apps.map((app) => (
              <AppCard key={app.appId} org={org} app={app} />
            ))}
          </div>
          {somethingBroken ? (
            <BrokenCard issues={issues} failedJobs={failedJobs} />
          ) : (
            <p className="small muted">Klíče i kanály fungují, žádná úloha nevázne.</p>
          )}
        </>
      )}
    </div>
  )
}

/** Součty přes všechny appky. Jen z `health` — nic dalšího se kvůli nim nenačítá. */
function Summary({ org, apps }: { org: string; apps: AppHealth[] }) {
  const pending = apps.reduce((sum, app) => sum + app.pendingReviews, 0)
  const urgent = apps.reduce((sum, app) => sum + app.pendingUrgent, 0)
  return (
    <Card>
      <div className="metrics">
        <div>
          <div className="metric-value">
            {pending > 0 ? <Link to={`/${org}/recenze`}>{pending}</Link> : <span className="muted">0</span>}
          </div>
          <div className="metric-label">čeká na odpověď</div>
        </div>
        <div>
          <div className={`metric-value ${urgent > 0 ? 'overview-urgent' : ''}`}>
            {/* `urgency` v adrese Inbox zatím nečte; odkaz je připravený na to, až bude. */}
            {urgent > 0 ? <Link to={`/${org}/recenze?urgency=HIGH`}>{urgent}</Link> : <span className="muted">0</span>}
          </div>
          <div className="metric-label">naléhavých</div>
        </div>
      </div>
    </Card>
  )
}

function AppCard({ org, app }: { org: string; app: AppHealth }) {
  return (
    <Card>
      <div className="spread">
        <h2 style={{ margin: 0 }}>
          <Link to={`/${org}/aplikace/${app.appId}`}>{app.name}</Link>
        </h2>
        <span className="row" style={{ gap: '0.35rem' }}>
          {app.competitor ? <Badge>konkurence</Badge> : null}
          {app.enabled ? <Badge tone="ok">zapnutá</Badge> : <Badge tone="warn">vypnutá</Badge>}
        </span>
      </div>
      <table className="overview-rows" style={{ marginTop: '0.75rem' }}>
        <tbody>
          {/* Na cizí recenze se neodpovídá, řádek by jen mátl nulou. */}
          {app.competitor ? null : (
            <tr>
              <th>Čeká na odpověď</th>
              <td>
                {app.pendingReviews > 0 ? (
                  <Link to={`/${org}/recenze?app=${app.appId}`}>{app.pendingReviews}</Link>
                ) : (
                  <span className="muted">žádná</span>
                )}
                {app.pendingUrgent > 0 ? (
                  <>
                    {' '}
                    <Link to={`/${org}/recenze?app=${app.appId}&urgency=HIGH`} className="plain">
                      <Badge tone="bad">
                        z toho {app.pendingUrgent} naléhav
                        {app.pendingUrgent === 1 ? 'á' : 'é'}
                      </Badge>
                    </Link>
                  </>
                ) : null}
              </td>
            </tr>
          )}
          <tr>
            <th>Nálada 30 dní</th>
            <td>
              <MoodLine org={org} appId={app.appId} />
            </td>
          </tr>
          <tr>
            <th>Hodnocení 7 dní</th>
            <td>
              <RatingsLine org={org} appId={app.appId} />
            </td>
          </tr>
          <tr>
            <th>Poslední alert</th>
            <td>
              <LastAlert org={org} appId={app.appId} />
            </td>
          </tr>
          <tr>
            <th>Poslední recenze</th>
            <td>
              <When iso={app.lastReviewAt} />
            </td>
          </tr>
        </tbody>
      </table>
    </Card>
  )
}

/**
 * Jedna řádka nálady na kartě aplikace. Vlastní dotaz na appku, ne společný pro celý
 * přehled: karta se vykresluje i tam, kde rozbory nejsou zapnuté, a čekat kvůli tomu
 * s celou stránkou by bylo horší než nechat řádku doskočit.
 *
 * Počítá se ze **všech** hodnocení, stejně jako výchozí pohled na stránce Rozbory —
 * jinak tu stálo 43 % a o klik dál 83 % a nikdo nevěděl, čemu věřit.
 */
function MoodLine({ org, appId }: { org: string; appId: string }) {
  const analysis = useAnalysis(org, appId, { days: 30 })
  if (analysis.isPending) return <span className="muted">…</span>
  const mood = analysis.data?.allReviewsMood
  if (!mood || mood.reviews === 0) {
    return <span className="muted">zatím není z čeho</span>
  }
  const { sentiment, previousSentiment, reviews } = mood
  const delta = previousSentiment ? Math.round((sentiment.negative - previousSentiment.negative) * 100) : 0
  return (
    <span>
      <Link to={`/${org}/rozbory?app=${appId}`}>{Math.round(sentiment.positive * 100)} % spokojených</Link>{' '}
      <span className="small muted">z {reviews} hodnocení</span>
      {/* Roste podíl nespokojených = nálada jde dolů; znaménko se čte obráceně. */}
      {delta !== 0 ? (
        <span
          className={`small metric-delta ${delta > 0 ? 'down' : 'up'}`}
          title="Změna podílu spokojených proti předchozím 30 dnům"
        >
          {' '}
          {delta > 0 ? '↓' : '↑'} {Math.abs(delta)} b.
        </span>
      ) : null}
    </span>
  )
}

/** Trend hodnocení za týden, per platforma. Vlastní dotaz ze stejného důvodu jako nálada. */
function RatingsLine({ org, appId }: { org: string; appId: string }) {
  const ratings = useRatings(org, appId, 7)
  if (ratings.isPending) return <span className="muted">…</span>
  const series = (ratings.data ?? []).filter((item) => validAverages(item).length > 0)
  if (series.length === 0) return <span className="muted">zatím bez přehledu hodnocení</span>
  return (
    <div className="rating-trends">
      {series.map((item) => (
        <RatingTrend key={`${item.platform}-${item.territory}`} series={item} />
      ))}
    </div>
  )
}

/**
 * Nula není hodnocení — tak vypadá rozpracovaný den v exportu z Play. Kdyby zůstala,
 * týdenní změna vyjde −4,6 a vypadá to jako katastrofa. Server ji ve `change` zatím
 * nefiltruje, proto se změna počítá tady.
 */
function validAverages(series: RatingsSeries): number[] {
  return series.points
    .map((point) => point.average)
    .filter((average): average is number => average != null && average > 0)
}

function RatingTrend({ series }: { series: RatingsSeries }) {
  const values = validAverages(series)
  const first = values[0]
  const last = values[values.length - 1]
  // Rodič sem pouští jen řady s aspoň jedním platným bodem; pojistka kvůli typům.
  if (first === undefined || last === undefined) return null
  const change = values.length > 1 ? last - first : 0
  // Pod půl setiny je to šum zaokrouhlení ve storu, ne pohyb hodnocení.
  const flat = Math.abs(change) < 0.005
  return (
    <span className="rating-trend">
      <PlatformBadge platform={series.platform} />
      {/* Čára, číslo a změna drží pohromadě; na úzkém displeji se pod odznak přesunou celé. */}
      <span className="rating-trend-value">
        <MiniLine values={values} />
        <strong>{last.toFixed(2)}</strong>
        {flat ? (
          <span className="small muted">beze změny</span>
        ) : (
          <span className={`small metric-delta ${change > 0 ? 'up' : 'down'}`} title="Změna průměru za 7 dní">
            {change > 0 ? '+' : '−'}
            {Math.abs(change).toFixed(2)}
          </span>
        )}
      </span>
    </span>
  )
}

/**
 * Čára 80×20 px s osou ořezanou kolem dat. `Sparkline` z rozborů kreslí sloupce od nuly —
 * u průměrů 4,5–4,6 by z toho byla rovná plocha a nic by nebylo vidět.
 */
function MiniLine({ values }: { values: number[] }) {
  if (values.length < 2) return null
  const min = Math.min(...values)
  const max = Math.max(...values)
  const padding = Math.max((max - min) * 0.2, 0.02)
  const low = min - padding
  const span = max + padding - low
  const points = values
    .map((value, index) => {
      const x = MINI_PAD + (index / (values.length - 1)) * (MINI_WIDTH - 2 * MINI_PAD)
      const y = MINI_HEIGHT - MINI_PAD - ((value - low) / span) * (MINI_HEIGHT - 2 * MINI_PAD)
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg viewBox={`0 0 ${MINI_WIDTH} ${MINI_HEIGHT}`} className="mini-line" aria-hidden="true">
      <polyline points={points} className="chart-line" />
    </svg>
  )
}

const MINI_WIDTH = 80
const MINI_HEIGHT = 20
const MINI_PAD = 2

/** Nejnovější výkyv v recenzích appky. Server vrací alerty za 90 dní, nejnovější první. */
function LastAlert({ org, appId }: { org: string; appId: string }) {
  const alerts = useAnalysisAlerts(org, appId)
  if (alerts.isPending) return <span className="muted">…</span>
  const latest = newestAlert(alerts.data ?? [])
  if (!latest) return <span className="muted">žádný za 90 dní</span>
  return (
    <span className="alert-line">
      <Link to={`/${org}/rozbory?app=${appId}`}>{new Date(latest.windowDate).toLocaleDateString('cs-CZ')}</Link>
      {latest.kind === 'NEGATIVE_SPIKE' ? (
        <Badge tone="bad">záporné recenze</Badge>
      ) : (
        <Badge tone="warn">{latest.topicName ?? latest.topicKey}</Badge>
      )}
      <span className="small muted">
        {latest.observed} místo obvyklých {latest.expected.toFixed(1)}
      </span>
    </span>
  )
}

/** Pořadí ze serveru se nespoléhá — stačí, aby ho někdo změnil, a tady by stál starý alert. */
function newestAlert(alerts: AnalysisAlert[]): AnalysisAlert | null {
  return alerts.reduce<AnalysisAlert | null>(
    (best, alert) => (best === null || alert.windowDate > best.windowDate ? alert : best),
    null,
  )
}

// ---------------------------------------------------------------- co nefunguje

interface Issue {
  key: string
  appName: string
  tone: 'warn' | 'bad'
  text: string
  to: string
}

/** Fakta, ne rady: neověřený klíč, kanál bez instalace. Co s tím, si člověk přečte v detailu. */
function appIssues(org: string, app: AppHealth): Issue[] {
  const keys = `/${org}/aplikace/${app.appId}#klice`
  const channels = `/${org}/aplikace/${app.appId}#kanaly`
  const issues: Issue[] = []

  if (app.credentials.length === 0) {
    issues.push({
      key: `${app.appId}-nokey`,
      appName: app.name,
      tone: 'warn',
      text: 'žádný klíč — nemáme čím stáhnout recenze',
      to: keys,
    })
  }
  for (const credential of app.credentials) {
    if (credential.validationStatus === 'INVALID') {
      issues.push({
        key: credential.id,
        appName: app.name,
        tone: 'bad',
        text: `klíč „${credential.label}“: ${credential.validationError ?? 'neplatný'}`,
        to: keys,
      })
    } else if (credential.validationStatus === 'UNKNOWN') {
      issues.push({
        key: credential.id,
        appName: app.name,
        tone: 'warn',
        text: `klíč „${credential.label}“ je neověřený`,
        to: keys,
      })
    }
  }

  if (app.channels.length === 0) {
    issues.push({
      key: `${app.appId}-nochannel`,
      appName: app.name,
      tone: 'warn',
      text: 'žádný kanál — recenze nemají kam chodit',
      to: channels,
    })
  }
  for (const channel of app.channels) {
    if (!channel.hasCredential) {
      issues.push({
        key: channel.id,
        appName: app.name,
        tone: 'bad',
        text: `kanál ${channel.targetRef} je bez instalace`,
        to: channels,
      })
    }
  }
  return issues
}

function BrokenCard({ issues, failedJobs }: { issues: Issue[]; failedJobs: Health['failedJobs'] }) {
  return (
    <Card title="Co nefunguje">
      {issues.length > 0 ? (
        <ul className="issue-list">
          {issues.map((issue) => (
            <li key={issue.key}>
              <Badge tone={issue.tone}>{issue.appName}</Badge>
              <span>
                {issue.text} · <Link to={issue.to}>opravit</Link>
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      {failedJobs.length > 0 ? (
        <>
          <h3 className="issue-heading">Úlohy, které se nepovedly</h3>
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
        </>
      ) : null}
    </Card>
  )
}
