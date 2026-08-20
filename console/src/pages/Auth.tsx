import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  useAcceptInvitation,
  useForgotPassword,
  useLogin,
  useRegister,
  useResetPassword,
  useVerifyEmail,
} from '../api/hooks'
import { Card, ErrorBox, Field } from '../components/ui'
import { useEffect } from 'react'

/** Nejkratší heslo, které projde na serveru. Když se to změní, řekne to server větou. */
const MIN_PASSWORD = 12

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const login = useLogin()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  // Kdo přišel z odkazu (typicky z pozvánky), má se po přihlášení vrátit tam, kam mířil.
  const next = params.get('next')

  return (
    <div className="center">
      <Card title="Přihlášení do appreviewzz">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            login.mutate({ email, password }, { onSuccess: () => navigate(next && next.startsWith('/') ? next : '/') })
          }}
        >
          <Field label="E-mail">
            <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Heslo">
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={login.error} />
            <button type="submit" disabled={login.isPending}>
              {login.isPending ? 'Přihlašuji…' : 'Přihlásit se'}
            </button>
          </div>
        </form>
        <p className="small muted" style={{ marginTop: '1rem' }}>
          <Link to="/registrace">Založit účet</Link> · <Link to="/zapomenute-heslo">Zapomenuté heslo</Link>
        </p>
      </Card>
    </div>
  )
}

export function RegisterPage() {
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const register = useRegister()
  const navigate = useNavigate()

  return (
    <div className="center">
      <Card title="Založení účtu">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            register.mutate({ email, password, displayName }, { onSuccess: () => navigate('/') })
          }}
        >
          <Field label="Jméno">
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="name" />
          </Field>
          <Field label="E-mail">
            <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Heslo" hint={`Aspoň ${MIN_PASSWORD} znaků. Délka je důležitější než speciální znaky.`}>
            <input
              type="password"
              autoComplete="new-password"
              minLength={MIN_PASSWORD}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={register.error} />
            <button type="submit" disabled={register.isPending}>
              {register.isPending ? 'Zakládám…' : 'Založit účet'}
            </button>
          </div>
        </form>
        <p className="small muted" style={{ marginTop: '1rem' }}>
          Už účet máš? <Link to="/login">Přihlásit se</Link>
        </p>
      </Card>
    </div>
  )
}

/** Cíl odkazu z ověřovacího e-mailu. Token se uplatní hned při otevření stránky. */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const verify = useVerifyEmail()
  const { mutate } = verify

  useEffect(() => {
    if (token) mutate(token)
  }, [token, mutate])

  return (
    <div className="center">
      <Card title="Potvrzení e-mailu">
        {!token ? <div className="error">Odkaz nemá token — otevři ho prosím přímo z e-mailu.</div> : null}
        {verify.isPending ? <p className="muted">Ověřuji…</p> : null}
        {verify.isSuccess ? <p>Hotovo, e-mail je potvrzený.</p> : null}
        <ErrorBox error={verify.error} />
        <p style={{ marginTop: '1rem' }}>
          <Link to="/">Pokračovat do console</Link>
        </p>
      </Card>
    </div>
  )
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const forgot = useForgotPassword()

  return (
    <div className="center">
      <Card title="Obnova hesla">
        {forgot.isSuccess ? (
          <>
            {/* Stejná věta pro známý i neznámý e-mail — jinak by formulář prozradil zákazníky. */}
            <p>Pokud u nás ten e-mail je, poslali jsme na něj odkaz pro nastavení nového hesla.</p>
            <p className="small muted">Odkaz platí hodinu.</p>
          </>
        ) : (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              forgot.mutate(email)
            }}
          >
            <Field label="E-mail">
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={forgot.error} />
              <button type="submit" disabled={forgot.isPending}>
                Poslat odkaz
              </button>
            </div>
          </form>
        )}
        <p className="small muted" style={{ marginTop: '1rem' }}>
          <Link to="/login">Zpátky na přihlášení</Link>
        </p>
      </Card>
    </div>
  )
}

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const reset = useResetPassword()

  return (
    <div className="center">
      <Card title="Nové heslo">
        {reset.isSuccess ? (
          <>
            <p>Heslo je nastavené a všechny dřívější přihlášení jsme zrušili.</p>
            <p>
              <Link to="/login">Přihlásit se</Link>
            </p>
          </>
        ) : (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              reset.mutate({ token, password })
            }}
          >
            <Field label="Nové heslo" hint={`Aspoň ${MIN_PASSWORD} znaků.`}>
              <input
                type="password"
                autoComplete="new-password"
                minLength={MIN_PASSWORD}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              {!token ? <div className="error">Odkaz nemá token — otevři ho prosím přímo z e-mailu.</div> : null}
              <ErrorBox error={reset.error} />
              <button type="submit" disabled={reset.isPending || !token}>
                Nastavit heslo
              </button>
            </div>
          </form>
        )}
      </Card>
    </div>
  )
}

/**
 * Cíl odkazu z pozvánky. Přijmout ji jde jen přihlášeným účtem se stejnou adresou —
 * proto se nepřihlášený nejdřív odkáže na registraci a token si stránka nechá v adrese.
 */
export function AcceptInvitationPage({ signedIn }: { signedIn: boolean }) {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const accept = useAcceptInvitation()
  const navigate = useNavigate()

  return (
    <div className="center">
      <Card title="Pozvánka do organizace">
        {!token ? <div className="error">Odkaz nemá token — otevři ho prosím přímo z e-mailu.</div> : null}
        {!signedIn ? (
          <>
            <p>Nejdřív se přihlas účtem s tou e-mailovou adresou, na kterou pozvánka přišla.</p>
            <div className="row">
              <Link to={`/login?next=${encodeURIComponent(`/pozvanka?token=${token}`)}`}>Přihlásit se</Link>
              <Link to="/registrace">Založit účet</Link>
            </div>
          </>
        ) : (
          <>
            <p>Pozvánku přijmeš jedním kliknutím.</p>
            <ErrorBox error={accept.error} />
            <button
              type="button"
              disabled={accept.isPending || !token}
              onClick={() =>
                accept.mutate(token, { onSuccess: (organization) => navigate(`/${organization.slug}`) })
              }
            >
              {accept.isPending ? 'Přijímám…' : 'Přijmout pozvánku'}
            </button>
          </>
        )}
      </Card>
    </div>
  )
}
