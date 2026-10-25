import {
  Children,
  cloneElement,
  isValidElement,
  useEffect,
  useId,
  useRef,
  type ReactNode,
  type ReactElement,
} from 'react'
import { ApiError } from '../api/client'
import type { Platform } from '../api/types'
import { ThemeSwitch } from './Theme'
import { IconAndroid, IconApple } from './icons'

export function Card({
  id,
  title,
  className,
  children,
}: {
  /** Kotva pro odkaz na sekci — bez ní se na kartu nedá doskočit z jiné obrazovky. */
  id?: string
  title?: string
  className?: string
  children: ReactNode
}) {
  return (
    <section id={id} className={className ? `card ${className}` : 'card'}>
      {title ? <h2>{title}</h2> : null}
      {children}
    </section>
  )
}

/** Tři odezvy a bod: značka signálu, sdílená s faviconou. */
export function BrandMark() {
  return (
    <span className="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 40 40" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
        <path d="M8 16v8m9-14v20m9-16v12" />
        <circle cx="32" cy="9" r="2.5" fill="currentColor" stroke="none" />
      </svg>
    </span>
  )
}

export function SignalArt() {
  return (
    <svg className="signal-art" viewBox="0 0 480 280" fill="none" aria-hidden="true">
      {[40, 70, 100, 130, 160, 190, 220].map((r) => (
        <circle key={r} cx="240" cy="140" r={r} stroke="currentColor" opacity={1 - r / 260} />
      ))}
      <path d="M10 140h150l20-30 24 60 28-100 28 140 24-70h186" stroke="currentColor" strokeWidth="2" />
    </svg>
  )
}

export function PageHeader({
  eyebrow,
  title,
  children,
  action,
}: {
  eyebrow?: string
  title: string
  children?: ReactNode
  action?: ReactNode
}) {
  return (
    <header className="page-header">
      <div>
        {eyebrow ? <div className="eyebrow">{eyebrow}</div> : null}
        <h1>{title}</h1>
        {children ? <p className="muted">{children}</p> : null}
      </div>
      {action}
    </header>
  )
}

/** Značka: signál a název. Jinde než v hlavičkách se nepoužívá. */
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
    <div className="auth-layout">
      <aside className="auth-story">
        <Brand />
        <div className="auth-story-copy">
          <div className="eyebrow">Každá recenze je zpětná vazba.</div>
          <h1>
            Naslouchejte.
            <br />
            Rozumějte.
            <br />
            <em>Odpovídejte.</em>
          </h1>
          <p>
            Souvislosti, které potřebujete.
            <br />
            Od prvního postřehu k další dobré odpovědi.
          </p>
          <SignalArt />
        </div>
        <span className="small">App Store & Google Play · V jednom pracovním prostoru</span>
      </aside>
      <main className="auth-form">
        <div className={wide ? 'auth wide' : 'auth'}>
          <div className="auth-brand">
            <Brand />
          </div>
          {children}
        </div>
        <ThemeSwitch />
      </main>
    </div>
  )
}

/**
 * Modální dialog nad nativním `<dialog>`. Escape, zamčený focus i překryv jsou tím pádem
 * věcí prohlížeče, ne tří set řádků vlastní obsluhy klávesnice — a chovají se všude stejně.
 */
export function Modal({
  title,
  wide,
  onClose,
  children,
}: {
  title: string
  /** Pro dialogy s tabulkami — na 560 px se rozpadají. */
  wide?: boolean
  onClose: () => void
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (!dialog?.open) dialog?.showModal()
  }, [])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      className={wide ? 'modal wide' : 'modal'}
      onClose={onClose}
      // Kliknutí mimo kartu zavírá; `<dialog>` hlásí kliknutí na překryv sám na sobě.
      onClick={(event) => {
        if (event.target === ref.current) ref.current?.close()
      }}
    >
      <div className="modal-body">
        <div className="modal-head">
          <h2 id={titleId}>{title}</h2>
          {/* Viditelné zavření: na telefonu není Escape a klepnutí vedle karty nikoho nenapadne. */}
          <button
            type="button"
            className="modal-close"
            aria-label="Zavřít"
            onClick={() => ref.current?.close()}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        {children}
      </div>
    </dialog>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  const id = useId()
  const controls = Children.map(children, (child) =>
    isValidElement(child)
      ? cloneElement(child as ReactElement<{ id?: string; 'aria-describedby'?: string }>, {
          id,
          'aria-describedby': hint ? `${id}-hint` : undefined,
        })
      : child,
  )
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {controls}
      {hint ? (
        <div className="hint" id={`${id}-hint`}>
          {hint}
        </div>
      ) : null}
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
  return (
    <div className="error" role="alert">
      {message}
    </div>
  )
}

export function Loading({ what = 'Načítám…' }: { what?: string }) {
  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>{what}</span>
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="empty-state">
      <BrandMark />
      <p>{children}</p>
    </div>
  )
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

/** Odkud recenze je — logo + iOS/Android, aby to v seznamu nezapadlo. */
export function PlatformBadge({ platform }: { platform: Platform }) {
  const ios = platform === 'IOS'
  return (
    <span className={`badge platform ${ios ? 'ios' : 'android'}`} title={ios ? 'App Store' : 'Google Play'}>
      {ios ? <IconApple /> : <IconAndroid />}
      {ios ? 'iOS' : 'Android'}
    </span>
  )
}

/** Časy z API jsou ISO v UTC; člověk je chce vidět ve své zóně. */
export function When({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="muted">—</span>
  const date = new Date(iso)
  return <span title={iso}>{date.toLocaleString('cs-CZ', { dateStyle: 'medium', timeStyle: 'short' })}</span>
}
