import type { SentimentWeek } from '../api/types'

/**
 * Nálada v čase jako inline SVG — sloupce po týdnech plus tenká čára průměru hvězd.
 *
 * Stejná filozofie jako [RatingsChart]: žádná grafová knihovna. Sloupce jsou **stohované
 * a normalizované na 100 %**, ne na absolutní počet: klienta zajímá poměr spokojených
 * a nespokojených, a týden s dvaceti recenzemi by jinak přebil týden se třemi, ve kterém
 * byly nespokojené všechny tři. Kolik recenzí týden měl, říká šířka sloupce a popisek.
 */
export function SentimentChart({ weeks }: { weeks: SentimentWeek[] }) {
  const points = weeks.filter((week) => week.reviews > 0)
  if (points.length < 2) {
    return <p className="muted small">Na graf jsou potřeba aspoň dva týdny s recenzemi.</p>
  }

  const slot = (WIDTH - 2 * MARGIN) / points.length
  const barWidth = Math.min(slot * 0.68, 48)
  const plot = HEIGHT - MARGIN - BOTTOM

  // Hvězdy jsou na druhé ose, ořezané kolem dat — pohyb o desetinu se jinak nepozná.
  const stars = points.map((week) => week.avgStars).filter((value): value is number => value != null)
  const low = stars.length > 0 ? Math.max(0, Math.min(...stars) - 0.4) : 0
  const high = stars.length > 0 ? Math.min(5, Math.max(...stars) + 0.4) : 5
  const starSpan = high - low || 1

  const centre = (index: number) => MARGIN + slot * index + slot / 2
  const starY = (value: number) => MARGIN + plot - ((value - low) / starSpan) * plot

  const line = points
    .map((week, index) => (week.avgStars == null ? null : `${index === 0 ? 'M' : 'L'} ${centre(index)} ${starY(week.avgStars)}`))
    .filter((segment): segment is string => segment != null)
    .join(' ')

  return (
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="Nálada recenzí po týdnech" className="chart">
      <line x1={MARGIN} y1={MARGIN + plot} x2={WIDTH - MARGIN} y2={MARGIN + plot} className="chart-axis" />
      {points.map((week, index) => {
        const share = (count: number) => (count / week.reviews) * plot
        const negative = share(week.negative)
        const neutral = share(week.neutral)
        const positive = plot - negative - neutral
        const x = centre(index) - barWidth / 2
        return (
          <g key={week.weekStart}>
            <rect x={x} y={MARGIN} width={barWidth} height={positive} className="mood-positive" />
            <rect x={x} y={MARGIN + positive} width={barWidth} height={neutral} className="mood-neutral" />
            <rect x={x} y={MARGIN + positive + neutral} width={barWidth} height={negative} className="mood-negative" />
            <title>
              {`Týden od ${week.weekStart}: ${week.reviews} recenzí, ${Math.round((week.positive / week.reviews) * 100)} % spokojených` +
                (week.avgStars != null ? `, Ø ${week.avgStars.toFixed(2)} ★` : '')}
            </title>
          </g>
        )
      })}
      {line ? <path d={line} className="chart-line" /> : null}
      {points.map((week, index) =>
        week.avgStars == null ? null : (
          <circle key={week.weekStart} cx={centre(index)} cy={starY(week.avgStars)} r={2.5} className="chart-dot" />
        ),
      )}
      {points.map((week, index) => (
        // Popisek jen u každého druhého týdne — u roku dat by se jinak slily do čáry.
        <text
          key={week.weekStart}
          x={centre(index)}
          y={HEIGHT - 6}
          textAnchor="middle"
          className="chart-label"
          opacity={points.length > 10 && index % 2 === 1 ? 0 : 1}
        >
          {week.weekStart.slice(8)}. {Number(week.weekStart.slice(5, 7))}.
        </text>
      ))}
    </svg>
  )
}

/** Trend tématu: osm sloupečků v jednom řádku tabulky. */
export function Sparkline({ points }: { points: number[] }) {
  const max = Math.max(...points, 1)
  return (
    <svg viewBox={`0 0 ${points.length * 6} 16`} className="sparkline" role="img" aria-label="Vývoj tématu v období">
      {points.map((value, index) => (
        <rect
          // Body nemají vlastní identitu; pořadí v období je jediný klíč, který dává smysl.
          key={`bod-${index}`}
          x={index * 6}
          y={16 - Math.max((value / max) * 16, value > 0 ? 2 : 0)}
          width={4}
          height={Math.max((value / max) * 16, value > 0 ? 2 : 0)}
          className="spark-bar"
        />
      ))}
    </svg>
  )
}

const WIDTH = 640
const HEIGHT = 190
const MARGIN = 16
/** Místo pod osou na popisky dnů. */
const BOTTOM = 26
