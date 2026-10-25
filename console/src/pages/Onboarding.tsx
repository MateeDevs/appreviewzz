import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  useApps,
  useAttachCredential,
  useCredentials,
  useHealth,
  useMe,
  useReviewCount,
  useValidateCredential,
} from '../api/hooks'
import {
  onboardingSteps,
  useOnboarding,
  useSaveOnboarding,
  useOnboardingSync,
  useRunOnboardingSync,
  type OnboardingProgress,
  type OnboardingStep,
} from '../api/onboarding'
import {
  Badge,
  Card,
  ErrorBox,
  Loading,
  PageHeader,
  PlatformBadge,
} from '../components/ui'
import { ConnectStoreWizard } from '../components/ConnectStoreWizard'
import { ReplyPreferences } from '../components/ReplyPreferences'
import { InviteColleague } from '../components/OptionalIntegrations'
import { AddAppDialog, ChannelsCard, CredentialsCard } from './Apps'
import type { App, Platform } from '../api/types'

const labels: Record<OnboardingStep, string> = {
  app: 'Vaše aplikace',
  connect: 'Připojení storů',
  preferences: 'Preference',
  extras: 'Váš tým',
  sync: 'První recenze',
}
const copy: Record<OnboardingStep, { title: string; detail: string }> = {
  app: {
    title: 'Začněme vaší aplikací.',
    detail:
      'Vložte odkaz ze storu. Rozpoznané údaje si před přidáním zkontrolujete. Obě platformy patří do jedné aplikace.',
  },
  connect: {
    title: 'Otevřete cestu k recenzím.',
    detail:
      'Propojíme každý store zvlášť. Klíče se ukládají šifrovaně a oprávnění ověříme přímo proti vaší aplikaci.',
  },
  preferences: {
    title: 'Ať odpovědi znějí jako vy.',
    detail:
      'Potvrďte jazyk přehledů a dejte AI stručné instrukce. Vše můžete později upravit u aplikace.',
  },
  extras: {
    title: 'Přizvěte svůj tým. Až budete chtít.',
    detail:
      'Recenze fungují přímo v konzoli. Slack, Teams i pozvání kolegů jsou volitelné a můžete je dokončit později.',
  },
  sync: {
    title: 'Vaše první recenze na jednom místě.',
    detail:
      'Ověřte první stažení. Ukazujeme skutečný výsledek každé platformy. Další recenze načítáme podle intervalu aplikace.',
  },
}

export function OnboardingPage() {
  const { org = '' } = useParams()
  const me = useMe()
  const apps = useApps(org)
  const progress = useOnboarding(org)
  const save = useSaveOnboarding(org)
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [connect, setConnect] = useState<Platform | null>(null)
  const heading = useRef<HTMLDivElement>(null)
  const previousStep = useRef('')
  const stepNav = useRef<HTMLElement>(null)
  const canManage = me.data?.organizations.some(
    (o) => o.slug === org && o.role !== 'MEMBER',
  )
  const ownApps = apps.data?.filter((app) => !app.competitor) ?? []
  const app = ownApps.find((a) => a.id === progress.data?.appId) ?? ownApps[0]
  const requested = params.get('step')
  const step: OnboardingStep = !app
    ? 'app'
    : onboardingSteps.includes(requested as OnboardingStep)
      ? (requested as OnboardingStep)
      : (progress.data?.step ?? 'connect')
  const index = onboardingSteps.indexOf(step)
  const connected =
    app?.platforms.filter(
      (p) =>
        !app.setup.platformsWithoutKey.includes(p) &&
        !app.setup.platformsWaitingForKey.includes(p),
    ) ?? []
  const state: OnboardingProgress = progress.data ?? {
    appId: app?.id ?? null,
    step,
    preferencesConfirmed: false,
    completed: false,
    started: false,
  }
  const canComplete =
    state.preferencesConfirmed && connected.length > 0 && app?.enabled === true
  const go = (
    next: OnboardingStep,
    changes: Partial<OnboardingProgress> = {},
  ) => {
    if (save.isPending) return
    save.mutate(
      {
        ...state,
        appId: app?.id ?? null,
        step: next,
        // Ztráta přístupu nesmí zablokovat návrat na krok, kde se dá opravit.
        completed: state.completed && canComplete,
        started: true,
        ...changes,
      },
      {
        onSuccess: () => setParams({ step: next }),
      },
    )
  }
  useEffect(() => {
    if (previousStep.current && previousStep.current !== step)
      heading.current?.focus()
    previousStep.current = step
    const bar = stepNav.current?.querySelector('ol')
    const active = bar?.querySelector<HTMLElement>('[aria-current="step"]')
    if (bar && active && bar.scrollWidth > bar.clientWidth) {
      const left = active.getBoundingClientRect().left - bar.getBoundingClientRect().left + bar.scrollLeft
      bar.scrollLeft = left - (bar.clientWidth - active.offsetWidth) / 2
    }
  }, [step, apps.isPending, progress.isPending])

  if (apps.isPending || progress.isPending)
    return <Loading what="Načítám rozpracované nastavení…" />
  if (apps.isError || progress.isError)
    return (
      <Card title="Nastavení se nepodařilo načíst">
        <ErrorBox error={apps.error ?? progress.error} />
        <button
          type="button"
          onClick={() => {
            void apps.refetch()
            void progress.refetch()
          }}
        >
          Zkusit znovu
        </button>
      </Card>
    )
  if (!canManage)
    return (
      <Card title="Vítejte v týmu">
        <p>
          Aplikace a připojení spravuje vlastník nebo správce tohoto prostoru.
          Vy už můžete pracovat s dostupnými recenzemi.
        </p>
        <Link className="button" to={`/${org}/recenze`}>
          Přejít na recenze
        </Link>
      </Card>
    )

  return (
    <div className="activation-page">
      <div className="activation-top">
        <span className="eyebrow">Váš pracovní prostor je připravený</span>
        <Link to={`/${org}`}>Dokončit později ↗</Link>
      </div>
      <div className="activation-layout">
        <nav ref={stepNav} className="activation-steps" aria-label="Postup nastavení">
          <div className="activation-caption">
            Od aplikace
            <br />k první odpovědi.
          </div>
          <ol>
            {onboardingSteps.map((item, i) => (
              <li key={item}>
                <button
                  type="button"
                  aria-current={item === step ? 'step' : undefined}
                  disabled={save.isPending || (!app && item !== 'app')}
                  onClick={() => go(item)}
                >
                  <span className="activation-number" aria-hidden="true">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                  <span>
                    {labels[item]}
                    {item === 'extras' ? <small>Volitelné</small> : null}
                  </span>
                </button>
              </li>
            ))}
          </ol>
          <div className="activation-assurance">
            <span aria-hidden="true">↺</span>
            <p>
              Nastavení se průběžně ukládá. Kdykoli se sem vraťte přes Průvodce.
            </p>
          </div>
        </nav>
        <section className="activation-main">
          <div ref={heading} tabIndex={-1} className="activation-heading">
            <PageHeader
              eyebrow={`Krok ${index + 1} z ${onboardingSteps.length} · ${labels[step]}`}
              title={copy[step].title}
            >
              {copy[step].detail}
            </PageHeader>
          </div>
          {app ? (
            <div className="activation-app">
              <span className="app-avatar">{app.name.slice(0, 1)}</span>
              <strong>{app.name}</strong>
              <span>
                {app.platforms.map((p) => (
                  <PlatformBadge key={p} platform={p} />
                ))}
              </span>
            </div>
          ) : null}
          <ErrorBox error={save.error} />
          {progress.data?.completed ? (
            <p className="notice" role="status">
              Základ pro práci v konzoli je dokončený. Zde můžete nastavení
              kdykoli doplnit.
            </p>
          ) : null}
          <div className="activation-body">
            {step === 'app' ? (
              app ? (
                <div className="stack">
                  <h2>Navážeme na vaši aplikaci</h2>
                  <p className="muted">
                    Aplikace už je uložená. Nebudeme ji vytvářet znovu.
                  </p>
                  {ownApps.length > 1 ? (
                    <div className="stack">
                      {ownApps.map((candidate) => (
                        <button
                          type="button"
                          className="secondary"
                          disabled={save.isPending}
                          key={candidate.id}
                          onClick={() =>
                            go('connect', {
                              appId: candidate.id,
                              preferencesConfirmed:
                                candidate.id === state.appId &&
                                state.preferencesConfirmed,
                              completed: false,
                            })
                          }
                        >
                          {candidate.name} →
                        </button>
                      ))}
                    </div>
                  ) : (
                    <button
                      type="button"
                      disabled={save.isPending}
                      onClick={() => go('connect')}
                    >
                      Pokračovat k připojení
                    </button>
                  )}
                </div>
              ) : (
                <AddAppDialog
                  org={org}
                  inline
                  ownOnly
                  onClose={() => {}}
                  onConnect={(created) => go('connect', { appId: created.id })}
                />
              )
            ) : null}
            {step === 'connect' && app ? (
              <div className="stack">
                {app.platforms.map((platform) => (
                  <StoreConnection
                    key={platform}
                    org={org}
                    app={app}
                    platform={platform}
                    onConnect={() => setConnect(platform)}
                  />
                ))}
                <details className="optional">
                  <summary>Pokročilé: vlastní nebo existující klíč</summary>
                  <CredentialsCard org={org} app={app} onConnect={setConnect} />
                </details>
                <div className="activation-actions">
                  <button
                    type="button"
                    disabled={save.isPending}
                    onClick={() => go('preferences')}
                  >
                    {connected.length > 0
                      ? 'Pokračovat k preferencím'
                      : 'Připojení dokončím později'}
                  </button>
                </div>
                {connected.length < app.platforms.length ? (
                  <p className="small muted">
                    Nepřipojené platformy zůstávají rozpracované. Pro dokončení
                    základu potřebujete alespoň jeden ověřený store.
                  </p>
                ) : null}
              </div>
            ) : null}
            {step === 'preferences' && app ? (
              <ReplyPreferences
                key={app.id}
                org={org}
                app={app}
                pending={save.isPending}
                onSaved={() => go('extras', { preferencesConfirmed: true })}
              />
            ) : null}
            {step === 'extras' && app ? (
              <div className="stack">
                <details className="optional activation-optional">
                  <summary>
                    <strong>Slack a Microsoft Teams</strong>
                    <span>Recenze přímo tam, kde se potkává váš tým</span>
                  </summary>
                  <ChannelsCard org={org} app={app} />
                </details>
                <details className="optional activation-optional">
                  <summary>
                    <strong>Pozvat kolegu</strong>
                    <span>Společná práce s recenzemi</span>
                  </summary>
                  <InviteColleague org={org} />
                </details>
                <button
                  type="button"
                  disabled={save.isPending}
                  onClick={() => go('sync')}
                >
                  Pokračovat bez dalších integrací
                </button>
                <p className="small muted">
                  Všechny uložené integrace zůstanou zachované.
                </p>
              </div>
            ) : null}
            {step === 'sync' && app ? (
              <FirstSync
                org={org}
                app={app}
                connected={connected}
                canComplete={canComplete}
                pending={save.isPending}
                onFinish={(to) => {
                  if (canComplete)
                    save.mutate(
                      {
                        ...state,
                        appId: app.id,
                        step: 'sync',
                        completed: true,
                        started: true,
                      },
                      { onSuccess: () => navigate(to) },
                    )
                  else navigate(to)
                }}
              />
            ) : null}
          </div>
          <div className="activation-footer">
            {index > 0 ? (
              <button
                type="button"
                className="link"
                disabled={save.isPending}
                onClick={() => go(onboardingSteps[index - 1]!)}
              >
                ← Zpět
              </button>
            ) : (
              <span />
            )}
            <span>
              {save.isPending
                ? 'Ukládám postup…'
                : 'Vytvořené prostředky zůstávají uložené'}
            </span>
          </div>
        </section>
      </div>
      {connect && app ? (
        <ConnectStoreWizard
          org={org}
          app={app}
          platform={connect}
          onClose={() => setConnect(null)}
        />
      ) : null}
    </div>
  )
}

function StoreConnection({
  org,
  app,
  platform,
  onConnect,
}: {
  org: string
  app: App
  platform: Platform
  onConnect: () => void
}) {
  const credentials = useCredentials(org)
  const health = useHealth(org)
  const validate = useValidateCredential(org)
  const attach = useAttachCredential(org)
  const type = platform === 'ANDROID' ? 'GP_SERVICE_ACCOUNT' : 'ASC_API_KEY'
  const key = credentials.data?.find(
    (c) => app.setup.credentialIds.includes(c.id) && c.type === type,
  )
  const missing = app.setup.platformsWithoutKey.includes(platform)
  const waiting = app.setup.platformsWaitingForKey.includes(platform)
  const scopedKey = health.data?.apps
    .find((item) => item.appId === app.id)
    ?.credentials.find((item) => item.id === key?.id)
  const status = missing
    ? 'Chybí připojení'
    : !waiting
      ? 'Připojení ověřeno'
      : scopedKey?.validationStatus === 'INVALID'
        ? 'Oprávnění nebo údaje vyžadují kontrolu'
        : 'Klíč čeká na ověření'
  const reusable =
    credentials.data?.filter(
      (c) => c.type === type && !app.setup.credentialIds.includes(c.id),
    ) ?? []
  return (
    <div className="store-connection stack">
      <div className="spread">
        <h2>{platform === 'ANDROID' ? 'Google Play' : 'App Store Connect'}</h2>
        <Badge tone={missing || waiting ? 'warn' : 'ok'}>{status}</Badge>
      </div>
      <p className="small muted">
        {platform === 'ANDROID'
          ? 'Service account vytvoříme my. V Play Console mu povolíte čtení a odpovídání na recenze.'
          : 'Týmový API klíč z App Store Connect. Pro recenze potřebuje přístup k této aplikaci a roli Customer Support nebo Admin.'}
      </p>
      {waiting && scopedKey?.validationError ? (
        <p className="notice">
          {scopedKey.validationError} Oprávnění se mohou propisovat se
          zpožděním. Pokud údaje nesedí, opravte je ve storu nebo připojte jiný
          klíč.
        </p>
      ) : null}
      <ErrorBox error={credentials.error ?? validate.error ?? attach.error} />
      {validate.data ? (
        <p role="status" className={validate.data.valid ? 'notice' : 'error'}>
          {validate.data.valid
            ? 'Přístup k aplikaci úspěšně ověřen.'
            : (validate.data.message ??
              'Ověření zatím neprošlo. Zkontrolujte oprávnění a zkuste to znovu.')}
        </p>
      ) : null}
      <div className="row">
        <button
          type="button"
          className={missing || waiting ? '' : 'secondary'}
          onClick={onConnect}
        >
          {missing ? 'Připojit store' : 'Upravit připojení'}
        </button>
        {key ? (
          <button
            type="button"
            className="secondary"
            disabled={validate.isPending}
            onClick={() => {
              if (!validate.isPending)
                validate.mutate({ appId: app.id, credentialId: key.id })
            }}
          >
            {validate.isPending ? 'Ověřuji…' : 'Ověřit přístup znovu'}
          </button>
        ) : null}
      </div>
      {missing && reusable.length > 0 ? (
        <details className="optional">
          <summary>Použít klíč uložený v organizaci</summary>
          {reusable.map((credential) => (
            <button
              key={credential.id}
              type="button"
              className="secondary"
              disabled={attach.isPending || validate.isPending}
              onClick={() =>
                attach.mutate(
                  { appId: app.id, credentialId: credential.id },
                  {
                    onSuccess: () =>
                      validate.mutate({
                        appId: app.id,
                        credentialId: credential.id,
                      }),
                  },
                )
              }
            >
              Připojit {credential.label}
            </button>
          ))}
        </details>
      ) : null}
    </div>
  )
}

function FirstSync({
  org,
  app,
  connected,
  canComplete,
  pending,
  onFinish,
}: {
  org: string
  app: App
  connected: Platform[]
  canComplete: boolean
  pending: boolean
  onFinish: (to: string) => void
}) {
  const sync = useOnboardingSync(org, app.id)
  const run = useRunOnboardingSync(org, app.id)
  const count = useReviewCount(org, app.id)
  const running =
    run.isPending ||
    sync.data?.some(
      (s) =>
        s.status === 'RUNNING' &&
        sync.dataUpdatedAt - Date.parse(s.startedAt) < 300000,
    )
  const hasData = (count.data?.count ?? 0) > 0
  return (
    <div className="stack first-sync">
      <ErrorBox error={sync.error ?? run.error ?? count.error} />
      {sync.isPending || count.isPending ? (
        <Loading what="Zjišťuji dostupné recenze…" />
      ) : null}
      <div className="first-value">
        <span className="eyebrow">{app.name} · recenze v konzoli</span>
        <h2>
          {hasData
            ? count.data?.count === 1 ? '1 recenze je připravena' : (count.data?.count ?? 0) < 5 ? `${count.data?.count} recenze jsou připravené` : `${count.data?.count} recenzí je připraveno`
            : running
              ? 'Načítáme recenze ze storu'
              : sync.data?.some((s) => s.status === 'SUCCEEDED')
                ? 'Zatím žádné načtené recenze'
                : 'První stažení je před vámi'}
        </h2>
        <p className="muted">
          {hasData
            ? 'Otevřete recenze, přečtěte zpětnou vazbu a připravte první odpověď.'
            : running
              ? 'Synchronizace běží na serveru. Můžete pokračovat; obnovení stránky ji nespustí znovu.'
              : 'Prázdný seznam nemusí znamenat aplikaci bez recenzí. Níže uvidíte, zda už store odpověděl.'}
        </p>
      </div>
      {app.platforms.map((platform) => {
        const result = sync.data?.find((s) => s.platform === platform)
        const stale =
          result?.status === 'RUNNING' &&
          sync.dataUpdatedAt - Date.parse(result.startedAt) >= 300000
        return (
          <div className="sync-result" key={platform}>
            <PlatformBadge platform={platform} />
            <div>
              <strong>
                {!connected.includes(platform)
                  ? 'Připojení ještě není ověřené'
                  : stale
                    ? 'Běh nedokončil odpověď. Spusťte ho znovu.'
                    : result?.status === 'RUNNING'
                      ? 'Synchronizace probíhá'
                      : result?.status === 'SUCCEEDED'
                        ? result.fetched === 0
                          ? 'Store odpověděl bez recenzí v dostupném rozsahu'
                          : `Store vrátil ${result.fetched} recenzí`
                        : result?.status === 'FAILED'
                          ? 'Stažení se nepodařilo'
                          : result?.status === 'BLOCKED'
                            ? 'Synchronizace čeká na připojení'
                            : 'První synchronizace ještě neproběhla'}
              </strong>
              {result?.message ? (
                <p className="small muted">{result.message}</p>
              ) : null}
            </div>
          </div>
        )
      })}
      {app.gpPackageName && !app.gpReportingBucket ? (
        <p className="small muted">
          Google Play API poskytuje poslední týden. Starší historii lze doplnit
          reportingovým bucketem v nastavení aplikace.
        </p>
      ) : null}
      {!canComplete ? (
        <p className="notice">
          Základ zůstává rozpracovaný. Ověřte alespoň jeden store, zapněte
          aplikaci a potvrďte preference. Do konzole můžete přejít už teď.
        </p>
      ) : null}
      <div className="activation-actions">
        <button
          type="button"
          className="secondary"
          disabled={running || connected.length === 0 || !app.enabled}
          onClick={() => {
            if (!running) run.mutate()
          }}
        >
          {running
            ? 'Synchronizace běží…'
            : sync.data?.length
              ? 'Zkusit synchronizaci znovu'
              : 'Načíst první recenze'}
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() =>
            onFinish(hasData ? `/${org}/recenze?app=${app.id}` : `/${org}`)
          }
        >
          {pending
            ? 'Dokončuji…'
            : hasData
              ? 'Otevřít recenze →'
              : 'Pokračovat do přehledu →'}
        </button>
      </div>
      <p className="small muted">
        Další kontrola probíhá podle intervalu {app.ingestIntervalMinutes}{' '}
        minut. Průvodce zůstává dostupný v navigaci.
      </p>
    </div>
  )
}
