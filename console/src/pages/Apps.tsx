import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import {
  useAddCredential,
  useAnalysisStatus,
  useApps,
  useAttachCredential,
  useBackfillAnalysis,
  useChannels,
  useCheckReportingBucket,
  useConnectSlack,
  useCreateApp,
  useCreateChannel,
  useCreateReplyTemplate,
  useCreateTopic,
  useCredentials,
  useDeleteChannel,
  useDeleteCredential,
  useDeleteReplyTemplate,
  useDeleteTopic,
  useRatings,
  useReplyTemplates,
  useResolveStoreLinks,
  useRunRatings,
  useTestChannels,
  useTopics,
  useUpdateApp,
  useUpdateChannel,
  useUpdateReplyTemplate,
  useValidateCredential,
} from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, Modal, PlatformBadge, When } from '../components/ui'
import { Select } from '../components/Select'
import { ConnectStoreWizard } from '../components/ConnectStoreWizard'
import { RatingsChart } from '../components/RatingsChart'
import type {
  App,
  Channel,
  ChannelCheck,
  Credential,
  Platform,
  RatingsSeries,
  ReplyTemplate,
  ReportingBucketCheck,
  ResolvedStore,
  StoreResolution,
} from '../api/types'

export function AppsPage() {
  const { org = '' } = useParams()
  const apps = useApps(org)
  const [adding, setAdding] = useState(false)
  // Napojení storu se otevírá i z výpisu: pilulka „chybí klíč ke Google Play" je nejkratší
  // cesta k dialogu, který ten klíč obstará.
  const [connect, setConnect] = useState<{ app: App; platform: Platform } | null>(null)

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
                    {app.competitor ? <> <Badge>konkurence</Badge></> : null}
                  </td>
                  <td className="small muted">{app.gpPackageName ?? app.ascAppId}</td>
                  <td>
                    <AppStatus org={org} app={app} onConnect={(platform) => setConnect({ app, platform })} />
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

      {adding ? (
        <AddAppDialog
          org={org}
          onClose={() => setAdding(false)}
          onConnect={(app, platform) => setConnect({ app, platform })}
        />
      ) : null}
      {connect ? (
        <ConnectStoreWizard org={org} app={connect.app} platform={connect.platform} onClose={() => setConnect(null)} />
      ) : null}
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
function AppStatus({ org, app, onConnect }: { org: string; app: App; onConnect: (platform: Platform) => void }) {
  if (!app.enabled) return <Badge tone="warn">vypnutá</Badge>
  // Konkurence nemá co nastavovat: čte se z veřejných zdrojů, bez klíče a bez kanálu.
  if (app.competitor) return <Badge tone="ok">sleduje se z veřejných zdrojů</Badge>
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
      <SetupTodos org={org} app={app} onConnect={onConnect} />
    </div>
  )
}

/** Appce nechybí nic, co by doplnil klient — jen se čeká, až klíč projde ověřením. */
function waitingOnly(app: App): boolean {
  return app.setup.gaps.length > 0 && app.setup.gaps.every((gap) => gap === 'STORE_KEY_WAITING')
}

/**
 * Co zbývá doplnit. Chybějící klíč otevírá rovnou dialog pro ten store — poslat člověka
 * na kartu s klíči by ho postavilo před formulář, přes který právě nechceme, aby šel.
 * Zbytek zůstává odkazem na svou sekci v detailu appky.
 */
function SetupTodos({ org, app, onConnect }: { org: string; app: App; onConnect: (platform: Platform) => void }) {
  return (
    <div className="setup-todo">
      {setupTodos(app).map((todo) =>
        todo.platform != null ? (
          <button key={todo.label} type="button" className="setup-todo-item" onClick={() => onConnect(todo.platform!)}>
            <span aria-hidden="true">+</span>
            {todo.label}
          </button>
        ) : (
          <Link key={todo.label} className="setup-todo-item" to={`/${org}/aplikace/${app.id}#${todo.section}`}>
            <span aria-hidden="true">+</span>
            {todo.label}
          </Link>
        ),
      )}
    </div>
  )
}

/**
 * Chybějící nastavení po položkách. Klíč se dělí po storech — „chybí klíč" u appky na obou
 * platformách neřekne, který z nich se čeká, a klient pak doplní ten, co už má.
 */
function setupTodos(app: App): Array<{ label: string; section: string; platform?: Platform }> {
  const todos: Array<{ label: string; section: string; platform?: Platform }> = app.setup.platformsWithoutKey.map(
    (platform) => ({
      label: platform === 'ANDROID' ? 'klíč ke Google Play' : 'klíč k App Storu',
      section: 'klice',
      platform,
    }),
  )
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
function AddAppDialog({
  org,
  onClose,
  onConnect,
}: {
  org: string
  onClose: () => void
  /** Přidaná appka ještě nemá klíč — nabídneme napojení rovnou, než na ně klient zapomene. */
  onConnect: (app: App, platform: Platform) => void
}) {
  const create = useCreateApp(org)
  const resolve = useResolveStoreLinks(org)
  const resolveLinks = resolve.mutate
  const [googlePlayUrl, setGooglePlayUrl] = useState('')
  const [appStoreUrl, setAppStoreUrl] = useState('')
  const [name, setName] = useState('')
  const [historyMonths, setHistoryMonths] = useState('1')
  const [competitor, setCompetitor] = useState(false)
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
              historyMonths: Number(historyMonths),
              competitor,
            },
            {
              onSuccess: (app) => {
                onClose()
                // Konkurence klíč nemá a mít nebude — dialog napojení by jen mátl.
                if (app.competitor) return
                // Appka bez klíče nedělá nic. Navazujeme proto rovnou na store, který
                // klient vyplnil — Google Play má přednost, protože ho zvládneme skoro celý.
                onConnect(app, app.gpPackageName ? 'ANDROID' : 'IOS')
              },
            },
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

        {/* Konkurence je jiný druh appky, ne nastavení: bez klíče, bez kanálu, bez odpovídání. */}
        <label className="pick" style={{ marginTop: '0.85rem' }}>
          <input type="checkbox" checked={competitor} onChange={(event) => setCompetitor(event.target.checked)} />
          <span>
            Sleduju jako konkurenci
            <div className="small muted">
              Cizí appka bez klíče: recenze z veřejného App Store feedu, u Google Play jen hodnocení ze
              storu. Nikam se nenotifikuje a neodpovídá se — slouží rozborům.
            </div>
          </span>
        </label>

        {competitor ? null : (
          <>
            <div style={{ marginTop: '0.85rem' }}>
              <Field
                label="Historie k rozboru"
                hint="Kolik měsíců zpátky dotáhnout recenze, aby bylo co rozebírat hned první den."
              >
                <Select
                  value={historyMonths}
                  options={HISTORY_MONTHS.map((months) => ({ value: String(months), label: historyMonthsLabel(months) }))}
                  onChange={setHistoryMonths}
                  ariaLabel="Historie k rozboru"
                />
              </Field>
              {googlePlayUrl.trim() !== '' ? (
                <p className="small muted">
                  U Androidu historii vydá jen reporting Play Console — vlož jeho bucket v nastavení
                  aplikace, jinak budou recenze až ode dneška. Google Play API dál než týden zpátky nevidí.
                </p>
              ) : null}
            </div>

            <p className="small muted" style={{ marginTop: '0.85rem' }}>
              Do kanálu půjdou recenze od chvíle, kdy appku přidáš. Starší se stáhnou do historie,
              ale nikoho neupozorní — jinak by první stažení vysypalo do Slacku celou historii appky.
            </p>
          </>
        )}
        {competitor && appStoreUrl.trim() === '' && googlePlayUrl.trim() !== '' ? (
          <p className="small muted" style={{ marginTop: '0.85rem' }}>
            Google Play veřejné recenze neukazuje — u konkurence jen s Androidem budou k dispozici jen
            hodnocení, ne texty. Přidej i odkaz na App Store, pokud appka na iOS je.
          </p>
        ) : null}

        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={create.error ?? resolve.error} />
          <div className="row">
            <button type="submit" disabled={!ready || create.isPending}>
              {create.isPending ? 'Přidávám…' : competitor ? 'Přidat konkurenci' : 'Přidat aplikaci'}
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

/** Záložky detailu. Klíč je i v URL (`?tab=`), aby šel odkaz na konkrétní záložku poslat dál. */
const APP_TABS = [
  { key: 'nastaveni', label: 'Nastavení' },
  { key: 'hodnoceni', label: 'Hodnocení' },
  { key: 'klice', label: 'Klíče a kanály' },
  { key: 'odpovedi', label: 'Odpovědi' },
] as const
type AppTab = (typeof APP_TABS)[number]['key']

function isAppTab(value: string | null): value is AppTab {
  return APP_TABS.some((tab) => tab.key === value)
}

export function AppDetailPage() {
  const { org = '', appId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const { hash } = useLocation()
  const apps = useApps(org)
  const update = useUpdateApp(org)
  const app = apps.data?.find((item) => item.id === appId)
  const [connect, setConnect] = useState<Platform | null>(null)

  // Deep-linky „#klice" a „#kanaly" z výpisu aplikací a průvodce vedou na karty v záložce
  // „Klíče a kanály": záložka se otevře podle hashe a doskok na kartu zařídí `useSetupFocus`.
  // Kliknutí na jinou záložku hash z URL zahodí, takže ho pak nic nedrží.
  const requested = params.get('tab')
  // Konkurence nemá klíče, kanály ani odpovědi — zbývají nastavení rozborů a hodnocení.
  const tabs = app?.competitor ? APP_TABS.filter((item) => item.key === 'nastaveni' || item.key === 'hodnoceni') : APP_TABS
  const wanted: AppTab = hash === '#klice' || hash === '#kanaly' ? 'klice' : isAppTab(requested) ? requested : 'nastaveni'
  const tab: AppTab = tabs.some((item) => item.key === wanted) ? wanted : 'nastaveni'

  if (apps.isPending) return <Loading />
  if (!app) return <ErrorBox error={new Error('Taková aplikace tu není.')} />

  return (
    <div className="stack">
      <div>
        <h1>{app.name}</h1>
        <p className="muted">
          {app.platforms.map(storeName).join(' + ')} ·{' '}
          {[app.gpPackageName, app.ascAppId].filter(Boolean).join(' · ')}
          {app.competitor ? ' · konkurence: veřejné recenze, bez odpovídání' : ''}
        </p>
        <div className="row" style={{ marginTop: '0.6rem' }}>
          <AppStatus org={org} app={app} onConnect={setConnect} />
          <button
            type="button"
            className="secondary"
            disabled={update.isPending}
            onClick={() => update.mutate({ id: appId, body: { ...settingsBase(app), enabled: !app.enabled } })}
          >
            {app.enabled ? 'Pozastavit sledování' : 'Znovu spustit sledování'}
          </button>
        </div>
        <ErrorBox error={update.error} />
      </div>

      <div className="tabs" role="tablist" aria-label="Části nastavení aplikace">
        {tabs.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            className={tab === item.key ? 'tab active' : 'tab'}
            onClick={() => setParams({ tab: item.key })}
          >
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'nastaveni' ? (
        <>
          <AppSettingsCard org={org} app={app} />
          <AnalysisCard org={org} appId={appId} />
        </>
      ) : null}
      {tab === 'hodnoceni' ? <RatingsCard org={org} appId={appId} /> : null}
      {tab === 'klice' ? (
        <>
          <CredentialsCard org={org} app={app} onConnect={setConnect} />
          <ChannelsCard org={org} app={app} />
        </>
      ) : null}
      {tab === 'odpovedi' ? (
        <>
          <ReplySettingsCard org={org} app={app} />
          <ReplyTemplatesCard org={org} appId={appId} />
        </>
      ) : null}
      {connect ? <ConnectStoreWizard org={org} app={app} platform={connect} onClose={() => setConnect(null)} /> : null}
    </div>
  )
}

/**
 * Zkouška bucketu mimo průvodce: dosáhne na něj klíč aplikace?
 *
 * Uložením se nic neověří — práva k bucketu se udělují v Cloud Storage, ne v Play Console,
 * takže tady se dá odpověď dostat hned místo čekání, jestli ráno dorazí oficiální hvězdičky.
 * Ukládá se dál formulářem; tohle je jen dotaz.
 */
function ReportingBucketProbe({ org, appId, bucket }: { org: string; appId: string; bucket: string }) {
  const check = useCheckReportingBucket(org)
  const [outcome, setOutcome] = useState<ReportingBucketCheck | null>(null)
  const value = bucket.trim()

  return (
    <div className="stack">
      <div className="row">
        <button
          type="button"
          className="secondary"
          disabled={value === '' || check.isPending}
          onClick={() => {
            setOutcome(null)
            check.mutate({ appId, bucket: value }, { onSuccess: setOutcome })
          }}
        >
          {check.isPending ? 'Zkoušíme…' : 'Vyzkoušet bucket'}
        </button>
      </div>
      {outcome ? (
        <div className="notice">
          <Badge tone={outcome.status === 'OK' ? 'ok' : outcome.worthSaving ? 'warn' : 'bad'}>
            {outcome.status === 'OK' ? 'funguje' : outcome.worthSaving ? 'zatím bez dat' : 'nedosáhli jsme'}
          </Badge>{' '}
          {outcome.message}
        </div>
      ) : null}
      <ErrorBox error={check.error} />
    </div>
  )
}

function storeName(platform: Platform): string {
  return platform === 'ANDROID' ? 'Google Play' : 'App Store'
}

/** ISO pořadí dnů — číslo se posílá na server, jméno vidí člověk. */
const WEEK_DAYS = ['pondělí', 'úterý', 'středa', 'čtvrtek', 'pátek', 'sobota', 'neděle']

/** Nabídka hloubky historie. Delší období nemá smysl nabízet klikem — na to je CLI. */
const HISTORY_MONTHS = [1, 3, 6, 12]

function historyMonthsLabel(months: number): string {
  if (months === 1) return '1 měsíc zpětně'
  if (months < 5) return `${months} měsíce zpětně`
  return `${months} měsíců zpětně`
}

const LOCALE_OPTIONS = [
  { value: 'cs', label: 'čeština' },
  { value: 'en', label: 'angličtina' },
]

function localeLabel(locale: 'CS' | 'EN'): string {
  return locale === 'EN' ? 'angličtina' : 'čeština'
}

/**
 * Co musí jít v každém PATCH appky, i když sekce mění něco jiného.
 *
 * Server název vyžaduje vždy a instrukce pro AI bere „chybí v těle" jako „smaž" — kdyby je
 * sekce Zprávy vynechala, uložení času přehledu by klientovi tiše smazalo rozepsaný tón
 * odpovědí. Sekce, která tahle pole opravdu mění, je přepíše svými hodnotami.
 */
function settingsBase(app: App): Record<string, unknown> {
  return { name: app.name, aiInstructions: app.aiInstructions }
}

/**
 * Jedna sekce nastavení s vlastním rozpracovaným stavem a vlastním „Uložit".
 *
 * Kdo mění čas přehledu, nemá při tom nechtěně odeslat i rozepsaný název z jiné sekce — a po
 * uložení hned vidí, že se to vzalo. Tlačítko je aktivní jen při skutečné změně; po uložení
 * se draft zahodí a hodnoty jdou znovu ze serveru.
 */
function SettingsSection<K extends string>({
  org,
  app,
  title,
  first,
  initial,
  body,
  children,
}: {
  org: string
  app: App
  title?: string
  /** První sekce v kartě nemá linku nad nadpisem — ta by zdvojila okraj karty. */
  first?: boolean
  initial: Record<K, string>
  /** Co z hodnot sekce odejde na server; zbytek těla doplní `settingsBase`. */
  body: (values: Record<K, string>) => Record<string, unknown>
  children: (values: Record<K, string>, set: (key: K, value: string) => void) => ReactNode
}) {
  const update = useUpdateApp(org)
  const [draft, setDraft] = useState<Record<K, string> | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (!saved) return
    const timer = setTimeout(() => setSaved(false), 3000)
    return () => clearTimeout(timer)
  }, [saved])

  const values = draft ?? initial
  const set = (key: K, value: string) => {
    setSaved(false)
    setDraft({ ...values, [key]: value })
  }
  const dirty = draft != null && (Object.keys(initial) as K[]).some((key) => draft[key] !== initial[key])

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        update.mutate(
          { id: app.id, body: { ...settingsBase(app), ...body(values) } },
          {
            onSuccess: () => {
              setDraft(null)
              setSaved(true)
            },
          },
        )
      }}
    >
      {title ? <h3 className={first ? 'form-section first' : 'form-section'}>{title}</h3> : null}
      {children(values, set)}
      <div className="row" style={{ marginTop: '1rem' }}>
        <button type="submit" disabled={!dirty || update.isPending}>
          {update.isPending ? 'Ukládám…' : 'Uložit'}
        </button>
        {saved ? <Badge tone="ok">Uloženo</Badge> : null}
      </div>
      {update.error ? (
        <div style={{ marginTop: '0.75rem' }}>
          <ErrorBox error={update.error} />
        </div>
      ) : null}
    </form>
  )
}

/**
 * Nastavení appky po sekcích: základ, zprávy do kanálu, rozbory. Odpovědi mají vlastní
 * záložku, protože se k nim chodí z jiného důvodu než k času přehledu.
 */
function AppSettingsCard({ org, app }: { org: string; app: App }) {
  return (
    <Card title="Nastavení">
      <SettingsSection
        org={org}
        app={app}
        title="Základ"
        first
        initial={{ name: app.name, gpReportingBucket: app.gpReportingBucket ?? '' }}
        body={(values) => ({
          name: values.name,
          gpReportingBucket: values.gpReportingBucket === '' ? null : values.gpReportingBucket,
        })}
      >
        {(values, set) => (
          <>
            <Field label="Název">
              <input value={values.name} onChange={(e) => set('name', e.target.value)} required />
            </Field>
            {app.gpPackageName ? (
              <>
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
                <ReportingBucketProbe org={org} appId={app.id} bucket={values.gpReportingBucket} />
              </>
            ) : null}
          </>
        )}
      </SettingsSection>

      <SettingsSection
        org={org}
        app={app}
        title="Zprávy do kanálu"
        initial={{
          locale: app.locale.toLowerCase(),
          timezone: app.timezone,
          dailyDigestAt: app.dailyDigestAt.slice(0, 5),
        }}
        body={(values) => ({ locale: values.locale, timezone: values.timezone, dailyDigestAt: values.dailyDigestAt })}
      >
        {(values, set) => (
          <>
            <Field label="Jazyk zpráv" hint="Výchozí jazyk pro nové kanály; každý kanál si ho může přepnout zvlášť.">
              <Select
                value={values.locale}
                options={LOCALE_OPTIONS}
                onChange={(value) => set('locale', value)}
                ariaLabel="Jazyk zpráv"
              />
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
          </>
        )}
      </SettingsSection>

      <SettingsSection
        org={org}
        app={app}
        title="Rozbory"
        initial={{
          analysisCadence: app.analysisCadence,
          weeklyDigestDay: String(app.weeklyDigestDay),
          // Prázdné pole znamená „drž se platformy"; posílá se jako nula, což server bere
          // jako zrušení výjimky.
          analysisMinReviews: app.analysisThresholdSource === 'APP' ? String(app.analysisMinReviews) : '',
          analysisMinTopicCount: app.analysisThresholdSource === 'APP' ? String(app.analysisMinTopicCount) : '',
          historyMonths: String(app.historyMonths),
        }}
        body={(values) => ({
          analysisCadence: values.analysisCadence,
          weeklyDigestDay: Number(values.weeklyDigestDay),
          analysisMinReviews: values.analysisMinReviews === '' ? 0 : Number(values.analysisMinReviews),
          analysisMinTopicCount: values.analysisMinTopicCount === '' ? 0 : Number(values.analysisMinTopicCount),
          historyMonths: Number(values.historyMonths),
        })}
      >
        {(values, set) => (
          <>
            <Field label="Jak často chodí rozbor" hint="Měsíční kadence dává smysl u appky, které chodí pár recenzí týdně.">
              <Select
                value={values.analysisCadence}
                options={[{ value: 'WEEKLY', label: 'týdně' }, { value: 'MONTHLY', label: 'měsíčně' }]}
                onChange={(value) => set('analysisCadence', value)}
                ariaLabel="Četnost rozboru"
              />
            </Field>
            {values.analysisCadence === 'WEEKLY' ? (
              <Field label="Den týdenního rozboru" hint="Rozbor recenzí odejde v tenhle den ve stejný čas jako denní přehled.">
                <Select
                  value={values.weeklyDigestDay}
                  options={WEEK_DAYS.map((day, index) => ({ value: String(index + 1), label: day }))}
                  onChange={(value) => set('weeklyDigestDay', value)}
                  ariaLabel="Den týdenního rozboru"
                />
              </Field>
            ) : (
              <p className="small muted">Měsíční rozbor chodí prvního dne v měsíci ve stejný čas jako denní přehled.</p>
            )}
            <Field
              label="Nejmenší počet recenzí pro rozbor"
              hint={
                values.analysisMinReviews === ''
                  ? `Prázdné = platformní hodnota (${app.analysisMinReviews}). Pod prahem se termín přeskočí a období se přičte k příštímu.`
                  : 'Výjimka jen pro tuhle aplikaci. Smazáním pole se vrátí platformní hodnota.'
              }
            >
              <input
                type="number"
                min={1}
                value={values.analysisMinReviews}
                placeholder={String(app.analysisMinReviews)}
                onChange={(e) => set('analysisMinReviews', e.target.value)}
              />
            </Field>
            <Field
              label="Od kolika zmínek se ukáže téma"
              hint={
                values.analysisMinTopicCount === ''
                  ? `Prázdné = platformní hodnota (${app.analysisMinTopicCount}).`
                  : 'Výjimka jen pro tuhle aplikaci. Smazáním pole se vrátí platformní hodnota.'
              }
            >
              <input
                type="number"
                min={1}
                value={values.analysisMinTopicCount}
                placeholder={String(app.analysisMinTopicCount)}
                onChange={(e) => set('analysisMinTopicCount', e.target.value)}
              />
            </Field>
            <Field
              label="Historie k rozboru"
              hint={
                app.gpPackageName && !app.gpReportingBucket
                  ? 'Android historii vydá jen reporting Play Console — bez bucketu v sekci Základ zůstane u recenzí ode dneška.'
                  : 'Kolik měsíců zpátky se dotahují recenze pro rozbory. Prodloužení se dotáhne během chvíle.'
              }
            >
              <Select
                value={values.historyMonths}
                options={HISTORY_MONTHS.map((months) => ({ value: String(months), label: historyMonthsLabel(months) }))}
                onChange={(value) => set('historyMonths', value)}
                ariaLabel="Historie k rozboru"
              />
            </Field>
          </>
        )}
      </SettingsSection>
    </Card>
  )
}

/**
 * Jak appka odpovídá sama: automatické poděkování za 5 ★ a tón AI návrhů. Je to vedle
 * šablon, protože obojí řeší tutéž otázku — co klient říká recenzentům.
 */
function ReplySettingsCard({ org, app }: { org: string; app: App }) {
  return (
    <Card title="Automatické poděkování a AI návrhy">
      <SettingsSection
        org={org}
        app={app}
        initial={{
          autoThanksEnabled: app.autoThanksEnabled ? 'ano' : 'ne',
          autoThanksTemplate: app.autoThanksTemplate ?? '',
          aiInstructions: app.aiInstructions ?? '',
        }}
        body={(values) => ({
          autoThanksEnabled: values.autoThanksEnabled === 'ano',
          autoThanksTemplate: values.autoThanksTemplate,
          aiInstructions: values.aiInstructions === '' ? null : values.aiInstructions,
        })}
      >
        {(values, set) => (
          <>
            <Field
              label="Automaticky děkovat za 5 ★"
              hint="Odešle se bez schválení. Jen u recenzí s pěti hvězdami, které nic nekritizují — a jen když k nim AI vyloží, že jde o pochvalu."
            >
              <Select
                value={values.autoThanksEnabled}
                options={[
                  { value: 'ne', label: 'Ne, odpovídáme sami' },
                  { value: 'ano', label: 'Ano, poděkovat automaticky' },
                ]}
                onChange={(value) => set('autoThanksEnabled', value)}
                ariaLabel="Automatické poděkování"
              />
            </Field>
            {values.autoThanksEnabled === 'ano' ? (
              <Field
                label="Záložní text poděkování"
                hint="Použije se, když AI návrh chybí. Bez návrhu i bez textu se nic neodešle. Google Play přijme nejvýš 350 znaků."
              >
                <textarea
                  value={values.autoThanksTemplate}
                  maxLength={350}
                  onChange={(e) => set('autoThanksTemplate', e.target.value)}
                />
              </Field>
            ) : null}
            <Field
              label="Instrukce pro AI návrhy"
              hint="Tón odpovědí, čemu se vyhnout, jak podepisovat. Nechej prázdné, když návrhy nechceš ovlivňovat."
            >
              <textarea value={values.aiInstructions} onChange={(e) => set('aiInstructions', e.target.value)} />
            </Field>
          </>
        )}
      </SettingsSection>
    </Card>
  )
}

/** Delší text do tabulky: celý se čte v dialogu, v řádku stačí začátek. */
function shorten(text: string, max: number): string {
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > max ? `${flat.slice(0, max - 1).trimEnd()}…` : flat
}

/** Horní mez textu šablony — server ji hlídá taky, tady je kvůli počítadlu. */
const TEMPLATE_MAX = 5000
/** Nejdelší odpověď, kterou Google Play přijme; delší šablona se v inboxu musí zkrátit. */
const GOOGLE_PLAY_REPLY_MAX = 350

/**
 * Šablony odpovědí (C2). Opakované odpovědi — poděkování, odkaz na podporu, „opraveno ve
 * verzi X" — se napíšou jednou a v inboxu se jen vyberou.
 */
function ReplyTemplatesCard({ org, appId }: { org: string; appId: string }) {
  const templates = useReplyTemplates(org, appId)
  const remove = useDeleteReplyTemplate(org, appId)
  const [editing, setEditing] = useState<ReplyTemplate | 'new' | null>(null)
  const [removing, setRemoving] = useState<ReplyTemplate | null>(null)

  return (
    <Card title="Šablony odpovědí">
      {templates.isPending ? <Loading /> : null}
      <ErrorBox error={templates.error} />
      {templates.data?.length === 0 ? (
        <p className="muted">
          Zatím žádná. Šablona ušetří psaní u opakovaných odpovědí — poděkování, odkaz na podporu, „opraveno ve
          verzi X".
        </p>
      ) : null}
      {templates.data && templates.data.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Název</th>
              <th>Text</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {templates.data.map((template) => (
              <tr key={template.id}>
                <td>{template.name}</td>
                <td className="small muted wrap">{shorten(template.body, 80)}</td>
                <td className="nowrap">
                  <div className="row">
                    <button type="button" className="secondary" onClick={() => setEditing(template)}>
                      Upravit
                    </button>
                    <button type="button" className="danger" onClick={() => setRemoving(template)}>
                      Smazat
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <div style={{ marginTop: templates.data && templates.data.length > 0 ? '1.1rem' : '0.75rem' }}>
        <button type="button" onClick={() => setEditing('new')}>
          Přidat šablonu
        </button>
      </div>
      <ErrorBox error={remove.error} />

      {editing ? (
        <ReplyTemplateDialog
          org={org}
          appId={appId}
          template={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      ) : null}
      {removing ? (
        <Modal title="Smazat šablonu" onClose={() => setRemoving(null)}>
          <div className="stack">
            <p>
              Opravdu smazat <strong>{removing.name}</strong>? Odpovědi, které z ní už odešly, zůstanou — jen ji
              příště nebude z čeho vybrat.
            </p>
            <div className="row">
              <button
                type="button"
                className="danger"
                disabled={remove.isPending}
                onClick={() => remove.mutate(removing.id, { onSuccess: () => setRemoving(null) })}
              >
                Smazat šablonu
              </button>
              <button type="button" className="secondary" onClick={() => setRemoving(null)}>
                Nechat
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </Card>
  )
}

/** Nová i upravovaná šablona v jednom dialogu — liší se jen tím, čím je předvyplněný. */
function ReplyTemplateDialog({
  org,
  appId,
  template,
  onClose,
}: {
  org: string
  appId: string
  template: ReplyTemplate | null
  onClose: () => void
}) {
  const create = useCreateReplyTemplate(org, appId)
  const update = useUpdateReplyTemplate(org, appId)
  const [name, setName] = useState(template?.name ?? '')
  const [body, setBody] = useState(template?.body ?? '')
  const pending = create.isPending || update.isPending
  const ready = name.trim() !== '' && body.trim() !== ''

  return (
    <Modal title={template ? 'Upravit šablonu' : 'Nová šablona'} onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          const input = { name: name.trim(), body }
          if (template) update.mutate({ id: template.id, ...input }, { onSuccess: onClose })
          else create.mutate(input, { onSuccess: onClose })
        }}
      >
        <Field label="Název" hint="Pod tímhle názvem šablonu vybereš v inboxu.">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Poděkování" autoFocus required />
        </Field>
        <Field
          label="Text"
          hint="Proměnné: {jmeno} = jméno recenzenta, {appka} = název appky, {verze} = verze. Google Play přijme nejvýš 350 znaků."
        >
          <textarea
            value={body}
            maxLength={TEMPLATE_MAX}
            rows={6}
            onChange={(e) => setBody(e.target.value)}
            placeholder="Díky, {jmeno}! Opraveno ve verzi {verze}."
            required
          />
        </Field>
        <div className="template-count small muted">
          {body.length} / {TEMPLATE_MAX} znaků
          {body.length > GOOGLE_PLAY_REPLY_MAX ? <span className="warn"> · delší než limit Google Play</span> : null}
        </div>
        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={create.error ?? update.error} />
          <div className="row">
            <button type="submit" disabled={!ready || pending}>
              {pending ? 'Ukládám…' : template ? 'Uložit' : 'Přidat šablonu'}
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
          Zatím žádná data. Přehled chodí každý den v čase ze záložky Nastavení; první běh jde spustit i rovnou.
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
  // Nula je rozpracovaný den v exportu, ne hodnocení — hlavička i graf ji přeskakují.
  const valid = series.points.filter((point) => point.average != null && point.average > 0)
  const latest = valid[valid.length - 1]
  const first = valid[0]
  const newRatings = series.points.reduce((sum, point) => sum + (point.newCount ?? 0), 0)
  const rawChange = latest?.average != null && first?.average != null && valid.length > 1 ? latest.average - first.average : null
  // Pod setinu je to šum: „▼ 0.00" by červeně hlásilo pokles, který neexistuje.
  const change = rawChange == null ? null : Math.abs(rawChange) < 0.005 ? 0 : rawChange

  return (
    <div className="stack" style={{ marginBottom: '1.5rem' }}>
      <div className="row">
        <PlatformBadge platform={series.platform} />
        <strong>{latest?.average != null ? latest.average.toFixed(2) : '—'}</strong>
        {change != null ? (
          <Badge tone={change > 0 ? 'ok' : change < 0 ? 'bad' : undefined}>
            {change > 0 ? '▲' : change < 0 ? '▼' : '='} {change === 0 ? 'beze změny' : `${Math.abs(change).toFixed(2)} za období`}
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

/**
 * Potvrzení před smazáním klíče.
 *
 * Mazání klíče je jediná akce v kartě, po které appka přestane sledovat recenze, takže se
 * na ni ptáme. Text říká i to, co se **nestane**: u spravovaného účtu zůstává pozvánka
 * v Play Console v platnosti, protože účet nemažeme — kdo si store napojí znovu, dostane
 * tentýž e-mail a v cizí konzoli už nic řešit nemusí.
 */
function RemoveCredentialDialog({
  credential,
  pending,
  onClose,
  onConfirm,
}: {
  credential: Credential
  pending: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const managed = credential.origin === 'PROVISIONED'

  return (
    <Modal title="Odebrat klíč" onClose={onClose}>
      <div className="stack">
        <p>
          Opravdu odebrat <strong>{credential.label}</strong>? Aplikace, které ho používají, spadnou zpátky do „čeká na
          nastavení" a přestanou stahovat recenze.
        </p>
        <p className="small muted">
          {managed
            ? 'Účet ' +
              (credential.hint ?? '') +
              ' v Play Console rušit nemusíš — necháme ho být a jen zneplatníme klíč. Když store napojíš znovu, ' +
              'dostaneš tentýž e-mail a pozvánka bude platit dál.'
            : 'Klíč u nás smažeme; v konzoli storu ho zruš sám, ať nikde nezůstane platný.'}
        </p>
        <div className="row">
          <button type="button" className="danger" disabled={pending} onClick={onConfirm}>
            Odebrat klíč
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Nechat
          </button>
        </div>
      </div>
    </Modal>
  )
}

function CredentialsCard({
  org,
  app,
  onConnect,
}: {
  org: string
  app: App
  onConnect: (platform: Platform) => void
}) {
  const appId = app.id
  const focus = useSetupFocus('klice')
  const credentials = useCredentials(org)
  const add = useAddCredential(org)
  const attach = useAttachCredential(org)
  const validate = useValidateCredential(org)
  const remove = useDeleteCredential(org)
  const [removing, setRemoving] = useState<Credential | null>(null)
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
      {/* Napojení přes dialog je hlavní cesta: u Google Play za klienta vyrobí service
          account, u App Storu ho provede konzolí Applu a aplikace vybere ze seznamu. */}
      <div className="row" style={{ marginBottom: '1rem' }}>
        <button type="button" onClick={() => onConnect('ANDROID')}>
          Připojit Google Play
        </button>
        <button type="button" onClick={() => onConnect('IOS')}>
          Připojit App Store
        </button>
      </div>
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
                  {credential.label} {credential.origin === 'PROVISIONED' ? <Badge>spravovaný námi</Badge> : null}
                  <div className="small muted">{credential.hint}</div>
                </td>
                <td className="small muted">{credential.fingerprint}</td>
                <td>
                  {credential.validationStatus === 'VALID' ? (
                    <>
                      <Badge tone="ok">funguje</Badge>
                      <div className="small muted">
                        <When iso={credential.validatedAt} />
                      </div>
                    </>
                  ) : credential.validationStatus === 'INVALID' ? (
                    <Badge tone="bad">{credential.validationError ?? 'neplatný'}</Badge>
                  ) : (
                    <Badge tone="warn">neověřený</Badge>
                  )}
                </td>
                <td className="nowrap">
                  <div className="row">
                    {/* Organizace může mít pro tentýž store víc klíčů — spravovaný účet vedle
                        vlastního enterprise klíče. Přiřazovat jde jen ten, který appka
                        nepoužívá; u toho druhého by tlačítko nedělalo nic. */}
                    {app.setup.credentialIds.includes(credential.id) ? (
                      <span className="small muted">používá tahle appka</span>
                    ) : (
                      <button
                        type="button"
                        className="secondary"
                        disabled={attach.isPending}
                        onClick={() => attach.mutate({ appId, credentialId: credential.id })}
                      >
                        Přiřadit k appce
                      </button>
                    )}
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
                    <button type="button" className="danger" onClick={() => setRemoving(credential)}>
                      Odebrat
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {result ? <div className="notice" style={{ marginTop: '0.75rem' }}>{result}</div> : null}
      <ErrorBox error={attach.error ?? validate.error ?? remove.error} />

      {removing ? (
        <RemoveCredentialDialog
          credential={removing}
          pending={remove.isPending}
          onClose={() => setRemoving(null)}
          onConfirm={() => remove.mutate(removing.id, { onSuccess: () => setRemoving(null) })}
        />
      ) : null}

      {/* Ruční nahrání zůstává pro dva případy, které dialog neobslouží: enterprise s vlastním
          service accountem a individuální (ne týmový) klíč z App Store Connect. */}
      <details className="optional" style={{ marginTop: '1.25rem' }}>
        <summary>Pokročilé: nahrát vlastní klíč</summary>
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
          <Select
            value={type}
            options={[
              { value: 'gp', label: 'Google Play — service account (JSON)' },
              { value: 'asc', label: 'App Store Connect — API klíč (.p8)' },
            ]}
            onChange={setType}
            ariaLabel="Store"
          />
        </Field>
        <Field label="Štítek" hint="Jak klíč poznáš ve výpisu.">
          <input value={label} onChange={(e) => setLabel(e.target.value)} required />
        </Field>
        <Field
          label="Soubor s klíčem"
          hint={
            type === 'gp'
              ? 'JSON service accountu z Play Console → Setup → API access. Stačí právo číst recenze a odpovídat na ně.'
              : '.p8 z App Store Connect → Users and Access → Integrations. Stačí role na čtení recenzí (Customer Support, případně Admin).'
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
      </details>
    </Card>
  )
}

/**
 * Rozbory recenzí (F8): kolik recenzí má výklad a co si k obecné taxonomii klient přidal.
 *
 * Stav výkladu je tu ta hlavní informace — když je stránka Rozbory prázdná, odpověď na
 * „proč" je skoro vždycky tady.
 */
function AnalysisCard({ org, appId }: { org: string; appId: string }) {
  const status = useAnalysisStatus(org, appId)
  const backfill = useBackfillAnalysis(org, appId)
  const topics = useTopics(org, appId)
  const removeTopic = useDeleteTopic(org, appId)
  const [adding, setAdding] = useState(false)

  const custom = (topics.data ?? []).filter((topic) => topic.custom)
  const total = status.data ? status.data.analyzed + status.data.missing : 0

  return (
    <Card title="Rozbory recenzí">
      <ErrorBox error={status.error} />
      {status.data ? (
        <p className="small muted">
          Výklad má {status.data.analyzed} z {total} recenzí
          {status.data.missing > 0 ? ` — ${status.data.missing} čeká.` : '.'} Taxonomie {status.data.taxonomyVersion}.
        </p>
      ) : null}
      <div className="row">
        <button
          type="button"
          className="secondary"
          disabled={backfill.isPending || status.data?.missing === 0}
          onClick={() => backfill.mutate()}
        >
          {backfill.isPending ? 'Zařazuji…' : 'Doplnit za historii'}
        </button>
        <button type="button" className="secondary" onClick={() => setAdding(true)}>
          Přidat vlastní téma
        </button>
      </div>
      <ErrorBox error={backfill.error} />
      {backfill.isSuccess ? (
        <div className="notice" style={{ marginTop: '0.5rem' }}>
          Doplňování běží na pozadí v dávkách. Za chvíli obnov stránku.
        </div>
      ) : null}

      <h3 style={{ marginTop: '1.25rem' }}>Vlastní témata</h3>
      {custom.length === 0 ? (
        <p className="muted">
          Zatím žádné. Vlastní téma se hodí na to, co obecná taxonomie nezná — třeba
          synchronizaci s konkrétním zařízením nebo školní účty.
        </p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Téma</th>
              <th>Popis pro AI</th>
              <th>Za 30 dní</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {custom.map((topic) => (
              <tr key={topic.key}>
                <td>
                  {topic.name}
                  {topic.enabled ? null : <div className="small muted">vypnuté</div>}
                </td>
                <td className="small muted">{topic.description}</td>
                <td>{topic.recentCount}</td>
                <td>
                  <button
                    type="button"
                    className="danger"
                    onClick={() => removeTopic.mutate(topic.key.replace('custom:', ''))}
                  >
                    Smazat
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ErrorBox error={removeTopic.error} />
      {adding ? <AddTopicDialog org={org} appId={appId} onClose={() => setAdding(false)} /> : null}
    </Card>
  )
}

function AddTopicDialog({ org, appId, onClose }: { org: string; appId: string; onClose: () => void }) {
  const create = useCreateTopic(org, appId)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  return (
    <Modal title="Vlastní téma" onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          create.mutate({ name, description }, { onSuccess: onClose })
        }}
      >
        <Field label="Název" hint="Takhle se téma bude jmenovat ve filtru a v rozborech.">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Garmin" required />
        </Field>
        <Field
          label="Popis (anglicky)"
          hint="Popis čte AI, proto anglicky — z něj pozná, co do tématu patří. Příklad: Problems syncing workouts from Garmin watches."
        >
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Problems syncing workouts from Garmin watches."
            required
          />
        </Field>
        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={create.error} />
          <div className="row">
            <button type="submit" disabled={create.isPending}>
              Přidat téma
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

/**
 * Co se do kanálu posílá. Tři nezávislé věci: jednotlivé recenze, denní přehled hodnocení
 * a týdenní rozbor — a tým, který chce jen rozbory, si zbytek vypne.
 */
const DELIVERIES: Array<{ key: 'deliverReviews' | 'deliverRatings' | 'deliverAnalyses'; label: string }> = [
  { key: 'deliverReviews', label: 'Recenze' },
  { key: 'deliverRatings', label: 'Hodnocení' },
  { key: 'deliverAnalyses', label: 'Rozbory' },
]

/** Jen zapnuté druhy zpráv — vypnuté v tabulce nikoho nezajímají, mění se dialogem. */
function deliveriesLabel(channel: Channel): string {
  const enabled = DELIVERIES.filter((item) => channel[item.key]).map((item) => item.label)
  return enabled.length > 0 ? enabled.join(' · ') : 'nic'
}

/**
 * Úprava kanálu. Jazyk kanálu je nezávislý na jazyku appky — anglický kanál pro zahraniční
 * tým vedle českého. Na server jdou jen změněná pole; vynechaná nechá, jak jsou.
 */
function EditChannelDialog({
  org,
  appId,
  channel,
  onClose,
}: {
  org: string
  appId: string
  channel: Channel
  onClose: () => void
}) {
  const update = useUpdateChannel(org, appId)
  const [locale, setLocale] = useState<'cs' | 'en'>(channel.locale === 'EN' ? 'en' : 'cs')
  const [enabled, setEnabled] = useState(channel.enabled)
  const [deliver, setDeliver] = useState({
    deliverReviews: channel.deliverReviews,
    deliverRatings: channel.deliverRatings,
    deliverAnalyses: channel.deliverAnalyses,
  })

  const changes: Omit<Parameters<typeof update.mutate>[0], 'id'> = {}
  if (locale !== (channel.locale === 'EN' ? 'en' : 'cs')) changes.locale = locale
  if (enabled !== channel.enabled) changes.enabled = enabled
  for (const item of DELIVERIES) {
    if (deliver[item.key] !== channel[item.key]) changes[item.key] = deliver[item.key]
  }
  const dirty = Object.keys(changes).length > 0

  return (
    <Modal title="Upravit kanál" onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          update.mutate({ id: channel.id, ...changes }, { onSuccess: onClose })
        }}
      >
        <p className="small muted" style={{ marginTop: 0 }}>
          {channel.targetLabel ?? channel.targetRef}
          {channel.targetLabel ? ` · ${channel.targetRef}` : ''}
        </p>
        <Field
          label="Jazyk zpráv"
          hint="Jazyk kanálu je nezávislý na jazyku appky — anglický kanál pro zahraniční tým vedle českého."
        >
          <Select
            value={locale}
            options={LOCALE_OPTIONS}
            onChange={(value) => setLocale(value === 'en' ? 'en' : 'cs')}
            ariaLabel="Jazyk zpráv kanálu"
          />
        </Field>
        <div className="field">
          <label>Co do kanálu chodí</label>
          <div className="stack picklist">
            {DELIVERIES.map((item) => (
              <label key={item.key} className="pick">
                <input
                  type="checkbox"
                  checked={deliver[item.key]}
                  onChange={(event) => setDeliver({ ...deliver, [item.key]: event.target.checked })}
                />
                <span>{item.label}</span>
              </label>
            ))}
          </div>
        </div>
        <Field label="Stav" hint="Pozastavený kanál zůstane napojený, jen do něj nic nechodí.">
          <Select
            value={enabled ? 'on' : 'off'}
            options={[
              { value: 'on', label: 'zapnutý' },
              { value: 'off', label: 'pozastavený' },
            ]}
            onChange={(value) => setEnabled(value === 'on')}
            ariaLabel="Stav kanálu"
          />
        </Field>
        <div className="stack" style={{ marginTop: '1rem' }}>
          <ErrorBox error={update.error} />
          <div className="row">
            <button type="submit" disabled={!dirty || update.isPending}>
              {update.isPending ? 'Ukládám…' : 'Uložit'}
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

function ChannelsCard({ org, app }: { org: string; app: App }) {
  const appId = app.id
  const focus = useSetupFocus('kanaly')
  const channels = useChannels(org, appId)
  const credentials = useCredentials(org)
  const create = useCreateChannel(org, appId)
  const remove = useDeleteChannel(org, appId)
  const test = useTestChannels(org, appId)
  const connect = useConnectSlack(org)
  const [targetRef, setTargetRef] = useState('')
  const [credentialId, setCredentialId] = useState('')
  // Nový kanál dostane jazyk appky — to je nejčastější případ; cizojazyčný tým si ho přepne.
  const [locale, setLocale] = useState(app.locale === 'EN' ? 'en' : 'cs')
  const [token, setToken] = useState('')
  const [checks, setChecks] = useState<ChannelCheck[] | null>(null)
  const [editing, setEditing] = useState<Channel | null>(null)

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
              <th>Co chodí</th>
              <th>Stav</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {channels.data?.map((channel) => (
              <tr key={channel.id}>
                <td>
                  {channel.targetLabel ?? channel.targetRef}
                  {channel.targetLabel ? <div className="small muted">{channel.targetRef}</div> : null}
                </td>
                <td className="small">{localeLabel(channel.locale)}</td>
                <td className="small muted">{deliveriesLabel(channel)}</td>
                <td>{channel.enabled ? <Badge tone="ok">zapnutý</Badge> : <Badge tone="warn">pozastavený</Badge>}</td>
                <td className="nowrap">
                  <div className="row">
                    <button type="button" className="secondary" onClick={() => setEditing(channel)}>
                      Upravit
                    </button>
                    <button type="button" className="danger" onClick={() => remove.mutate(channel.id)}>
                      Odpojit
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ErrorBox error={remove.error} />

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

      {editing ? <EditChannelDialog org={org} appId={appId} channel={editing} onClose={() => setEditing(null)} /> : null}

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
                { targetRef, credentialId: credentialId || (installs[0]?.id ?? ''), locale },
                { onSuccess: () => setTargetRef('') },
              )
            }}
          >
            <Field label="Workspace">
              <Select
                value={credentialId || (installs[0]?.id ?? '')}
                options={installs.map((install) => ({ value: install.id, label: install.hint ?? install.label }))}
                onChange={setCredentialId}
                ariaLabel="Slack workspace"
              />
            </Field>
            <Field
              label="ID kanálu"
              hint="Ve Slacku: klikni na kanál → View channel details → dole je ID (začíná C). Jméno kanálu se mění, ID ne."
            >
              <input value={targetRef} onChange={(e) => setTargetRef(e.target.value)} placeholder="C0123456789" required />
            </Field>
            <Field
              label="Jazyk zpráv"
              hint="Jazyk kanálu je nezávislý na jazyku appky — anglický kanál pro zahraniční tým vedle českého."
            >
              <Select value={locale} options={LOCALE_OPTIONS} onChange={setLocale} ariaLabel="Jazyk zpráv kanálu" />
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
