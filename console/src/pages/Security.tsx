import { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import {
  useConfirmTotp,
  useDisableTotp,
  useMfaStatus,
  useRegenerateRecoveryCodes,
  useStartTotp,
} from '../api/hooks'
import { Badge, ErrorBox, Field, Loading, Modal } from '../components/ui'

/**
 * Zabezpečení účtu (F5.3). Druhý faktor je věc uživatele, ne organizace — a je to krátké
 * zařizování, ne místo k pobytu; proto dialog nad rozdělanou prací, ne vlastní stránka.
 */
export function SecurityDialog({ onClose }: { onClose: () => void }) {
  const status = useMfaStatus()

  return (
    <Modal title="Zabezpečení účtu" onClose={onClose}>
      {status.isPending ? <Loading /> : null}
      <ErrorBox error={status.error} />
      {status.data ? (
        status.data.enabled ? (
          <EnabledTotp remaining={status.data.remainingRecoveryCodes} onClose={onClose} />
        ) : (
          <TotpSetupFlow onClose={onClose} />
        )
      ) : null}
    </Modal>
  )
}

/** Zapínání: QR kód, opsaný kód, a teprve pak záchranné kódy. */
function TotpSetupFlow({ onClose }: { onClose: () => void }) {
  const start = useStartTotp()
  const confirm = useConfirmTotp()
  const [code, setCode] = useState('')

  if (confirm.data)
    return <RecoveryCodes codes={confirm.data.codes} title="Druhý faktor je zapnutý" onClose={onClose} />

  if (!start.data) {
    return (
      <>
        <p>
          Druhý faktor znamená, že k přihlášení nestačí heslo — ještě šestimístný kód
          z autentizační appky v telefonu (Google Authenticator, 1Password, Aegis…).
        </p>
        <ErrorBox error={start.error} />
        <button type="button" disabled={start.isPending} onClick={() => start.mutate()}>
          {start.isPending ? 'Připravuji…' : 'Zapnout druhý faktor'}
        </button>
      </>
    )
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        confirm.mutate(code)
      }}
    >
      <p>Naskenuj kód v autentizační appce:</p>
      <div style={{ background: '#fff', padding: '1rem', width: 'fit-content', borderRadius: 'var(--radius)' }}>
        {/* Vykresluje se v prohlížeči; obrázek s tajemstvím tak nikam neputuje. */}
        <QRCodeSVG value={start.data.provisioningUri} size={180} />
      </div>
      <p className="small muted" style={{ marginTop: '0.75rem' }}>
        Nejde naskenovat? Přepiš do appky ručně tenhle klíč:
        <br />
        <code>{start.data.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
      </p>
      <Field label="Kód z appky" hint="Šest číslic. Mění se po půl minutě, tak ho opiš rovnou.">
        <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoFocus required />
      </Field>
      <div className="stack" style={{ marginTop: '1rem' }}>
        <ErrorBox error={confirm.error} />
        <button type="submit" disabled={confirm.isPending}>
          {confirm.isPending ? 'Ověřuji…' : 'Potvrdit a zapnout'}
        </button>
      </div>
    </form>
  )
}

function EnabledTotp({ remaining, onClose }: { remaining: number; onClose: () => void }) {
  const [mode, setMode] = useState<'none' | 'disable' | 'recovery'>('none')
  const regenerate = useRegenerateRecoveryCodes()
  const disable = useDisableTotp()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')

  if (regenerate.data)
    return <RecoveryCodes codes={regenerate.data.codes} title="Nové záchranné kódy" onClose={onClose} />

  return (
    <>
      <p className="row">
        <Badge tone="ok">Zapnutý</Badge>
        <span>K přihlášení je potřeba heslo i kód z autentizační appky.</span>
      </p>
      <p className={remaining <= 2 ? 'error' : 'small muted'}>
        Nepoužitých záchranných kódů: {remaining}
        {remaining <= 2 ? ' — vygeneruj si nové, než dojdou.' : ''}
      </p>

      {mode === 'none' ? (
        <div className="row">
          <button type="button" onClick={() => setMode('recovery')}>
            Nové záchranné kódy
          </button>
          <button type="button" className="link" onClick={() => setMode('disable')}>
            Vypnout druhý faktor
          </button>
        </div>
      ) : null}

      {mode === 'recovery' ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            regenerate.mutate(code)
          }}
        >
          <p className="small muted">Ty dosavadní tím přestanou platit.</p>
          <Field label="Kód z appky">
            <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoFocus required />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={regenerate.error} />
            <div className="row">
              <button type="submit" disabled={regenerate.isPending}>
                Vygenerovat
              </button>
              <button type="button" className="link" onClick={() => setMode('none')}>
                Zpátky
              </button>
            </div>
          </div>
        </form>
      ) : null}

      {mode === 'disable' ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            disable.mutate({ password, code })
          }}
        >
          {/* Heslo i kód schválně: ukradená relace nesmí druhý faktor sundat. */}
          <Field label="Heslo">
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </Field>
          <Field label="Kód z appky">
            <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" required />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={disable.error} />
            <div className="row">
              <button type="submit" disabled={disable.isPending}>
                Vypnout
              </button>
              <button type="button" className="link" onClick={() => setMode('none')}>
                Zpátky
              </button>
            </div>
          </div>
        </form>
      ) : null}
    </>
  )
}

/** Kódy se ukazují jednou; server si nechává jen otisk a znovu je nikdo nezjistí. */
function RecoveryCodes({ codes, title, onClose }: { codes: string[]; title: string; onClose: () => void }) {
  return (
    <>
      <h3>{title}</h3>
      <p>
        Tohle jsou záchranné kódy — jediná cesta do účtu, když přijdeš o telefon. Ulož si je
        někam mimo tenhle počítač. <strong>Zobrazují se jednou.</strong>
      </p>
      <pre className="codes">{codes.join('\n')}</pre>
      <div className="row">
        <button
          type="button"
          onClick={() => {
            void navigator.clipboard?.writeText(codes.join('\n'))
          }}
        >
          Zkopírovat
        </button>
        <button type="button" className="link" onClick={onClose}>
          Hotovo
        </button>
      </div>
    </>
  )
}
