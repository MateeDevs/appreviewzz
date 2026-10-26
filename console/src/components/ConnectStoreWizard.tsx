import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useApps, useCredentials, useMe } from '../api/hooks'
import type { App, Platform, ReportingBucketCheck } from '../api/types'
import { Badge, ErrorBox, Loading } from './ui'
import { AppIdentityForm } from './AppIdentityForm'
import { AppStoreSetup } from './AppStoreSetup'
import { GooglePlaySetup } from './GooglePlaySetup'
import {
  SetupWizardFrame,
  WizardActivity,
  WizardActions,
  WizardClose,
  WizardHeading,
} from './SetupWizardFrame'
import { storeName } from './storeSetupShared'

type Page = 'identity' | 'overview' | 'ANDROID' | 'IOS' | 'result'

export function ConnectStoreWizard({
  org,
  platform,
  app,
  onClose,
}: {
  org: string
  platform: Platform
  app?: App
  onClose: () => void
}) {
  return (
    <AppSetupWizard
      key={`${org}:${app?.id ?? 'new'}:${platform}`}
      org={org}
      initialApp={app}
      initialPlatform={platform}
      onClose={onClose}
    />
  )
}

/** Shared identity form for onboarding; its existing persisted orchestration remains authoritative. */
export function AddAppDialog({
  org,
  inline = false,
  ownOnly = false,
  onConnect,
  onClose,
  initialApp,
}: {
  org: string
  inline?: boolean
  ownOnly?: boolean
  onConnect: (app: App, platform: Platform) => void
  onClose: () => void
  initialApp?: App
}) {
  if (inline)
    return (
      <div className="first-app-form">
        <AppIdentityForm
          key={org}
          org={org}
          ownOnly={ownOnly}
          onSaved={(app) => onConnect(app, app.gpPackageName ? 'ANDROID' : 'IOS')}
        />
      </div>
    )
  return <AppSetupWizard key={org} org={org} initialApp={initialApp} onClose={onClose} />
}

function AppSetupWizard({
  org,
  initialApp,
  initialPlatform,
  onClose,
}: {
  org: string
  initialApp?: App
  initialPlatform?: Platform
  onClose: () => void
}) {
  const role = useMe().data?.organizations.find((item) => item.slug === org)?.role
  const apps = useApps(org),
    credentials = useCredentials(org)
  const [reporting, setReporting] = useState<ReportingBucketCheck['status'] | null>(null)
  const [target, setTarget] = useState<App | undefined>(initialApp)
  const [page, setPage] = useState<Page>(initialApp ? (initialPlatform ?? 'overview') : 'identity')
  const [started, setStarted] = useState<Set<Platform>>(
    new Set(initialApp && initialPlatform ? [initialPlatform] : []),
  )
  const [dirty, setDirty] = useState({ identity: false, google: false, apple: false })
  const [busy, setBusy] = useState({ identity: false, google: false, apple: false })
  const [params, setParams] = useSearchParams(),
    location = useLocation()
  const app = apps.data?.find((item) => item.id === target?.id) ?? target
  const setIdentityDirty = useCallback(
    (value: boolean) => setDirty((s) => ({ ...s, identity: value })),
    [],
  )
  const setGoogleDirty = useCallback(
    (value: boolean) => setDirty((s) => ({ ...s, google: value })),
    [],
  )
  const setAppleDirty = useCallback(
    (value: boolean) => setDirty((s) => ({ ...s, apple: value })),
    [],
  )
  const setIdentityBusy = useCallback(
    (value: boolean) => setBusy((s) => ({ ...s, identity: value })),
    [],
  )
  const setGoogleBusy = useCallback(
    (value: boolean) => setBusy((s) => ({ ...s, google: value })),
    [],
  )
  const setAppleBusy = useCallback((value: boolean) => setBusy((s) => ({ ...s, apple: value })), [])
  const state = (platform: Platform) => {
    if (!app || app.setup.platformsWithoutKey.includes(platform)) return 'missing'
    if (app.setup.platformsWaitingForKey.includes(platform)) return 'waiting'
    return 'verified'
  }
  const open = (platform: Platform) => {
    setStarted((s) => new Set([...s, platform]))
    setPage(platform)
  }
  useEffect(() => {
    // Resume references only an existing resource, never a private draft or credential.
    if (
      initialPlatform ||
      !target ||
      !location.pathname.endsWith('/aplikace') ||
      params.get('setup') === target.id
    )
      return
    const next = new URLSearchParams(params)
    next.set('setup', target.id)
    setParams(next, { replace: true })
  }, [target, initialPlatform, location.pathname, params, setParams])
  const allBusy = Object.values(busy).some(Boolean)
  const phases = [
    { id: 'identity', label: 'Aplikace', detail: app?.name ?? 'Odkazy a název', done: !!app },
    { id: 'overview', label: 'Připojení', detail: 'Platformy a předpoklady' },
    ...(app?.competitor
      ? []
      : (app?.platforms ?? []).map((platform) => ({
          id: platform,
          label: storeName(platform),
          detail:
            state(platform) === 'verified'
              ? 'Přístup ověřený'
              : state(platform) === 'waiting'
                ? 'Čeká na ověření'
                : 'Připojení před vámi',
          done: state(platform) === 'verified',
        }))),
    { id: 'result', label: 'Výsledek', detail: 'Uloženo a další kroky' },
  ]
  const summary = () => {
    setPage('result')
    void apps.refetch()
    void credentials.refetch()
  }
  if (role === 'MEMBER')
    return (
      <SetupWizardFrame
        title="Připojení aplikace"
        phases={[]}
        current=""
        dirty={false}
        busy={false}
        onClose={onClose}
      >
        <section className="setup-step stack">
          <WizardHeading eyebrow="OPRÁVNĚNÍ" title="Připojení spravuje administrátor">
            Požádejte vlastníka nebo administrátora tohoto pracovního prostoru o připojení aplikace.
            Vaše role umožňuje práci s dostupnými recenzemi.
          </WizardHeading>
          <WizardActions>
            <WizardClose>Zavřít průvodce</WizardClose>
          </WizardActions>
        </section>
      </SetupWizardFrame>
    )
  return (
    <SetupWizardFrame
      title={
        initialPlatform
          ? page === 'ANDROID' || page === 'IOS'
            ? `Připojit ${storeName(page)}`
            : 'Připojení aplikace'
          : 'Přidat aplikaci'
      }
      phases={phases}
      current={page}
      dirty={Object.values(dirty).some(Boolean)}
      busy={allBusy}
      onClose={onClose}
    >
      {page === 'identity' ? (
        <section data-wizard-active="true">
          <AppIdentityForm
            org={org}
            onDirty={setIdentityDirty}
            onBusy={setIdentityBusy}
            onSaved={(saved) => {
              setTarget(saved)
              setBusy((s) => ({ ...s, identity: false }))
              setDirty((s) => ({ ...s, identity: false }))
              setPage(saved.competitor ? 'result' : 'overview')
            }}
          />
        </section>
      ) : null}
      {app && page === 'overview' ? (
        <section className="setup-step stack" data-wizard-active="true">
          <WizardHeading eyebrow="02 / PŘIPOJENÍ" title="Otevřete cestu k recenzím">
            Aplikace {app.name} je uložená. Každý store připojíte a ověříte samostatně; pořadí si
            můžete zvolit.
          </WizardHeading>
          <PlatformRows app={app} state={state} onOpen={open} />
          <p className="notice">
            Google Play: potřebujete správce Play Console, který pozve náš účet. App Store: Account
            Holder nebo Admin vytvoří týmový klíč s rolí Customer Support. Pokud tato oprávnění
            nemáte, požádejte správce o pomoc; uloženou aplikaci najdete i po zavření v portfoliu.
          </p>
          <p className="small muted">
            Identita uložené aplikace se při připojování nemění. Úprava názvu a další nastavení jsou
            dostupné v detailu.
          </p>
          <WizardActions>
            <button
              type="button"
              onClick={() =>
                open(app.platforms.find((p) => state(p) !== 'verified') ?? app.platforms[0]!)
              }
            >
              Pokračovat v připojení
            </button>
            <button type="button" className="secondary" onClick={summary}>
              Dokončit později
            </button>
          </WizardActions>
        </section>
      ) : null}
      {app && started.has('ANDROID') ? (
        <section hidden={page !== 'ANDROID'} data-wizard-active={page === 'ANDROID'}>
          <WizardActivity active={page === 'ANDROID'}>
            <GooglePlaySetup
              org={org}
              app={app}
              onReporting={setReporting}
              active={page === 'ANDROID'}
              onBack={() => setPage('overview')}
              onDone={summary}
              onBusy={setGoogleBusy}
              onDirty={setGoogleDirty}
            />
          </WizardActivity>
        </section>
      ) : null}
      {app && started.has('IOS') ? (
        <section hidden={page !== 'IOS'} data-wizard-active={page === 'IOS'}>
          <WizardActivity active={page === 'IOS'}>
            <AppStoreSetup
              org={org}
              app={app}
              allowMultiple={!initialPlatform}
              active={page === 'IOS'}
              onBack={() => setPage('overview')}
              onDone={summary}
              onBusy={setAppleBusy}
              onDirty={setAppleDirty}
            />
          </WizardActivity>
        </section>
      ) : null}
      {page === 'result' && app ? (
        <section className="setup-step stack" data-wizard-active="true">
          <WizardHeading
            eyebrow="VÝSLEDEK / ULOŽENÉ NASTAVENÍ"
            title={
              app.competitor
                ? 'Konkurence je přidaná'
                : app.platforms.every((p) => state(p) === 'verified')
                  ? 'Připojení aplikace je připravené'
                  : 'Aplikace je uložená. Připojení můžete dokončit.'
            }
          >
            {app.name}
            {app.competitor
              ? ' sledujeme z dostupných veřejných zdrojů. Vlastní klíče nejsou potřeba.'
              : ' zůstává v portfoliu se stavem každé platformy. Ověřený přístup a načtené recenze jsou dva různé kroky.'}
          </WizardHeading>
          {!app.competitor ? (
            <>
              <PlatformRows app={app} state={state} onOpen={open} />
              {app.gpPackageName ? (
                <div className="notice">
                  <strong>Oficiální hodnocení Google Play</strong>
                  <p>
                    {reporting === 'OK'
                      ? 'Export hodnocení pro tuto aplikaci byl nalezen. Adresa je uložená; načtení dat proběhne při synchronizaci.'
                      : reporting === 'NO_EXPORT'
                        ? 'Adresa je uložená a bucket je dostupný. Export pro tuto aplikaci zatím chybí; oficiální hodnocení nejsou potvrzená.'
                        : app.gpReportingBucket
                          ? 'Adresa exportů je uložená. Pro aktuální ověření dostupnosti exportu otevřete Google Play → Připojit také exporty. Uložená adresa sama nepotvrzuje funkčnost hodnocení.'
                          : 'Exporty nejsou připojené. Oficiální hodnocení a historie z exportů zůstávají odložené.'}
                  </p>
                </div>
              ) : null}
              <p className="small muted">
                Stav prvního importu a načtení recenzí najdete v průvodci aktivací. Historie, AI
                preference a Slack/Teams zůstávají dostupné v nastavení.
              </p>
            </>
          ) : (
            <p className="notice">
              App Store: veřejný feed recenzí v omezeném rozsahu. Google Play: veřejné hodnocení bez
              textů recenzí. Toto sledování nenahrazuje aktivaci vlastní aplikace.
            </p>
          )}
          <ErrorBox error={apps.error ?? credentials.error} />
          {apps.isFetching ? <Loading what="Obnovujeme uložené stavy…" /> : null}
          <WizardActions>
            {!app.competitor && app.platforms.some((p) => state(p) !== 'verified') ? (
              <button
                type="button"
                onClick={() => open(app.platforms.find((p) => state(p) !== 'verified')!)}
              >
                Pokračovat: {storeName(app.platforms.find((p) => state(p) !== 'verified')!)}
              </button>
            ) : (
              <Link className="button" to={`/${org}/recenze?app=${app.id}`}>
                Otevřít recenze
              </Link>
            )}
            <Link className="button secondary" to={`/${org}/aplikace/${app.id}`}>
              Nastavení aplikace
            </Link>
            <WizardClose>
              {location.pathname.endsWith('/onboarding') ? 'Zpět do aktivace' : 'Hotovo'}
            </WizardClose>
          </WizardActions>
        </section>
      ) : null}
    </SetupWizardFrame>
  )
}
function PlatformRows({
  app,
  state,
  onOpen,
}: {
  app: App
  state: (platform: Platform) => string
  onOpen: (platform: Platform) => void
}) {
  return (
    <div className="setup-platforms">
      {app.platforms.map((platform) => (
        <div className="setup-platform-row" key={platform}>
          <div>
            <strong>{storeName(platform)}</strong>
            <code>{platform === 'ANDROID' ? app.gpPackageName : app.ascAppId}</code>
          </div>
          <Badge tone={state(platform) === 'verified' ? 'ok' : 'warn'}>
            {state(platform) === 'verified'
              ? 'Přístup ověřený'
              : state(platform) === 'waiting'
                ? 'Přístup zatím nepotvrzený'
                : 'Nepřipojeno'}
          </Badge>
          <button type="button" className="secondary" onClick={() => onOpen(platform)}>
            {state(platform) === 'verified' ? 'Upravit připojení' : 'Připojit store'}
          </button>
        </div>
      ))}
    </div>
  )
}
