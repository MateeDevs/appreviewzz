import type { RatingsSeries } from '../api/types'

/**
 * Vývoj průměrného hodnocení jako inline SVG.
 *
 * Bez grafové knihovny schválně: jedna čára s pár body nestojí za 100 kB v bundlu, který si
 * klient stáhne při každé návštěvě. Osa Y je záměrně **ořezaná kolem dat**, ne od nuly —
 * u hodnocení v rozsahu 1–5 se pohyb o desetinu jinak vůbec nepozná.
 */
export function RatingsChart({ series }: { series: RatingsSeries }) {
  const points = series.points.filter((point) => point.average != null)
  if (points.length < 2) {
    return <p className="muted small">Na graf je potřeba aspoň dva dny — první přehled je zítra.</p>
  }

  const values = points.map((point) => point.average as number)
  const min = Math.min(...values)
  const max = Math.max(...values)
  // Minimální rozpětí, aby se z rovné řady nestala čára po okraji grafu.
  const padding = Math.max((max - min) * 0.2, 0.05)
  const low = Math.max(0, min - padding)
  const high = Math.min(5, max + padding)
  const span = high - low || 1

  const x = (index: number) => (index / (points.length - 1)) * (WIDTH - 2 * MARGIN) + MARGIN
  const y = (value: number) => HEIGHT - MARGIN - ((value - low) / span) * (HEIGHT - 2 * MARGIN)

  const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${x(index)} ${y(point.average as number)}`).join(' ')
  const area = `${line} L ${x(points.length - 1)} ${HEIGHT - MARGIN} L ${x(0)} ${HEIGHT - MARGIN} Z`
  const last = points[points.length - 1]

  return (
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`Vývoj hodnocení ${series.platform}`} className="chart">
      <line x1={MARGIN} y1={HEIGHT - MARGIN} x2={WIDTH - MARGIN} y2={HEIGHT - MARGIN} className="chart-axis" />
      <path d={area} className="chart-area" />
      <path d={line} className="chart-line" />
      {points.map((point, index) => (
        <circle key={point.date} cx={x(index)} cy={y(point.average as number)} r={2.5} className="chart-dot">
          <title>{`${point.date}: ${(point.average as number).toFixed(2)}${point.newCount != null ? ` · +${point.newCount}` : ''}`}</title>
        </circle>
      ))}
      <text x={MARGIN} y={MARGIN} className="chart-label">
        {high.toFixed(2)}
      </text>
      <text x={MARGIN} y={HEIGHT - MARGIN - 4} className="chart-label">
        {low.toFixed(2)}
      </text>
      <text x={WIDTH - MARGIN} y={y(last?.average as number) - 8} textAnchor="end" className="chart-value">
        {(last?.average as number).toFixed(2)}
      </text>
    </svg>
  )
}

const WIDTH = 640
const HEIGHT = 160
const MARGIN = 16
