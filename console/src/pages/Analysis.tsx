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
import { Badge, Card, Empty, ErrorBox, Loading, Modal } from '../components/ui'
import { SentimentChart, Sparkline } from '../components/SentimentChart'
import { Select } from '../components/Select'
import { countryLabel } from '../components/countries'
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

const CUSTOM_PERIOD = 'CUSTOM'

/** Server vlastní rozsah delší než dva roky odmítne — srovnávací období by šlo přes čtyři. */
const MAX_CUSTOM_DAYS = 731

const isIsoDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value)

const toIsoDate = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

const shiftIsoDate = (value: string, days: number) => {
  const [year = 0, month = 1, day = 1] = value.split('-').map(Number)
  return toIsoDate(new Date(year, month - 1, day + days))
}

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

/** „439.0 h" nikdo nepřepočítává; od dvou dnů se čte ve dnech. */
const formatHours = (hours: number) => {
  if (hours < 1) return `${Math.round(hours * 60)} min`
  if (hours < 48) return `${hours.toFixed(hours < 10 ? 1 : 0)} h`
  return `${Math.round(hours / 24)} d`
}

const languageNames = new Intl.DisplayNames(['cs'], { type: 'language' })

/** Store posílá kód; člověk chce jméno. Kód, který prohlížeč nezná, zůstane kódem. */
const languageLabel = (code: string) => {
  try {
    return languageNames.of(code) ?? code
  } catch {
    return code
  }
}

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
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const custom = isIsoDate(from) && isIsoDate(to) ? { from, to } : undefined
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
  const filters = custom
    ? { ...custom, platform, territory }
    : { days: period ? undefined : days, period, platform, territory }
  const analysis = useAnalysis(org, selected, filters)
  const status = useAnalysisStatus(org, selected)

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  const setCustomRange = (nextFrom: string, nextTo: string) => {
    const next = new URLSearchParams(params)
    next.set('from', nextFrom)
    next.set('to', nextTo)
    next.delete('period')
    next.delete('days')
    setParams(next, { replace: true })
  }

  const setPeriod = (value: string) => {
    if (value === CUSTOM_PERIOD) {
      // Vlastní rozsah začíná tím, co je právě vidět — prázdná pole by stránku vyprázdnila.
      const today = toIsoDate(new Date())
      setCustomRange(analysis.data?.periodStart ?? shiftIsoDate(today, -29), analysis.data?.periodEnd ?? today)
      return
    }
    const next = new URLSearchParams(params)
    next.delete('from')
    next.delete('to')
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
            options={apps.data?.filter((app) => !app.competitor).map((app) => ({ value: app.id, label: app.name }))}
            groups={
              apps.data?.some((app) => app.competitor)
                ? [
                    {
                      label: 'Konkurence',
                      options: apps.data.filter((app) => app.competitor).map((app) => ({ value: app.id, label: app.name })),
                    },
                  ]
                : undefined
            }
            onChange={(value) => setParam('app', value)}
            ariaLabel="Aplikace"
            fitContent
          />
          <PeriodSelect
            value={custom ? CUSTOM_PERIOD : (period ?? `DAYS_${days}`)}
            customDays={!custom && !period && !isPresetDays ? days : undefined}
            onChange={setPeriod}
          />
          {custom ? <DateRangeInputs from={custom.from} to={custom.to} onChange={setCustomRange} /> : null}
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
          minTopicCount={apps.data?.find((app) => app.id === selected)?.analysisMinTopicCount}
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
        { label: 'Vlastní', options: [{ value: CUSTOM_PERIOD, label: 'Vlastní rozsah' }] },
      ]}
      onChange={onChange}
      ariaLabel="Období rozboru"
      className="period-select"
      fitContent
    />
  )
}

/**
 * Od–do z kalendáře. Hlídá, aby rozsah dával smysl ještě před dotazem: posunutý začátek
 * za konec potáhne konec s sebou (a naopak) a delší rozsah se zkrátí na limit serveru.
 */
function DateRangeInputs({
  from,
  to,
  onChange,
}: {
  from: string
  to: string
  onChange: (from: string, to: string) => void
}) {
  const today = toIsoDate(new Date())
  const changeFrom = (value: string) => {
    if (!isIsoDate(value)) return
    let end = to < value ? value : to
    if (end > shiftIsoDate(value, MAX_CUSTOM_DAYS - 1)) end = shiftIsoDate(value, MAX_CUSTOM_DAYS - 1)
    onChange(value, end)
  }
  const changeTo = (value: string) => {
    if (!isIsoDate(value)) return
    let start = from > value ? value : from
    if (start < shiftIsoDate(value, -(MAX_CUSTOM_DAYS - 1))) start = shiftIsoDate(value, -(MAX_CUSTOM_DAYS - 1))
    onChange(start, value)
  }
  return (
    <div className="date-range">
      <input
        type="date"
        aria-label="Období od"
        value={from}
        max={today}
        onChange={(event) => changeFrom(event.target.value)}
      />
      <span className="muted" aria-hidden="true">
        –
      </span>
      <input
        type="date"
        aria-label="Období do"
        value={to}
        max={today}
        onChange={(event) => changeTo(event.target.value)}
      />
    </div>
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
  minTopicCount,
  moodScope,
  onMoodScopeChange,
}: {
  org: string
  appId: string
  overview: AnalysisOverview
  /** Práh zmínek, od kterého se téma dostane do tabulky — platí pro appku, ne pro období. */
  minTopicCount: number | undefined
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
        <p className="small muted" style={{ marginTop: 0 }}>
          Všechny recenze = i hodnocení bez textu (nálada z hvězd). Jen s textem = recenze, které AI rozebrala;
          z nich jsou témata a jazyky níž.
        </p>
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
          <Empty>
            Žádné téma nepřekročilo práh {minTopicCount ?? '—'} zmínek za období.{' '}
            <Link to={`/${org}/aplikace/${appId}?tab=nastaveni`}>Práh nastavíš v detailu aplikace</Link>.
          </Empty>
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
            {/* Bez tabulky by „zlepšilo se" působilo jako jediný výsledek — proto je u prázdného stavu až druhá věta. */}
            {overview.topics.length === 0 ? 'Oproti minulému období ubylo: ' : 'Zlepšilo se: '}
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
                {overview.territories.slice(0, 8).map((item) => {
                  const country = countryLabel(item.territory)
                  return (
                    <tr key={item.territory}>
                      <td title={item.territory}>
                        {country.flag ? (
                          <span className="country-flag" aria-hidden="true">
                            {country.flag}
                          </span>
                        ) : null}
                        {country.name}
                      </td>
                      <td>{item.reviews}</td>
                      <td>{percent(item.negativeShare)}</td>
                      <td>{item.avgStars?.toFixed(1) ?? '—'}</td>
                    </tr>
                  )
                })}
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
                {overview.replies.medianHours != null ? formatHours(overview.replies.medianHours) : '—'}
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
              Jazyky: {overview.languages.slice(0, 5).map((item) => `${languageLabel(item.language)} (${item.reviews})`).join(', ')}.
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
  const months = completedMonths(12)
  const [month, setMonth] = useState(months[0]?.value ?? '')
  const [confirm, setConfirm] = useState(false)

  const copy = (url: string, id: string) => {
    navigator.clipboard?.writeText(url).then(
      () => setCopied(id),
      // Bez schránky (starý prohlížeč, http) se odkaz aspoň ukáže k ručnímu zkopírování.
      () => setCopied(''),
    )
  }

  // Report za měsíc už existuje → přegenerování přepíše čísla, která klient vidí na sdíleném
  // odkazu. Proto se napřed ptáme; nový měsíc se generuje rovnou.
  const existing = reports.data?.some((report) => report.periodStart.slice(0, 7) === month) ?? false
  const monthLabel = months.find((item) => item.value === month)?.label ?? month
  const run = () => {
    setConfirm(false)
    generate.mutate(month)
  }

  return (
    <Card title="Měsíční reporty">
      <div className="spread" style={{ marginBottom: '0.75rem' }}>
        <p className="small muted" style={{ margin: 0 }}>
          Stránka pro klienta se sdílitelným odkazem. V prohlížeči se dá uložit jako PDF.
        </p>
        <div className="row" style={{ flex: 'none' }}>
          <Select value={month} options={months} onChange={setMonth} ariaLabel="Měsíc reportu" fitContent />
          <button
            type="button"
            className="secondary"
            onClick={() => (existing ? setConfirm(true) : run())}
            disabled={generate.isPending || !month}
          >
            Vygenerovat
          </button>
        </div>
      </div>
      {confirm ? (
        <Modal title="Přegenerovat report" onClose={() => setConfirm(false)}>
          <div className="stack">
            <p>
              Report za {monthLabel} už existuje. Přegenerování přepíše čísla, která klient vidí na sdíleném
              odkazu.
            </p>
            <div className="row">
              <button type="button" className="danger" onClick={run}>
                Přegenerovat
              </button>
              <button type="button" className="secondary" onClick={() => setConfirm(false)}>
                Nechat
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
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
                      </a>
                      <span className="muted"> · </span>
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
 * Posledních `count` ukončených měsíců, nejnovější první. Běžící měsíc se nenabízí — report
 * z půlky měsíce by vypadal hotově a za týden by lhal.
 */
function completedMonths(count: number): { value: string; label: string }[] {
  const now = new Date()
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(now.getFullYear(), now.getMonth() - 1 - index, 1)
    return {
      value: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
      label: date.toLocaleDateString('cs-CZ', { month: 'long', year: 'numeric' }),
    }
  })
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
        <Empty>Zatím žádná verze nemá rozebranou recenzi s textem.</Empty>
      </Card>
    )
  }

  // Verze, na které je brzy, nepatří do tabulky vedle těch zhodnocených: osm řádků
  // „zatím nejde zhodnotit" zakryje jediný řádek, který něco říká.
  const assessed = versions.data.filter((item) => item.assessable)
  const collecting = versions.data.filter((item) => !item.assessable)

  return (
    <Card title="Dopad verzí">
      {assessed.length === 0 ? (
        <Empty>Žádná verze zatím nemá po vydání dost recenzí s textem, aby se dopad dal zhodnotit.</Empty>
      ) : (
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
            {assessed.map((item) => (
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
      )}
      {collecting.length > 0 ? (
        <>
          <h3 style={{ marginTop: '1.25rem' }}>Sbírají recenze</h3>
          <p className="small muted" style={{ margin: 0 }}>
            Dopad se hodnotí od {collecting[0]?.minReviews} recenzí s textem po vydání.
          </p>
          <ul className="collecting-list">
            {collecting.map((item) => (
              <VersionCollecting key={`${item.platform}-${item.version}`} org={org} appId={appId} item={item} />
            ))}
          </ul>
        </>
      ) : null}
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
  return (
    <>
      <tr>
        <td>
          <strong>{item.version}</strong>{' '}
          <span className="small muted">{item.platform === 'ANDROID' ? 'Google Play' : 'App Store'}</span>
          <div className="small muted">od {new Date(item.firstSeen).toLocaleDateString('cs-CZ')}</div>
        </td>
        <VersionMetrics item={item} />
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

/** Srovnání před a po vydání — jen u verze, která má po vydání dost recenzí s textem. */
function VersionMetrics({ item }: { item: VersionImpact }) {
  const stars = (value: number | null) => (value == null ? '—' : value.toFixed(2))
  return (
    <>
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
    </>
  )
}

/**
 * Verze, o které už víme, ale na dopad je brzy. Čísla ze dvou recenzí by vypadala jako
 * závěr, takže místo nich ukazujeme, kolik recenzí s textem už je — a odkaz na ně.
 */
function VersionCollecting({ org, appId, item }: { org: string; appId: string; item: VersionImpact }) {
  const have = Math.min(item.after.reviews, item.minReviews)
  return (
    <li>
      <span className="data-dots" aria-hidden="true" title={`${item.after.reviews} z ${item.minReviews}`}>
        {Array.from({ length: item.minReviews }, (_, index) => (
          <span key={index} className={index < have ? 'filled' : undefined} />
        ))}
      </span>
      <strong>{item.version}</strong>
      <span>{item.platform === 'ANDROID' ? 'Google Play' : 'App Store'}</span>
      <span>od {new Date(item.firstSeen).toLocaleDateString('cs-CZ')}</span>
      <span>·</span>
      {item.after.reviews > 0 ? (
        <Link to={`/${org}/recenze?app=${appId}&version=${encodeURIComponent(item.version)}`}>
          {reviewsWithText(item.after.reviews)}
        </Link>
      ) : (
        <span>zatím bez recenze s textem</span>
      )}
    </li>
  )
}

function reviewsWithText(count: number): string {
  if (count === 1) return '1 recenze s textem'
  if (count >= 2 && count <= 4) return `${count} recenze s textem`
  return `${count} recenzí s textem`
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
        // Stejné jméno jako v tabulce Trhy — jinak by člověk hledal „CZE" vedle „Česko".
        ...options.map((item) => {
          const country = countryLabel(item)
          return { value: item, label: country.flag ? `${country.flag} ${country.name}` : country.name }
        }),
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
