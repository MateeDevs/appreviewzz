import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAnalysis, useAnalysisAlerts, useHealth, useRatings } from '../api/hooks'
import { Badge, Card, ErrorBox, Loading, PageHeader, PlatformBadge, SignalArt, When } from '../components/ui'
import type { AppHealth, Health, RatingsSeries } from '../api/types'

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

  const own = apps.filter((app) => !app.competitor)
  const urgent = own.reduce((sum, app) => sum + app.pendingUrgent, 0)
  const pending = own.reduce((sum, app) => sum + app.pendingReviews, 0)
  const queue = [...own].sort(
    (a, b) => b.pendingUrgent - a.pendingUrgent || b.pendingReviews - a.pendingReviews,
  )
  const first = queue.find((app) => app.pendingReviews > 0) ?? own[0]
  return (
    <div className="stack dashboard-page">
      <PageHeader
        eyebrow="Vaše portfolio"
        title="Přehled"
        action={
          <Link className="button" to={`/${org}/aplikace`}>
            Spravovat aplikace ↗
          </Link>
        }
      >
        Jasné priority. Souvislosti pro další krok.
      </PageHeader>
      {apps.length === 0 ? (
        <Card>
          <div className="empty-state">
            <SignalArt />
            <div>
              <h2>První signál začíná vaší aplikací.</h2>
              <p>Připojte App Store nebo Google Play. Recenze a jejich souvislosti se potkají tady.</p>
              <Link className="button" to={`/${org}/onboarding`}>
                Připojit první aplikaci →
              </Link>
            </div>
          </div>
        </Card>
      ) : (
        <>
          <section className="overview-hero" aria-label="Pracovní souhrn">
            <div>
              <div className="eyebrow">Vaše pracovní fronta</div>
              <div className="hero-number">{pending.toLocaleString('cs-CZ')}</div>
              <div className="hero-label">
                {pending === 1
                  ? 'recenze čeká na odpověď'
                  : pending >= 2 && pending <= 4
                    ? 'recenze čekají na odpověď'
                    : 'recenzí čeká na odpověď'}
              </div>
              <p className="small">
                Aktuální stav · {own.length}{' '}
                {own.length >= 1 && own.length <= 4 ? 'vlastní aplikace' : 'vlastních aplikací'}
              </p>
              {first ? (
                <Link
                  className="button"
                  to={`/${org}/recenze?app=${first.appId}${urgent ? '&urgency=HIGH' : ''}`}
                >
                  {urgent ? 'Začít naléhavými' : 'Otevřít recenze'} →
                </Link>
              ) : null}
            </div>
            <div className="hero-priority">
              <div className="eyebrow">Přednostně</div>
              <h2>
                {urgent > 0
                  ? urgent === 1
                    ? '1 naléhavá recenze'
                    : urgent <= 4
                      ? `${urgent} naléhavé recenze`
                      : `${urgent} naléhavých recenzí`
                  : pending > 0
                    ? 'Bez naléhavých recenzí.'
                    : 'Žádná čekající odpověď.'}
              </h2>
              <p className="small">
                {urgent > 0
                  ? 'Nejprve projděte zpětnou vazbu, která vyžaduje pozornost.'
                  : pending > 0
                    ? 'Ve frontě čekají běžné recenze. Otevřete je a pokračujte v odpovídání.'
                    : 'Nové recenze se objeví při dalším stažení ze storu.'}
              </p>
              <ul>
                {queue
                  .filter((app) => app.pendingReviews > 0)
                  .slice(0, 3)
                  .map((app) => (
                    <li key={app.appId}>
                      <Link
                        to={`/${org}/recenze?app=${app.appId}${app.pendingUrgent ? '&urgency=HIGH' : ''}`}
                      >
                        <span>{app.name}</span>
                        <strong>
                          {app.pendingUrgent ? `${app.pendingUrgent} naléhavé` : `${app.pendingReviews} čeká`}{' '}
                          ↗
                        </strong>
                      </Link>
                    </li>
                  ))}
              </ul>
            </div>
            <SignalArt />
          </section>
          {somethingBroken ? <BrokenCard issues={issues} failedJobs={failedJobs} /> : null}
          {first ? <PrioritySignals org={org} app={first} /> : null}
          <section aria-labelledby="portfolio-title">
            <div className="section-heading">
              <div>
                <div className="eyebrow">01 / Portfolio</div>
                <h2 id="portfolio-title">Aplikace pod dohledem</h2>
              </div>
              <span className="small muted">Store rating a nálada jsou samostatné metriky.</span>
            </div>
            <div className="portfolio">
              <div className="portfolio-row header">
                <div>Aplikace</div>
                <div>Čeká na odpověď</div>
                <div>Store rating · 7 dní</div>
                <div>Nálada · 30 dní</div>
              </div>
              {apps.map((app) => (
                <AppRow key={app.appId} org={org} app={app} />
              ))}
            </div>
          </section>
          {!somethingBroken ? (
            <p className="small muted">✓ Klíče a kanály bez hlášených potíží. Žádná selhaná úloha.</p>
          ) : null}
        </>
      )}
    </div>
  )
}

function PrioritySignals({ org, app }: { org: string; app: AppHealth }) {
  const alerts = useAnalysisAlerts(org, app.appId)
  if (alerts.error) return <ErrorBox error={alerts.error} />
  const items = (alerts.data ?? []).slice(0, 3)
  if (!items.length) return null
  return (
    <section>
      <div className="section-heading">
        <div>
          <div className="eyebrow">Ze zpětné vazby</div>
          <h2>Signály · {app.name}</h2>
        </div>
        <Link to={`/${org}/rozbory?app=${app.appId}`}>Otevřít rozbory ↗</Link>
      </div>
      <div className="signal-stream">
        {items.map((alert, index) => (
          <div className="signal-row" key={alert.id}>
            <span className="signal-index">0{index + 1}</span>
            <div className="grow">
              <strong>{alert.topicName ?? 'Nárůst záporných recenzí'}</strong>
              <div className="small muted">
                {new Date(alert.windowDate).toLocaleDateString('cs-CZ')} · {alert.observed} místo obvyklých{' '}
                {alert.expected.toFixed(1)} za den · baseline 28 dní
              </div>
            </div>
            <Link
              to={`/${org}/recenze?app=${app.appId}&state=all&textOnly=true&from=${alert.windowDate}&to=${alert.windowDate}${alert.kind === 'NEGATIVE_SPIKE' ? '&sentiment=NEGATIVE' : ''}${alert.topicKey ? `&topic=${encodeURIComponent(alert.topicKey)}` : ''}`}
            >
              Prohlédnout recenze ↗
            </Link>
          </div>
        ))}
      </div>
      <p className="small muted">
        Výkyvy za 90 dní u aplikace s nejvyšší prioritou. Odkaz otevře recenze daného dne.
      </p>
    </section>
  )
}

function AppRow({ org, app }: { org: string; app: AppHealth }) {
  const root = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin: '100px' },
    )
    if (root.current) observer.observe(root.current)
    return () => observer.disconnect()
  }, [])
  return (
    <div className="portfolio-row" ref={root}>
      <div className="app-identity">
        <span className="app-monogram" aria-hidden="true">
          {app.name.slice(0, 1)}
        </span>
        <div>
          <Link to={`/${org}/aplikace/${app.appId}`}>{app.name}</Link>
          <div className="small muted">
            Poslední recenze: <When iso={app.lastReviewAt} />
          </div>
          <div className="small muted">
            {app.competitor
              ? 'Konkurence · pouze ke čtení'
              : app.enabled
                ? 'Vlastní aplikace'
                : 'Sledování vypnuté'}
          </div>
        </div>
      </div>
      <div>
        {app.competitor ? (
          <span className="small muted">Pouze ke čtení</span>
        ) : (
          <>
            <div className="portfolio-waiting">
              <Link to={`/${org}/recenze?app=${app.appId}`}>{app.pendingReviews}</Link>
            </div>
            {app.pendingUrgent > 0 ? (
              <Link to={`/${org}/recenze?app=${app.appId}&urgency=HIGH`}>
                <Badge tone="bad">{app.pendingUrgent} naléhavé ↗</Badge>
              </Link>
            ) : (
              <span className="small muted">žádná naléhavá</span>
            )}
          </>
        )}
      </div>
      <div>
        {visible ? (
          <RatingsLine org={org} appId={app.appId} />
        ) : (
          <span className="small muted">Trend načteme po zobrazení.</span>
        )}
      </div>
      <div>
        {visible ? (
          <MoodLine org={org} appId={app.appId} />
        ) : (
          <span className="small muted">Náladu načteme po zobrazení.</span>
        )}
      </div>
    </div>
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
  if (analysis.error) return <span className="small error-inline">Náladu se nepodařilo načíst.</span>
  const mood = analysis.data?.allReviewsMood
  if (!mood || mood.reviews === 0) {
    return <span className="muted">zatím není z čeho</span>
  }
  const { sentiment, previousSentiment, reviews } = mood
  const delta = previousSentiment ? Math.round((sentiment.positive - previousSentiment.positive) * 100) : 0
  return (
    <span className="mood-compact">
      <Link to={`/${org}/rozbory?app=${appId}`}>{Math.round(sentiment.positive * 100)} % spokojených</Link>{' '}
      <span className="small muted">z {reviews} hodnocení</span>
      {/* Roste podíl nespokojených = nálada jde dolů; znaménko se čte obráceně. */}
      {delta !== 0 ? (
        <span
          className={`small metric-delta ${delta > 0 ? 'up' : 'down'}`}
          title="Změna podílu spokojených proti předchozím 30 dnům v procentních bodech"
        >
          {' '}
          {delta > 0 ? '↑' : '↓'} {Math.abs(delta)} b.
        </span>
      ) : null}
      <span className="mood-strip" aria-hidden="true">
        <span style={{ width: `${sentiment.positive * 100}%` }} />
      </span>
    </span>
  )
}

/** Trend hodnocení za týden, per platforma. Vlastní dotaz ze stejného důvodu jako nálada. */
function RatingsLine({ org, appId }: { org: string; appId: string }) {
  const ratings = useRatings(org, appId, 7)
  if (ratings.isPending) return <span className="muted">…</span>
  if (ratings.error) return <span className="small error-inline">Rating se nepodařilo načíst.</span>
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
      <span className="small muted">{series.territory}</span>
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
    <Card title="Nastavení vyžaduje pozornost" className="diagnostics">
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
          <table tabIndex={0} className="row-cards">
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
                  <td className="lead">{job.task}</td>
                  <td data-label="pokusů">{job.attempts}</td>
                  <td>
                    <When iso={job.lastFailedAt} />
                  </td>
                  <td className="small full">{job.error ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : null}
    </Card>
  )
}
