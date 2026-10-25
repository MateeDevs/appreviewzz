import { useEffect, useState, type InputHTMLAttributes } from 'react'
import { useMe, useResendVerification } from '../api/hooks'
import { ApiError } from '../api/client'
import { ErrorBox, Field } from './ui'

export function PasswordInput(props: InputHTMLAttributes<HTMLInputElement>) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="password-control">
      <input {...props} type={visible ? 'text' : 'password'} />
      <button
        type="button"
        className="password-toggle"
        aria-label={visible ? 'Skrýt heslo' : 'Zobrazit heslo'}
        aria-pressed={visible}
        onClick={() => setVisible(!visible)}
      >
        {visible ? 'Skrýt' : 'Ukázat'}
      </button>
    </div>
  )
}

/** Field předává id a popis přímo inputu, i když má heslo vlastní obal. */
export function PasswordField({
  label = 'Heslo',
  hint,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label?: string; hint?: string }) {
  return (
    <Field label={label} hint={hint}>
      <PasswordInput {...props} />
    </Field>
  )
}

export function VerificationNotice() {
  const me = useMe()
  const resend = useResendVerification()
  const [remaining, setRemaining] = useState(0)
  useEffect(() => {
    if (remaining <= 0) return
    const timer = setTimeout(() => setRemaining(remaining - 1), 1000)
    return () => clearTimeout(timer)
  }, [remaining])
  return (
    <div className="verification-notice stack">
      <span className="auth-symbol" aria-hidden="true">
        ↗
      </span>
      <p>
        Ověřovací odkaz jsme poslali na <strong>{me.data?.email}</strong>.
        Otevřete ho a vraťte se sem. Potom založíte svůj pracovní prostor.
      </p>
      <p className="small muted">
        E-mail nevidíte? Zkontrolujte nevyžádanou poštu. Při návratu do této
        záložky ověření znovu načteme.
      </p>
      <ErrorBox error={resend.error} />
      {resend.isSuccess ? (
        <p role="status" className="notice">
          Nový odkaz byl odeslán.
        </p>
      ) : null}
      <button
        type="button"
        className="secondary"
        disabled={resend.isPending || remaining > 0}
        onClick={() => {
          if (resend.isPending || remaining > 0) return
          resend.mutate(undefined, {
            onSuccess: () => setRemaining(60),
            onError: (error) => {
              if (error instanceof ApiError && error.status === 429)
                setRemaining(error.retryAfter ?? 60)
            },
          })
        }}
      >
        {resend.isPending
          ? 'Odesílám…'
          : remaining > 0
            ? `Poslat znovu za ${remaining} s`
            : 'Poslat ověřovací e-mail znovu'}
      </button>
      <button
        type="button"
        className="link"
        disabled={me.isFetching}
        onClick={() => void me.refetch()}
      >
        Už jsem e-mail ověřil/a
      </button>
    </div>
  )
}
