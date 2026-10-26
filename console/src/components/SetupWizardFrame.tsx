import {
  createContext,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'

const FooterContext = createContext<HTMLElement | null>(null)
const ActivityContext = createContext(true)
const CloseContext = createContext<() => void>(() => {})
export function WizardClose({ children }: { children: ReactNode }) {
  const close = useContext(CloseContext)
  return (
    <button type="button" className="secondary" onClick={close}>
      {children}
    </button>
  )
}
export function WizardActivity({ active, children }: { active: boolean; children: ReactNode }) {
  const parent = useContext(ActivityContext)
  return <ActivityContext.Provider value={parent && active}>{children}</ActivityContext.Provider>
}

export interface SetupPhase {
  id: string
  label: string
  detail: string
  done?: boolean
}

/** Dedicated frame: ordinary confirmation dialogs retain their size and dismissal rules. */
export function SetupWizardFrame({
  title,
  phases,
  current,
  dirty,
  busy,
  onClose,
  children,
}: {
  title: string
  phases: SetupPhase[]
  current: string
  dirty: boolean
  busy: boolean
  onClose: () => void
  children: ReactNode
}) {
  const navigate = useNavigate()
  const closeOrigin = useRef<HTMLElement | null>(null)
  const [destination, setDestination] = useState<string | null>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const id = useId()
  const [footer, setFooter] = useState<HTMLDivElement | null>(null)
  const [confirming, setConfirming] = useState(false)
  const index = Math.max(
    0,
    phases.findIndex((p) => p.id === current),
  )
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const node = dialog.current!
    node.showModal()
    return () => {
      node.close()
      if (previous?.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (dirty || busy) event.preventDefault()
    }
    window.addEventListener('beforeunload', protect)
    return () => window.removeEventListener('beforeunload', protect)
  }, [dirty, busy])
  useEffect(() => {
    dialog.current?.querySelector<HTMLElement>('[data-wizard-active="true"] h3')?.focus()
  }, [current])
  const requestClose = () => {
    if (busy) return
    if (dirty) {
      closeOrigin.current = document.activeElement as HTMLElement
      setDestination(null)
      setConfirming(true)
    } else onClose()
  }
  return (
    <dialog
      ref={dialog}
      className="setup-wizard"
      aria-labelledby={id}
      onCancel={(event) => {
        event.preventDefault()
        if (confirming) {
          setConfirming(false)
          requestAnimationFrame(() => closeOrigin.current?.focus())
        } else requestClose()
      }}
      onClickCapture={(event) => {
        const link = (event.target as HTMLElement).closest<HTMLAnchorElement>('a[href]')
        if (
          !dirty ||
          !link ||
          link.target === '_blank' ||
          new URL(link.href).origin !== location.origin
        )
          return
        event.preventDefault()
        closeOrigin.current = link
        setDestination(new URL(link.href).pathname + new URL(link.href).search)
        setConfirming(true)
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return
        const controls = [
          ...event.currentTarget.querySelectorAll<HTMLElement>(
            'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary, [tabindex="0"]',
          ),
        ].filter((node) => node.getClientRects().length && !node.closest('[hidden]'))
        const first = controls[0],
          last = controls.at(-1)
        if (
          event.shiftKey &&
          (document.activeElement === first ||
            document.activeElement?.getAttribute('tabindex') === '-1')
        ) {
          event.preventDefault()
          last?.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first?.focus()
        }
      }}
    >
      <header className="setup-wizard-top">
        <span id={id}>{title}</span>
        <button
          type="button"
          className="modal-close"
          aria-label="Zavřít"
          disabled={busy}
          onClick={requestClose}
        >
          ×
        </button>
      </header>
      {confirming ? (
        <div className="setup-discard" role="alert">
          <h3>Opustit rozpracované nastavení?</h3>
          <p>
            Neuložená pole a vybraný soubor se zahodí. Uložené aplikace a klíče zůstanou v
            organizaci; připojení můžete dokončit z jejich nastavení.
          </p>
          <div className="row">
            <button
              type="button"
              autoFocus
              onClick={() => {
                setConfirming(false)
                requestAnimationFrame(() => closeOrigin.current?.focus())
              }}
            >
              Pokračovat v nastavení
            </button>
            <button
              type="button"
              className="secondary"
              onClick={() => (destination ? navigate(destination) : onClose())}
            >
              {destination
                ? 'Zahodit neuložené údaje a pokračovat'
                : 'Zahodit neuložené údaje a zavřít'}
            </button>
          </div>
        </div>
      ) : null}
      <div className="setup-wizard-layout" hidden={confirming}>
        <aside className="setup-wizard-nav">
          <div className="eyebrow">VAŠE CESTA K RECENZÍM</div>
          <ol aria-label="Fáze přidání aplikace">
            {phases.map((phase, i) => (
              <li key={phase.id} aria-current={current === phase.id ? 'step' : undefined}>
                <span
                  className="setup-phase-number"
                  aria-label={phase.done ? 'Dokončeno' : undefined}
                >
                  {phase.done ? '✓' : String(i + 1).padStart(2, '0')}
                </span>
                <span>
                  <strong>{phase.label}</strong>
                  <small>{phase.detail}</small>
                </span>
              </li>
            ))}
          </ol>
          <p className="setup-wizard-note">
            Uložené aplikace a klíče zůstávají ve vašem pracovním prostoru. Připojení můžete
            dokončit i později.
          </p>
        </aside>
        <div className="setup-wizard-work">
          {phases.length ? (
            <div className="setup-wizard-mobile" aria-hidden="true">
              {index + 1} / {phases.length} · {phases[index]?.label}
            </div>
          ) : null}
          <FooterContext.Provider value={footer}>
            <CloseContext.Provider value={requestClose}>
              <div className="setup-wizard-scroll">{children}</div>
            </CloseContext.Provider>
          </FooterContext.Provider>
          <div className="setup-wizard-footer" ref={setFooter} />
        </div>
      </div>
    </dialog>
  )
}

export function WizardActions({ children }: { children: ReactNode }) {
  const footer = useContext(FooterContext)
  const active = useContext(ActivityContext)
  if (!active) return null
  const actions = <div className="setup-wizard-actions">{children}</div>
  return footer ? createPortal(actions, footer) : actions
}

export function WizardHeading({
  eyebrow,
  title,
  children,
}: {
  eyebrow: string
  title: string
  children: ReactNode
}) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    if (!ref.current?.closest('[hidden]')) ref.current?.focus()
  }, [title])
  return (
    <div className="setup-step-heading">
      <div className="eyebrow">{eyebrow}</div>
      <h3 ref={ref} tabIndex={-1}>
        {title}
      </h3>
      <p>{children}</p>
    </div>
  )
}
