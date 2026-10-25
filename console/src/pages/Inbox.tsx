import { useEffect, useLayoutEffect, useRef, useState } from 'react'
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
import { draftKey, hasDrafts, readDraft, writeDraft } from '../state/replyDrafts'
import {
  Badge,
  Card,
  Empty,
  ErrorBox,
  Field,
  Loading,
  PageHeader,
  PlatformBadge,
  Stars,
  When,
} from '../components/ui'
import { Select } from '../components/Select'
import type {
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

const NO_SELECTION: ReadonlySet<string> = new Set()

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
  const [params, setParams] = useSearchParams()
  const [selection, setSelection] = useState<{ listKey: string; ids: ReadonlySet<string> }>(() => ({
    listKey: '',
    ids: new Set(),
  }))
  const [replyFocusId, setReplyFocusId] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkError, setBulkError] = useState<string | null>(null)
  const setState = useSetReviewState(org)
  const appId = params.get('app') ?? ''
  const selected = apps.data?.some((app) => app.id === appId) ? appId : (apps.data?.[0]?.id ?? '')
  const topic = params.get('topic') ?? ''
  const version = params.get('version') ?? ''
  const valid = <T extends string>(value: string | null, values: readonly T[], fallback: T) =>
    values.includes(value as T) ? (value as T) : fallback
  const platform = valid(params.get('platform'), ['', 'ANDROID', 'IOS'] as const, '')
  const sort = valid(
    params.get('sort'),
    SORT_OPTIONS.map((item) => item.value),
    'NEWEST',
  )
  const type = valid(params.get('type'), ['', ...Object.keys(TYPE_LABELS)] as (ReviewType | '')[], '')
  const urgency = valid(params.get('urgency'), ['', 'LOW', 'MEDIUM', 'HIGH'] as const, '')
  const filter = Math.max(
    0,
    ['pending', 'replied', 'ignored', 'all'].indexOf(params.get('state') ?? 'pending'),
  )
  const openReview = params.get('review') ?? ''
  const navigationReview = useRef(openReview)
  useLayoutEffect(() => {
    navigationReview.current = openReview
  }, [openReview])
  const focusedId = openReview
  const page = Math.min(100000, Math.max(1, Math.floor(Number(params.get('page')) || 1)))
  const offset = (page - 1) * PAGE
  const setParam = (key: string, value: string, replace = false) => {
    const next = new URLSearchParams(params)
    if (key !== 'app' && selected) next.set('app', selected)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key !== 'review') {
      next.delete('review')
      if (key !== 'page') next.delete('page')
    }
    setParams(next, { replace })
  }
  const setOpenReview = (value: string, replace = false) => {
    navigationReview.current = value
    setReplyFocusId('')
    setParam('review', value, replace)
    if (!value && openReview)
      requestAnimationFrame(() =>
        document
          .getElementById(`review-${openReview}`)
          ?.querySelector<HTMLButtonElement>('.review-preview')
          ?.focus(),
      )
  }
  const setAppId = (value: string) => setParam('app', value)
  const setTopic = (value: string) => setParam('topic', value)
  const setPage = (value: number) => setParam('page', value > 1 ? String(value) : '')
  const setFilter = (value: number) =>
    setParam('state', ['pending', 'replied', 'ignored', 'all'][value] ?? 'pending')
  const setType = (value: ReviewType | '') => setParam('type', value)
  const setUrgency = (value: Urgency | '') => setParam('urgency', value)

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (hasDrafts()) event.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])
  const selectedApp = apps.data?.find((app) => app.id === selected)
  const appName = selectedApp?.name ?? ''
  // Konkurence je jen ke čtení: cizí recenze, na které se neodpovídá.
  const readOnly = selectedApp?.competitor === true
  const date = (key: string) => (/^\d{4}-\d{2}-\d{2}$/.test(params.get(key) ?? '') ? params.get(key)! : '')
  const from = date('from'),
    to = date('to')
  const territory = params.get('territory') ?? ''
  const textOnly = params.get('textOnly') === 'true'
  const sentiment = valid(
    params.get('sentiment'),
    ['', 'NEGATIVE', 'POSITIVE', 'NEUTRAL', 'MIXED'] as const,
    '',
  )
  const filters = {
    states: FILTERS[filter]?.states ?? [],
    topic,
    type,
    urgency,
    version,
    platform,
    from,
    to,
    territory,
    textOnly,
    sentiment,
  }
  const reviews = useReviews(org, selected, { ...filters, limit: PAGE, offset, sort })
  const count = useReviewCount(org, selected, filters)
  const topics = useTopics(org, selected)

  const list = reviews.data ?? []
  const listKey = JSON.stringify([org, selected, filters, page, sort, list.map((review) => review.id)])
  // Výběr i zvýraznění se vážou k recenzím na obrazovce; po přepnutí stránky by ukazovaly
  // na něco, co není vidět. Výběr proto platí jen pro seznam, ke kterému vznikl.
  const selectedIds = selection.listKey === listKey ? selection.ids : NO_SELECTION
  const setSelectedIds = (
    next: ReadonlySet<string> | ((current: ReadonlySet<string>) => ReadonlySet<string>),
  ) =>
    setSelection((current) => {
      const ids = current.listKey === listKey ? current.ids : NO_SELECTION
      return { listKey, ids: typeof next === 'function' ? next(ids) : next }
    })
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
    setBulkError(null)
    let failed = 0
    const remaining = new Set(selectedIds)
    for (const review of selectedOnPage) {
      try {
        await setState.mutateAsync({ reviewId: review.id, state: 'IGNORED' })
        remaining.delete(review.id)
      } catch {
        failed++
      }
    }
    setSelectedIds(remaining)
    if (failed)
      setBulkError(
        `Nepodařilo se odložit ${failed} recenzí. Úspěšné změny zůstaly uložené; zbývající můžete zkusit znovu.`,
      )
    setBulkBusy(false)
  }

  const moveFocus = (direction: 1 | -1) => {
    if (list.length === 0) return
    const index = list.findIndex((review) => review.id === navigationReview.current)
    const next =
      index < 0
        ? direction === 1
          ? 0
          : list.length - 1
        : Math.min(list.length - 1, Math.max(0, index + direction))
    const id = list[next]?.id ?? ''
    setOpenReview(id, true)
    document.getElementById(`review-${id}`)?.scrollIntoView({ block: 'nearest' })
  }

  // Klávesy jako v poště: inbox se prochází shora dolů, ruce zůstávají na klávesnici.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return
      if (
        document.querySelector('dialog[open]') ||
        (event.target as HTMLElement)?.closest('[role="listbox"], [role="menu"], [role="combobox"]')
      )
        return
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
          if (
            navigationReview.current &&
            !readOnly &&
            !list.find((review) => review.id === navigationReview.current)?.removedAt
          ) {
            event.preventDefault()
            setReplyFocusId(navigationReview.current)
            document.querySelector<HTMLTextAreaElement>('.reply-editor textarea')?.focus()
          }
          break
        case 'i':
          if (!readOnly && focusedId && !setState.isPending) {
            event.preventDefault()
            // Zvýraznění předem přeskočí na další — odložená recenze z pohledu zmizí.
            const index = list.findIndex((review) => review.id === focusedId)
            const nextId = list[index + 1]?.id ?? list[index - 1]?.id ?? ''
            setOpenReview(nextId, true)
            setState.mutate({ reviewId: focusedId, state: 'IGNORED' })
          }
          break
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  })

  if (apps.isPending) return <Loading />
  if (apps.error) return <ErrorBox error={apps.error} />
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
    <div className={`stack inbox-page${openReview ? ' has-detail' : ''}`}>
      <PageHeader eyebrow="Pracovní fronta" title="Recenze">
        Od zpětné vazby k odpovědi. S celým kontextem.
      </PageHeader>

      <Card className="filter-panel">
        <div className="row inbox-filters">
          <Select
            value={selected}
            options={apps.data
              ?.filter((app) => !app.competitor)
              .map((app) => ({ value: app.id, label: app.name }))}
            groups={
              apps.data?.some((app) => app.competitor)
                ? [
                    {
                      label: 'Konkurence',
                      options: apps.data
                        .filter((app) => app.competitor)
                        .map((app) => ({ value: app.id, label: app.name })),
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
        <button
          type="button"
          className="secondary mobile-filter-toggle"
          aria-expanded={advanced}
          onClick={() => setAdvanced(!advanced)}
        >
          Filtry
          {urgency === 'HIGH'
            ? ' · naléhavé'
            : topic || type || version || from || to
              ? ' · aktivní'
              : ''}{' '}
          {advanced ? '−' : '+'}
        </button>
        <div className={advanced ? 'advanced-filters expanded' : 'advanced-filters'}>
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
            {from || to ? (
              <Badge>
                {from || '…'} – {to || '…'} · zóna aplikace
              </Badge>
            ) : null}
            {territory ? <Badge>{territory}</Badge> : null}
            {textOnly ? <Badge>S textem</Badge> : null}
            {sentiment ? (
              <Badge>
                Nálada:{' '}
                {sentiment === 'NEGATIVE'
                  ? 'záporná'
                  : sentiment === 'POSITIVE'
                    ? 'kladná'
                    : 'neutrální / smíšená'}
              </Badge>
            ) : null}
            {topic || type || urgency || version || from || to || territory || textOnly || sentiment ? (
              <button
                type="button"
                className="secondary"
                onClick={() => {
                  const next = new URLSearchParams(params)
                  for (const key of [
                    'topic',
                    'type',
                    'urgency',
                    'version',
                    'page',
                    'review',
                    'from',
                    'to',
                    'territory',
                    'sentiment',
                    'textOnly',
                  ])
                    next.delete(key)
                  setParams(next)
                }}
              >
                Zrušit filtry
              </button>
            ) : null}
          </div>
        </div>
        {readOnly ? (
          <p className="small muted inbox-keys">Konkurence — recenze jen ke čtení, odpovídat se nedá.</p>
        ) : (
          <p className="small muted inbox-keys keyboard-only">
            Klávesy: j/k další / předchozí · r editor · i odložit · Escape seznam
          </p>
        )}
      </Card>

      <div className="inbox-workspace">
        <section className="inbox-list" aria-label="Seznam recenzí">
          <div className="inbox-list-head">
            <h2>{FILTERS[filter]?.label}</h2>
            {pager}
          </div>
          {reviews.isPending ? <Loading /> : null}
          <ErrorBox error={reviews.error ?? setState.error ?? bulkError} />
          {reviews.data?.length === 0 ? (
            <Empty>Tomuto výběru neodpovídá žádná recenze. Zkuste upravit filtry.</Empty>
          ) : null}
          {list.length > 0 && !readOnly ? (
            <label className="inbox-check inbox-select-all">
              <input
                type="checkbox"
                checked={allOnPageSelected}
                onChange={(event) =>
                  setSelectedIds(event.target.checked ? new Set(list.map((review) => review.id)) : new Set())
                }
              />{' '}
              Vybrat stránku
            </label>
          ) : null}
          {selectedOnPage.length > 0 ? (
            <div className="inbox-bulk" role="toolbar" aria-label="Hromadné akce">
              <strong>{selectedCount(selectedOnPage.length)}</strong>
              <button className="secondary" type="button" disabled={bulkBusy} onClick={ignoreSelected}>
                Odložit vybrané
              </button>
              <button
                type="button"
                className="link"
                disabled={bulkBusy}
                onClick={() => setSelectedIds(new Set())}
              >
                Zrušit výběr
              </button>
            </div>
          ) : null}
          {list.map((review) => (
            <div
              key={review.id}
              id={`review-${review.id}`}
              className={`inbox-item${openReview === review.id ? ' selected' : ''}`}
            >
              {!readOnly ? (
                <input
                  type="checkbox"
                  checked={selectedIds.has(review.id)}
                  onChange={(e) => toggleSelected(review.id, e.target.checked)}
                  aria-label={`Vybrat recenzi od ${review.authorName ?? 'anonyma'}`}
                />
              ) : null}
              <button
                type="button"
                className="review-preview"
                aria-current={openReview === review.id ? 'true' : undefined}
                onClick={() => setOpenReview(review.id)}
              >
                <span className="spread">
                  <span className="review-author">{review.authorName ?? 'Anonym'}</span>
                  <span className="small muted">
                    <RelativeWhen iso={review.submittedAt} />
                  </span>
                </span>
                <span className="row">
                  <Stars count={review.starRating} />
                  <PlatformBadge platform={review.platform} />
                  {review.insight?.urgency === 'HIGH' ? <Badge tone="bad">naléhavé</Badge> : null}
                </span>
                <strong className="preview-title">
                  {review.title || review.body || 'Hodnocení bez textu'}
                </strong>
                {review.title ? <span className="preview-body">{review.body}</span> : null}
                <span className="row small muted">
                  {review.removedAt ? (
                    <Badge tone="bad">smazaná ve storu</Badge>
                  ) : (
                    <StateBadge state={review.state} />
                  )}
                  {review.appVersion ? `v${review.appVersion}` : ''}
                </span>
              </button>
            </div>
          ))}
          {total != null && total > PAGE ? <div className="inbox-list-head">{pager}</div> : null}
        </section>
        <section className="inbox-detail" aria-label="Detail recenze">
          {openReview ? (
            <ReviewPane
              key={`${org}/${selected}/${openReview}`}
              org={org}
              appId={selected}
              appName={appName}
              id={openReview}
              focusEditor={replyFocusId === openReview}
              readOnly={readOnly}
              onClose={() => setOpenReview('')}
            />
          ) : (
            <div className="detail-placeholder">
              <div className="eyebrow">Vyberte recenzi</div>
              <h2>Každá odpověď začíná porozuměním.</h2>
              <p>Otevřete recenzi ze seznamu. Najdete tu její kontext, historii i koncept odpovědi.</p>
              <span className="small muted keyboard-only">j / k pro procházení · r pro psaní</span>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function ReviewPane({
  org,
  appId,
  appName,
  id,
  readOnly,
  focusEditor,
  onClose,
}: {
  org: string
  appId: string
  appName: string
  id: string
  readOnly: boolean
  focusEditor: boolean
  onClose: () => void
}) {
  const detail = useReview(org, id)
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    if (detail.data && focusEditor) {
      heading.current?.closest('article')?.querySelector<HTMLTextAreaElement>('textarea')?.focus()
      return
    }
    if (detail.data && window.matchMedia('(max-width: 1180px)').matches) {
      heading.current?.focus({ preventScroll: true })
      heading.current?.closest('.inbox-detail')?.scrollIntoView({ block: 'start' })
    }
  }, [detail.data, focusEditor])
  if (detail.isPending) return <Loading what="Načítám recenzi…" />
  if (detail.error) return <ErrorBox error={detail.error} />
  const review = detail.data?.review
  if (!review) return <Empty>Recenzi se nepodařilo najít.</Empty>
  return (
    <article className="review-document">
      <div className="detail-toolbar">
        <button type="button" className="secondary" onClick={onClose}>
          ← Zpět na seznam
        </button>
        <span className="small muted">{appName}</span>
        <StateBadge state={review.state} />
      </div>
      <div className="review-source">
        <div className="row">
          <PlatformBadge platform={review.platform} />
          <Stars count={review.starRating} />
        </div>
        <h2 ref={heading} tabIndex={-1}>
          {review.title || 'Hodnocení aplikace'}
        </h2>
        <p className="small muted">
          {review.authorName ?? 'Anonym'} · <When iso={review.submittedAt} />
          <RewrittenWhen review={review} />
          {review.appVersion ? ` · verze ${review.appVersion}` : ''}
          {review.territory ? ` · ${review.territory}` : ''}
        </p>
        <p className="review-body">{review.body ?? 'Hodnocení bez textu.'}</p>
        {review.insight?.translation ? (
          <div className="review-translation">
            <span className="eyebrow">Překlad</span>
            <p>{review.insight.translation}</p>
          </div>
        ) : null}
        <InsightBadges insight={review.insight} />
        {review.insight ? (
          <InsightDetail insight={review.insight} />
        ) : (
          <p className="small muted">Recenze zatím nemá AI výklad.</p>
        )}
      </div>
      {review.developerResponseBody ? (
        <div className="review-reply">
          <strong>Odpověď ve storu</strong> · <When iso={review.developerResponseAt} />
          {answersEarlierWording(review) ? <Badge tone="warn">k dřívějšímu znění</Badge> : null}
          <p>{review.developerResponseBody}</p>
        </div>
      ) : null}
      <ReplyForm
        org={org}
        appId={appId}
        appName={appName}
        review={review}
        readOnly={readOnly || Boolean(review.removedAt)}
        onClose={onClose}
      />
    </article>
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
        <button
          type="button"
          className="secondary"
          onClick={() => onPage(page - 1)}
          disabled={page <= 1 || pending}
        >
          Předchozí
        </button>
        <button
          type="button"
          className="secondary"
          onClick={() => onPage(page + 1)}
          disabled={!hasNext || pending}
        >
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

/** „· upraveno před 7 dny", když autor recenzi po odeslání přepsal. */
function RewrittenWhen({ review }: { review: Review }) {
  const updated = review.storeUpdatedAt
  // Android posílá čas změny i u nepřepsané recenze, shodný s časem odeslání.
  if (!updated || Date.parse(updated) - Date.parse(review.submittedAt) < 60_000) return null
  return (
    <>
      {' · upraveno '}
      <RelativeWhen iso={updated} />
    </>
  )
}

/**
 * Odpověď starší než to, co v recenzi teď stojí: autor ji po odpovědi přepsal. App Store
 * u přepsané recenze nechá původní odpověď viset, takže bez téhle poznámky to vypadá,
 * jako bychom odpověděli dřív, než recenze vůbec přišla.
 */
function answersEarlierWording(review: Review): boolean {
  if (!review.developerResponseAt) return false
  const wording = review.storeUpdatedAt ?? review.submittedAt
  return Date.parse(review.developerResponseAt) < Date.parse(wording)
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
        <Badge
          key={topic.key}
          tone={topic.sentiment === 'NEGATIVE' ? 'bad' : topic.sentiment === 'POSITIVE' ? 'ok' : undefined}
        >
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
        {insight.urgency === 'HIGH'
          ? 'Vyžaduje přednostní pozornost'
          : `Naléhavost: ${URGENCY_LABELS[insight.urgency]}`}
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
  readOnly,
}: {
  org: string
  appId: string
  appName: string
  readOnly: boolean
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
  const key = draftKey(org, appId, reviewId)
  const [body, updateBody] = useState(() => readDraft(key))
  const setBody = (value: string | ((current: string) => string)) => {
    const next = typeof value === 'function' ? value(readDraft(key)) : value
    writeDraft(key, next)
    updateBody(next)
  }
  // Text před překladem. Překlad textareu přepíše a člověk musí mít cestu zpět.
  const [original, setOriginal] = useState<string | null>(null)
  const [assistMessage, setAssistMessage] = useState<string | null>(null)
  const [queued, setQueued] = useState<string | null>(null)
  const limit = review.platform === 'ANDROID' ? GP_REPLY_LIMIT : 5000
  const pendingReply = detail.data?.replies.some((item) => item.status === 'PENDING') ?? false
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
    const current = readDraft(key)
    setOriginal(keepOriginal || current ? current : null)
    setBody(result.text)
  }

  const insertTemplate = (id: string) => {
    const template = templates.data?.find((item) => item.id === id)
    if (!template) return
    const text = fillTemplate(template.body, review, appName)
    setBody((current) => (current.trim() ? `${current.replace(/\s+$/, '')}\n\n${text}` : text))
  }

  return (
    <div className="reply-editor">
      <ErrorBox error={detail.error} />
      {detail.data && detail.data.replies.length > 0 ? (
        <div className="notice" style={{ marginBottom: '0.75rem' }}>
          {detail.data.replies.map((item) => (
            <div key={item.id} className="small">
              <strong>{item.authorDisplayName ?? item.source}</strong>
              {/* Kdo odpověděl, není detail: automatická odpověď šla ven bez schválení. */}
              {item.source === 'AUTO' ? (
                <>
                  {' '}
                  <Badge>auto</Badge>
                </>
              ) : null}
              : {item.body}{' '}
              {item.status === 'PUBLISHED' ? (
                <Badge tone="ok">publikováno</Badge>
              ) : item.status === 'FAILED' ? (
                <Badge tone="bad">selhalo{item.error ? ` · ${item.error}` : ''}</Badge>
              ) : (
                <Badge tone="warn">ve frontě</Badge>
              )}
            </div>
          ))}
        </div>
      ) : null}

      {readOnly ? (
        <div className="notice">
          {review.removedAt
            ? 'Autor recenzi ve storu smazal. Odpovědět už nejde.'
            : 'Konkurenční aplikace · recenze pouze ke čtení.'}
        </div>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (pendingReply || reply.isPending || !hasText || overLimit) return
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
              disabled={suggest.isPending || reply.isPending || pendingReply}
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
            label="Koncept odpovědi"
            hint={
              <span className="spread">
                <span>Koncept zůstává v této relaci i po přepnutí recenze.</span>
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
            <textarea
              disabled={reply.isPending || pendingReply}
              value={body}
              onChange={(e) => setBody(e.target.value)}
              required
              aria-label="Koncept odpovědi"
              placeholder="Napište odpověď, nebo začněte AI návrhem…"
            />
          </Field>
          {foreign || original != null ? (
            <div className="row" style={{ marginTop: '0.5rem' }}>
              {foreign ? (
                <button
                  type="button"
                  className="secondary"
                  disabled={translate.isPending || !hasText || reply.isPending || pendingReply}
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
            <button
              type="submit"
              disabled={
                reply.isPending ||
                overLimit ||
                !hasText ||
                pendingReply ||
                detail.isPending ||
                Boolean(queued && detail.isFetching)
              }
            >
              {pendingReply ? 'Čeká na publikaci' : reply.isPending ? 'Zařazuji…' : 'Zařadit k odeslání'}
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
            {queued && (pendingReply || detail.isFetching) ? <div className="notice">{queued}</div> : null}
          </div>
        </form>
      )}
    </div>
  )
}
