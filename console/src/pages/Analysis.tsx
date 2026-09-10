import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  useAnalysis,
  useAnalysisAlerts,
  useAnalysisStatus,
  useApps,
  useBackfillAnalysis,
  useMe,
  useGenerateReport,
  useReports,
  useRunAnalysis,
  useShareReport,
  useUnshareReport,
  useVersionImpact,
} from '../api/hooks'
import type { AnalysisCalendarPeriod } from '../api/hooks'
import { Badge, Card, Empty, ErrorBox, Loading } from '../components/ui'
import { SentimentChart, Sparkline } from '../components/SentimentChart'
import { Select } from '../components/Select'
import type { AnalysisOverview, Platform, TopicStatus, VersionImpact } from '../api/types'

const STATUS_LABELS: Record<TopicStatus, { label: string; tone?: 'ok' | 'warn' | 'bad' }> = {
  NEW: { label: 'nové', tone: 'warn' },
  GROWING: { label: 'roste', tone: 'bad' },
  STABLE: { label: 'beze změny' },
  FALLING: { label: 'klesá', tone: 'ok' },
}

const CALENDAR_PERIODS: { value: AnalysisCalendarPeriod; label: string }[] = [
  { value: 'THIS_WEEK', label: 'Tento týden' },
  { value: 'PREVIOUS_WEEK', label: 'Minulý týden' },
  { value: 'THIS_MONTH', label: 'Tento měsíc' },
  { value: 'PREVIOUS_MONTH', label: 'Minulý měsíc' },
  { value: 'THIS_YEAR', label: 'Tento rok' },
  { value: 'PREVIOUS_YEAR', label: 'Minulý rok' },
]

const ROLLING_PERIODS = [
  { days: 7, label: 'Posledních 7 dní' },
  { days: 30, label: 'Posledních 30 dní' },
  { days: 90, label: 'Posledních 90 dní' },
  { days: 365, label: 'Posledních 365 dní' },
]

const isCalendarPeriod = (value: string): value is AnalysisCalendarPeriod =>
  CALENDAR_PERIODS.some((period) => period.value === value)

const formatPeriod = (start: string, end: string) => {
  const format = (value: string) => {
    const [year, month, day] = value.split('-').map(Number)
    if (!year || !month || !day) return value
    return new Date(year, month - 1, day).toLocaleDateString('cs-CZ')
  }
  return `${format(start)} – ${format(end)}`
}

const percent = (share: number) => `${Math.round(share * 100)} %`

/**
 * Rozbory recenzí (F8).
 *
 * Stránka **nevypisuje ani jednu recenzi** — od čísla se ke konkrétním recenzím jde
 * odkazem do inboxu s filtrem. Jinak by z ní byl druhý inbox s horšími filtry.
 */
export function AnalysisPage() {
  const { org = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const apps = useApps(org)
  const me = useMe()

  const appId = params.get('app') ?? ''
  const requestedPeriod = params.get('period') ?? ''
  const period = isCalendarPeriod(requestedPeriod) ? requestedPeriod : undefined
  const daysParam = params.get('days')?.trim()
  const requestedDays = daysParam ? Number(daysParam) : 30
  const days = Number.isInteger(requestedDays) ? Math.min(365, Math.max(7, requestedDays)) : 30
  const isPresetDays = ROLLING_PERIODS.some((item) => item.days === days)
  const platform: Platform | '' = (params.get('platform') as Platform | null) ?? ''
  const territory = params.get('territory') ?? ''
  const moodScope = params.get('mood') === 'with-text' ? 'with-text' : 'all'

  const selected = appId || (apps.data?.[0]?.id ?? '')
  const filters = { days: period ? undefined : days, period, platform, territory }
  const analysis = useAnalysis(org, selected, filters)
  const status = useAnalysisStatus(org, selected)

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  const setPeriod = (value: string) => {
    const next = new URLSearchParams(params)
    if (value.startsWith('DAYS_')) {
      next.set('days', value.slice('DAYS_'.length))
      next.delete('period')
    } else {
      next.set('period', value)
      next.delete('days')
    }
    setParams(next, { replace: true })
  }

  const role = me.data?.organizations.find((item) => item.slug === org)?.role
  const canRun = role === 'OWNER' || role === 'ADMIN'

  if (apps.isPending) return <Loading />
  if (apps.data?.length === 0) {
    return (
      <Card>
        <p>Napřed je potřeba přidat aplikaci — bez ní není co rozebírat.</p>
      </Card>
    )
  }

  return (
    <div className="stack">
      <div>
        <h1>Rozbory</h1>
        <p className="muted">O čem lidé píšou, jak se to vyvíjí a co s tím udělalo vydání.</p>
      </div>

      <Card>
        <div className="row">
          <Select
            value={selected}
            options={apps.data?.map((app) => ({ value: app.id, label: app.name }))}
            onChange={(value) => setParam('app', value)}
            ariaLabel="Aplikace"
            fitContent
          />
          <PeriodSelect
            value={period ?? `DAYS_${days}`}
            customDays={!period && !isPresetDays ? days : undefined}
            onChange={setPeriod}
          />
          <Select
            value={platform}
            options={[
              { value: '', label: 'Obě platformy' },
              { value: 'ANDROID', label: 'Google Play' },
              { value: 'IOS', label: 'App Store' },
            ]}
            onChange={(value) => setParam('platform', value)}
            ariaLabel="Platforma"
            fitContent
          />
          <TerritorySelect
            territories={analysis.data?.territories.map((item) => item.territory) ?? []}
            value={territory}
            onChange={(value) => setParam('territory', value)}
          />
          {analysis.data ? (
            <span className="small muted" aria-live="polite">
              {formatPeriod(analysis.data.periodStart, analysis.data.periodEnd)}
            </span>
          ) : null}
        </div>
      </Card>

      <CoverageBar
        org={org}
        appId={selected}
        overview={analysis.data}
        analyzed={status.data?.analyzed ?? 0}
        missing={status.data?.missing ?? 0}
        canRun={canRun}
      />

      {analysis.isPending ? <Loading /> : null}
      <ErrorBox error={analysis.error} />
      {analysis.data ? (
        <Overview
          org={org}
          appId={selected}
          overview={analysis.data}
          moodScope={moodScope}
          onMoodScopeChange={(value) => setParam('mood', value === 'with-text' ? value : '')}
        />
      ) : null}
    </div>
  )
}

type PeriodOption = { value: string; label: string }

function PeriodSelect({
  value,
  customDays,
  onChange,
}: {
  value: string
  customDays?: number
  onChange: (value: string) => void
}) {
  const calendarOptions: PeriodOption[] = CALENDAR_PERIODS
  const rollingOptions: PeriodOption[] = [
    ...(customDays ? [{ value: `DAYS_${customDays}`, label: `Posledních ${customDays} dní` }] : []),
    ...ROLLING_PERIODS.map((item) => ({ value: `DAYS_${item.days}`, label: item.label })),
  ]
  return (
    <Select
      value={value}
      groups={[
        { label: 'Kalendářní období', options: calendarOptions },
        { label: 'Klouzavé období', options: rollingOptions },
      ]}
      onChange={onChange}
      ariaLabel="Období rozboru"
      className="period-select"
      fitContent
    />
  )
}

/**
 * Kolik recenzí má výklad a od kdy data jsou. Bez toho se podíly čtou špatně: „12 % o pádech"
 * z poloviny otagovaných recenzí je jiné číslo než z celku, a nikdo to na grafu nepozná.
 */
function CoverageBar({
  org,
  appId,
  overview,
  analyzed,
  missing,
  canRun,
}: {
  org: string
  appId: string
  overview: AnalysisOverview | undefined
  analyzed: number
  missing: number
  canRun: boolean
}) {
  const backfill = useBackfillAnalysis(org, appId)
  const run = useRunAnalysis(org, appId)
  const [sent, setSent] = useState<string | null>(null)

  return (
    <Card>
      <div className="spread">
        <div className="small muted">
          Výklad má {analyzed} z {analyzed + missing} recenzí.
          {overview?.dataSince ? ` Data od ${new Date(overview.dataSince).toLocaleDateString('cs-CZ')}.` : ''}
          {missing > 0 ? ' Zbytek se dopočítá na pozadí.' : ''}
        </div>
        <div className="row">
          {missing > 0 ? (
            <button type="button" className="secondary" onClick={() => backfill.mutate()} disabled={backfill.isPending}>
              Doplnit za historii
            </button>
          ) : null}
          {canRun ? (
            <button
              type="button"
              className="secondary"
              disabled={run.isPending}
              onClick={() =>
                run.mutate(undefined, {
                  onSuccess: (result) =>
                    setSent(
                      result.skipped
                        ? describeSkip(result.skipped)
                        : `Odesláno do ${result.sent} kanálů (${result.reviews} recenzí).`,
                    ),
                })
              }
            >
              Poslat rozbor teď
            </button>
          ) : null}
        </div>
      </div>
      <ErrorBox error={backfill.error ?? run.error} />
      {backfill.data?.queued ? <div className="notice">Doplnění výkladů je ve frontě.</div> : null}
      {sent ? <div className="notice">{sent}</div> : null}
    </Card>
  )
}

function Overview({
  org,
  appId,
  overview,
  moodScope,
  onMoodScopeChange,
}: {
  org: string
  appId: string
  overview: AnalysisOverview
  moodScope: 'with-text' | 'all'
  onMoodScopeChange: (value: 'with-text' | 'all') => void
}) {
  if (overview.analyzed === 0) {
    return (
      <Card title="Zatím není co rozebírat">
        <p>
          Žádná recenze téhle aplikace nemá výklad. Buď ještě žádná nepřišla, nebo není nastavená AI —
          bez ní se recenze doručují, ale netagují.
        </p>
      </Card>
    )
  }
  const inbox = (topic?: string) =>
    `/${org}/recenze${topic ? `?app=${appId}&topic=${encodeURIComponent(topic)}` : ''}`
  const mood = moodScope === 'all' ? overview.allReviewsMood : overview
  const delta = mood.previousSentiment ? mood.sentiment.negative - mood.previousSentiment.negative : null

  return (
    <>
      <Card>
        <div className="card-heading">
          <h2>Nálada v čase</h2>
          <div className="segmented" role="group" aria-label="Recenze zahrnuté do nálady">
            <button
              type="button"
              className={moodScope === 'all' ? 'active' : undefined}
              aria-pressed={moodScope === 'all'}
              onClick={() => onMoodScopeChange('all')}
            >
              Všechny recenze
            </button>
            <button
              type="button"
              className={moodScope === 'with-text' ? 'active' : undefined}
              aria-pressed={moodScope === 'with-text'}
              onClick={() => onMoodScopeChange('with-text')}
            >
              Jen s textem
            </button>
          </div>
        </div>
        {mood.reviews === 0 ? (
          <p className="muted">Za tohle období ve vybraném rozsahu žádné recenze nepřišly.</p>
        ) : (
          <>
            <div className="metrics" style={{ marginBottom: '1rem' }}>
              <div>
                <div className="metric-value">{percent(mood.sentiment.positive)}</div>
                <div className="metric-label">spokojených</div>
                {delta != null && Math.round(delta * 100) !== 0 ? (
                  // Roste podíl nespokojených = nálada jde dolů; znaménko se čte obráceně.
                  <div className={`metric-delta ${delta > 0 ? 'down' : 'up'}`}>
                    {delta > 0 ? '↓' : '↑'} {Math.abs(Math.round(delta * 100))} b. proti minulému období
                  </div>
                ) : null}
              </div>
              <div>
                <div className="metric-value">{mood.reviews}</div>
                <div className="metric-label">{moodScope === 'all' ? 'všech recenzí' : 'recenzí s textem'}</div>
              </div>
              <div>
                <div className="metric-value">{mood.avgStars?.toFixed(2) ?? '—'}</div>
                <div className="metric-label">průměr hvězd</div>
              </div>
            </div>
            {moodScope === 'all' && mood.reviews > overview.reviews ? (
              <p className="muted small">
                Zahrnuto i {mood.reviews - overview.reviews} hodnocení bez textu. Témata a jazyky níž vycházejí z{' '}
                {overview.reviews} recenzí s textem.
              </p>
            ) : moodScope === 'with-text' && overview.tooFewReviews ? (
              <p className="muted small">
                Za období je jen {overview.reviews} recenzí s textem; do kanálu se rozbor posílá až od{' '}
                {overview.minReviews}. Čísla níž jsou orientační.
              </p>
            ) : null}
            <SentimentChart weeks={mood.weekly} />
          </>
        )}
      </Card>

      <Card title="Témata">
        {overview.topics.length === 0 ? (
          <Empty>Žádné téma se neopakovalo natolik, aby stálo za zmínku.</Empty>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Téma</th>
                <th>Podíl recenzí</th>
                <th>Ø ★</th>
                <th>Vývoj</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {overview.topics.map((topic) => {
                const status = STATUS_LABELS[topic.status]
                return (
                  <tr key={topic.key}>
                    <td>
                      <Link to={inbox(topic.key)}>{topic.name}</Link>{' '}
                      {topic.status === 'STABLE' ? null : <Badge tone={status.tone}>{status.label}</Badge>}
                    </td>
                    <td>
                      <span className="share-bar" title={`${topic.count}× · ${percent(topic.negativeShare)} záporných`}>
                        <span
                          className={topic.negativeShare >= 0.5 ? 'bad' : undefined}
                          style={{ width: `${Math.min(topic.share * 100, 100)}%` }}
                        />
                      </span>
                      <span className="small muted">
                        {percent(topic.share)} · {topic.count}×
                      </span>
                    </td>
                    <td>{topic.avgStars?.toFixed(1) ?? '—'}</td>
                    <td>
                      <Sparkline points={topic.trend} />
                    </td>
                    <td className="small muted">
                      {topic.previousCount > 0 ? `minule ${topic.previousCount}×` : 'minule nic'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        {overview.improved.length > 0 ? (
          <p className="small muted" style={{ marginTop: '0.75rem' }}>
            Zlepšilo se:{' '}
            {overview.improved.map((item) => `${item.name} (z ${item.before}× na ${item.after}×)`).join(', ')}.
          </p>
        ) : null}
      </Card>

      <div className="cards-2">
        <Card title="Trhy">
          {overview.territories.length === 0 ? (
            <Empty>Store u těchhle recenzí neuvedl zemi.</Empty>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Země</th>
                  <th>Recenzí</th>
                  <th>Nespokojených</th>
                  <th>Ø ★</th>
                </tr>
              </thead>
              <tbody>
                {overview.territories.slice(0, 8).map((item) => (
                  <tr key={item.territory}>
                    <td>{item.territory}</td>
                    <td>{item.reviews}</td>
                    <td>{percent(item.negativeShare)}</td>
                    <td>{item.avgStars?.toFixed(1) ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="Odpovídání">
          <div className="metrics">
            <div>
              <div className="metric-value">{percent(overview.replies.share)}</div>
              <div className="metric-label">
                odpovězeno ({overview.replies.replied} z {overview.replies.total})
              </div>
            </div>
            <div>
              <div className="metric-value">
                {overview.replies.medianHours != null ? `${overview.replies.medianHours.toFixed(1)} h` : '—'}
              </div>
              <div className="metric-label">medián do odpovědi</div>
            </div>
          </div>
          {overview.replies.uplifted > 0 || overview.replies.dropped > 0 ? (
            <p className="small muted" style={{ marginTop: '0.75rem' }}>
              Po odpovědi {overview.replies.uplifted} recenzí přidalo hvězdy
              {overview.replies.dropped > 0 ? `, ${overview.replies.dropped} ubralo` : ''}.
            </p>
          ) : (
            <p className="small muted" style={{ marginTop: '0.75rem' }}>
              Zatím nemáme dost přepsaných recenzí na to, abychom viděli efekt odpovědí.
            </p>
          )}
          {overview.languages.length > 0 ? (
            <p className="small muted">
              Jazyky: {overview.languages.slice(0, 5).map((item) => `${item.language} (${item.reviews})`).join(', ')}.
            </p>
          ) : null}
        </Card>
      </div>

      <VersionImpactCard org={org} appId={appId} />
      <AlertsCard org={org} appId={appId} />
      <ReportsCard org={org} appId={appId} />
    </>
  )
}

/**
 * Měsíční reporty pro klienta (C1).
 *
 * Report je zmrazený snímek: odkaz, který agentura pošle klientovi, ukáže za rok totéž co
 * dnes. Přegenerování měsíce čísla přepíše — a je to jediná cesta, jak se změní.
 */
function ReportsCard({ org, appId }: { org: string; appId: string }) {
  const reports = useReports(org, appId)
  const generate = useGenerateReport(org, appId)
  const share = useShareReport(org, appId)
  const unshare = useUnshareReport(org, appId)
  const [copied, setCopied] = useState('')

  const copy = (url: string, id: string) => {
    navigator.clipboard?.writeText(url).then(
      () => setCopied(id),
      // Bez schránky (starý prohlížeč, http) se odkaz aspoň ukáže k ručnímu zkopírování.
      () => setCopied(''),
    )
  }

  return (
    <Card title="Měsíční reporty">
      <div className="spread" style={{ marginBottom: '0.75rem' }}>
        <p className="small muted" style={{ margin: 0 }}>
          Stránka pro klienta se sdílitelným odkazem. V prohlížeči se dá uložit jako PDF.
        </p>
        <button type="button" className="secondary" onClick={() => generate.mutate(undefined)} disabled={generate.isPending}>
          Vygenerovat minulý měsíc
        </button>
      </div>
      <ErrorBox error={generate.error ?? share.error ?? unshare.error} />
      {reports.isPending ? <Loading /> : null}
      {reports.data?.length === 0 ? (
        <Empty>Zatím žádný report. První se vygeneruje prvního dne příštího měsíce — nebo tlačítkem výš.</Empty>
      ) : null}
      {reports.data && reports.data.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Období</th>
              <th>Recenzí</th>
              <th>Odkaz</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {reports.data.map((report) => (
              <tr key={report.id}>
                <td>
                  {new Date(report.periodStart).toLocaleDateString('cs-CZ')} –{' '}
                  {new Date(report.periodEnd).toLocaleDateString('cs-CZ')}
                </td>
                <td>{report.reviews}</td>
                <td>
                  {report.shareUrl ? (
                    <>
                      <a href={report.shareUrl} target="_blank" rel="noreferrer">
                        Otevřít
                      </a>{' '}
                      <button type="button" className="link" onClick={() => copy(report.shareUrl as string, report.id)}>
                        {copied === report.id ? 'zkopírováno' : 'zkopírovat odkaz'}
                      </button>
                    </>
                  ) : (
                    <span className="muted">nesdílí se</span>
                  )}
                </td>
                <td>
                  {report.shareUrl ? (
                    <button type="button" className="secondary" onClick={() => unshare.mutate(report.id)}>
                      Zrušit sdílení
                    </button>
                  ) : (
                    <button type="button" className="secondary" onClick={() => share.mutate(report.id)}>
                      Sdílet odkaz
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </Card>
  )
}

/**
 * Výkyvy za 90 dní. Statistika, ne AI: u každého je vidět, kolik toho bylo a kolik
 * bývá — bez toho se alert po druhém výskytu začne ignorovat.
 */
function AlertsCard({ org, appId }: { org: string; appId: string }) {
  const alerts = useAnalysisAlerts(org, appId)
  if (alerts.isPending) return null
  if (!alerts.data || alerts.data.length === 0) {
    return (
      <Card title="Výkyvy za 90 dní">
        <Empty>Nic mimo obvyklý provoz. Alert chodí, až je záporných recenzí za den výrazně víc než obvykle.</Empty>
      </Card>
    )
  }
  return (
    <Card title="Výkyvy za 90 dní">
      <table>
        <thead>
          <tr>
            <th>Den</th>
            <th>Co</th>
            <th>Kolik</th>
            <th>Obvykle</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {alerts.data.map((alert) => (
            <tr key={alert.id}>
              <td>{new Date(alert.windowDate).toLocaleDateString('cs-CZ')}</td>
              <td>
                {alert.kind === 'NEGATIVE_SPIKE' ? (
                  <Badge tone="bad">záporné recenze</Badge>
                ) : (
                  <Badge tone="warn">{alert.topicName ?? alert.topicKey}</Badge>
                )}
              </td>
              <td>{alert.observed}</td>
              <td className="muted">{alert.expected.toFixed(1)}</td>
              <td>
                <Link
                  className="small"
                  to={`/${org}/recenze?app=${appId}${alert.topicKey ? `&topic=${encodeURIComponent(alert.topicKey)}` : ''}`}
                >
                  Ukázat recenze
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}

/**
 * Dopad verzí (B3). „Před" je 30 dní před prvním výskytem verze na téže platformě —
 * kalendářní měsíc by srovnával podle toho, kdy se člověk na stránku podíval.
 */
function VersionImpactCard({ org, appId }: { org: string; appId: string }) {
  const versions = useVersionImpact(org, appId)
  const [open, setOpen] = useState('')

  if (versions.isPending) return <Card title="Dopad verzí"><Loading /></Card>
  if (versions.error) return <Card title="Dopad verzí"><ErrorBox error={versions.error} /></Card>
  if (!versions.data || versions.data.length === 0) {
    return (
      <Card title="Dopad verzí">
        <Empty>Zatím žádná verze nemá dost recenzí na to, aby se dalo srovnávat (potřeba aspoň pět).</Empty>
      </Card>
    )
  }

  return (
    <Card title="Dopad verzí">
      <table>
        <thead>
          <tr>
            <th>Verze</th>
            <th>Ø ★ před → po</th>
            <th>Nespokojených před → po</th>
            <th>Co se změnilo</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {versions.data.map((item) => (
            <VersionRow
              key={`${item.platform}-${item.version}`}
              org={org}
              appId={appId}
              item={item}
              open={open === `${item.platform}-${item.version}`}
              onToggle={() => setOpen(open === `${item.platform}-${item.version}` ? '' : `${item.platform}-${item.version}`)}
            />
          ))}
        </tbody>
      </table>
    </Card>
  )
}

function VersionRow({
  org,
  appId,
  item,
  open,
  onToggle,
}: {
  org: string
  appId: string
  item: VersionImpact
  open: boolean
  onToggle: () => void
}) {
  const stars = (value: number | null) => (value == null ? '—' : value.toFixed(2))
  return (
    <>
      <tr>
        <td>
          <strong>{item.version}</strong>{' '}
          <span className="small muted">{item.platform === 'ANDROID' ? 'Google Play' : 'App Store'}</span>
          <div className="small muted">od {new Date(item.firstSeen).toLocaleDateString('cs-CZ')}</div>
        </td>
        <td>
          {stars(item.before.avgStars)} → {stars(item.after.avgStars)}
          {item.starsDelta != null && Math.abs(item.starsDelta) >= 0.05 ? (
            <span className={`small metric-delta ${item.starsDelta > 0 ? 'up' : 'down'}`}>
              {' '}
              {item.starsDelta > 0 ? '+' : ''}
              {item.starsDelta.toFixed(2)}
            </span>
          ) : null}
        </td>
        <td>
          {percent(item.before.negativeShare)} → {percent(item.after.negativeShare)}
          {Math.abs(item.negativeDelta) >= 0.05 ? (
            <span className={`small metric-delta ${item.negativeDelta > 0 ? 'down' : 'up'}`}>
              {' '}
              {item.negativeDelta > 0 ? '+' : ''}
              {Math.round(item.negativeDelta * 100)} b.
            </span>
          ) : null}
        </td>
        <td>
          {item.newTopics.slice(0, 3).map((topic) => (
            <span key={topic.key}>
              <Badge tone="bad">
                + {topic.name} ({topic.count})
              </Badge>{' '}
            </span>
          ))}
          {item.goneTopics.slice(0, 3).map((topic) => (
            <span key={topic.key}>
              <Badge tone="ok">− {topic.name}</Badge>{' '}
            </span>
          ))}
          {item.newTopics.length === 0 && item.goneTopics.length === 0 ? (
            <span className="small muted">nic, o čem by se psalo jinak</span>
          ) : null}
        </td>
        <td>
          <button type="button" className="secondary" onClick={onToggle}>
            {open ? 'Skrýt' : 'Rozbalit'}
          </button>
        </td>
      </tr>
      {open ? (
        <tr>
          <td colSpan={5}>
            <div className="small">
              <strong>Po vydání</strong> ({item.after.reviews} recenzí):{' '}
              {item.after.topics.map((topic) => `${topic.name} ${topic.count}×`).join(', ') || 'žádné téma se neopakovalo'}
            </div>
            <div className="small muted">
              <strong>Před vydáním</strong> ({item.before.reviews} recenzí):{' '}
              {item.before.topics.map((topic) => `${topic.name} ${topic.count}×`).join(', ') || 'žádné téma se neopakovalo'}
            </div>
            <Link className="small" to={`/${org}/recenze?app=${appId}&version=${encodeURIComponent(item.version)}`}>
              Ukázat recenze verze {item.version}
            </Link>
          </td>
        </tr>
      ) : null}
    </>
  )
}

function TerritorySelect({
  territories,
  value,
  onChange,
}: {
  territories: string[]
  value: string
  onChange: (value: string) => void
}) {
  // Vybraný trh musí v seznamu zůstat, i když ho zúžený přehled zrovna nevrací — jinak
  // by se filtr sám zrušil hned po přepnutí.
  const options = [...new Set([...territories, value].filter(Boolean))].sort()
  if (options.length === 0) return null
  return (
    <Select
      value={value}
      options={[
        { value: '', label: 'Všechny trhy' },
        ...options.map((item) => ({ value: item, label: item })),
      ]}
      onChange={onChange}
      ariaLabel="Trh"
      fitContent
    />
  )
}

/** Přeskočení není chyba — je to stav, který má klient dostat větou. */
function describeSkip(reason: string): string {
  switch (reason) {
    case 'NO_CHANNEL':
      return 'Aplikace nemá kanál, do kterého by rozbor chodil.'
    case 'NO_INSIGHTS':
      return 'Žádná recenze zatím nemá výklad — napřed je potřeba doplnit rozbory.'
    case 'NOT_ENOUGH_REVIEWS':
      return 'Za období je zatím málo recenzí; období běží dál a rozbor přijde, až se jich nasbírá dost.'
    case 'APP_DISABLED':
      return 'Aplikace je vypnutá.'
    default:
      return `Rozbor se neposlal (${reason}).`
  }
}
