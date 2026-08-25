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

/** Hvězdička ve čtverečku — tentýž tvar jako favicona, ať se značka nerozchází. */
function BrandMark() {
  return (
    <span className="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" fill="currentColor">
        <path d="M16 6.5l3.1 6.3 7 1-5 4.9 1.2 6.9-6.3-3.3-6.3 3.3 1.2-6.9-5-4.9 7-1z" />
      </svg>
    </span>
  )
}

/** Značka: hvězdička a název. Jinde než v hlavičkách se nepoužívá. */
export function Brand({ subtitle }: { subtitle?: ReactNode }) {
  return (
    <div className="brand">
      <BrandMark />
      <div>
        <div className="brand-name">appreviewzz</div>
        {subtitle ? <div className="brand-org">{subtitle}</div> : null}
      </div>
    </div>
  )
}

/**
 * Rám pro obrazovky bez navigace (přihlášení, pozvánka, rozcestník). Značka nad kartou
 * je jediné, co člověku před přihlášením řekne, kde vlastně je.
 */
export function AuthShell({ wide, children }: { wide?: boolean; children: ReactNode }) {
  return (
    <div className="center">
      <div className={wide ? 'auth wide' : 'auth'}>
        <div className="auth-brand">
          <BrandMark />
          <span className="brand-name">appreviewzz</span>
        </div>
        {children}
      </div>
    </div>
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
  return (
    <div className="loading">
      <span className="spinner" aria-hidden="true" />
      <span>{what}</span>
    </div>
  )
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
