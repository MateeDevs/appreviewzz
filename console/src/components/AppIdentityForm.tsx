import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useApps, useCreateApp, useResolveStoreLinks } from '../api/hooks'
import type { App, ResolvedStore, StoreResolution } from '../api/types'
import { ErrorBox, Field } from './ui'
import { WizardActions, WizardHeading } from './SetupWizardFrame'

export function AppIdentityForm({
  org,
  ownOnly = false,
  onSaved,
  onDirty,
  onBusy,
  onClose,
}: {
  org: string
  ownOnly?: boolean
  onSaved: (app: App) => void
  onDirty?: (value: boolean) => void
  onBusy?: (value: boolean) => void
  onClose?: () => void
}) {
  const formId = useId()
  const apps = useApps(org),
    create = useCreateApp(org),
    resolve = useResolveStoreLinks(org)
  const lookup = resolve.mutateAsync
  const [gp, setGp] = useState(''),
    [asc, setAsc] = useState(''),
    [name, setName] = useState('')
  const [competitor, setCompetitor] = useState(false),
    [manual, setManual] = useState(false)
  const [gpId, setGpId] = useState(''),
    [ascId, setAscId] = useState(''),
    [confirmed, setConfirmed] = useState(false)
  const [resolution, setResolution] = useState<StoreResolution | null>(null)
  const [looking, setLooking] = useState(false),
    [failure, setFailure] = useState<unknown>(null)
  const edited = useRef(false),
    saving = useRef(false),
    mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    onDirty?.(!!(gp || asc || name || gpId || ascId))
  }, [gp, asc, name, gpId, ascId, onDirty])
  useEffect(() => {
    onBusy?.(create.isPending)
  }, [create.isPending, onBusy])
  useEffect(() => {
    let cancelled = false
    if (manual || (!gp.trim() && !asc.trim())) return
    const timer = setTimeout(() => {
      setLooking(true)
      lookup({ googlePlayUrl: gp.trim() || undefined, appStoreUrl: asc.trim() || undefined })
        .then(
          (result) => {
            if (cancelled) return
            setResolution(result)
            const suggestion = result.googlePlay?.name ?? result.appStore?.name
            if (suggestion && !edited.current) setName(suggestion)
          },
          (error) => {
            if (!cancelled) setFailure(error)
          },
        )
        .finally(() => {
          if (!cancelled) setLooking(false)
        })
    }, 450)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [gp, asc, manual, lookup])
  const ids = manual
    ? { gp: gpId.trim(), asc: ascId.trim() }
    : { gp: resolution?.googlePlay?.identifier ?? '', asc: resolution?.appStore?.identifier ?? '' }
  const stores = [resolution?.googlePlay, resolution?.appStore].filter(
    (s): s is ResolvedStore => !!s,
  )
  const different =
    !manual &&
    stores.length === 2 &&
    !!stores[0]?.name &&
    !!stores[1]?.name &&
    stores[0].name !== stores[1].name
  const matching = (apps.data ?? []).filter(
    (a) => (ids.gp && a.gpPackageName === ids.gp) || (ids.asc && a.ascAppId === ids.asc),
  )
  const fullyMatches = (app: App) =>
    (!ids.gp || app.gpPackageName === ids.gp) &&
    (!ids.asc || app.ascAppId === ids.asc) &&
    app.competitor === competitor
  const validIds = manual
    ? (!ids.gp || /^[a-zA-Z][\w]*(?:\.[\w]+)+$/.test(ids.gp)) && (!ids.asc || /^\d+$/.test(ids.asc))
    : (!gp.trim() || !!ids.gp) && (!asc.trim() || !!ids.asc)
  const ready =
    !!(ids.gp || ids.asc) && !!name.trim() && validIds && (!different || confirmed) && !looking
  const changed = () => {
    setResolution(null)
    setConfirmed(false)
    setFailure(null)
    setLooking(false)
  }
  const submit = async () => {
    if (!ready || saving.current) return
    if (matching.length) {
      const match = matching.find(fullyMatches)
      if (match) {
        onDirty?.(false)
        onSaved(match)
      }
      return
    }
    saving.current = true
    setFailure(null)
    try {
      const result = await create.mutateAsync({
        name: name.trim(),
        gpPackageName: ids.gp || null,
        ascAppId: ids.asc || null,
        competitor,
      })
      if (mounted.current) {
        onDirty?.(false)
        onSaved(result)
      }
    } catch (error) {
      // Includes a lost success response: reconcile persisted identities before offering retry.
      const refreshed = await apps.refetch()
      const existing = refreshed.data?.find(
        (a) =>
          ((ids.gp && a.gpPackageName === ids.gp) || (ids.asc && a.ascAppId === ids.asc)) &&
          fullyMatches(a),
      )
      if (!mounted.current) return
      if (existing) {
        onDirty?.(false)
        onSaved(existing)
      } else setFailure(error)
    } finally {
      saving.current = false
    }
  }
  return (
    <form
      id={formId}
      className="setup-step stack"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <WizardHeading eyebrow="01 / APLIKACE" title="Kterou aplikaci přidáváte?">
        Vložte odkaz na její stránku ve storu. Pokud má aplikace Android i iOS, přidejte oba odkazy.
      </WizardHeading>
      {!ownOnly ? (
        <fieldset className="setup-kind">
          <legend className="sr-only">Typ aplikace</legend>
          <label className={competitor ? '' : 'selected'}>
            <input
              type="radio"
              name="kind"
              checked={!competitor}
              onChange={() => setCompetitor(false)}
            />
            <span>
              <strong>Vlastní aplikace</strong>
              <small>Přístup k recenzím a odpovídání</small>
            </span>
          </label>
          <label className={competitor ? 'selected' : ''}>
            <input
              type="radio"
              name="kind"
              checked={competitor}
              onChange={() => setCompetitor(true)}
            />
            <span>
              <strong>Sledovat konkurenci</strong>
              <small>Veřejná data bez klíčů</small>
            </span>
          </label>
        </fieldset>
      ) : null}
      {!manual ? (
        <>
          <Field label="Odkaz na Google Play" hint="Nepovinné, pokud aplikace nemá Android.">
            <input
              value={gp}
              onChange={(e) => {
                setGp(e.target.value)
                changed()
              }}
              placeholder="https://play.google.com/store/apps/details?id=cz.firma.aplikace"
              autoComplete="off"
            />
          </Field>
          <StoreHint store={resolution?.googlePlay} />
          <Field label="Odkaz na App Store" hint="Nepovinné, pokud aplikace nemá iOS.">
            <input
              value={asc}
              onChange={(e) => {
                setAsc(e.target.value)
                changed()
              }}
              placeholder="https://apps.apple.com/app/id1234567890"
              autoComplete="off"
            />
          </Field>
          <StoreHint store={resolution?.appStore} />
          {looking ? (
            <p role="status" className="hint">
              Ověřujeme odkazy a hledáme názvy aplikací…
            </p>
          ) : null}
        </>
      ) : (
        <div className="setup-manual stack">
          <p className="small muted">
            Identifikátory najdete ve store odkazu: Android za <code>id=</code>, iOS za{' '}
            <code>/id</code>. Název vyplňte ručně.
          </p>
          <Field label="Package name (Android)">
            <input
              value={gpId}
              onChange={(e) => setGpId(e.target.value)}
              placeholder="cz.firma.aplikace"
            />
          </Field>
          <Field label="Číselné App ID (iOS)">
            <input
              value={ascId}
              onChange={(e) => setAscId(e.target.value)}
              inputMode="numeric"
              placeholder="1234567890"
            />
          </Field>
          {!validIds ? (
            <p role="alert" className="error">
              Package name musí obsahovat části oddělené tečkou; App ID obsahuje pouze číslice.
            </p>
          ) : null}
        </div>
      )}
      <button
        type="button"
        className="link setup-manual-toggle"
        onClick={() => {
          setManual(!manual)
          changed()
        }}
      >
        {manual ? 'Zadat odkazy na store' : 'Pokročilé: zadat identifikátory ručně'}
      </button>
      <Field label="Název" hint="Název pro váš přehled a zprávy. Můžete jej později změnit.">
        <input
          value={name}
          onChange={(e) => {
            edited.current = true
            setName(e.target.value)
          }}
          required
          maxLength={255}
        />
      </Field>
      {different ? (
        <label className="pick">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          <span>
            Potvrzuji, že listingy „{stores[0]?.name}“ a „{stores[1]?.name}“ patří k jedné aplikaci.
          </span>
        </label>
      ) : null}
      {matching.length ? (
        <div className="notice" role="status">
          <strong>Tato aplikace už v organizaci je.</strong>
          {matching.map((a) => (
            <div key={a.id}>
              <span>{a.name}</span> · <Link to={`/${org}/aplikace/${a.id}`}>Otevřít nastavení</Link>
            </div>
          ))}
          {!matching.some(fullyMatches) ? (
            <p>
              Shoduje se jen část údajů nebo typ aplikace. Otevřete existující aplikaci; další
              listing automaticky nespojujeme.
            </p>
          ) : (
            <p>Pokračováním použijete uloženou aplikaci. Druhá kopie nevznikne.</p>
          )}
        </div>
      ) : null}
      {competitor ? (
        <p className="notice">
          Bez klíčů: App Store poskytuje veřejný feed recenzí v omezeném rozsahu, Google Play
          veřejné hodnocení. U Androidu touto cestou nezískáme texty recenzí. Odpovídání a týmové
          zprávy nejsou dostupné.
        </p>
      ) : (
        <p className="small muted">
          V dalším kroku připojíte jednotlivé story. Historii, AI a týmové zprávy najdete i později
          v nastavení aplikace.
        </p>
      )}
      <ErrorBox error={failure} />
      <WizardActions>
        <button
          type="submit"
          form={formId}
          disabled={
            !ready ||
            create.isPending ||
            apps.isPending ||
            (matching.length > 0 && !matching.some(fullyMatches))
          }
        >
          {create.isPending
            ? 'Ukládáme aplikaci…'
            : matching.some(fullyMatches)
              ? 'Pokračovat s existující aplikací'
              : competitor
                ? 'Přidat konkurenci'
                : 'Přidat aplikaci'}
        </button>
        {onClose ? (
          <button type="button" className="secondary" onClick={onClose}>
            Dokončit později
          </button>
        ) : null}
      </WizardActions>
    </form>
  )
}
function StoreHint({ store }: { store: ResolvedStore | null | undefined }) {
  if (!store) return null
  return (
    <p className={store.identifier ? 'setup-resolved small' : 'error'} role="status">
      {store.identifier ? (
        <>
          <strong>{store.platform === 'ANDROID' ? 'Google Play' : 'App Store'}</strong> ·{' '}
          {store.name ?? 'Název není dostupný'}
          <br />
          <code>{store.identifier}</code>
          {store.error ? (
            <>
              <br />
              {store.error} Název můžete zadat ručně.
            </>
          ) : null}
        </>
      ) : (
        (store.error ?? 'Odkaz nerozpoznán. Zkopírujte celou adresu ze storu.')
      )}
    </p>
  )
}
