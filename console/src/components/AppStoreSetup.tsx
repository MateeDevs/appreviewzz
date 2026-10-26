import { useEffect, useId, useRef, useState } from 'react'
import {
  useAddCredential,
  useApps,
  useAttachCredential,
  useCreateApp,
  useCredentials,
  useStoreApps,
  useValidateCredential,
} from '../api/hooks'
import type { App, Credential } from '../api/types'
import { ErrorBox, Field, Loading } from './ui'
import { appStoreCopy as copy, isIssuerId, keyIdFromFileName } from './connectStoreCopy'
import { WizardActions, WizardActivity, WizardHeading } from './SetupWizardFrame'
import {
  ExternalLink,
  Steplist,
  StoreProgress,
  validationMessage,
  type StoreSetupProps,
} from './storeSetupShared'

export function AppStoreSetup({
  org,
  app,
  allowMultiple = false,
  active,
  onBack,
  onDone,
  onBusy,
  onDirty,
}: StoreSetupProps & { app?: App; allowMultiple?: boolean }) {
  const credentials = useCredentials(org)
  const [step, setStep] = useState<'create' | 'upload' | 'pick'>('create'),
    [credential, setCredential] = useState<Credential | null>(null)
  const [draft, setDraft] = useState({ fileName: '', content: '', keyId: '', issuerId: '' })
  const keys = credentials.data?.filter((key) => key.type === 'ASC_API_KEY') ?? []
  return (
    <div className="setup-step stack">
      <StoreProgress
        steps={['Příprava klíče', 'Nahrání', 'Aplikace a ověření']}
        current={['create', 'upload', 'pick'].indexOf(step)}
      />
      {step === 'create' ? (
        <>
          <WizardHeading eyebrow="APP STORE CONNECT / PŘÍPRAVA" title={copy.create.heading}>
            {copy.create.lead}
          </WizardHeading>
          {keys.length ? (
            <div className="notice stack">
              <strong>Použít uložený klíč</strong>
              <p>Soubor znovu nepotřebujete. Přístup ke konkrétní aplikaci ověříme znovu.</p>
              {keys.map((key) => (
                <button
                  type="button"
                  className="secondary"
                  key={key.id}
                  onClick={() => {
                    setCredential(key)
                    setStep('pick')
                  }}
                >
                  {key.label} · {key.hint ?? 'týmový klíč'}
                </button>
              ))}
            </div>
          ) : null}
          <ExternalLink href={copy.integrationsUrl}>Otevřít App Store Connect</ExternalLink>
          <Steplist items={copy.create.steps} />
          <details className="optional">
            <summary>Nemáte přístup nebo se nabídka liší?</summary>
            {copy.create.warnings.map((w) => (
              <p key={w}>{w}</p>
            ))}
            <p>
              Průvodce používá týmové klíče s Issuer ID. Individuální klíče dědí oprávnění svého
              uživatele; jejich ruční konfigurace zůstává v pokročilém nastavení klíčů.
            </p>
          </details>
          <ErrorBox error={credentials.error} />
          <WizardActions>
            <button type="button" onClick={() => setStep('upload')}>
              Klíč už mám
            </button>
            <button type="button" className="secondary" onClick={onBack}>
              ← Zpět
            </button>
            <button type="button" className="link" onClick={onDone}>
              Dokončit později
            </button>
          </WizardActions>
        </>
      ) : null}
      {step === 'upload' ? (
        <AppStoreUpload
          org={org}
          draft={draft}
          setDraft={setDraft}
          onBusy={onBusy}
          onDirty={onDirty}
          onBack={() => setStep('create')}
          onDone={(key) => {
            setCredential(key)
            setDraft({ fileName: '', content: '', keyId: '', issuerId: '' })
            onDirty(false)
            setStep('pick')
          }}
        />
      ) : null}
      {credential ? (
        <div hidden={step !== 'pick'}>
          <WizardActivity active={step === 'pick'}>
            <AppStorePick
              key={credential.id}
              allowMultiple={allowMultiple}
              org={org}
              credentialId={credential.id}
              app={app}
              active={active && step === 'pick'}
              onBusy={onBusy}
              onBack={() => setStep('create')}
              onDone={onDone}
            />
          </WizardActivity>
        </div>
      ) : null}
    </div>
  )
}

type KeyDraft = { fileName: string; content: string; keyId: string; issuerId: string }
function AppStoreUpload({
  org,
  draft,
  setDraft,
  onBusy,
  onDirty,
  onBack,
  onDone,
}: {
  org: string
  draft: KeyDraft
  setDraft: (draft: KeyDraft) => void
  onBusy: (busy: boolean) => void
  onDirty: (dirty: boolean) => void
  onBack: () => void
  onDone: (key: Credential) => void
}) {
  const formId = useId()
  const add = useAddCredential(org)
  const [fileError, setFileError] = useState<string | null>(null),
    [failure, setFailure] = useState<unknown>(null),
    [reading, setReading] = useState(false),
    [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null),
    sequence = useRef(0),
    lock = useRef(false),
    mounted = useRef(true)
  const latest = useRef(draft)
  useEffect(() => {
    latest.current = draft
  }, [draft])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    onBusy(add.isPending || reading)
  }, [add.isPending, reading, onBusy])
  useEffect(() => {
    onDirty(!!(draft.content || draft.fileName || draft.keyId || draft.issuerId))
  }, [draft, onDirty])
  const take = async (file: File) => {
    const token = ++sequence.current
    setFailure(null)
    setFileError(null)
    setReading(false)
    const next = {
      ...latest.current,
      fileName: file.name,
      content: '',
      keyId: keyIdFromFileName(file.name) ?? '',
    }
    setDraft(next)
    if (!/\.p8$/i.test(file.name)) {
      setFileError('Vyberte soubor s příponou .p8 stažený z App Store Connect.')
      return
    }
    if (!file.size || file.size > 64 * 1024) {
      setFileError('Soubor je prázdný nebo příliš velký. Klíč .p8 může mít nejvýše 64 kB.')
      return
    }
    setReading(true)
    try {
      const content = await file.text()
      if (token !== sequence.current || !mounted.current) return
      const error = content.trim().startsWith('{')
        ? copy.upload.looksLikeJson
        : /^-----BEGIN PRIVATE KEY-----\s+[A-Za-z0-9+/=\s]+\s+-----END PRIVATE KEY-----\s*$/.test(
              content.trim(),
            )
          ? null
          : copy.upload.notAKey
      setFileError(error)
      setDraft({
        ...latest.current,
        fileName: file.name,
        keyId: next.keyId,
        content: error ? '' : content,
      })
    } catch {
      if (token === sequence.current && mounted.current)
        setFileError(
          'Soubor se nepodařilo přečíst. Zkuste jej vybrat znovu nebo stáhněte původní klíč.',
        )
    } finally {
      if (token === sequence.current && mounted.current) setReading(false)
    }
  }
  const validKey = /^[A-Z0-9]{10}$/.test(draft.keyId.trim()),
    validIssuer = isIssuerId(draft.issuerId)
  const ready = !!draft.content && !fileError && validKey && validIssuer && !reading
  return (
    <form
      id={formId}
      className="stack"
      onSubmit={(event) => {
        event.preventDefault()
        if (!ready || lock.current) return
        lock.current = true
        setFailure(null)
        void add
          .mutateAsync({
            type: 'asc',
            deduplicate: true,
            label: `App Store Connect · ${draft.keyId.trim()}`,
            content: draft.content,
            keyId: draft.keyId.trim(),
            issuerId: draft.issuerId.trim(),
          })
          .then(
            (key) => {
              if (mounted.current) onDone(key)
            },
            (error) => {
              if (mounted.current) setFailure(error)
            },
          )
          .finally(() => {
            lock.current = false
          })
      }}
    >
      <WizardHeading eyebrow="APP STORE CONNECT / SOUBOR" title={copy.upload.heading}>
        Vyberte původní soubor .p8 a identifikátory ze stejného týmu. Soubor před uložením zůstává
        jen v paměti této stránky; po obnovení jej musíte vybrat znovu.
      </WizardHeading>
      <button
        type="button"
        className={dragging ? 'dropzone dragging' : 'dropzone'}
        disabled={add.isPending || reading}
        onClick={() => input.current?.click()}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          if (add.isPending) return
          const file = event.dataTransfer.files[0]
          if (file) void take(file)
        }}
      >
        {draft.fileName || copy.upload.drop}
        <span className="hint">
          {draft.fileName ? 'Kliknutím soubor nahradíte' : 'Soubor .p8 · nejvýše 64 kB'}
        </span>
      </button>
      <input
        ref={input}
        type="file"
        accept=".p8"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) void take(file)
          event.target.value = ''
        }}
      />
      {fileError ? (
        <p className="error" role="alert">
          {fileError}
        </p>
      ) : null}
      <Field label={copy.upload.keyIdLabel} hint={copy.upload.keyIdHint}>
        <input
          value={draft.keyId}
          disabled={add.isPending}
          onChange={(event) => setDraft({ ...draft, keyId: event.target.value.toUpperCase() })}
          autoComplete="off"
          spellCheck={false}
          maxLength={10}
          required
        />
      </Field>
      {draft.keyId && !validKey ? (
        <p className="error" role="alert">
          Key ID musí mít deset písmen A–Z nebo číslic.
        </p>
      ) : null}
      <Field label={copy.upload.issuerLabel} hint={copy.upload.issuerHint}>
        <input
          value={draft.issuerId}
          disabled={add.isPending}
          onChange={(event) => setDraft({ ...draft, issuerId: event.target.value })}
          autoComplete="off"
          spellCheck={false}
          required
        />
      </Field>
      {draft.issuerId && !validIssuer ? (
        <p className="error" role="alert">
          {copy.upload.issuerInvalid}
        </p>
      ) : null}
      <p className="notice">
        Server klíč zašifruje před uložením. Ve výpisu vrací pouze metadata a otisk, nikdy obsah
        klíče. Po uložení jej můžete znovu použít pro další aplikace.
      </p>
      <ErrorBox error={failure} />
      <WizardActions>
        <button type="submit" form={formId} disabled={!ready || add.isPending}>
          {add.isPending ? 'Ukládáme klíč…' : 'Nahrát klíč'}
        </button>
        <button
          type="button"
          className="secondary"
          disabled={add.isPending || reading}
          onClick={onBack}
        >
          ← Zpět
        </button>
      </WizardActions>
    </form>
  )
}

interface PickOutcome {
  identifier: string
  name: string
  appId?: string
  error?: string
}
function AppStorePick({
  org,
  credentialId,
  app,
  allowMultiple,
  active,
  onBusy,
  onBack,
  onDone,
}: {
  org: string
  credentialId: string
  app?: App
  allowMultiple: boolean
  active: boolean
  onBusy: (busy: boolean) => void
  onBack: () => void
  onDone: () => void
}) {
  const storeApps = useStoreApps(org, credentialId),
    apps = useApps(org),
    create = useCreateApp(org),
    attach = useAttachCredential(org),
    validate = useValidateCredential(org)
  const [selected, setSelected] = useState<Set<string>>(
      new Set(app?.ascAppId ? [app.ascAppId] : []),
    ),
    [outcomes, setOutcomes] = useState<PickOutcome[]>([]),
    [saving, setSaving] = useState(false)
  const lock = useRef(false),
    mounted = useRef(true),
    created = useRef(new Map<string, App>())
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    onBusy(saving)
  }, [saving, onBusy])
  const available = (storeApps.data ?? []).filter(
    (candidate) => !app || allowMultiple || candidate.identifier === app.ascAppId,
  )
  const chosen = available.filter((candidate) => selected.has(candidate.identifier))
  const successful = new Set(outcomes.filter((o) => !o.error).map((o) => o.identifier))
  const failed = outcomes.filter((o) => o.error)
  const confirm = async () => {
    if (lock.current || !chosen.length) return
    lock.current = true
    setSaving(true)
    const results = outcomes.filter((o) => !o.error)
    const refreshed = await apps.refetch()
    const existing = new Map(
      (refreshed.data ?? []).filter((a) => a.ascAppId).map((a) => [a.ascAppId!, a]),
    )
    for (const candidate of chosen.filter((c) => !successful.has(c.identifier))) {
      try {
        let target = created.current.get(candidate.identifier) ?? existing.get(candidate.identifier)
        if (!target) {
          try {
            target = await create.mutateAsync({
              name: candidate.name,
              ascAppId: candidate.identifier,
              gpPackageName: null,
            })
          } catch (error) {
            const reconciled = await apps.refetch()
            target = reconciled.data?.find((a) => a.ascAppId === candidate.identifier)
            if (!target) throw error
          }
          created.current.set(candidate.identifier, target)
        }
        if (target.competitor)
          throw new Error(
            'Konkurenční aplikaci nelze připojit jako vlastní. Otevřete její nastavení.',
          )
        await attach.mutateAsync({ appId: target.id, credentialId })
        const checked = await validate.mutateAsync({ appId: target.id, credentialId })
        if (!checked.valid) throw new Error(validationMessage(checked.kind))
        results.push({ identifier: candidate.identifier, name: candidate.name, appId: target.id })
      } catch (error) {
        results.push({
          identifier: candidate.identifier,
          name: candidate.name,
          error:
            error instanceof Error && error.message.startsWith('Konkurenční')
              ? error.message
              : 'Přístup k této aplikaci se nepodařilo ověřit. Zkontrolujte tým a oprávnění klíče; potom zkuste tuto položku znovu.',
        })
      }
      if (!mounted.current) break
      setOutcomes([...results])
    }
    lock.current = false
    if (mounted.current) setSaving(false)
  }
  if (!active) return null
  return (
    <div className="stack">
      <WizardHeading
        eyebrow="APP STORE CONNECT / APLIKACE"
        title={
          saving
            ? 'Ověřujeme vybrané aplikace'
            : outcomes.length
              ? failed.length
                ? 'Část připojení vyžaduje kontrolu'
                : 'Přístup k aplikacím je ověřený'
              : 'Vyberte aplikace k připojení'
        }
      >
        {app
          ? `Připojujete ${app.name}. Ověříme přístup přímo k této aplikaci.`
          : 'Vyberte jednu nebo více aplikací dostupných tomuto klíči. Existující aplikace znovu nezakládáme.'}
      </WizardHeading>
      {storeApps.isPending ? <Loading what="Načítáme aplikace z App Store Connect…" /> : null}
      {storeApps.error ? (
        <div className="error" role="alert">
          Seznam aplikací se nepodařilo načíst. Zkontrolujte platnost klíče, Issuer ID a tým v App
          Store Connect. Klíč zůstává bezpečně uložený.
        </div>
      ) : null}
      {storeApps.isSuccess &&
      ((!app && !available.length) ||
        (app && !storeApps.data.some((candidate) => candidate.identifier === app.ascAppId))) ? (
        <div className="error" role="alert">
          {app ? `Klíč nevidí aplikaci ${app.name}.` : 'Klíč nevidí žádnou dostupnou aplikaci.'}{' '}
          Zkontrolujte správný tým, Team Key a oprávnění. Nový upload není nutný, pokud opravíte
          přístup ke stejnému klíči.
        </div>
      ) : null}
      {available.map((candidate) => (
        <label className="pick setup-app-pick" key={candidate.identifier}>
          <input
            type="checkbox"
            disabled={
              saving ||
              successful.has(candidate.identifier) ||
              (!!app && candidate.identifier === app.ascAppId)
            }
            checked={selected.has(candidate.identifier)}
            onChange={(event) =>
              setSelected((previous) => {
                const next = new Set(previous)
                if (event.target.checked) next.add(candidate.identifier)
                else next.delete(candidate.identifier)
                return next
              })
            }
          />
          <span>
            <strong>{candidate.name}</strong>
            <small>
              {candidate.bundleId ?? candidate.identifier} · App ID {candidate.identifier}
            </small>
            {successful.has(candidate.identifier) ? (
              <span className="small">Přístup ověřený ✓</span>
            ) : null}
          </span>
        </label>
      ))}
      {outcomes.length ? (
        <ul className="setup-outcomes" aria-live="polite">
          {outcomes.map((result) => (
            <li key={result.identifier}>
              <strong>{result.name}</strong>
              <p>
                {result.error ?? 'Přístup k aplikaci ověřený. Import recenzí je samostatný krok.'}
              </p>
            </li>
          ))}
        </ul>
      ) : null}
      {saving ? <Loading what="Připojujeme a ověřujeme jednotlivé aplikace…" /> : null}
      <WizardActions>
        {outcomes.length && !failed.length && !saving ? (
          <button type="button" onClick={onDone}>
            Zobrazit souhrn
          </button>
        ) : (
          <button
            type="button"
            disabled={
              saving || !chosen.some((c) => !successful.has(c.identifier)) || !storeApps.isSuccess
            }
            onClick={() => void confirm()}
          >
            {saving
              ? 'Ověřujeme aplikace…'
              : failed.length
                ? 'Zkusit znovu neúspěšné'
                : 'Sledovat vybrané'}
          </button>
        )}
        <button type="button" className="secondary" disabled={saving} onClick={onBack}>
          ← Zpět
        </button>
        <button type="button" className="link" disabled={saving} onClick={onDone}>
          Dokončit později
        </button>
      </WizardActions>
      <button
        type="button"
        className="secondary"
        disabled={saving || storeApps.isFetching}
        onClick={() => void storeApps.refetch()}
      >
        Zkusit načíst aplikace znovu
      </button>
    </div>
  )
}
