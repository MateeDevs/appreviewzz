import type { RatingsSeries } from '../api/types'

/** Ořezaná osa je výslovně popsaná. Chybějící měření přeruší čáru. */
export function RatingsChart({ series }: { series: RatingsSeries }) {
  const measured = series.points.filter((point) => point.average != null && point.average > 0)
  if (measured.length < 2)
    return <p className="small muted">Na trend potřebujeme alespoň dva dny s platným hodnocením.</p>
  const values = measured.map((point) => point.average!)
  const padding = Math.max((Math.max(...values) - Math.min(...values)) * 0.2, 0.05)
  const low = Math.max(1, Math.min(...values) - padding),
    high = Math.min(5, Math.max(...values) + padding)
  const width = 640,
    height = 210,
    left = 45,
    right = 16,
    top = 18,
    bottom = 34
  const firstDate = Date.parse(series.points[0]!.date),
    lastDate = Date.parse(series.points.at(-1)!.date)
  const x = (date: string) =>
    left + ((Date.parse(date) - firstDate) / (lastDate - firstDate || 1)) * (width - left - right)
  const y = (value: number) => top + ((high - value) / (high - low || 1)) * (height - top - bottom)
  const path = series.points
    .map((point, index) => {
      if (!point.average || point.average < 1) {
        return ''
      }
      const previous = series.points[index - 1]?.average
      const command = previous != null && previous >= 1 ? 'L' : 'M'
      return `${command}${x(point.date)} ${y(point.average)}`
    })
    .join(' ')
  return (
    <figure style={{ margin: 0 }}>
      <p className="small muted">
        {series.platform === 'IOS' ? 'App Store' : 'Google Play'} · {series.territory} ·{' '}
        {series.points[0]!.date} – {series.points.at(-1)!.date}. Osa oříznutá na {low.toFixed(2)}–
        {high.toFixed(2)} ★.
      </p>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="chart"
        role="img"
        aria-label={`Vývoj store ratingu, ořezaná osa ${low.toFixed(2)} až ${high.toFixed(2)} hvězd. Přesná data v tabulce pod grafem.`}
      >
        {[low, (low + high) / 2, high].map((value) => (
          <g key={value}>
            <line
              x1={left}
              x2={width - right}
              y1={y(value)}
              y2={y(value)}
              className="chart-axis"
              strokeDasharray="3 5"
            />
            <text x={left - 8} y={y(value) + 4} textAnchor="end" className="chart-label">
              {value.toFixed(2)}
            </text>
          </g>
        ))}
        <path d={path} className="chart-line" />
        {measured.map((point) => (
          <g
            key={point.date}
            tabIndex={0}
            role="img"
            aria-label={`${point.date}: ${point.average!.toFixed(2)} hvězd, ${point.totalCount ?? 'neznámý počet'} hodnocení`}
          >
            <circle cx={x(point.date)} cy={y(point.average!)} r={4} className="chart-dot" />
            <title>
              {point.date}: {point.average!.toFixed(2)} ★
            </title>
          </g>
        ))}
        <text x={left} y={height - 6} className="chart-label">
          {series.points[0]!.date}
        </text>
        <text x={width - right} y={height - 6} textAnchor="end" className="chart-label">
          {series.points.at(-1)!.date}
        </text>
      </svg>
      <details className="chart-data">
        <summary>Přesná měření a zdroj</summary>
        <table tabIndex={0}>
          <thead>
            <tr>
              <th>Den</th>
              <th>Store rating</th>
              <th>Počet hodnocení</th>
              <th>Zdroj</th>
            </tr>
          </thead>
          <tbody>
            {series.points.map((point) => (
              <tr key={point.date}>
                <td>{point.date}</td>
                <td>{point.average && point.average > 0 ? point.average.toFixed(2) : 'Bez měření'}</td>
                <td>{point.totalCount ?? '—'}</td>
                <td>{point.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
