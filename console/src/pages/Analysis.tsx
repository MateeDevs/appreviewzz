import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  useAnalysis,
  useAnalysisStatus,
  useApps,
  useBackfillAnalysis,
  useMe,
  useRunAnalysis,
  useVersionImpact,
} from '../api/hooks'
import { Badge, Card, Empty, ErrorBox, Loading } from '../components/ui'
import { SentimentChart, Sparkline } from '../components/SentimentChart'
import type { AnalysisOverview, Platform, TopicStatus, VersionImpact } from '../api/types'

const STATUS_LABELS: Record<TopicStatus, { label: string; tone?: 'ok' | 'warn' | 'bad' }> = {
  NEW: { label: 'nové', tone: 'warn' },
  GROWING: { label: 'roste', tone: 'bad' },
  STABLE: { label: 'beze změny' },
  FALLING: { label: 'klesá', tone: 'ok' },
}

const PERIODS = [
  { days: 30, label: '30 dní' },
  { days: 90, label: '90 dní' },
  { days: 365, label: 'rok' },
]

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
  const days = Number(params.get('days')) || 30
  const platform = (params.get('platform') as Platform | null) ?? ''
  const territory = params.get('territory') ?? ''

  const selected = appId || (apps.data?.[0]?.id ?? '')
  const analysis = useAnalysis(org, selected, { days, platform, territory })
  const status = useAnalysisStatus(org, selected)

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
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
          <select value={selected} onChange={(e) => setParam('app', e.target.value)} style={{ width: 'auto' }}>
            {apps.data?.map((app) => (
              <option key={app.id} value={app.id}>
                {app.name}
              </option>
            ))}
          </select>
          {PERIODS.map((period) => (
            <button
              key={period.days}
              type="button"
              className={period.days === days ? '' : 'secondary'}
              onClick={() => setParam('days', String(period.days))}
            >
              {period.label}
            </button>
          ))}
          <select
            value={platform}
            onChange={(e) => setParam('platform', e.target.value)}
            style={{ width: 'auto' }}
          >
            <option value="">Obě platformy</option>
            <option value="ANDROID">Google Play</option>
            <option value="IOS">App Store</option>
          </select>
          <TerritorySelect
            territories={analysis.data?.territories.map((item) => item.territory) ?? []}
            value={territory}
            onChange={(value) => setParam('territory', value)}
          />
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
      {analysis.data ? <Overview org={org} appId={selected} overview={analysis.data} /> : null}
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

function Overview({ org, appId, overview }: { org: string; appId: string; overview: AnalysisOverview }) {
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
  if (overview.reviews === 0) {
    return (
      <Card title="Za tohle období nic nepřišlo">
        <p className="muted">Zkuste delší období nebo zrušte filtr platformy a trhu.</p>
      </Card>
    )
  }

  const inbox = (topic?: string) =>
    `/${org}/recenze${topic ? `?app=${appId}&topic=${encodeURIComponent(topic)}` : ''}`
  const delta = overview.previousSentiment ? overview.sentiment.negative - overview.previousSentiment.negative : null

  return (
    <>
      <Card title="Nálada v čase">
        <div className="metrics" style={{ marginBottom: '1rem' }}>
          <div>
            <div className="metric-value">{percent(overview.sentiment.positive)}</div>
            <div className="metric-label">spokojených</div>
            {delta != null && Math.round(delta * 100) !== 0 ? (
              // Roste podíl nespokojených = nálada jde dolů; znaménko se čte obráceně.
              <div className={`metric-delta ${delta > 0 ? 'down' : 'up'}`}>
                {delta > 0 ? '↓' : '↑'} {Math.abs(Math.round(delta * 100))} b. proti minulému období
              </div>
            ) : null}
          </div>
          <div>
            <div className="metric-value">{overview.reviews}</div>
            <div className="metric-label">recenzí s textem</div>
          </div>
          <div>
            <div className="metric-value">{overview.avgStars?.toFixed(2) ?? '—'}</div>
            <div className="metric-label">průměr hvězd</div>
          </div>
        </div>
        {overview.tooFewReviews ? (
          <p className="muted small">
            Za období je jen {overview.reviews} recenzí s textem; do kanálu se rozbor posílá až od{' '}
            {overview.minReviews}. Čísla níž jsou orientační.
          </p>
        ) : null}
        <SentimentChart weeks={overview.weekly} />
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
    </>
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
    <select value={value} onChange={(e) => onChange(e.target.value)} style={{ width: 'auto' }}>
      <option value="">Všechny trhy</option>
      {options.map((item) => (
        <option key={item} value={item}>
          {item}
        </option>
      ))}
    </select>
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
