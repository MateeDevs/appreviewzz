import { useEffect, useRef, useState } from 'react'
import {
  useAddCredential,
  useAttachCredential,
  useCheckReportingBucket,
  useCredentials,
  useProvisionGooglePlay,
  useUpdateApp,
  useValidateCredential,
} from '../api/hooks'
import type { App, Credential, ReportingBucketCheck } from '../api/types'
import { ErrorBox, Field, Loading } from './ui'
import { googlePlayCopy as copy } from './connectStoreCopy'
import { WizardActions, WizardActivity, WizardHeading } from './SetupWizardFrame'
import {
  CopyValue,
  ExternalLink,
  Steplist,
  StoreProgress,
  validationMessage,
  type StoreSetupProps,
} from './storeSetupShared'

type Step = 'scope' | 'invite' | 'check' | 'reporting'
type Scope = 'reviews' | 'ratings'
type CheckState =
  | { status: 'checking' }
  | { status: 'ok' }
  | { status: 'failed'; kind?: string | null }

export function GooglePlaySetup({
  org,
  app,
  active,
  onReporting,
  onBack,
  onDone,
  onBusy,
  onDirty,
}: StoreSetupProps & { app: App; onReporting?: (status: ReportingBucketCheck['status']) => void }) {
  const credentials = useCredentials(org),
    provision = useProvisionGooglePlay(org),
    attach = useAttachCredential(org),
    add = useAddCredential(org)
  const [step, setStep] = useState<Step>('scope'),
    [scope, setScope] = useState<Scope>('reviews')
  const [credential, setCredential] = useState<Credential | null>(null),
    [failure, setFailure] = useState<unknown>(null)
  const [working, setWorking] = useState(false),
    [custom, setCustom] = useState(false),
    [json, setJson] = useState('')
  const lock = useRef(false),
    mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    onBusy(working)
  }, [working, onBusy])
  useEffect(() => {
    onDirty(!!json)
  }, [json, onDirty])
  const keys = credentials.data?.filter((c) => c.type === 'GP_SERVICE_ACCOUNT') ?? []
  const task = async (run: () => Promise<void>) => {
    if (lock.current) return
    lock.current = true
    setWorking(true)
    setFailure(null)
    try {
      await run()
    } catch (error) {
      if (mounted.current) setFailure(error)
    } finally {
      lock.current = false
      if (mounted.current) setWorking(false)
    }
  }
  const pick = (key: Credential) => {
    setCredential(key)
    setStep('invite')
  }
  const begin = (chosen: Scope) => {
    setScope(chosen)
    void task(async () => {
      const key = keys.find((c) => c.origin === 'PROVISIONED') ?? (await provision.mutateAsync())
      if (mounted.current) pick(key)
    })
  }
  const next = () =>
    void task(async () => {
      if (!credential) return
      await attach.mutateAsync({ appId: app.id, credentialId: credential.id })
      if (mounted.current) setStep('check')
    })
  const steps = ['Rozsah', 'Pozvání', 'Ověření', ...(scope === 'ratings' ? ['Exporty'] : [])]
  return (
    <div className="setup-step stack">
      <StoreProgress
        steps={steps}
        current={['scope', 'invite', 'check', 'reporting'].indexOf(step)}
        completed={[
          0,
          1,
          ...(!app.setup.platformsWithoutKey.includes('ANDROID') &&
          !app.setup.platformsWaitingForKey.includes('ANDROID')
            ? [2]
            : []),
        ]}
      />
      {step === 'scope' ? (
        <>
          <WizardHeading eyebrow="GOOGLE PLAY / ROZSAH" title={copy.scope.heading}>
            {copy.scope.lead}
          </WizardHeading>
          <div className="setup-choices">
            {(['reviews', 'ratings'] as const).map((s) => (
              <button
                type="button"
                className="setup-choice"
                key={s}
                disabled={working}
                onClick={() => begin(s)}
              >
                <span className="setup-choice-icon" aria-hidden="true">
                  {s === 'reviews' ? '↳' : '☆'}
                </span>
                <strong>{copy.scope[s].title}</strong>
                <span>{copy.scope[s].lead}</span>
                <small>{copy.scope[s].detail}</small>
                <span className="setup-choice-cta">Zvolit tento rozsah →</span>
              </button>
            ))}
          </div>
          <p className="small muted">
            Pozvání účtu může provést vlastník nebo správce Play Console. Pokud tato práva nemáte,
            požádejte jej o provedení následujících kroků.
          </p>
          {keys.length ? (
            <details className="optional">
              <summary>Použít účet uložený v organizaci</summary>
              <p className="small muted">
                I dříve ověřený klíč znovu ověříme pro aplikaci {app.name}. Rozsah exportů můžete
                doplnit po ověření.
              </p>
              {keys.map((key) => (
                <button
                  key={key.id}
                  className="secondary"
                  type="button"
                  disabled={working}
                  onClick={() => pick(key)}
                >
                  {key.label} · {key.hint ?? 'vlastní účet'}
                </button>
              ))}
            </details>
          ) : null}
          <details
            className="optional"
            open={custom}
            onToggle={(event) => setCustom(event.currentTarget.open)}
          >
            <summary>Pokročilé: vlastní service account</summary>
            <p className="small muted">
              Máte vlastní účet s povoleným Google Play Android Developer API? Vložte jeho JSON
              klíč. Uloží se přes stejné šifrované úložiště jako spravovaný účet.
            </p>
            <Field label="JSON klíč vlastního účtu">
              <textarea
                rows={4}
                value={json}
                onChange={(event) => setJson(event.target.value)}
                autoComplete="off"
                spellCheck={false}
              />
            </Field>
            <button
              type="button"
              disabled={!json.trim() || working}
              onClick={() =>
                void task(async () => {
                  const key = await add.mutateAsync({
                    type: 'gp',
                    deduplicate: true,
                    label: 'Google Play · vlastní účet',
                    content: json,
                  })
                  if (mounted.current) {
                    setJson('')
                    onDirty(false)
                    pick(key)
                  }
                })
              }
            >
              Uložit vlastní klíč
            </button>
          </details>
          {working ? <Loading what="Připravujeme uložený účet…" /> : null}
        </>
      ) : null}
      {step === 'invite' && credential ? (
        <>
          <WizardHeading eyebrow="GOOGLE PLAY / POZVÁNÍ" title={copy.invite.heading}>
            {copy.invite.lead}
          </WizardHeading>
          {credential.hint ? (
            <>
              <p className="field-label">E-mail pro pole Email address</p>
              <CopyValue value={credential.hint} />
            </>
          ) : (
            <p className="notice">
              E-mail service accountu najdete ve vlastním JSON v poli client_email.
            </p>
          )}
          <p className="setup-target">
            <strong>{app.name}</strong>
            <code>{app.gpPackageName}</code>
          </p>
          <ExternalLink href={copy.consoleUrl}>Otevřít Users and permissions</ExternalLink>
          <Steplist items={copy.invite.steps[scope]} />
          <p className="notice">{copy.invite.note}</p>
          <p className="small muted">
            Účet už je pozvaný? Nic znovu nezakládejte; rovnou ověřte jeho přístup k této aplikaci.
          </p>
        </>
      ) : null}
      {step === 'check' && credential ? (
        <GooglePlayCheck
          key={credential.id}
          org={org}
          app={app}
          credentialId={credential.id}
          active={active}
        />
      ) : null}
      <div hidden={step !== 'reporting'}>
        <WizardActivity active={step === 'reporting'}>
          <GooglePlayReporting
            org={org}
            app={app}
            active={active && step === 'reporting'}
            onReporting={onReporting}
            onBack={() => setStep('check')}
            onDone={onDone}
            onBusy={onBusy}
            onDirty={onDirty}
          />
        </WizardActivity>
      </div>
      {step !== 'reporting' ? (
        <>
          <ErrorBox error={failure ?? credentials.error} />
          <WizardActions>
            {step === 'invite' ? (
              <button type="button" disabled={working} onClick={next}>
                {working ? 'Ukládáme připojení…' : 'Ověřit přístup'}
              </button>
            ) : null}
            {step === 'check' ? (
              <>
                <button
                  type="button"
                  onClick={scope === 'ratings' ? () => setStep('reporting') : onDone}
                >
                  {scope === 'ratings' ? 'Pokračovat k exportům' : 'Zobrazit souhrn'}
                </button>
                {scope !== 'ratings' ? (
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setScope('ratings')
                      setStep('reporting')
                    }}
                  >
                    Připojit také exporty
                  </button>
                ) : null}
              </>
            ) : null}
            <button
              type="button"
              className="secondary"
              disabled={working}
              onClick={() =>
                step === 'scope' ? onBack() : setStep(step === 'check' ? 'invite' : 'scope')
              }
            >
              ← Zpět
            </button>
            {step !== 'scope' ? (
              <button type="button" className="link" disabled={working} onClick={onDone}>
                Dokončit později
              </button>
            ) : null}
          </WizardActions>
        </>
      ) : null}
    </div>
  )
}

function GooglePlayCheck({
  org,
  app,
  credentialId,
  active,
}: {
  org: string
  app: App
  credentialId: string
  active: boolean
}) {
  const validate = useValidateCredential(org),
    check = validate.mutateAsync
  const [state, setState] = useState<CheckState>({ status: 'checking' }),
    [attempt, setAttempt] = useState(0)
  const inFlight = useRef(false)
  useEffect(() => {
    if (!active) return
    let cancelled = false,
      timer: ReturnType<typeof setTimeout> | undefined
    let tries = 0
    const tick = async () => {
      if (cancelled) return
      if (inFlight.current) {
        timer = setTimeout(() => void tick(), 250)
        return
      }
      inFlight.current = true
      if (!cancelled) setState({ status: 'checking' })
      try {
        const outcome = await check({ appId: app.id, credentialId })
        if (cancelled) return
        setState(outcome.valid ? { status: 'ok' } : { status: 'failed', kind: outcome.kind })
        // Retry authentication propagation serially; permanent and upstream errors stay actionable.
        if (!outcome.valid && (!outcome.kind || outcome.kind === 'AUTH') && ++tries < 12)
          timer = setTimeout(() => void tick(), 10000)
      } catch {
        if (!cancelled) setState({ status: 'failed', kind: 'TRANSIENT' })
      } finally {
        inFlight.current = false
      }
    }
    timer = setTimeout(() => void tick(), 0)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [app.id, credentialId, check, active, attempt])
  return (
    <>
      <WizardHeading eyebrow="GOOGLE PLAY / OVĚŘENÍ" title="Ověřte přístup k aplikaci">
        Kontrolujeme skutečný přístup k aplikaci {app.name}, nikoli jen platnost účtu. Žádnou
        recenzi ani odpověď tím nezveřejníme.
      </WizardHeading>
      {state.status === 'checking' ? (
        <Loading what="Ověřujeme přístup…" />
      ) : (
        <div className={state.status === 'ok' ? 'notice' : 'notice setup-warning'} role="status">
          <strong>
            {state.status === 'ok'
              ? 'Přístup k aplikaci je ověřený'
              : 'Přístup zatím není potvrzený'}
          </strong>
          <p>
            {state.status === 'ok'
              ? 'Připojení recenzí je připravené. Import je samostatný krok; připojení samo o sobě nedokazuje, že už jsou recenze stažené.'
              : validationMessage(state.kind)}
          </p>
        </div>
      )}
      {state.status === 'failed' ? (
        <>
          <p className="small muted">
            {!state.kind || state.kind === 'AUTH'
              ? 'U právě změněných oprávnění zkoušíme kontrolu každých 10 sekund, nejvýše dvě minuty, pouze dokud je tento krok otevřený.'
              : 'Po opravě údajů nebo obnovení dostupnosti storu ověření spusťte znovu.'}{' '}
            Později můžete ověření spustit z nastavení aplikace.
          </p>
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>
            Ověřit přístup znovu
          </button>
        </>
      ) : null}
    </>
  )
}

const reportMessages: Record<ReportingBucketCheck['status'], { title: string; body: string }> = {
  OK: {
    title: 'Export hodnocení pro tuto aplikaci nalezen',
    body: 'Adresa je uložená. Nalezli jsme export pro tuto aplikaci; načtení jeho dat proběhne při synchronizaci hodnocení.',
  },
  NO_EXPORT: {
    title: 'Bucket je dostupný, export aplikace zatím chybí',
    body: 'Adresu uložíme. Zkontrolujte vývojářský účet a dostupnost reportu v Play Console. Google může export zveřejnit se zpožděním několika dnů; u aplikace bez hodnocení nemusí existovat.',
  },
  DENIED: {
    title: 'K exportům zatím nemáme přístup',
    body: 'V Play Console u tohoto service accountu zkontrolujte globální View app information and download bulk reports (read-only). Po změně oprávnění zkuste kontrolu znovu.',
  },
  MISSING: {
    title: 'Bucket nebyl nalezen',
    body: 'Zkopírujte celou Cloud Storage URI ze správného vývojářského účtu. Neexistující adresu neukládáme.',
  },
  UNAVAILABLE: {
    title: 'Cloud Storage je dočasně nedostupné',
    body: 'Ověření zkuste znovu. Uložení samotné adresy nepotvrzuje funkčnost exportů.',
  },
}
function GooglePlayReporting({
  org,
  app,
  active,
  onReporting,
  onBack,
  onDone,
  onBusy,
  onDirty,
}: StoreSetupProps & { app: App; onReporting?: (status: ReportingBucketCheck['status']) => void }) {
  const update = useUpdateApp(org),
    check = useCheckReportingBucket(org)
  const [value, setValue] = useState(app.gpReportingBucket ?? ''),
    [outcome, setOutcome] = useState<ReportingBucketCheck | null>(null),
    [saved, setSaved] = useState(false),
    [failure, setFailure] = useState<unknown>(null),
    [busy, setBusy] = useState(false)
  const resultRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (active && outcome) {
      resultRef.current?.focus()
      resultRef.current?.scrollIntoView({ block: 'nearest' })
    }
  }, [active, outcome])
  const lock = useRef(false),
    mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    if (active) onBusy(busy)
  }, [busy, onBusy, active])
  useEffect(() => {
    if (active) onDirty(!saved && value !== (app.gpReportingBucket ?? ''))
  }, [value, saved, app.gpReportingBucket, onDirty, active])
  const valid = /^gs:\/\/[^/\s]+(?:\/[^\s]*)?$/.test(value.trim())
  const run = async (force = false) => {
    if (lock.current || !valid) return
    lock.current = true
    setBusy(true)
    setFailure(null)
    const bucket = 'gs://' + value.trim().slice(5).split('/')[0]
    try {
      const result = force ? outcome : await check.mutateAsync({ appId: app.id, bucket })
      if (!mounted.current || !result) return
      setOutcome(result)
      if (force || result.worthSaving) {
        await update.mutateAsync({
          id: app.id,
          body: { name: app.name, gpReportingBucket: bucket },
        })
        if (mounted.current) {
          setSaved(true)
          onDirty(false)
          onReporting?.(result.status)
        }
      }
    } catch (error) {
      if (mounted.current) setFailure(error)
    } finally {
      lock.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return (
    <>
      <WizardHeading eyebrow="GOOGLE PLAY / EXPORTY" title={copy.reporting.heading}>
        {copy.reporting.lead}
      </WizardHeading>
      <Steplist items={copy.reporting.steps} />
      <Field label={copy.reporting.fieldLabel} hint={copy.reporting.fieldHint}>
        <input
          value={value}
          disabled={busy}
          onChange={(event) => {
            setValue(event.target.value)
            setOutcome(null)
            setSaved(false)
          }}
          placeholder="gs://pubsite_prod_rev_01234567890123456789"
        />
      </Field>
      {value && !valid ? (
        <p role="alert" className="error">
          Vložte adresu začínající gs:// a názvem bucketu, bez mezer.
        </p>
      ) : null}
      {outcome ? (
        <div className="notice" role="status" ref={resultRef} tabIndex={-1}>
          <strong>{reportMessages[outcome.status].title}</strong>
          <p>{reportMessages[outcome.status].body}</p>
          {saved ? (
            <p>
              Adresa uložena.
              {outcome.status !== 'OK' ? ' Oficiální hodnocení zatím nejsou potvrzená.' : ''}
            </p>
          ) : null}
        </div>
      ) : null}
      <ErrorBox error={failure} />
      <ExternalLink href={copy.consoleUrl}>Otevřít Play Console</ExternalLink>
      <WizardActions>
        {saved ? (
          <button type="button" onClick={onDone}>
            Zobrazit souhrn
          </button>
        ) : (
          <button type="button" disabled={!valid || busy} onClick={() => void run()}>
            {busy ? 'Ověřujeme a ukládáme…' : 'Ověřit a uložit exporty'}
          </button>
        )}
        {!saved && outcome && !outcome.worthSaving && outcome.status !== 'MISSING' ? (
          <button
            type="button"
            className="secondary"
            disabled={busy}
            onClick={() => void run(true)}
          >
            Uložit i tak
          </button>
        ) : null}
        <button type="button" className="secondary" disabled={busy} onClick={onBack}>
          ← Zpět
        </button>
        <button
          type="button"
          className="link"
          disabled={busy}
          onClick={() => {
            onDirty(false)
            onDone()
          }}
        >
          Doplnit později
        </button>
      </WizardActions>
      {!saved && outcome && !outcome.worthSaving && outcome.status !== 'MISSING' ? (
        <p className="small muted">
          „Uložit i tak“ uloží pouze adresu. Přístup k oficiálním hodnocením tím nebude potvrzený.
        </p>
      ) : null}
    </>
  )
}
