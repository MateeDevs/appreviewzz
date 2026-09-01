import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import {
  useAddCredential,
  useApps,
  useAttachCredential,
  useChannels,
  useConnectSlack,
  useCreateApp,
  useCreateChannel,
  useCredentials,
  useDeleteChannel,
  useRatings,
  useResolveStoreLinks,
  useRunRatings,
  useTestChannels,
  useUpdateApp,
  useValidateCredential,
} from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, Modal, When } from '../components/ui'
import { RatingsChart } from '../components/RatingsChart'
import type { App, ChannelCheck, RatingsSeries, ResolvedStore, StoreResolution } from '../api/types'

export function AppsPage() {
  const { org = '' } = useParams()
  const apps = useApps(org)
  const [adding, setAdding] = useState(false)

  return (
    <div className="stack">
      <div>
        <h1>Aplikace</h1>
        <p className="muted">Appky, jejichž recenze sledujeme.</p>
      </div>

      <Card>
        {apps.isPending ? <Loading /> : null}
        <ErrorBox error={apps.error} />
        {apps.data?.length === 0 ? <p className="muted">Zatím žádná.</p> : null}
        {apps.data && apps.data.length > 0 ? (
          <table>
            <thead>
              <tr>
                <th>Název</th>
                <th>Store</th>
                <th>Stav</th>
              </tr>
            </thead>
            <tbody>
              {apps.data.map((app) => (
                <tr key={app.id}>
                  <td>
                    <Link to={`/${org}/aplikace/${app.id}`}>{app.name}</Link>
                  </td>
                  <td className="small muted">{app.gpPackageName ?? app.ascAppId}</td>
                  <td>
                    <AppStatus org={org} app={app} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        <div style={{ marginTop: apps.data && apps.data.length > 0 ? '1.1rem' : '0.75rem' }}>
          <button type="button" onClick={() => setAdding(true)}>
            Přidat aplikaci
          </button>
        </div>
      </Card>

      {adding ? <AddAppDialog org={org} onClose={() => setAdding(false)} /> : null}
    </div>
  )
}

/**
 * Stav appky ve výpisu.
 *
 * Appka je od založení „zapnutá", ale dokud nemá klíč ke storu a kanál, nemá čím stahovat
 * ani kam psát — a klient marně čeká na zprávy. Proto se nedodělané nastavení hlásí dřív
 * než zapnutí a rovnou nabídne, co doplnit.
 */
function AppStatus({ org, app }: { org: string; app: App }) {
  if (!app.enabled) return <Badge tone="warn">vypnutá</Badge>
  if (app.setup.ready) return <Badge tone="ok">sleduje se</Badge>

  // Klíč, který čeká na ověření, není nedodělek klienta: udělal, co měl, a čeká se na store.
  // Kdyby se to schovalo pod „čeká na nastavení", šel by to hledat mezi svoje úkoly.
  if (waitingOnly(app)) {
    return (
      <div className="setup-status">
        <Badge tone="warn">čeká na ověření klíče</Badge>
        <p className="muted">Klíč je nahraný, ověřujeme přístup ke storu. Zkoušíme to sami každou čtvrthodinu.</p>
      </div>
    )
  }

  return (
    <div className="setup-status">
      <Badge tone="warn">čeká na nastavení</Badge>
      <SetupTodos org={org} app={app} />
    </div>
  )
}

/** Appce nechybí nic, co by doplnil klient — jen se čeká, až klíč projde ověřením. */
function waitingOnly(app: App): boolean {
  return app.setup.gaps.length > 0 && app.setup.gaps.every((gap) => gap === 'STORE_KEY_WAITING')
}

/** Co zbývá doplnit. Každá položka je odkaz na svou sekci v detailu appky. */
function SetupTodos({ org, app }: { org: string; app: App }) {
  return (
    <div className="setup-todo">
      {setupTodos(app).map((todo) => (
        <Link key={todo.label} className="setup-todo-item" to={`/${org}/aplikace/${app.id}#${todo.section}`}>
          <span aria-hidden="true">+</span>
          {todo.label}
        </Link>
      ))}
    </div>
  )
}

/**
 * Chybějící nastavení po položkách. Klíč se dělí po storech — „chybí klíč" u appky na obou
 * platformách neřekne, který z nich se čeká, a klient pak doplní ten, co už má.
 */
function setupTodos(app: App): Array<{ label: string; section: string }> {
  const todos = app.setup.platformsWithoutKey.map((platform) => ({
    label: platform === 'ANDROID' ? 'klíč ke Google Play' : 'klíč k App Storu',
    section: 'klice',
  }))
  // Pojistka pro případ, že by server hlásil chybějící klíč bez konkrétního storu.
  if (todos.length === 0 && app.setup.gaps.includes('STORE_KEY')) {
    todos.push({ label: 'klíč ke storu', section: 'klice' })
  }
  if (app.setup.gaps.includes('CHANNEL')) todos.push({ label: 'kanál pro zprávy', section: 'kanaly' })
  return todos
}

/**
 * Doskok na sekci z odkazu „chybí klíč".
 *
 * Samotný scroll je málo: člověk přijde na stránku plnou karet a neví, na kterou se dívat.
 * Karta proto po doskoku problikne — jednou, krátce, a po `prefers-reduced-motion` vůbec
 * (o to se stará CSS).
 */
function useSetupFocus(id: string): string | undefined {
  const { hash } = useLocation()
  const [focused, setFocused] = useState(false)

  useEffect(() => {
    if (hash !== `#${id}`) return
    // Karta se vykresluje až po načtení dat, takže se na ni ptáme po zapsání do DOM.
    const node = document.getElementById(id)
    if (!node) return
    node.scrollIntoView({ behavior: 'smooth', block: 'start' })
    setFocused(true)
    const timer = setTimeout(() => setFocused(false), 1800)
    return () => clearTimeout(timer)
  }, [hash, id])

  return focused ? 'ripple' : undefined
}

/**
 * Přidání aplikace odkazem ze storu.
 *
 * Klient nemá odkud znát package name ani číselné App ID — zná adresu, na které appku ve
 * storu vidí. Z ní se obojí vytáhne a rovnou se doptáme storu na název, takže zbývá jediné
 * rozhodnutí: který název použít. Odkazy se ověřují průběžně, aby se překlep poznal dřív,
 * než klient dialog potvrdí.
 */
function AddAppDialog({ org, onClose }: { org: string; onClose: () => void }) {
  const create = useCreateApp(org)
  const resolve = useResolveStoreLinks(org)
  const resolveLinks = resolve.mutate
  const [googlePlayUrl, setGooglePlayUrl] = useState('')
  const [appStoreUrl, setAppStoreUrl] = useState('')
  const [name, setName] = useState('')
  const [resolved, setResolved] = useState<{ links: string; result: StoreResolution } | null>(null)
  // Jakmile klient název přepíše, přestaneme mu ho pod rukama přepisovat výsledkem ze storu.
  const nameEdited = useRef(false)

  const links = `${googlePlayUrl.trim()}\u0000${appStoreUrl.trim()}`
  // Výsledek platí jen pro odkazy, ze kterých vznikl — jinak by po úpravě odkazu chvíli
  // svítil identifikátor od předchozího.
  const current = resolved?.links === links ? resolved.result : null

  useEffect(() => {
    const [googlePlay, appStore] = links.split('\u0000')
    if (googlePlay === '' && appStore === '') return
    // Odkaz se vkládá po částech (nebo se lepí ze schránky) — počkáme, až psaní ustane.
    const timer = setTimeout(() => {
      resolveLinks(
        { googlePlayUrl: googlePlay || undefined, appStoreUrl: appStore || undefined },
        {
          onSuccess: (result) => {
            setResolved({ links, result })
            const suggestion = result.googlePlay?.name ?? result.appStore?.name
            if (suggestion && !nameEdited.current) setName(suggestion)
          },
        },
      )
    }, 500)
    return () => clearTimeout(timer)
  }, [links, resolveLinks])

  const identifiers = [current?.googlePlay, current?.appStore].filter(
    (store): store is ResolvedStore => store != null && store.identifier !== '',
  )
  const suggestions = [...new Set(identifiers.map((store) => store.name).filter((value): value is string => value != null))]
  // Vyplněný odkaz, ze kterého nic nekouká, přidání blokuje: jinak by se appka tiše založila
  // jen s tím druhým storem a klient by se to dozvěděl až tím, že recenze nechodí.
  const broken =
    (googlePlayUrl.trim() !== '' && current?.googlePlay?.identifier === '') ||
    (appStoreUrl.trim() !== '' && current?.appStore?.identifier === '')
  const ready = identifiers.length > 0 && name.trim() !== '' && !broken

  return (
    <Modal title="Přidat aplikaci" onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          create.mutate(
            {
              name: name.trim(),
              gpPackageName: current?.googlePlay?.identifier || null,
              ascAppId: current?.appStore?.identifier || null,
            },
            { onSuccess: onClose },
          )
        }}
      >
        <Field
          label="Odkaz na Google Play"
          hint="Adresa stránky appky v Play Storu. Nech prázdné, když appka na Androidu není."
        >
          <input
            value={googlePlayUrl}
            onChange={(e) => setGooglePlayUrl(e.target.value)}
            placeholder="https://play.google.com/store/apps/details?id=cz.matee.appka"
            autoFocus
          />
        </Field>
        <StoreHint store={current?.googlePlay} filled={googlePlayUrl.trim() !== ''} pending={resolve.isPending} />

        <Field label="Odkaz na App Store" hint="Adresa stránky appky v App Storu. Nech prázdné, když appka na iOS není.">
          <input
            value={appStoreUrl}
            onChange={(e) => setAppStoreUrl(e.target.value)}
            placeholder="https://apps.apple.com/cz/app/appka/id1234567890"
          />
        </Field>
        <StoreHint store={current?.appStore} filled={appStoreUrl.trim() !== ''} pending={resolve.isPending} />

        <div style={{ marginTop: '0.85rem' }}>
          <Field label="Název" hint="Pod tímhle názvem appku uvidíš v consoli i ve zprávách do kanálu.">
            <input
              value={name}
              onChange={(e) => {
                nameEdited.current = true
                setName(e.target.value)
              }}
              required
            />
          </Field>
          {suggestions.length > 0 ? (
            <div className="chips">
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  className={suggestion === name ? 'chip selected' : 'chip'}
                  onClick={() => {
                    nameEdited.current = true
                    setName(suggestion)
                  }}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <p className="small muted" style={{ marginTop: '0.85rem' }}>
          Do kanálu půjdou recenze od chvíle, kdy appku přidáš. Starší se stáhnou do historie,
          ale nikoho neupozorní — jinak by první stažení vysypalo do Slacku celou historii appky.
        </p>

        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={create.error ?? resolve.error} />
          <div className="row">
            <button type="submit" disabled={!ready || create.isPending}>
              {create.isPending ? 'Přidávám…' : 'Přidat aplikaci'}
            </button>
            <button type="button" className="secondary" onClick={onClose}>
              Zrušit
            </button>
          </div>
        </div>
      </form>
    </Modal>
  )
}

/** Co se z odkazu přečetlo — identifikátor pod polem je jediné potvrzení, že sedí ten správný. */
function StoreHint({
  store,
  filled,
  pending,
}: {
  store: ResolvedStore | null | undefined
  filled: boolean
  pending: boolean
}) {
  if (!filled) return null
  if (!store) return pending ? <div className="hint">Hledám appku ve storu…</div> : null
  if (store.identifier === '') return <div className="hint" style={{ color: 'var(--error)' }}>{store.error}</div>
  return (
    <div className="hint">
      <code>{store.identifier}</code>
      {store.name ? ` · ${store.name}` : ` · ${store.error ?? ''}`}
    </div>
  )
}

export function AppDetailPage() {
  const { org = '', appId = '' } = useParams()
  const apps = useApps(org)
  const app = apps.data?.find((item) => item.id === appId)

  if (apps.isPending) return <Loading />
  if (!app) return <ErrorBox error={new Error('Taková aplikace tu není.')} />

  return (
    <div className="stack">
      <div>
        <h1>{app.name}</h1>
        <p className="muted">
          {app.platforms.join(' + ')} · {app.gpPackageName ?? app.ascAppId}
        </p>
        {app.enabled && !app.setup.ready ? (
          <div className="row" style={{ marginTop: '0.6rem' }}>
            {waitingOnly(app) ? (
              <Badge tone="warn">čeká na ověření klíče</Badge>
            ) : (
              <>
                <Badge tone="warn">čeká na nastavení</Badge>
                <SetupTodos org={org} app={app} />
              </>
            )}
          </div>
        ) : null}
      </div>
      <AppSettingsCard org={org} appId={appId} />
      <RatingsCard org={org} appId={appId} />
      <CredentialsCard org={org} appId={appId} />
      <ChannelsCard org={org} appId={appId} />
    </div>
  )
}

function AppSettingsCard({ org, appId }: { org: string; appId: string }) {
  const apps = useApps(org)
  const update = useUpdateApp(org)
  const app = apps.data?.find((item) => item.id === appId)
  const [draft, setDraft] = useState<Record<string, string> | null>(null)

  if (!app) return null
  const values = draft ?? {
    name: app.name,
    gpReportingBucket: app.gpReportingBucket ?? '',
    locale: app.locale.toLowerCase(),
    timezone: app.timezone,
    dailyDigestAt: app.dailyDigestAt.slice(0, 5),
    aiInstructions: app.aiInstructions ?? '',
  }
  const set = (key: string, value: string) => setDraft({ ...values, [key]: value })

  return (
    <Card title="Nastavení">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          update.mutate(
            {
              id: appId,
              body: {
                name: values.name,
                gpReportingBucket: values.gpReportingBucket === '' ? null : values.gpReportingBucket,
                locale: values.locale,
                timezone: values.timezone,
                dailyDigestAt: values.dailyDigestAt,
                aiInstructions: values.aiInstructions === '' ? null : values.aiInstructions,
                enabled: app.enabled,
              },
            },
            { onSuccess: () => setDraft(null) },
          )
        }}
      >
        <Field label="Název">
          <input value={values.name} onChange={(e) => set('name', e.target.value)} required />
        </Field>
        {app.gpPackageName ? (
          <Field
            label="Bucket s reportingem Play Console"
            hint="Najdeš ho v Play Console → Stáhnout přehledy → Kopírovat URI (pubsite_prod_…). Bez něj se Android hodnocení berou z veřejné stránky storu, tedy zaokrouhlená."
          >
            <input
              value={values.gpReportingBucket}
              placeholder="pubsite_prod_rev_01234567890123456789"
              onChange={(e) => set('gpReportingBucket', e.target.value)}
            />
          </Field>
        ) : null}
        <Field label="Jazyk zpráv">
          <select value={values.locale} onChange={(e) => set('locale', e.target.value)}>
            <option value="cs">čeština</option>
            <option value="en">angličtina</option>
          </select>
        </Field>
        <Field label="Časová zóna" hint="Podle ní se počítá čas denního přehledu.">
          <input value={values.timezone} onChange={(e) => set('timezone', e.target.value)} />
        </Field>
        <Field label="Čas denního přehledu">
          <input type="time" value={values.dailyDigestAt} onChange={(e) => set('dailyDigestAt', e.target.value)} />
        </Field>
        {/* Watermark se nenastavuje, jen ukazuje: je to čas přidání appky a měnit ho zpětně
            by znamenalo buď zaplavit kanál historií, nebo zamlčet recenze, které už přišly. */}
        <div className="field">
          <label>Posílat recenze od</label>
          <p className="small muted" style={{ margin: 0 }}>
            Do kanálu jdou recenze od chvíle, kdy se appka přidala do console
            {app.notifyFrom ? (
              <>
                {' '}
                (<When iso={app.notifyFrom} />)
              </>
            ) : null}
            . Starší zůstávají v historii, ale nikoho neupozorní.
          </p>
        </div>
        <Field
          label="Instrukce pro AI návrhy"
          hint="Tón odpovědí, čemu se vyhnout, jak podepisovat. Nechej prázdné, když návrhy nechceš ovlivňovat."
        >
          <textarea value={values.aiInstructions} onChange={(e) => set('aiInstructions', e.target.value)} />
        </Field>
        <div className="row" style={{ marginTop: '1rem' }}>
          <button type="submit" disabled={update.isPending || draft === null}>
            Uložit
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => update.mutate({ id: appId, body: { name: app.name, enabled: !app.enabled } })}
          >
            {app.enabled ? 'Pozastavit sledování' : 'Znovu spustit sledování'}
          </button>
        </div>
        <div style={{ marginTop: '0.75rem' }}>
          <ErrorBox error={update.error} />
        </div>
      </form>
    </Card>
  )
}

/** Nahrání klíče. Soubor se čte v prohlížeči a posílá jako text — payload jde jen dovnitř. */
/**
 * Vývoj hodnocení. Graf sám o sobě nikoho nezajímá — zajímá ho, jestli to jde nahoru nebo
 * dolů a kolik hodnocení přibylo. Proto jsou čísla nad grafem, ne pod ním.
 */
function RatingsCard({ org, appId }: { org: string; appId: string }) {
  const ratings = useRatings(org, appId)
  const run = useRunRatings(org)

  return (
    <Card title="Hodnocení">
      {ratings.isPending ? <Loading /> : null}
      <ErrorBox error={ratings.error} />
      <ErrorBox error={run.error} />

      {ratings.data?.every((series) => series.points.length === 0) ? (
        <p className="muted">
          Zatím žádná data. Přehled chodí každý den v čase nastaveném výš; první běh jde spustit i rovnou.
        </p>
      ) : null}

      {ratings.data?.map((series) => (series.points.length === 0 ? null : <RatingsSeriesBlock key={series.platform} series={series} />))}

      <div className="row" style={{ marginTop: '1rem' }}>
        <button type="button" onClick={() => run.mutate(appId)} disabled={run.isPending}>
          {run.isPending ? 'Posílám…' : 'Poslat přehled teď'}
        </button>
        {run.data ? <RunSummary result={run.data} /> : null}
      </div>
    </Card>
  )
}

function RatingsSeriesBlock({ series }: { series: RatingsSeries }) {
  const latest = series.points[series.points.length - 1]
  const newRatings = series.points.reduce((sum, point) => sum + (point.newCount ?? 0), 0)
  const change = series.change

  return (
    <div className="stack" style={{ marginBottom: '1.5rem' }}>
      <div className="row">
        <strong>{series.platform === 'ANDROID' ? '🤖 Android' : '🍎 iOS'}</strong>
        <span>{latest?.average != null ? latest.average.toFixed(2) : '—'}</span>
        {change != null ? (
          <Badge tone={change > 0 ? 'ok' : change < 0 ? 'bad' : undefined}>
            {change > 0 ? '▲' : change < 0 ? '▼' : '▪︎'} {Math.abs(change).toFixed(2)} za období
          </Badge>
        ) : null}
        <span className="muted small">
          {latest?.totalCount != null ? `${latest.totalCount} hodnocení` : ''}
          {newRatings > 0 ? ` · +${newRatings} za období` : ''}
        </span>
      </div>
      <RatingsChart series={series} />
      {latest ? <p className="muted small">Poslední data k {latest.date} ({sourceLabel(latest.source)}).</p> : null}
    </div>
  )
}

function RunSummary({ result }: { result: { platforms: number; sent: number; alreadySent: number; errors: string[] } }) {
  if (result.errors.length > 0) return <span className="muted small">{result.errors.join(' · ')}</span>
  if (result.sent > 0) return <span className="muted small">Odesláno do {result.sent} kanálů.</span>
  if (result.alreadySent > 0) return <span className="muted small">Dnešní přehled už odešel.</span>
  return <span className="muted small">Hodnocení uložena, ale nikam se neposílala.</span>
}

/** Odkud čísla jsou — u scrapu je dobré vědět, že je to odhad z veřejné stránky. */
function sourceLabel(source: string): string {
  switch (source) {
    case 'GP_CSV':
      return 'Play Console'
    case 'GP_SCRAPE':
      return 'veřejný listing Play'
    case 'ITUNES_LOOKUP':
      return 'App Store'
    case 'ASC_LISTING':
      return 'veřejný listing App Store'
    default:
      return source
  }
}

function CredentialsCard({ org, appId }: { org: string; appId: string }) {
  const focus = useSetupFocus('klice')
  const credentials = useCredentials(org)
  const add = useAddCredential(org)
  const attach = useAttachCredential(org)
  const validate = useValidateCredential(org)
  const [type, setType] = useState('gp')
  const [label, setLabel] = useState('')
  const [content, setContent] = useState('')
  const [keyId, setKeyId] = useState('')
  const [issuerId, setIssuerId] = useState('')
  const [result, setResult] = useState<string | null>(null)

  const storeKeys = (credentials.data ?? []).filter(
    (credential) => credential.type === 'GP_SERVICE_ACCOUNT' || credential.type === 'ASC_API_KEY',
  )

  return (
    <Card id="klice" className={focus} title="Klíče ke storu">
      <ErrorBox error={credentials.error} />
      {storeKeys.length === 0 ? (
        <p className="muted">Zatím žádný klíč — bez něj nemáme čím recenze stáhnout.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Klíč</th>
              <th>Otisk</th>
              <th>Ověření</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {storeKeys.map((credential) => (
              <tr key={credential.id}>
                <td>
                  {credential.label}
                  <div className="small muted">{credential.hint}</div>
                </td>
                <td className="small muted">{credential.fingerprint}</td>
                <td>
                  {credential.validationStatus === 'VALID' ? (
                    <Badge tone="ok">
                      funguje · <When iso={credential.validatedAt} />
                    </Badge>
                  ) : credential.validationStatus === 'INVALID' ? (
                    <Badge tone="bad">{credential.validationError ?? 'neplatný'}</Badge>
                  ) : (
                    <Badge tone="warn">neověřený</Badge>
                  )}
                </td>
                <td>
                  <div className="row">
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => attach.mutate({ appId, credentialId: credential.id })}
                    >
                      Přiřadit k appce
                    </button>
                    <button
                      type="button"
                      className="secondary"
                      disabled={validate.isPending}
                      onClick={() =>
                        validate.mutate(
                          { appId, credentialId: credential.id },
                          {
                            onSuccess: (outcome) =>
                              setResult(outcome.valid ? 'Klíč funguje.' : (outcome.message ?? 'Klíč neprošel.')),
                          },
                        )
                      }
                    >
                      Ověřit proti storu
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {result ? <div className="notice" style={{ marginTop: '0.75rem' }}>{result}</div> : null}
      <ErrorBox error={attach.error ?? validate.error} />

      <h3 style={{ marginTop: '1.25rem' }}>Nahrát klíč</h3>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          add.mutate(
            {
              type,
              label,
              content,
              keyId: keyId.trim() === '' ? undefined : keyId.trim(),
              issuerId: issuerId.trim() === '' ? undefined : issuerId.trim(),
            },
            {
              onSuccess: () => {
                setContent('')
                setLabel('')
                setKeyId('')
                setIssuerId('')
              },
            },
          )
        }}
      >
        <Field label="Store">
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="gp">Google Play — service account (JSON)</option>
            <option value="asc">App Store Connect — API klíč (.p8)</option>
          </select>
        </Field>
        <Field label="Štítek" hint="Jak klíč poznáš ve výpisu.">
          <input value={label} onChange={(e) => setLabel(e.target.value)} required />
        </Field>
        <Field
          label="Soubor s klíčem"
          hint={
            type === 'gp'
              ? 'JSON service accountu z Play Console → Setup → API access. Stačí právo číst recenze a odpovídat na ně.'
              : '.p8 z App Store Connect → Users and Access → Integrations. Stačí role Customer Support.'
          }
        >
          <input
            type="file"
            accept={type === 'gp' ? '.json,application/json' : '.p8,text/plain'}
            onChange={async (event) => {
              const file = event.target.files?.[0]
              if (file) setContent(await file.text())
            }}
          />
        </Field>
        {type === 'asc' ? (
          <>
            <Field label="Key ID" hint="Deset znaků, opíšeš je z tabulky klíčů v App Store Connect.">
              <input value={keyId} onChange={(e) => setKeyId(e.target.value)} required />
            </Field>
            <Field label="Issuer ID">
              <input value={issuerId} onChange={(e) => setIssuerId(e.target.value)} />
            </Field>
          </>
        ) : null}
        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={add.error} />
          <button type="submit" disabled={add.isPending || content === ''}>
            Nahrát klíč
          </button>
          <p className="small muted">
            Klíč se zašifruje ještě před uložením a z vaultu už ven nevyjde — ve výpisu uvidíš jen otisk.
          </p>
        </div>
      </form>
    </Card>
  )
}

function ChannelsCard({ org, appId }: { org: string; appId: string }) {
  const focus = useSetupFocus('kanaly')
  const channels = useChannels(org, appId)
  const credentials = useCredentials(org)
  const create = useCreateChannel(org, appId)
  const remove = useDeleteChannel(org, appId)
  const test = useTestChannels(org, appId)
  const connect = useConnectSlack(org)
  const [targetRef, setTargetRef] = useState('')
  const [credentialId, setCredentialId] = useState('')
  const [token, setToken] = useState('')
  const [checks, setChecks] = useState<ChannelCheck[] | null>(null)

  const installs = (credentials.data ?? []).filter((credential) => credential.type === 'SLACK_INSTALL')

  return (
    <Card id="kanaly" className={focus} title="Kanály">
      <ErrorBox error={channels.error} />
      {channels.data?.length === 0 ? (
        <p className="muted">Zatím žádný kanál — recenze nemají kam chodit.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Kanál</th>
              <th>Jazyk</th>
              <th>Stav</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {channels.data?.map((channel) => (
              <tr key={channel.id}>
                <td>
                  {channel.targetLabel ?? channel.targetRef}
                  <div className="small muted">{channel.targetRef}</div>
                </td>
                <td className="small">{channel.locale.toLowerCase()}</td>
                <td>{channel.enabled ? <Badge tone="ok">zapnutý</Badge> : <Badge tone="warn">vypnutý</Badge>}</td>
                <td>
                  <button type="button" className="danger" onClick={() => remove.mutate(channel.id)}>
                    Odpojit
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {channels.data && channels.data.length > 0 ? (
        <div style={{ marginTop: '0.75rem' }}>
          <button
            type="button"
            className="secondary"
            disabled={test.isPending}
            onClick={() => test.mutate(undefined, { onSuccess: setChecks })}
          >
            {test.isPending ? 'Zkouším…' : 'Poslat zkušební zprávu'}
          </button>
          {checks?.map((check) => (
            <div key={check.channelId} className={check.ok ? 'notice' : 'error'} style={{ marginTop: '0.5rem' }}>
              <strong>{check.targetRef}</strong>{' '}
              {check.ok ? 'zpráva dorazila.' : `${check.error ?? 'nepovedlo se'} — ${check.hint ?? ''}`}
            </div>
          ))}
        </div>
      ) : null}

      {installs.length === 0 ? (
        <>
          <h3 style={{ marginTop: '1.25rem' }}>Připojit Slack</h3>
          <form
            onSubmit={(event) => {
              event.preventDefault()
              connect.mutate({ token }, { onSuccess: () => setToken('') })
            }}
          >
            <Field
              label="Bot token workspace"
              hint="Slack App → OAuth & Permissions → Bot User OAuth Token. Začíná xoxb-."
            >
              <input value={token} onChange={(e) => setToken(e.target.value)} placeholder="xoxb-…" required />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={connect.error} />
              <button type="submit" disabled={connect.isPending}>
                Připojit workspace
              </button>
            </div>
          </form>
        </>
      ) : (
        <>
          <h3 style={{ marginTop: '1.25rem' }}>Přidat kanál</h3>
          <form
            onSubmit={(event) => {
              event.preventDefault()
              create.mutate(
                { targetRef, credentialId: credentialId || (installs[0]?.id ?? '') },
                { onSuccess: () => setTargetRef('') },
              )
            }}
          >
            <Field label="Workspace">
              <select value={credentialId} onChange={(e) => setCredentialId(e.target.value)}>
                {installs.map((install) => (
                  <option key={install.id} value={install.id}>
                    {install.hint ?? install.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="ID kanálu"
              hint="Ve Slacku: klikni na kanál → View channel details → dole je ID (začíná C). Jméno kanálu se mění, ID ne."
            >
              <input value={targetRef} onChange={(e) => setTargetRef(e.target.value)} placeholder="C0123456789" required />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={create.error} />
              <button type="submit" disabled={create.isPending}>
                Připojit kanál
              </button>
              <p className="small muted">
                U privátního kanálu nezapomeň bota pozvat: <code>/invite @appreviewzz</code>
              </p>
            </div>
          </form>
        </>
      )}
    </Card>
  )
}
