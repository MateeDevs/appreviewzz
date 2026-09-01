import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  useAddCredential,
  useApps,
  useAttachCredential,
  useCreateApp,
  useProvisionGooglePlay,
  useResolveStoreLinks,
  useStoreApps,
  useUpdateApp,
  useValidateCredential,
} from '../api/hooks'
import type { App, Credential, Platform, StoreApp } from '../api/types'
import { Badge, ErrorBox, Field, Loading, Modal } from './ui'
import { appStoreCopy, googlePlayCopy, isIssuerId, keyIdFromFileName } from './connectStoreCopy'

/**
 * Napojení storu krok za krokem.
 *
 * Celý dialog stojí na jednom rozdílu mezi obchody:
 *
 * - **Google Play** service account vyrobíme my, takže klientovi zbývá jediná věc, kterou
 *   za něj udělat nejde — pozvat náš e-mail do Play Console. Zbytek (klíč, jeho uložení,
 *   přiřazení k appce, ověření) běží pod rukama.
 * - **App Store Connect** klíč vydat programově nejde a Apple ho ukáže jen jednou, takže
 *   klienta vedeme jeho konzolí. Zato pak umíme vypsat aplikace, na které klíč dosáhne —
 *   Apple ID se neopisuje, odklikne se.
 *
 * Ruční nahrání klíče zůstává v kartě „Klíče ke storu" pod „Pokročilé": enterprise s vlastním
 * service accountem ani individuální klíč z ASC se tímhle dialogem obsloužit nedají.
 */
export function ConnectStoreWizard({
  org,
  platform,
  app,
  onClose,
}: {
  org: string
  platform: Platform
  /** Appka, ze které se dialog otevřel. Bez ní si ji klient v prvním kroku přidá. */
  app?: App
  onClose: () => void
}) {
  return platform === 'ANDROID' ? (
    <GooglePlayWizard org={org} app={app} onClose={onClose} />
  ) : (
    <AppStoreWizard org={org} app={app} onClose={onClose} />
  )
}

/** Ukazatel kroků. Tentýž tvar jako v průvodci nastavením, aby to bylo poznat na první pohled. */
function Steps({ titles, current }: { titles: string[]; current: number }) {
  return (
    <div className="steps">
      {titles.map((title, index) => (
        <span key={title} className={index === current ? 'step current' : index < current ? 'step done' : 'step'}>
          {index < current ? '✓' : index + 1}. {title}
        </span>
      ))}
    </div>
  )
}

/**
 * Hodnota, kterou má člověk přenést do cizí konzole. Monospace, protože se čte znak po znaku,
 * a tlačítko, protože ručně opsaný e-mail service accountu je nejlepší způsob, jak si pozvánku
 * rozbít o překlep.
 */
function CopyValue({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 1600)
    return () => clearTimeout(timer)
  }, [copied])

  return (
    <div className="copy-value">
      <code>{value}</code>
      <button
        type="button"
        className="secondary"
        onClick={() => {
          void navigator.clipboard?.writeText(value).then(() => setCopied(true))
        }}
      >
        {copied ? 'Zkopírováno' : 'Kopírovat'}
      </button>
    </div>
  )
}

function Steplist({ items }: { items: readonly string[] }) {
  return (
    <ol className="howto">
      {items.map((item) => (
        <li key={item}>{item}</li>
      ))}
    </ol>
  )
}

// ---------------------------------------------------------------- Google Play

type GooglePlayStep = 'app' | 'invite' | 'check'

function GooglePlayWizard({ org, app, onClose }: { org: string; app?: App; onClose: () => void }) {
  const [target, setTarget] = useState<App | undefined>(app)
  const [credentialId, setCredentialId] = useState<string | null>(null)
  const [step, setStep] = useState<GooglePlayStep>(app ? 'invite' : 'app')
  const titles = app ? ['Pozvánka', 'Kontrola'] : ['Aplikace', 'Pozvánka', 'Kontrola']
  const index = step === 'app' ? 0 : (step === 'invite' ? 0 : 1) + (app ? 0 : 1)

  return (
    <Modal title={googlePlayCopy.title} onClose={onClose}>
      <Steps titles={titles} current={index} />
      {step === 'app' ? (
        <PickApp
          org={org}
          platform="ANDROID"
          onPicked={(picked) => {
            setTarget(picked)
            setStep('invite')
          }}
          onClose={onClose}
        />
      ) : null}
      {step === 'invite' && target ? (
        <GooglePlayInvite
          org={org}
          app={target}
          onDone={(id) => {
            setCredentialId(id)
            setStep('check')
          }}
          onClose={onClose}
        />
      ) : null}
      {step === 'check' && target && credentialId ? (
        <GooglePlayCheck org={org} app={target} credentialId={credentialId} onClose={onClose} />
      ) : null}
    </Modal>
  )
}

/**
 * Vyrobení účtu a pozvánka.
 *
 * Účet se vyrábí hned při otevření kroku, ne až na tlačítko: klient nemá co rozhodovat
 * a čekat na spinner po kliknutí by bylo o vteřinu déle. Endpoint je idempotentní, takže
 * návrat do kroku nic dalšího nezaloží.
 */
function GooglePlayInvite({
  org,
  app,
  onDone,
  onClose,
}: {
  org: string
  app: App
  onDone: (credentialId: string) => void
  onClose: () => void
}) {
  const provision = useProvisionGooglePlay(org)
  const attach = useAttachCredential(org)
  const [credential, setCredential] = useState<Credential | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const provisionOnce = useRef(false)
  const run = provision.mutateAsync

  useEffect(() => {
    if (provisionOnce.current) return
    provisionOnce.current = true
    // Výsledek si držíme sami: callbacky předané do `mutate` react-query zahodí, když se
    // komponenta mezitím přerenderuje kvůli invalidaci po přidání appky — a dialog by
    // navždy zůstal viset na spinneru.
    run(undefined).then(setCredential, setFailure)
  }, [run])

  if (failure) {
    return (
      <>
        <ErrorBox error={failure} />
        <div className="row" style={{ marginTop: '1rem' }}>
          <button type="button" className="secondary" onClick={onClose}>
            Zavřít
          </button>
        </div>
      </>
    )
  }
  if (!credential?.hint) return <Loading what="Vyrábíme service account…" />

  return (
    <div className="stack">
      <div>
        <h3>{googlePlayCopy.invite.heading}</h3>
        <p className="muted">{googlePlayCopy.invite.lead}</p>
      </div>

      <Field label={googlePlayCopy.invite.emailLabel}>
        <CopyValue value={credential.hint} />
      </Field>

      <Steplist items={googlePlayCopy.invite.steps} />
      <p className="small muted">{googlePlayCopy.invite.note}</p>

      <ErrorBox error={attach.error} />
      <div className="row">
        <a className="button" href={googlePlayCopy.consoleUrl} target="_blank" rel="noreferrer noopener">
          {googlePlayCopy.invite.openConsole}
        </a>
        <button
          type="button"
          disabled={attach.isPending}
          onClick={() => {
            // Klíč se k appce přiřadí tiše: bez toho by ověření nemělo proti čemu běžet
            // a klient by v dalším kroku řešil krok, o kterém nemá jak vědět.
            attach.mutate({ appId: app.id, credentialId: credential.id }, { onSuccess: () => onDone(credential.id) })
          }}
        >
          Pozvánku jsem odeslal
        </button>
      </div>
    </div>
  )
}

/** Kolikrát a jak často se po odeslání pozvánky ptáme, než to předáme jobu na pozadí. */
const CHECK_INTERVAL_MS = 10_000
const CHECK_ATTEMPTS = 12

function GooglePlayCheck({
  org,
  app,
  credentialId,
  onClose,
}: {
  org: string
  app: App
  credentialId: string
  onClose: () => void
}) {
  const validate = useValidateCredential(org)
  const [state, setState] = useState<'checking' | 'ok' | 'waiting'>('checking')
  const [message, setMessage] = useState<string | null>(null)
  const attempts = useRef(0)
  const check = validate.mutateAsync

  const runCheck = useCallback(async () => {
    const outcome = await check({ appId: app.id, credentialId })
    setMessage(outcome.message)
    if (outcome.valid) setState('ok')
    return outcome.valid
  }, [app.id, check, credentialId])

  useEffect(() => {
    if (state !== 'checking') return
    let cancelled = false

    const tick = async () => {
      if (cancelled) return
      const ok = await runCheck().catch(() => false)
      if (cancelled || ok) return
      attempts.current += 1
      // Po dvou minutách nemá smysl držet klienta u dialogu — dál to hlídá job na pozadí.
      if (attempts.current >= CHECK_ATTEMPTS) setState('waiting')
    }

    void tick()
    const timer = setInterval(() => void tick(), CHECK_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [runCheck, state])

  return (
    <div className="stack">
      <div>
        <h3>{googlePlayCopy.check.heading}</h3>
        <p className="muted">{googlePlayCopy.check.lead}</p>
      </div>

      {state === 'checking' ? <Loading what={googlePlayCopy.check.checking} /> : null}
      {state === 'ok' ? (
        <div className="notice">
          <Badge tone="ok">funguje</Badge> {googlePlayCopy.check.ok}
        </div>
      ) : null}
      {state === 'waiting' ? <div className="notice">{googlePlayCopy.check.background}</div> : null}
      {/* Hlášku z konektoru ukazujeme doslova: „nemá oprávnění" versus „aplikaci nezná" je
          přesně ten rozdíl, podle kterého klient pozná, co v Play Console udělal jinak. */}
      {state !== 'ok' && message ? <p className="small muted">{message}</p> : null}

      <ReportingBucket org={org} app={app} />

      <div className="row">
        <button type="button" onClick={onClose}>
          {state === 'ok' ? 'Hotovo' : 'Zavřít'}
        </button>
        {state === 'waiting' ? (
          <button
            type="button"
            className="secondary"
            onClick={() => {
              attempts.current = 0
              setState('checking')
            }}
          >
            {googlePlayCopy.check.retry}
          </button>
        ) : null}
      </div>
    </div>
  )
}

/** Nepovinné: oficiální hodnocení a historie z exportu Play Console. */
function ReportingBucket({ org, app }: { org: string; app: App }) {
  const update = useUpdateApp(org)
  const [value, setValue] = useState(app.gpReportingBucket ?? '')
  const [saved, setSaved] = useState(false)
  const invalid = value.trim() !== '' && !value.trim().startsWith('gs://')

  return (
    <details className="optional">
      <summary>{googlePlayCopy.reporting.heading}</summary>
      <p className="muted">{googlePlayCopy.reporting.lead}</p>
      <Steplist items={googlePlayCopy.reporting.steps} />
      <Field label={googlePlayCopy.reporting.fieldLabel} hint={googlePlayCopy.reporting.fieldHint}>
        <input
          value={value}
          onChange={(event) => {
            setValue(event.target.value)
            setSaved(false)
          }}
          placeholder="gs://pubsite_prod_rev_01234567890123456789"
        />
      </Field>
      {invalid ? <div className="error">{googlePlayCopy.reporting.invalid}</div> : null}
      <p className="small muted">{googlePlayCopy.reporting.note}</p>
      <ErrorBox error={update.error} />
      <div className="row">
        <button
          type="button"
          className="secondary"
          disabled={invalid || update.isPending}
          onClick={() =>
            update.mutate(
              { id: app.id, body: { gpReportingBucket: value.trim() === '' ? null : value.trim() } },
              { onSuccess: () => setSaved(true) },
            )
          }
        >
          Uložit
        </button>
        {saved ? <span className="small muted">Uloženo.</span> : null}
      </div>
    </details>
  )
}

// ---------------------------------------------------------------- App Store

type AppStoreStep = 'create' | 'upload' | 'pick'

function AppStoreWizard({ org, app, onClose }: { org: string; app?: App; onClose: () => void }) {
  const [step, setStep] = useState<AppStoreStep>('create')
  const [credentialId, setCredentialId] = useState<string | null>(null)
  const titles = ['Klíč', 'Nahrání', 'Aplikace']
  const index = step === 'create' ? 0 : step === 'upload' ? 1 : 2

  return (
    <Modal title={appStoreCopy.title} onClose={onClose}>
      <Steps titles={titles} current={index} />
      {step === 'create' ? <AppStoreCreate onDone={() => setStep('upload')} onClose={onClose} /> : null}
      {step === 'upload' ? (
        <AppStoreUpload
          org={org}
          onDone={(id) => {
            setCredentialId(id)
            setStep('pick')
          }}
        />
      ) : null}
      {step === 'pick' && credentialId ? (
        <AppStorePick org={org} credentialId={credentialId} preselect={app} onClose={onClose} />
      ) : null}
    </Modal>
  )
}

function AppStoreCreate({ onDone, onClose }: { onDone: () => void; onClose: () => void }) {
  return (
    <div className="stack">
      <div>
        <h3>{appStoreCopy.create.heading}</h3>
        <p className="muted">{appStoreCopy.create.lead}</p>
      </div>

      {appStoreCopy.create.warnings.map((warning) => (
        <div key={warning} className="notice">
          {warning}
        </div>
      ))}

      <Steplist items={appStoreCopy.create.steps} />
      <Field label="Název klíče ke zkopírování">
        <CopyValue value={appStoreCopy.create.keyName} />
      </Field>

      <div className="row">
        <a className="button" href={appStoreCopy.integrationsUrl} target="_blank" rel="noreferrer noopener">
          {appStoreCopy.create.open}
        </a>
        <button type="button" onClick={onDone}>
          Klíč mám, pokračovat
        </button>
        <button type="button" className="secondary" onClick={onClose}>
          Zrušit
        </button>
      </div>
    </div>
  )
}

/**
 * Nahrání `.p8`.
 *
 * Key ID se bere z názvu souboru (`AuthKey_XXXXXXXXXX.p8`), zbývá tedy jediné pole
 * k opsání — Issuer ID. Obsah i tvar se kontrolují **tady**, ne až odpovědí serveru:
 * prohozený soubor je nejčastější chyba a poznat ji jde bez jediného požadavku.
 */
function AppStoreUpload({ org, onDone }: { org: string; onDone: (credentialId: string) => void }) {
  const add = useAddCredential(org)
  const [fileName, setFileName] = useState('')
  const [content, setContent] = useState('')
  const [keyId, setKeyId] = useState('')
  const [issuerId, setIssuerId] = useState('')
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const take = async (file: File) => {
    setFileName(file.name)
    setContent(await file.text())
    const fromName = keyIdFromFileName(file.name)
    if (fromName) setKeyId(fromName)
  }

  const looksLikeJson = content.trim().startsWith('{')
  const contentError =
    content === ''
      ? null
      : looksLikeJson
        ? appStoreCopy.upload.looksLikeJson
        : content.includes('BEGIN PRIVATE KEY')
          ? null
          : appStoreCopy.upload.notAKey
  const issuerError = issuerId.trim() === '' || isIssuerId(issuerId) ? null : appStoreCopy.upload.issuerInvalid
  // Issuer ID je tady povinné: dialog vede tvorbou **týmového** klíče a bez něj by se klíč
  // uložil jako individuální, který výpis aplikací týmu nevrátí — a chyba by přišla o krok
  // později a nesrozumitelně. Individuální klíč patří pod „Pokročilé".
  const ready = content !== '' && contentError == null && keyId.trim().length > 0 && isIssuerId(issuerId)

  return (
    <form
      className="stack"
      onSubmit={(event) => {
        event.preventDefault()
        add.mutate(
          {
            type: 'asc',
            label: `App Store Connect · ${keyId.trim()}`,
            content,
            keyId: keyId.trim(),
            issuerId: issuerId.trim(),
          },
          { onSuccess: (credential) => onDone(credential.id) },
        )
      }}
    >
      <h3>{appStoreCopy.upload.heading}</h3>

      <button
        type="button"
        className={dragging ? 'dropzone dragging' : 'dropzone'}
        onClick={() => input.current?.click()}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          const file = event.dataTransfer.files?.[0]
          if (file) void take(file)
        }}
      >
        {fileName === '' ? appStoreCopy.upload.drop : fileName}
      </button>
      <input
        ref={input}
        type="file"
        accept=".p8,text/plain"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) void take(file)
        }}
      />
      {contentError ? <div className="error">{contentError}</div> : null}

      <Field label={appStoreCopy.upload.keyIdLabel} hint={appStoreCopy.upload.keyIdHint}>
        <input value={keyId} onChange={(event) => setKeyId(event.target.value)} required />
      </Field>
      <Field label={appStoreCopy.upload.issuerLabel} hint={appStoreCopy.upload.issuerHint}>
        <input
          value={issuerId}
          onChange={(event) => setIssuerId(event.target.value)}
          placeholder="69a6de70-0000-47e3-e053-5b8c7c11a4d1"
          required
        />
      </Field>
      {issuerError ? <div className="error">{issuerError}</div> : null}

      <ErrorBox error={add.error} />
      <div className="row">
        <button type="submit" disabled={!ready || add.isPending}>
          {add.isPending ? 'Nahrávám…' : appStoreCopy.upload.submit}
        </button>
      </div>
      <p className="small muted">
        Klíč se zašifruje ještě před uložením a z vaultu už ven nevyjde — ve výpisu uvidíš jen otisk.
      </p>
    </form>
  )
}

/** Výsledek zakládání jedné appky z výběru. Chyby se hlásí po položkách, ne jednou větou za celek. */
interface PickOutcome {
  identifier: string
  name: string
  error?: string
}

function AppStorePick({
  org,
  credentialId,
  preselect,
  onClose,
}: {
  org: string
  credentialId: string
  preselect?: App
  onClose: () => void
}) {
  const storeApps = useStoreApps(org, credentialId)
  const apps = useApps(org)
  const client = useQueryClient()
  const create = useCreateApp(org)
  const attach = useAttachCredential(org)
  const [selected, setSelected] = useState<Set<string>>(new Set(preselect?.ascAppId ? [preselect.ascAppId] : []))
  const [outcomes, setOutcomes] = useState<PickOutcome[] | null>(null)
  const [saving, setSaving] = useState(false)

  const existing = new Map((apps.data ?? []).filter((app) => app.ascAppId).map((app) => [app.ascAppId as string, app]))

  // Výpis aplikací je zároveň ověření klíče — server podle něj přepsal jeho stav. Bez tohohle
  // by karta s klíči za dialogem dál tvrdila „neověřený", i když se právě prokázal opak.
  useEffect(() => {
    if (storeApps.isPending) return
    void client.invalidateQueries({ queryKey: ['credentials', org] })
  }, [client, org, storeApps.isPending, storeApps.status])

  const confirm = async () => {
    setSaving(true)
    const results: PickOutcome[] = []
    for (const storeApp of (storeApps.data ?? []).filter((candidate) => selected.has(candidate.identifier))) {
      try {
        // Appka už v organizaci být může (přidaná odkazem ze storu) — pak se jen připojí klíč.
        // Zakládat ji znovu by narazilo na unikát na asc_app_id a vypadalo jako chyba klienta.
        const app =
          existing.get(storeApp.identifier) ??
          (await create.mutateAsync({ name: storeApp.name, ascAppId: storeApp.identifier, gpPackageName: null }))
        await attach.mutateAsync({ appId: app.id, credentialId })
        results.push({ identifier: storeApp.identifier, name: storeApp.name })
      } catch (error) {
        results.push({
          identifier: storeApp.identifier,
          name: storeApp.name,
          error: error instanceof Error ? error.message : String(error),
        })
      }
    }
    setOutcomes(results)
    setSaving(false)
  }

  if (storeApps.isPending) return <Loading what="Ptáme se Applu na aplikace…" />
  if (storeApps.error) return <ErrorBox error={storeApps.error} />

  if (outcomes) {
    const failed = outcomes.filter((outcome) => outcome.error)
    return (
      <div className="stack">
        <h3>{failed.length === 0 ? 'Hotovo' : 'Část se nepovedla'}</h3>
        <ul className="howto">
          {outcomes.map((outcome) => (
            <li key={outcome.identifier}>
              {outcome.name}
              {outcome.error ? <span className="error-inline"> — {outcome.error}</span> : ' — sledujeme'}
            </li>
          ))}
        </ul>
        <p className="small muted">{appStoreCopy.pick.moderation}</p>
        <div className="row">
          <button type="button" onClick={onClose}>
            Zavřít
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="stack">
      <div>
        <h3>{appStoreCopy.pick.heading}</h3>
        <p className="muted">{appStoreCopy.pick.lead}</p>
      </div>

      {storeApps.data?.length === 0 ? <p className="muted">{appStoreCopy.pick.empty}</p> : null}
      <div className="stack picklist">
        {storeApps.data?.map((storeApp: StoreApp) => (
          <label key={storeApp.identifier} className="pick">
            <input
              type="checkbox"
              checked={selected.has(storeApp.identifier)}
              onChange={(event) =>
                setSelected((current) => {
                  const next = new Set(current)
                  if (event.target.checked) next.add(storeApp.identifier)
                  else next.delete(storeApp.identifier)
                  return next
                })
              }
            />
            <span>
              {storeApp.name}
              <span className="small muted"> {storeApp.bundleId ?? storeApp.identifier}</span>
            </span>
            {existing.has(storeApp.identifier) ? <Badge>{appStoreCopy.pick.existing}</Badge> : null}
          </label>
        ))}
      </div>

      <p className="small muted">{appStoreCopy.pick.moderation}</p>
      <div className="row">
        <button type="button" disabled={selected.size === 0 || saving} onClick={() => void confirm()}>
          {saving ? 'Ukládám…' : appStoreCopy.pick.submit}
        </button>
        <button type="button" className="secondary" onClick={onClose}>
          Zrušit
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- společné kroky

/**
 * Přidání appky odkazem ze storu uvnitř dialogu. Je to tatáž cesta jako „Přidat aplikaci",
 * jen zúžená na jeden store — dialog o napojení Google Play nemá důvod ptát se na App Store.
 */
function PickApp({
  org,
  platform,
  onPicked,
  onClose,
}: {
  org: string
  platform: Platform
  onPicked: (app: App) => void
  onClose: () => void
}) {
  const apps = useApps(org)
  const create = useCreateApp(org)
  const resolve = useResolveStoreLinks(org)
  const resolveLinks = resolve.mutate
  const [url, setUrl] = useState('')
  const [resolved, setResolved] = useState<{ url: string; identifier: string; name: string | null } | null>(null)
  const [name, setName] = useState('')
  const nameEdited = useRef(false)

  const trimmed = url.trim()
  const current = resolved?.url === trimmed ? resolved : null
  const candidates = (apps.data ?? []).filter((app) => app.platforms.includes(platform))

  useEffect(() => {
    if (trimmed === '') return
    // Odkaz se lepí ze schránky po částech — počkáme, až psaní ustane.
    const timer = setTimeout(() => {
      resolveLinks(
        platform === 'ANDROID' ? { googlePlayUrl: trimmed } : { appStoreUrl: trimmed },
        {
          onSuccess: (result) => {
            const store = platform === 'ANDROID' ? result.googlePlay : result.appStore
            if (!store) return
            setResolved({ url: trimmed, identifier: store.identifier, name: store.name })
            if (store.name && !nameEdited.current) setName(store.name)
          },
        },
      )
    }, 500)
    return () => clearTimeout(timer)
  }, [platform, resolveLinks, trimmed])

  return (
    <div className="stack">
      <div>
        <h3>{googlePlayCopy.app.heading}</h3>
        <p className="muted">{googlePlayCopy.app.hint}</p>
      </div>

      {candidates.length > 0 ? (
        <div className="chips">
          {candidates.map((app) => (
            <button key={app.id} type="button" className="chip" onClick={() => onPicked(app)}>
              {app.name}
            </button>
          ))}
        </div>
      ) : null}

      <Field label="Odkaz na store">
        <input
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder={
            platform === 'ANDROID'
              ? 'https://play.google.com/store/apps/details?id=cz.matee.appka'
              : 'https://apps.apple.com/cz/app/appka/id1234567890'
          }
          autoFocus
        />
      </Field>
      {current?.identifier ? <p className="small muted">Našli jsme {current.identifier}.</p> : null}

      <Field label="Název">
        <input
          value={name}
          onChange={(event) => {
            nameEdited.current = true
            setName(event.target.value)
          }}
        />
      </Field>

      <ErrorBox error={create.error ?? resolve.error} />
      <div className="row">
        <button
          type="button"
          disabled={!current?.identifier || name.trim() === '' || create.isPending}
          onClick={() =>
            create.mutate(
              {
                name: name.trim(),
                gpPackageName: platform === 'ANDROID' ? (current?.identifier ?? null) : null,
                ascAppId: platform === 'IOS' ? (current?.identifier ?? null) : null,
              },
              { onSuccess: onPicked },
            )
          }
        >
          Pokračovat
        </button>
        <button type="button" className="secondary" onClick={onClose}>
          Zrušit
        </button>
      </div>
    </div>
  )
}
