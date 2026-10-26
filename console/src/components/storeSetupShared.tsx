import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { Platform } from '../api/types'

export type StoreSetupProps = {
  org: string
  active: boolean
  onBack: () => void
  onDone: () => void
  onDirty: (dirty: boolean) => void
  onBusy: (busy: boolean) => void
}
export const storeName = (platform: Platform) =>
  platform === 'ANDROID' ? 'Google Play' : 'App Store Connect'
export function Steplist({ items }: { items: readonly string[] }) {
  return (
    <ol className="setup-howto">
      {items.map((item, i) => (
        <li key={item}>
          <span aria-hidden="true">{i + 1}</span>
          <p>{item}</p>
        </li>
      ))}
    </ol>
  )
}
export function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a className="button secondary" href={href} target="_blank" rel="noreferrer noopener">
      {children} ↗<span className="sr-only"> (nová záložka)</span>
    </a>
  )
}
export function CopyValue({ value }: { value: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (state === 'idle') return
    const timer = setTimeout(() => setState('idle'), 5000)
    return () => clearTimeout(timer)
  }, [state])
  return (
    <div className="setup-copy">
      <div className="row">
        <input
          aria-label="Hodnota ke zkopírování"
          readOnly
          value={value}
          ref={input}
          onFocus={(event) => event.currentTarget.select()}
        />
        <button
          type="button"
          className="secondary"
          onClick={async () => {
            try {
              if (!navigator.clipboard) throw new Error('unavailable')
              await navigator.clipboard.writeText(value)
              setState('copied')
            } catch {
              input.current?.focus()
              input.current?.select()
              setState('failed')
            }
          }}
        >
          {state === 'copied' ? 'Zkopírováno' : 'Kopírovat'}
        </button>
      </div>
      <span className="hint" role="status">
        {state === 'failed'
          ? 'Automatické kopírování není dostupné. Hodnota je označená; zkopírujte ji pomocí Ctrl/Cmd+C nebo nabídky telefonu.'
          : state === 'copied'
            ? 'Hodnota byla zkopírována do schránky.'
            : 'Tuto hodnotu přeneste do externí konzole.'}
      </span>
    </div>
  )
}
export function StoreProgress({ steps, current }: { steps: string[]; current: number }) {
  const ref = useRef<HTMLOListElement>(null)
  useEffect(() => {
    const heading = [
      ...(ref.current?.closest('.setup-step')?.querySelectorAll<HTMLElement>('h3') ?? []),
    ].find((node) => !node.closest('[hidden]'))
    heading?.focus()
  }, [current])
  return (
    <ol ref={ref} className="setup-store-progress" aria-label="Kroky připojení storu">
      {steps.map((step, i) => (
        <li key={step} aria-current={i === current ? 'step' : undefined}>
          <span>{i < current ? '✓' : i + 1}</span>
          {step}
        </li>
      ))}
    </ol>
  )
}

/** Never render connector messages verbatim: they can contain upstream internals. */
export function validationMessage(kind?: string | null) {
  switch (kind) {
    case 'AUTH':
      return 'Přístup zatím není potvrzený. Zkontrolujte pozvaný účet a oprávnění k této aplikaci. Změny se mohou propisovat se zpožděním.'
    case 'NOT_FOUND':
      return 'Store tuto aplikaci pod daným účtem nenašel. Zkontrolujte identifikátor aplikace a vývojářský účet.'
    case 'INVALID_REQUEST':
      return 'Údaje aplikace nebo klíče store odmítl. Zkontrolujte správnou aplikaci a platnost klíče.'
    case 'RATE_LIMITED':
      return 'Store dočasně omezuje požadavky. Vyčkejte a zkuste ověření znovu.'
    case 'TRANSIENT':
      return 'Store je dočasně nedostupný. Uložené připojení zůstává zachované; ověření zkuste znovu.'
    default:
      return 'Přístup zatím není potvrzený. Zkontrolujte oprávnění; pokud jste je právě udělili, mohou se ještě propisovat.'
  }
}
