import type { ReactNode } from 'react'
import { ApiError } from '../api/client'

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="card">
      {title ? <h2>{title}</h2> : null}
      {children}
    </section>
  )
}

export function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
      {hint ? <div className="hint">{hint}</div> : null}
    </div>
  )
}

/**
 * Chyba z API. Server posílá větu pro člověka; když ji nemá, ukáže se aspoň to,
 * co se stalo — nikdy ne prázdný červený obdélník.
 */
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const message =
    error instanceof ApiError ? error.message : error instanceof Error ? error.message : String(error)
  return <div className="error">{message}</div>
}

export function Loading({ what = 'Načítám…' }: { what?: string }) {
  return <p className="muted">{what}</p>
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="muted">{children}</p>
}

export function Badge({ tone, children }: { tone?: 'ok' | 'warn' | 'bad'; children: ReactNode }) {
  return <span className={tone ? `badge ${tone}` : 'badge'}>{children}</span>
}

export function Stars({ count }: { count: number }) {
  return (
    <span className="stars" title={`${count} z 5`}>
      {'★'.repeat(count)}
      {'☆'.repeat(5 - count)}
    </span>
  )
}

/** Časy z API jsou ISO v UTC; člověk je chce vidět ve své zóně. */
export function When({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="muted">—</span>
  const date = new Date(iso)
  return <span title={iso}>{date.toLocaleString('cs-CZ', { dateStyle: 'medium', timeStyle: 'short' })}</span>
}
