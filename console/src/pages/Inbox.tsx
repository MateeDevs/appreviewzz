import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useApps, useReply, useReview, useReviews, useSetReviewState, useTopics } from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, Stars, When } from '../components/ui'
import type { ReviewInsight, ReviewState, ReviewType, TopicOption, Urgency } from '../api/types'

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
  const [appId, setAppId] = useState('')
  const [filter, setFilter] = useState(0)
  const [topic, setTopic] = useState('')
  const [type, setType] = useState<ReviewType | ''>('')
  const [urgency, setUrgency] = useState<Urgency | ''>('')
  const [openReview, setOpenReview] = useState<string>('')

  const selected = appId || (apps.data?.[0]?.id ?? '')
  const reviews = useReviews(org, selected, {
    states: FILTERS[filter]?.states ?? [],
    topic,
    type,
    urgency,
  })
  const topics = useTopics(org, selected)

  if (apps.isPending) return <Loading />
  if (apps.data?.length === 0) {
    return (
      <Card>
        <p>Napřed je potřeba přidat aplikaci — bez ní není co sledovat.</p>
      </Card>
    )
  }

  return (
    <div className="stack">
      <div>
        <h1>Recenze</h1>
        <p className="muted">Co přišlo ze storu a co s tím.</p>
      </div>

      <Card>
        <div className="row">
          <select value={selected} onChange={(e) => setAppId(e.target.value)} style={{ width: 'auto' }}>
            {apps.data?.map((app) => (
              <option key={app.id} value={app.id}>
                {app.name}
              </option>
            ))}
          </select>
          {FILTERS.map((item, index) => (
            <button
              key={item.label}
              type="button"
              className={index === filter ? '' : 'secondary'}
              onClick={() => setFilter(index)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="row" style={{ marginTop: '0.5rem' }}>
          <TopicSelect topics={topics.data} value={topic} onChange={setTopic} />
          <select value={type} onChange={(e) => setType(e.target.value as ReviewType | '')} style={{ width: 'auto' }}>
            <option value="">Jakýkoli typ</option>
            {Object.entries(TYPE_LABELS).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
          <select value={urgency} onChange={(e) => setUrgency(e.target.value as Urgency | '')} style={{ width: 'auto' }}>
            <option value="">Jakákoli naléhavost</option>
            {Object.entries(URGENCY_LABELS).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
          {topic || type || urgency ? (
            <button
              type="button"
              className="secondary"
              onClick={() => {
                setTopic('')
                setType('')
                setUrgency('')
              }}
            >
              Zrušit filtry
            </button>
          ) : null}
        </div>
      </Card>

      <Card>
        {reviews.isPending ? <Loading /> : null}
        <ErrorBox error={reviews.error} />
        {reviews.data?.length === 0 ? <p className="muted">Tady nic není.</p> : null}
        {reviews.data?.map((review) => (
          <div className="review" key={review.id}>
            <div className="spread">
              <div>
                <Stars count={review.starRating} />{' '}
                <strong>{review.authorName ?? 'Anonym'}</strong>{' '}
                <span className="small muted">
                  {review.platform === 'ANDROID' ? 'Google Play' : 'App Store'} · <When iso={review.submittedAt} />
                  {review.appVersion ? ` · verze ${review.appVersion}` : ''}
                </span>
              </div>
              <StateBadge state={review.state} />
            </div>
            <InsightBadges insight={review.insight} />
            {review.title ? <div><strong>{review.title}</strong></div> : null}
            <p style={{ marginTop: '0.35rem' }}>{review.body ?? <span className="muted">(bez textu)</span>}</p>
            {review.developerResponseBody ? (
              <p className="small muted">Odpověď vývojáře: {review.developerResponseBody}</p>
            ) : null}
            <button
              type="button"
              className="secondary"
              onClick={() => setOpenReview(openReview === review.id ? '' : review.id)}
            >
              {openReview === review.id ? 'Zavřít' : 'Odpovědět'}
            </button>
            {openReview === review.id ? <ReplyForm org={org} reviewId={review.id} /> : null}
          </div>
        ))}
      </Card>
    </div>
  )
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
    <select value={value} onChange={(e) => onChange(e.target.value)} style={{ width: 'auto' }}>
      <option value="">Jakékoli téma</option>
      {[...groups.entries()].map(([group, items]) => (
        <optgroup key={group} label={group}>
          {items.map((item) => (
            <option key={item.key} value={item.key}>
              {item.name}
              {item.recentCount > 0 ? ` (${item.recentCount})` : ''}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
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

/** Citáty jsou ověřené jako doslovný úryvek recenze — proto se dají ukázat jako citace. */
function InsightDetail({ insight }: { insight: ReviewInsight }) {
  const quotes = insight.topics.filter((topic) => topic.quote)
  return (
    <div className="notice" style={{ marginBottom: '0.75rem' }}>
      <div className="small">
        {TYPE_LABELS[insight.type]} · naléhavost {URGENCY_LABELS[insight.urgency]}
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

function ReplyForm({ org, reviewId }: { org: string; reviewId: string }) {
  const detail = useReview(org, reviewId)
  const reply = useReply(org)
  const setState = useSetReviewState(org)
  const [body, setBody] = useState('')
  const [queued, setQueued] = useState<string | null>(null)

  return (
    <div style={{ marginTop: '0.75rem' }}>
      {detail.data?.review.insight ? <InsightDetail insight={detail.data.review.insight} /> : null}
      {detail.data && detail.data.replies.length > 0 ? (
        <div className="notice" style={{ marginBottom: '0.75rem' }}>
          {detail.data.replies.map((item) => (
            <div key={item.id} className="small">
              <strong>{item.authorDisplayName ?? item.source}</strong>: {item.body}{' '}
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
              },
            },
          )
        }}
      >
        <Field
          label="Odpověď"
          hint="Google Play má limit 350 znaků, App Store 5 000. Odpověď publikuje worker, takže se objeví ve storu za chvíli."
        >
          <textarea value={body} onChange={(e) => setBody(e.target.value)} required />
        </Field>
        <div className="row" style={{ marginTop: '0.75rem' }}>
          <button type="submit" disabled={reply.isPending}>
            Odeslat do storu
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setState.mutate({ reviewId, state: 'IGNORED' })}
            disabled={setState.isPending}
          >
            Odložit
          </button>
        </div>
        <div className="stack" style={{ marginTop: '0.5rem' }}>
          <ErrorBox error={reply.error ?? setState.error} />
          {queued ? <div className="notice">{queued}</div> : null}
        </div>
      </form>
    </div>
  )
}
