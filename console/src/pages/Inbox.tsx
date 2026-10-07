import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  useApps,
  useReply,
  useReplyTemplates,
  useReview,
  useReviewCount,
  useReviews,
  useSetReviewState,
  useSuggestReply,
  useTopics,
  useTranslateReply,
} from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, PlatformBadge, Stars, When } from '../components/ui'
import { Select } from '../components/Select'
import type {
  Platform,
  Review,
  ReviewInsight,
  ReviewSort,
  ReviewState,
  ReviewType,
  TopicOption,
  Urgency,
} from '../api/types'

/** Popisky typů recenzí. Server posílá klíč, konzole je česká — překlad patří sem. */
const TYPE_LABELS: Record<ReviewType, string> = {
  BUG: 'Chyba',
  FEATURE_REQUEST: 'Přání funkce',
  COMPLAINT: 'Stížnost',
  PRAISE: 'Pochvala',
  QUESTION: 'Dotaz',
  OTHER: 'Ostatní',
}

const URGENCY_LABELS: Record<Urgency, string> = {
  LOW: 'nízká',
  MEDIUM: 'střední',
  HIGH: 'naléhavé',
}

const SORT_OPTIONS: { value: ReviewSort; label: string }[] = [
  { value: 'NEWEST', label: 'Nejnovější' },
  { value: 'OLDEST', label: 'Nejstarší' },
  { value: 'LOWEST_STARS', label: 'Nejméně hvězd napřed' },
  { value: 'HIGHEST_STARS', label: 'Nejvíc hvězd napřed' },
]

/** Velikost stránky. Server má strop 200, ale padesát recenzí je tak akorát na jedno projití. */
const PAGE = 50

const FILTERS: { label: string; states: ReviewState[] }[] = [
  { label: 'Čeká na odpověď', states: ['NEW', 'UPDATED', 'NOTIFIED'] },
  { label: 'Odpovězené', states: ['REPLIED'] },
  { label: 'Odložené', states: ['IGNORED'] },
  { label: 'Všechny', states: [] },
]

/**
 * Recenze a odpovídání z console.
 *
 * Odpověď se zařadí do fronty a publikuje ji worker, takže se po odeslání neukazuje
 * „hotovo", ale „ve frontě" — to je pravda o tom, co se stalo.
 */
export function InboxPage() {
  const { org = '' } = useParams()
  const apps = useApps(org)
  // Aplikace, téma, řazení i stránka jsou v adrese: z rozborů se sem chodí odkazem „ukaž mi
  // těch devět recenzí o pádech" a ten musí jít poslat kolegovi.
  const [params, setParams] = useSearchParams()
  const [filter, setFilterState] = useState(0)
  const [type, setTypeState] = useState<ReviewType | ''>('')
  const [urgency, setUrgencyState] = useState<Urgency | ''>('')
  const [openReview, setOpenReview] = useState<string>('')
  const [focusedId, setFocusedId] = useState<string>('')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [bulkBusy, setBulkBusy] = useState(false)
  const setState = useSetReviewState(org)

  const appId = params.get('app') ?? ''
  const topic = params.get('topic') ?? ''
  const version = params.get('version') ?? ''
  const platform: Platform | '' = (params.get('platform') as Platform | null) ?? ''
  const sort: ReviewSort = (params.get('sort') as ReviewSort | null) ?? 'NEWEST'
  const page = Math.max(1, Number(params.get('page')) || 1)
  const offset = (page - 1) * PAGE

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    // Jiná podmínka = jiný seznam; stránka 3 ze starého výběru by ukázala prázdno.
    if (key !== 'page') next.delete('page')
    setParams(next, { replace: true })
  }
  const setAppId = (value: string) => setParam('app', value)
  const setTopic = (value: string) => setParam('topic', value)
  const setPage = (value: number) => setParam('page', value > 1 ? String(value) : '')
  // Filtry mimo adresu musí stránku shodit taky — jinak by se dalo dostat na prázdnou.
  const setFilter = (value: number) => {
    setFilterState(value)
    setPage(1)
  }
  const setType = (value: ReviewType | '') => {
    setTypeState(value)
    setPage(1)
  }
  const setUrgency = (value: Urgency | '') => {
    setUrgencyState(value)
    setPage(1)
  }

  const selected = appId || (apps.data?.[0]?.id ?? '')
  const selectedApp = apps.data?.find((app) => app.id === selected)
  const appName = selectedApp?.name ?? ''
  // Konkurence je jen ke čtení: cizí recenze, na které se neodpovídá.
  const readOnly = selectedApp?.competitor === true
  const filters = { states: FILTERS[filter]?.states ?? [], topic, type, urgency, version, platform }
  const reviews = useReviews(org, selected, { ...filters, limit: PAGE, offset, sort })
  const count = useReviewCount(org, selected, filters)
  const topics = useTopics(org, selected)

  const list = reviews.data ?? []
  const listKey = list.map((review) => review.id).join(',')
  // Výběr i zvýraznění se vážou k recenzím na obrazovce; po přepnutí stránky by ukazovaly
  // na něco, co není vidět. Výběr se proto po změně seznamu zahodí.
  useEffect(() => {
    setSelectedIds(new Set())
  }, [listKey])
  const selectedOnPage = list.filter((review) => selectedIds.has(review.id))
  const allOnPageSelected = list.length > 0 && selectedOnPage.length === list.length

  const toggleSelected = (id: string, checked: boolean) => {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })
  }

  // Odložení po jedné: endpoint hromadné změny není a tahle akce se dělá na pár recenzí.
  // Každý úspěch sám zneplatní cache, takže seznam doběhne s posledním voláním.
  const ignoreSelected = async () => {
    setBulkBusy(true)
    try {
      for (const review of selectedOnPage) {
        await setState.mutateAsync({ reviewId: review.id, state: 'IGNORED' })
      }
      setSelectedIds(new Set())
    } finally {
      setBulkBusy(false)
    }
  }

  const moveFocus = (direction: 1 | -1) => {
    if (list.length === 0) return
    const index = list.findIndex((review) => review.id === focusedId)
    const next = index < 0 ? (direction === 1 ? 0 : list.length - 1) : Math.min(list.length - 1, Math.max(0, index + direction))
    const id = list[next]?.id ?? ''
    setFocusedId(id)
    document.getElementById(`review-${id}`)?.scrollIntoView({ block: 'nearest' })
  }

  // Klávesy jako v poště: inbox se prochází shora dolů, ruce zůstávají na klávesnici.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return
      // Escape má zavřít formulář i z textarey — jediná klávesa, která v polích platí.
      if (event.key === 'Escape') {
        if (openReview) setOpenReview('')
        return
      }
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      switch (event.key) {
        case 'j':
          event.preventDefault()
          moveFocus(1)
          break
        case 'k':
          event.preventDefault()
          moveFocus(-1)
          break
        case 'r':
          if (focusedId) {
            event.preventDefault()
            setOpenReview(focusedId)
          }
          break
        case 'i':
          if (focusedId && !setState.isPending) {
            event.preventDefault()
            // Zvýraznění předem přeskočí na další — odložená recenze z pohledu zmizí.
            const index = list.findIndex((review) => review.id === focusedId)
            const nextId = list[index + 1]?.id ?? list[index - 1]?.id ?? ''
            setFocusedId(nextId)
            if (openReview === focusedId) setOpenReview('')
            setState.mutate({ reviewId: focusedId, state: 'IGNORED' })
          }
          break
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  })

  if (apps.isPending) return <Loading />
  if (apps.data?.length === 0) {
    return (
      <Card>
        <p>Napřed je potřeba přidat aplikaci — bez ní není co sledovat.</p>
      </Card>
    )
  }

  // Počet drží předchozí hodnotu, dokud nový nedorazí; k jinému seznamu by ale neseděl.
  const total = count.isPlaceholderData ? undefined : count.data?.count
  const pager = (
    <Pager page={page} shown={list.length} total={total} onPage={setPage} pending={reviews.isFetching} />
  )

  return (
    <div className="stack">
      <div>
        <h1>Recenze</h1>
        <p className="muted">Co přišlo ze storu a co s tím.</p>
      </div>

      <Card>
        <div className="row inbox-filters">
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
            onChange={setAppId}
            ariaLabel="Aplikace"
            fitContent
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
          {/* Stav recenze je přepínač jednoho pohledu, ne čtyři akce — proto segment, ne tlačítka. */}
          <div className="segmented" role="group" aria-label="Stav recenze">
            {FILTERS.map((item, index) => (
              <button
                key={item.label}
                type="button"
                className={index === filter ? 'active' : undefined}
                aria-pressed={index === filter}
                onClick={() => setFilter(index)}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>
        <div className="row inbox-filters" style={{ marginTop: '0.5rem' }}>
          <TopicSelect topics={topics.data} value={topic} onChange={setTopic} />
          <Select
            value={type}
            options={[
              { value: '', label: 'Jakýkoli typ' },
              ...Object.entries(TYPE_LABELS).map(([value, label]) => ({ value, label })),
            ]}
            onChange={(value) => setType(value as ReviewType | '')}
            ariaLabel="Typ recenze"
            fitContent
          />
          <Select
            value={urgency}
            options={[
              { value: '', label: 'Jakákoli naléhavost' },
              ...Object.entries(URGENCY_LABELS).map(([value, label]) => ({ value, label })),
            ]}
            onChange={(value) => setUrgency(value as Urgency | '')}
            ariaLabel="Naléhavost recenze"
            fitContent
          />
          <Select
            value={sort}
            options={SORT_OPTIONS}
            onChange={(value) => setParam('sort', value === 'NEWEST' ? '' : value)}
            ariaLabel="Řazení"
            fitContent
          />
          {version ? <Badge>verze {version}</Badge> : null}
          {topic || type || urgency || version ? (
            <button
              type="button"
              className="secondary"
              onClick={() => {
                setTopic('')
                setParam('version', '')
                setType('')
                setUrgency('')
              }}
            >
              Zrušit filtry
            </button>
          ) : null}
        </div>
        {readOnly ? (
          <p className="small muted inbox-keys">Konkurence — recenze jen ke čtení, odpovídat se nedá.</p>
        ) : (
          <p className="small muted inbox-keys keyboard-only">Klávesy: j/k pohyb · r odpovědět · i odložit</p>
        )}
      </Card>

      <Card>
        {reviews.isPending ? <Loading /> : null}
        <ErrorBox error={reviews.error ?? setState.error} />
        {reviews.data?.length === 0 ? <p className="muted">Tady nic není.</p> : null}
        {list.length > 0 ? (
          <div className="inbox-toolbar">
            <label className="inbox-check">
              <input
                type="checkbox"
                checked={allOnPageSelected}
                onChange={(event) => setSelectedIds(event.target.checked ? new Set(list.map((review) => review.id)) : new Set())}
                aria-label="Vybrat vše na stránce"
              />
              <span className="small">Vybrat vše na stránce</span>
            </label>
            {pager}
          </div>
        ) : null}
        {selectedOnPage.length > 0 ? (
          <div className="inbox-bulk" role="toolbar" aria-label="Hromadné akce">
            <strong>{selectedCount(selectedOnPage.length)}</strong>
            <button type="button" className="secondary" onClick={ignoreSelected} disabled={bulkBusy}>
              {bulkBusy ? 'Odkládám…' : 'Odložit vybrané'}
            </button>
            <button type="button" className="link" onClick={() => setSelectedIds(new Set())} disabled={bulkBusy}>
              Zrušit výběr
            </button>
          </div>
        ) : null}
        {list.map((review) => (
          <div
            className={`review${review.id === focusedId ? ' focused' : ''}`}
            id={`review-${review.id}`}
            key={review.id}
            onClick={() => setFocusedId(review.id)}
          >
            <label className="review-check">
              <input
                type="checkbox"
                checked={selectedIds.has(review.id)}
                onChange={(event) => toggleSelected(review.id, event.target.checked)}
                aria-label={`Vybrat recenzi od ${review.authorName ?? 'anonyma'}`}
              />
            </label>
            <div className="review-main">
              <div className="spread">
                <div className="review-head">
                  <PlatformBadge platform={review.platform} />
                  <Stars count={review.starRating} />
                  <strong>{review.authorName ?? 'Anonym'}</strong>
                  <span className="small muted">
                    <RelativeWhen iso={review.submittedAt} />
                    {review.appVersion ? ` · verze ${review.appVersion}` : ''}
                    {review.territory ? ` · ${review.territory}` : ''}
                  </span>
                </div>
                <StateBadge state={review.state} />
              </div>
              <InsightBadges insight={review.insight} />
              {review.title ? <div style={{ marginTop: '0.35rem' }}><strong>{review.title}</strong></div> : null}
              <p className="review-body">{review.body ?? <span className="muted">(bez textu)</span>}</p>
              {/* Překlad pod originálem, ne místo něj: co člověk napsal, je fakt. */}
              {review.insight?.translation ? (
                <p className="small muted" style={{ fontStyle: 'italic' }}>
                  Překlad: {review.insight.translation}
                </p>
              ) : null}
              {review.developerResponseBody ? (
                <div className="review-reply">
                  <strong>Odpověď ve storu</strong>
                  {review.developerResponseAt ? (
                    <span className="small">
                      {' '}
                      · <When iso={review.developerResponseAt} />
                    </span>
                  ) : null}
                  <div style={{ marginTop: '0.2rem' }}>{review.developerResponseBody}</div>
                </div>
              ) : null}
              {openReview === review.id ? (
                <ReplyForm
                  org={org}
                  appId={selected}
                  appName={appName}
                  review={review}
                  onClose={() => setOpenReview('')}
                />
              ) : readOnly ? null : (
                <div className="review-actions">
                  <button type="button" className="secondary" onClick={() => setOpenReview(review.id)}>
                    {review.developerResponseBody ? 'Upravit odpověď' : 'Odpovědět'}
                  </button>
                </div>
              )}
            </div>
          </div>
        ))}
        {list.length > 0 && total != null && total > PAGE ? <div className="inbox-toolbar bottom">{pager}</div> : null}
      </Card>
    </div>
  )
}

function reviewCount(count: number): string {
  if (count === 1) return '1 recenze'
  if (count >= 2 && count <= 4) return `${count} recenze`
  return `${count} recenzí`
}

function selectedCount(count: number): string {
  if (count === 1) return 'Vybrána 1 recenze'
  if (count >= 2 && count <= 4) return `Vybrány ${count} recenze`
  return `Vybráno ${count} recenzí`
}

/**
 * Stránkování. Dokud počet nedorazil, ukáže se aspoň rozsah — tlačítka se dovolí
 * až s ním, jinak by „Další" vedlo na prázdnou stránku.
 */
function Pager({
  page,
  shown,
  total,
  onPage,
  pending,
}: {
  page: number
  shown: number
  total: number | undefined
  onPage: (page: number) => void
  pending: boolean
}) {
  const from = (page - 1) * PAGE + 1
  const to = (page - 1) * PAGE + shown
  if (total != null && total <= PAGE) return <span className="result-count">{reviewCount(total)}</span>
  const hasNext = total != null && to < total
  return (
    <div className="pager">
      <span className="result-count">
        Zobrazeno {from}–{to}
        {total != null ? ` z ${total}` : ''}
      </span>
      <span className="pager-buttons">
        <button type="button" className="secondary" onClick={() => onPage(page - 1)} disabled={page <= 1 || pending}>
          Předchozí
        </button>
        <button type="button" className="secondary" onClick={() => onPage(page + 1)} disabled={!hasNext || pending}>
          Další
        </button>
      </span>
    </div>
  )
}

/**
 * Relativní čas: v inboxu zajímá „jak dlouho to čeká", ne přesný den. Přesný čas zůstává
 * v `title`; po měsíci už relativní údaj nic neříká a ukáže se datum.
 */
function relativeTime(iso: string, now: Date = new Date()): string | null {
  const date = new Date(iso)
  const minutes = Math.round((now.getTime() - date.getTime()) / 60_000)
  if (minutes < 1) return 'před chvílí'
  if (minutes < 60) return minutes === 1 ? 'před minutou' : `před ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return hours === 1 ? 'před hodinou' : `před ${hours} h`
  const days = Math.floor(hours / 24)
  if (days >= 30) return null
  if (days === 1) return 'včera'
  return `před ${days} dny`
}

function RelativeWhen({ iso }: { iso: string }) {
  const relative = relativeTime(iso)
  if (relative == null) return <When iso={iso} />
  const absolute = new Date(iso).toLocaleString('cs-CZ', { dateStyle: 'medium', timeStyle: 'short' })
  return <span title={absolute}>{relative}</span>
}

/**
 * Výběr tématu. Základní taxonomie je seskupená, vlastní témata aplikace na konci —
 * jinak by v seznamu dvaceti tří položek zapadla.
 */
function TopicSelect({
  topics,
  value,
  onChange,
}: {
  topics: TopicOption[] | undefined
  value: string
  onChange: (key: string) => void
}) {
  const groups = new Map<string, TopicOption[]>()
  topics?.forEach((item) => {
    const key = item.group ?? 'Vlastní témata'
    groups.set(key, [...(groups.get(key) ?? []), item])
  })

  return (
    <Select
      value={value}
      options={[{ value: '', label: 'Jakékoli téma' }]}
      groups={[...groups.entries()].map(([label, items]) => ({
        label,
        options: items.map((item) => ({
          value: item.key,
          label: `${item.name}${item.recentCount > 0 ? ` (${item.recentCount})` : ''}`,
        })),
      }))}
      onChange={onChange}
      ariaLabel="Téma recenze"
      fitContent
    />
  )
}

/**
 * Štítky recenze. Barva podle sentimentu tématu, ne podle hvězd — pětihvězdičková recenze
 * si klidně stěžuje na reklamy a právě to je ta informace navíc.
 */
function InsightBadges({ insight }: { insight?: ReviewInsight | null }) {
  if (!insight) return null
  const typeLabel = TYPE_LABELS[insight.type]
  // Téma se může jmenovat stejně jako typ (téma „Pochvala“ + typ PRAISE) — pak by týž text
  // stál na řádku dvakrát. Druhý štítek nic nepřidává, takže se vynechá.
  const typeInTopics = insight.topics.some(
    (topic) => topic.name.localeCompare(typeLabel, undefined, { sensitivity: 'base' }) === 0,
  )
  return (
    <div className="row" style={{ marginTop: '0.35rem', gap: '0.35rem' }}>
      {insight.topics.map((topic) => (
        <Badge key={topic.key} tone={topic.sentiment === 'NEGATIVE' ? 'bad' : topic.sentiment === 'POSITIVE' ? 'ok' : undefined}>
          {topic.name}
        </Badge>
      ))}
      {typeInTopics ? null : <Badge>{typeLabel}</Badge>}
      {insight.urgency === 'HIGH' ? <Badge tone="warn">naléhavé</Badge> : null}
    </div>
  )
}

function StateBadge({ state }: { state: ReviewState }) {
  switch (state) {
    case 'REPLIED':
      return <Badge tone="ok">odpovězeno</Badge>
    case 'IGNORED':
      return <Badge>odloženo</Badge>
    case 'SUPPRESSED':
      return <Badge>bez notifikace</Badge>
    case 'UPDATED':
      return <Badge tone="warn">upravená autorem</Badge>
    case 'NOTIFIED':
      return <Badge tone="warn">čeká na odpověď</Badge>
    default:
      return <Badge tone="warn">nová</Badge>
  }
}

/**
 * Co AI z recenze vyčetla nad rámec štítků: naléhavost, jazyk a doslovné citáty k tématům.
 * Témata i typ už stojí ve štítcích nad textem, tady se neopakují.
 */
function InsightDetail({ insight }: { insight: ReviewInsight }) {
  const quotes = insight.topics.filter((topic) => topic.quote)
  if (quotes.length === 0 && insight.urgency === 'LOW' && !insight.language) return null
  return (
    <div className="notice" style={{ marginBottom: '0.75rem' }}>
      <div className="small">
        Naléhavost {URGENCY_LABELS[insight.urgency]}
        {insight.language ? ` · jazyk ${insight.language}` : ''}
      </div>
      {quotes.map((topic) => (
        <div key={topic.key} className="small">
          <strong>{topic.name}</strong>: „{topic.quote}"
        </div>
      ))}
    </div>
  )
}

/** Google Play odpověď nad limit odmítne; App Store má 5 000 a tam se počítadlo neukazuje. */
const GP_REPLY_LIMIT = 350

/** Jazyk recenze bez regionu: `en-GB` i `en-US` je pro překlad prostě angličtina. */
function primaryLanguage(language: string | null | undefined): string | null {
  const primary = language?.split('-')[0]?.trim().toLowerCase()
  return primary ? primary : null
}

/** Proměnné šablony dosazuje konzole — server o recenzi v tu chvíli nic neví. */
function fillTemplate(template: string, review: Review, appName: string): string {
  return template
    .replaceAll('{jmeno}', (review.authorName ?? '').trim())
    .replaceAll('{appka}', appName)
    .replaceAll('{verze}', review.appVersion ?? '')
}

function ReplyForm({
  org,
  appId,
  appName,
  review,
  onClose,
}: {
  org: string
  appId: string
  appName: string
  review: Review
  onClose: () => void
}) {
  const reviewId = review.id
  const detail = useReview(org, reviewId)
  const reply = useReply(org)
  const setState = useSetReviewState(org)
  const suggest = useSuggestReply(org)
  const translate = useTranslateReply(org)
  const templates = useReplyTemplates(org, appId)
  const [body, setBody] = useState('')
  // Text před překladem. Překlad textareu přepíše a člověk musí mít cestu zpět.
  const [original, setOriginal] = useState<string | null>(null)
  const [assistMessage, setAssistMessage] = useState<string | null>(null)
  const [queued, setQueued] = useState<string | null>(null)
  const limit = review.platform === 'ANDROID' ? GP_REPLY_LIMIT : null
  const overLimit = limit != null && body.length > limit
  const language = primaryLanguage(review.insight?.language)
  const foreign = language != null && language !== 'cs'
  const hasText = body.trim() !== ''

  const applyAssist = (result: { text: string | null; message: string | null }, keepOriginal: boolean) => {
    if (result.text == null) {
      setAssistMessage(result.message ?? 'Návrh se nepodařilo získat.')
      return
    }
    setAssistMessage(null)
    setOriginal(keepOriginal ? body : null)
    setBody(result.text)
  }

  const insertTemplate = (id: string) => {
    const template = templates.data?.find((item) => item.id === id)
    if (!template) return
    const text = fillTemplate(template.body, review, appName)
    setBody((current) => (current.trim() ? `${current.replace(/\s+$/, '')}\n\n${text}` : text))
  }

  return (
    <div style={{ marginTop: '0.75rem' }}>
      {detail.data?.review.insight ? <InsightDetail insight={detail.data.review.insight} /> : null}
      {detail.data && detail.data.replies.length > 0 ? (
        <div className="notice" style={{ marginBottom: '0.75rem' }}>
          {detail.data.replies.map((item) => (
            <div key={item.id} className="small">
              <strong>{item.authorDisplayName ?? item.source}</strong>
              {/* Kdo odpověděl, není detail: automatická odpověď šla ven bez schválení. */}
              {item.source === 'AUTO' ? <> <Badge>auto</Badge></> : null}: {item.body}{' '}
              {item.status === 'PUBLISHED' ? (
                <Badge tone="ok">publikováno</Badge>
              ) : item.status === 'FAILED' ? (
                <Badge tone="bad">{item.error ?? 'selhalo'}</Badge>
              ) : (
                <Badge tone="warn">ve frontě</Badge>
              )}
            </div>
          ))}
        </div>
      ) : null}

      <form
        onSubmit={(event) => {
          event.preventDefault()
          reply.mutate(
            { reviewId, body },
            {
              onSuccess: (result) => {
                setQueued(result.message)
                setBody('')
                setOriginal(null)
              },
            },
          )
        }}
      >
        <div className="reply-tools">
          <button
            type="button"
            className="secondary"
            disabled={suggest.isPending}
            onClick={() => suggest.mutate(reviewId, { onSuccess: (result) => applyAssist(result, false) })}
          >
            {suggest.isPending ? 'Navrhuji…' : hasText ? 'Nahradit návrhem' : 'Navrhnout odpověď'}
          </button>
          {templates.data && templates.data.length > 0 ? (
            <Select
              value=""
              placeholder="Vložit šablonu…"
              options={templates.data.map((item) => ({ value: item.id, label: item.name }))}
              onChange={insertTemplate}
              ariaLabel="Vložit šablonu"
              fitContent
            />
          ) : null}
        </div>
        <Field
          label="Odpověď"
          hint={
            <span className="spread">
              <span>Odpověď publikuje worker, ve storu se objeví za chvíli.</span>
              {limit != null ? (
                <span className={overLimit ? 'reply-counter error-inline' : 'reply-counter'}>
                  {body.length} / {limit}
                </span>
              ) : (
                <span className="reply-counter">{body.length} / 5 000</span>
              )}
            </span>
          }
        >
          <textarea value={body} onChange={(e) => setBody(e.target.value)} required autoFocus />
        </Field>
        {foreign || original != null ? (
          <div className="row" style={{ marginTop: '0.5rem' }}>
            {foreign ? (
              <button
                type="button"
                className="secondary"
                disabled={translate.isPending || !hasText}
                onClick={() =>
                  translate.mutate({ reviewId, body }, { onSuccess: (result) => applyAssist(result, true) })
                }
              >
                {translate.isPending ? 'Překládám…' : `Přeložit do jazyka recenze (${language})`}
              </button>
            ) : null}
            {original != null ? (
              <button
                type="button"
                className="link"
                onClick={() => {
                  setBody(original)
                  setOriginal(null)
                }}
              >
                Vrátit původní
              </button>
            ) : null}
          </div>
        ) : null}
        {templates.data && templates.data.length === 0 ? (
          <p className="small muted" style={{ marginTop: '0.5rem' }}>
            <Link to={`/${org}/aplikace/${appId}`}>Šablony odpovědí si nastavíš v detailu aplikace</Link>
          </p>
        ) : null}
        <div className="row" style={{ marginTop: '0.75rem' }}>
          <button type="submit" disabled={reply.isPending || overLimit}>
            Odeslat do storu
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setState.mutate({ reviewId, state: 'IGNORED' }, { onSuccess: onClose })}
            disabled={setState.isPending}
          >
            Odložit
          </button>
          <button type="button" className="link" onClick={onClose}>
            Zavřít
          </button>
        </div>
        <div className="stack" style={{ marginTop: '0.5rem' }}>
          <ErrorBox error={reply.error ?? setState.error ?? suggest.error ?? translate.error} />
          {assistMessage ? <div className="notice">{assistMessage}</div> : null}
          {queued ? <div className="notice">{queued}</div> : null}
        </div>
      </form>
    </div>
  )
}
