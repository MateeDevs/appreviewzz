import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  useAcceptInvitation,
  useForgotPassword,
  useLogin,
  useLogout,
  useMe,
  useRegister,
  useResetPassword,
  useVerifyEmail,
  useVerifySecondFactor,
} from '../api/hooks'
import { needsSecondFactor } from '../api/types'
import { AuthShell, Card, ErrorBox, Field } from '../components/ui'
import { useEffect } from 'react'
import { PasswordField, VerificationNotice } from '../components/AuthFields'
import { authLink, safeNext } from '../state/authRedirect'

/** Nejkratší heslo, které projde na serveru. Když se to změní, řekne to server větou. */
const MIN_PASSWORD = 12

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // Rozdělané přihlášení: heslo prošlo, čeká se na kód z autentizační appky.
  const [challenge, setChallenge] = useState<string | null>(null)
  const login = useLogin()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  // Kdo přišel z odkazu (typicky z pozvánky), má se po přihlášení vrátit tam, kam mířil.
  const next = safeNext(params.get('next'))
  const goOn = () => navigate(next)

  if (challenge)
    return (
      <SecondFactorForm
        challenge={challenge}
        onDone={goOn}
        onCancel={() => setChallenge(null)}
      />
    )

  return (
    <AuthShell>
      <Card title="Vítejte zpátky">
        <p className="auth-intro">
          Přihlaste se a navazujte tam, kde jste skončili.
        </p>
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (login.isPending) return
            login.mutate(
              { email, password },
              {
                onSuccess: (outcome) => {
                  if (needsSecondFactor(outcome))
                    setChallenge(outcome.challenge)
                  else goOn()
                },
              },
            )
          }}
        >
          <Field label="E-mail">
            <input
              type="email"
              name="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </Field>
          <PasswordField
            autoComplete="current-password"
            name="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={login.error} />
            <button type="submit" disabled={login.isPending}>
              {login.isPending ? 'Přihlašuji…' : 'Přihlásit se'}
            </button>
          </div>
        </form>
        <p className="small muted" style={{ marginTop: '1rem' }}>
          <Link to={authLink('/registrace', next)}>Založit účet</Link> ·{' '}
          <Link to="/zapomenute-heslo">Zapomenuté heslo</Link>
        </p>
      </Card>
    </AuthShell>
  )
}

/**
 * Druhý krok přihlášení. Bere kód z appky i záchranný kód — jedno pole schválně: člověk,
 * který zrovna nemá telefon, jinak neví, kam ten papírový kód napsat.
 */
function SecondFactorForm({
  challenge,
  onDone,
  onCancel,
}: {
  challenge: string
  onDone: () => void
  onCancel: () => void
}) {
  const [code, setCode] = useState('')
  const verify = useVerifySecondFactor()

  return (
    <AuthShell>
      <Card title="Kód z autentizační appky">
        <p className="auth-intro">Ještě jeden krok pro bezpečí vašeho účtu.</p>
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (verify.isPending) return
            verify.mutate({ challenge, code }, { onSuccess: onDone })
          }}
        >
          <Field
            label="Šestimístný kód"
            hint="Nemáš telefon po ruce? Napiš sem některý ze záchranných kódů."
          >
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              name="code"
              autoComplete="one-time-code"
              inputMode="text"
              autoFocus
              required
            />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={verify.error} />
            <button type="submit" disabled={verify.isPending}>
              {verify.isPending ? 'Ověřuji…' : 'Pokračovat'}
            </button>
          </div>
        </form>
        <p className="small muted" style={{ marginTop: '1rem' }}>
          <button type="button" className="link" onClick={onCancel}>
            Zpátky na přihlášení
          </button>
        </p>
      </Card>
    </AuthShell>
  )
}

export function RegisterPage() {
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const register = useRegister()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))

  return (
    <AuthShell>
      <Card title="Začněte vlastní aplikací">
        <p className="auth-intro">
          Jeden účet pro recenze, souvislosti i lepší odpovědi. Nejprve ověříme
          váš e-mail.
        </p>
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (register.isPending) return
            register.mutate(
              { email, password, displayName },
              {
                onSuccess: () => navigate(next === '/' ? '/organizace' : next),
              },
            )
          }}
        >
          <Field label="Jméno">
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
            />
          </Field>
          <Field label="E-mail">
            <input
              type="email"
              name="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </Field>
          <PasswordField
            hint={`Aspoň ${MIN_PASSWORD} znaků. Délka je důležitější než speciální znaky.`}
            autoComplete="new-password"
            name="password"
            minLength={MIN_PASSWORD}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={register.error} />
            <button type="submit" disabled={register.isPending}>
              {register.isPending ? 'Zakládám…' : 'Založit účet'}
            </button>
          </div>
        </form>
        <p className="small muted" style={{ marginTop: '1rem' }}>
          Už účet máte? <Link to={authLink('/login', next)}>Přihlásit se</Link>
        </p>
      </Card>
    </AuthShell>
  )
}

/** Cíl odkazu z ověřovacího e-mailu. Token se uplatní hned při otevření stránky. */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const verify = useVerifyEmail()
  const me = useMe()
  const { mutate } = verify
  const appliedToken = useRef('')
  useEffect(() => {
    if (token && appliedToken.current !== token) {
      appliedToken.current = token
      mutate(token)
    }
  }, [token, mutate])

  return (
    <AuthShell>
      <Card title="Potvrzení e-mailu">
        {!token ? (
          <div className="error" role="alert">
            V odkazu chybí ověřovací kód. Otevřete celý odkaz z e-mailu.
          </div>
        ) : null}
        {verify.isPending ? <p className="muted">Ověřuji…</p> : null}
        {verify.isSuccess || me.data?.emailVerified ? (
          <p className="notice" role="status">
            Hotovo, e-mail je potvrzený. Pokračujte do svého pracovního
            prostoru.
          </p>
        ) : null}
        {!me.data?.emailVerified ? <ErrorBox error={verify.error} /> : null}
        {verify.isError && token && !me.data?.emailVerified ? (
          <button
            type="button"
            className="secondary"
            disabled={verify.isPending}
            onClick={() => verify.mutate(token)}
          >
            Zkusit ověření znovu
          </button>
        ) : null}
        {me.data && !me.data.emailVerified && !verify.isSuccess ? (
          <VerificationNotice />
        ) : null}
        {!me.data && (verify.isError || !token) ? (
          <p className="muted">
            Odkaz mohl vypršet nebo už být použitý.{' '}
            <Link to="/login?next=%2Forganizace">Přihlaste se</Link> a požádejte
            o nový.
          </p>
        ) : null}
        <p style={{ marginTop: '1rem' }}>
          <Link to="/">Pokračovat do pracovního prostoru</Link>
        </p>
      </Card>
    </AuthShell>
  )
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const forgot = useForgotPassword()

  return (
    <AuthShell>
      <Card title="Obnova hesla">
        {forgot.isSuccess ? (
          <>
            {/* Stejná věta pro známý i neznámý e-mail — jinak by formulář prozradil zákazníky. */}
            <p>
              Pokud u nás ten e-mail je, poslali jsme na něj odkaz pro nastavení
              nového hesla.
            </p>
            <p className="small muted">Odkaz platí hodinu.</p>
          </>
        ) : (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              if (!forgot.isPending) forgot.mutate(email)
            }}
          >
            <Field label="E-mail">
              <input
                type="email"
                name="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={forgot.error} />
              <button type="submit" disabled={forgot.isPending}>
                {forgot.isPending ? 'Odesílám…' : 'Poslat odkaz'}
              </button>
            </div>
          </form>
        )}
        <p className="small muted" style={{ marginTop: '1rem' }}>
          <Link to="/login">Zpátky na přihlášení</Link>
        </p>
      </Card>
    </AuthShell>
  )
}

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const reset = useResetPassword()

  return (
    <AuthShell>
      <Card title="Nové heslo">
        {reset.isSuccess ? (
          <>
            <p>
              Heslo je nastavené a všechny dřívější přihlášení jsme zrušili.
            </p>
            <p>
              <Link to="/login">Přihlásit se</Link>
            </p>
          </>
        ) : (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              if (!reset.isPending && token) reset.mutate({ token, password })
            }}
          >
            <PasswordField
              label="Nové heslo"
              hint={`Aspoň ${MIN_PASSWORD} znaků.`}
              autoComplete="new-password"
              name="password"
              minLength={MIN_PASSWORD}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <div className="stack" style={{ marginTop: '1rem' }}>
              {!token ? (
                <div className="error">
                  Odkaz nemá token — otevři ho prosím přímo z e-mailu.
                </div>
              ) : null}
              <ErrorBox error={reset.error} />
              {reset.isError || !token ? (
                <Link to="/zapomenute-heslo">Vyžádat nový odkaz</Link>
              ) : null}
              <button type="submit" disabled={reset.isPending || !token}>
                {reset.isPending ? 'Ukládám…' : 'Nastavit heslo'}
              </button>
            </div>
          </form>
        )}
      </Card>
    </AuthShell>
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
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()

  return (
    <AuthShell>
      <Card title="Pozvánka do organizace">
        {!token ? (
          <div className="error">
            Odkaz nemá token — otevři ho prosím přímo z e-mailu.
          </div>
        ) : null}
        {!signedIn ? (
          <>
            <p>
              Nejdřív se přihlas účtem s tou e-mailovou adresou, na kterou
              pozvánka přišla.
            </p>
            <div className="row">
              <Link
                to={`/login?next=${encodeURIComponent(`/pozvanka?token=${token}`)}`}
              >
                Přihlásit se
              </Link>
              <Link
                to={authLink(
                  '/registrace',
                  `/pozvanka?token=${encodeURIComponent(token)}`,
                )}
              >
                Založit účet
              </Link>
            </div>
          </>
        ) : (
          <>
            <p>
              Přijměte pozvánku jako <strong>{me.data?.email}</strong> a vstupte
              rovnou do týmu. Další organizaci zakládat nemusíte.
            </p>
            <ErrorBox error={accept.error} />
            {accept.isError ? (
              <p className="small muted">
                Pozvánka může být použitá či expirovaná, nebo je pro jiný účet.
                Požádejte správce o novou nebo se přihlaste správným e-mailem.
              </p>
            ) : null}
            <button
              type="button"
              className="link"
              disabled={logout.isPending}
              onClick={() => logout.mutate()}
            >
              Použít jiný účet
            </button>
            <button
              type="button"
              disabled={accept.isPending || !token}
              onClick={() =>
                !accept.isPending &&
                accept.mutate(token, {
                  onSuccess: (organization) =>
                    navigate(`/${organization.slug}`),
                })
              }
            >
              {accept.isPending ? 'Přijímám…' : 'Přijmout pozvánku'}
            </button>
          </>
        )}
      </Card>
    </AuthShell>
  )
}
