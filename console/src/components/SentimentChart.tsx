import { useState } from 'react'
import type { SentimentWeek } from '../api/types'

/** Jedna osa v procentech. Nulový vzorek přeruší řadu; hvězdy zůstávají samostatně v tabulce. */
export function SentimentChart({ weeks }: { weeks: SentimentWeek[] }) {
  const [active, setActive] = useState<number | null>(null)
  if (weeks.filter((week) => week.reviews > 0).length < 2)
    return <p className="small muted">Na trend potřebujeme alespoň dva týdny s hodnocením.</p>
  const width = 640,
    height = 260,
    left = 45,
    right = 16,
    top = 20,
    bottom = 36
  const x = (index: number) => left + (index / Math.max(1, weeks.length - 1)) * (width - left - right)
  const y = (share: number) => top + (1 - share) * (height - top - bottom)
  const lines = [
    ['positive', 'Kladná', 'var(--ok)', ''],
    ['neutral', 'Neutrální', 'var(--muted)', '2 5'],
    ['negative', 'Záporná', 'var(--error)', '7 4'],
  ] as const
  const selected = active == null ? undefined : weeks[active]
  return (
    <figure className="sentiment-figure" style={{ margin: 0 }}>
      <div className="chart-legend">
        {lines.map(([key, label, color, dash]) => (
          <span key={key}>
            <svg width="18" height="8" aria-hidden="true">
              <path d="M0 4H18" stroke={color} strokeWidth="2" strokeDasharray={dash} />
            </svg>
            {label}
          </span>
        ))}
      </div>
      <svg
        className="chart"
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label="Podíl kladné, neutrální a záporné nálady po týdnech. Svislá osa 0 až 100 procent. Přesné hodnoty jsou v tabulce pod grafem."
      >
        {[0, 0.25, 0.5, 0.75, 1].map((share) => (
          <g key={share}>
            <line
              x1={left}
              x2={width - right}
              y1={y(share)}
              y2={y(share)}
              className="chart-axis"
              strokeDasharray={share ? '3 5' : undefined}
            />
            <text x={left - 10} y={y(share) + 4} textAnchor="end" className="chart-label">
              {share * 100}%
            </text>
          </g>
        ))}
        {lines.map(([key, , color, dash]) => {
          let connected = false
          const path = weeks
            .map((week, index) => {
              if (!week.reviews) {
                connected = false
                return ''
              }
              const command = connected ? 'L' : 'M'
              connected = true
              return `${command}${x(index)} ${y(week[key] / week.reviews)}`
            })
            .join(' ')
          return (
            <path
              key={key}
              d={path}
              fill="none"
              stroke={color}
              strokeWidth={key === 'positive' ? 3 : 2}
              strokeDasharray={dash}
              strokeLinejoin="round"
            />
          )
        })}
        {weeks.map((week, index) => (
          <g key={week.weekStart}>
            {week.reviews > 0 ? (
              <g
                tabIndex={0}
                role="img"
                aria-label={`Týden od ${week.weekStart}: ${week.reviews} hodnocení; kladná ${Math.round((week.positive / week.reviews) * 100)} %, záporná ${Math.round((week.negative / week.reviews) * 100)} %`}
                onFocus={() => setActive(index)}
                onBlur={() => setActive(null)}
                onMouseEnter={() => setActive(index)}
                onMouseLeave={() => setActive(null)}
                onClick={() => setActive(index)}
              >
                <rect
                  x={x(index) - 10}
                  y={top}
                  width={20}
                  height={height - top - bottom}
                  fill="transparent"
                />
                {lines.map(([key, , color]) => (
                  <circle
                    key={key}
                    cx={x(index)}
                    cy={y(week[key] / week.reviews)}
                    r={active === index ? 5 : 3}
                    fill={color}
                    stroke="var(--surface)"
                    strokeWidth="1.5"
                  />
                ))}
              </g>
            ) : null}
            {index === 0 ||
            index === weeks.length - 1 ||
            index % Math.max(1, Math.ceil(weeks.length / 7)) === 0 ? (
              <text x={x(index)} y={height - 8} textAnchor="middle" className="chart-label">
                {Number(week.weekStart.slice(8))}. {Number(week.weekStart.slice(5, 7))}.
              </text>
            ) : null}
          </g>
        ))}
      </svg>
      <figcaption className="small muted chart-caption">
        {selected
          ? `Týden od ${selected.weekStart} · ${selected.reviews} hodnocení · kladná ${Math.round((selected.positive / selected.reviews) * 100)} % · záporná ${Math.round((selected.negative / selected.reviews) * 100)} %`
          : 'Týdenní podíly · mezery znamenají chybějící vzorek, ne nulovou náladu.'}
      </figcaption>
      <details className="chart-data">
        <summary>Přesné hodnoty a vzorky grafu</summary>
        <table tabIndex={0}>
          <thead>
            <tr>
              <th>Týden od</th>
              <th>Vzorek</th>
              <th>Kladná</th>
              <th>Neutrální</th>
              <th>Záporná</th>
              <th>Ø ★</th>
            </tr>
          </thead>
          <tbody>
            {weeks.map((week) => (
              <tr key={week.weekStart}>
                <th scope="row">{week.weekStart}</th>
                <td>{week.reviews}</td>
                <td>{week.positive}</td>
                <td>{week.neutral}</td>
                <td>{week.negative}</td>
                <td>{week.avgStars?.toFixed(2) ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
export function Sparkline({ points }: { points: number[] }) {
  const max = Math.max(...points, 1)
  return (
    <svg
      viewBox={`0 0 ${Math.max(points.length, 1) * 6} 16`}
      className="sparkline"
      role="img"
      aria-label={`Počet zmínek v osmi úsecích období: ${points.join(', ')}`}
    >
      {points.map((value, index) => (
        <rect
          key={index}
          x={index * 6}
          y={16 - (value / max) * 16}
          width={4}
          height={(value / max) * 16}
          className="spark-bar"
        />
      ))}
    </svg>
  )
}
