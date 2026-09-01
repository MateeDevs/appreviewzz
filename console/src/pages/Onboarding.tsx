import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useApps, useCredentials, useHealth, useMe } from '../api/hooks'
import { Card, ErrorBox, Loading } from '../components/ui'
import { ConnectStoreWizard } from '../components/ConnectStoreWizard'
import type { App, Platform } from '../api/types'

interface Step {
  title: string
  done: boolean
  detail: string
  /** Odkaz do sekce, kde se krok dodělá. Krok s vlastním dialogem má místo něj [Step.connect]. */
  action?: { label: string; to: string }
  /** Krok, který se odbaví dialogem — klíč ke storu si klient nikam neopisuje. */
  connect?: { label: string; platform: Platform }
}

/**
 * Průvodce nastavením (plán §6.2).
 *
 * Není to modální flow, ale **kontrolní seznam odvozený z toho, co v systému opravdu je**.
 * Formuláře zůstávají tam, kam patří, takže se nikde nezdvojují — a když se klient
 * v půlce zasekne a vrátí se za týden, průvodce mu ukáže přesně to, co ještě chybí.
 */
export function OnboardingPage() {
  const { org = '' } = useParams()
  const me = useMe()
  const apps = useApps(org)
  const credentials = useCredentials(org)
  const health = useHealth(org)
  const [connect, setConnect] = useState<{ app?: App; platform: Platform } | null>(null)

  if (apps.isPending || credentials.isPending || health.isPending) return <Loading />

  const firstApp = apps.data?.[0]
  const storeKeys = (credentials.data ?? []).filter(
    (credential) => credential.type === 'GP_SERVICE_ACCOUNT' || credential.type === 'ASC_API_KEY',
  )
  const validated = storeKeys.filter((credential) => credential.validationStatus === 'VALID')
  const slackInstalls = (credentials.data ?? []).filter((credential) => credential.type === 'SLACK_INSTALL')
  const channels = (health.data?.apps ?? []).flatMap((app) => app.channels)
  const appPath = firstApp ? `/${org}/aplikace/${firstApp.id}` : `/${org}/aplikace`

  const steps: Step[] = [
    {
      title: 'Organizace',
      done: true,
      detail: 'Hotovo. Kolegy si přizveš v sekci Tým — pozvánka jim přijde e-mailem.',
      action: { label: 'Pozvat kolegy', to: `/${org}/tym` },
    },
    {
      title: 'Aplikace',
      done: (apps.data?.length ?? 0) > 0,
      detail:
        (apps.data?.length ?? 0) > 0
          ? `Sledujeme ${apps.data?.length} ${apps.data?.length === 1 ? 'aplikaci' : 'aplikace'}.`
          : 'Vlož odkaz na appku v Google Play a v App Storu — package name i App ID si console vytáhne sama.',
      action: { label: 'Přidat aplikaci', to: `/${org}/aplikace` },
    },
    {
      title: 'Klíč ke storu',
      done: validated.length > 0,
      detail:
        validated.length > 0
          ? 'Klíč je nahraný a ověřený proti storu.'
          : storeKeys.length > 0
            ? 'Klíč je nahraný, ale ještě neověřený — ověření řekne hned, jestli má potřebná práva.'
            : 'Google Play napojíš jednou pozvánkou — service account vyrobíme my. K App Storu tě provedeme konzolí Applu.',
      connect: {
        label: 'Připojit store',
        // Bez appky dialog začne jejím přidáním; s appkou rovnou tím, co jí chybí.
        platform: firstApp == null || firstApp.gpPackageName ? 'ANDROID' : 'IOS',
      },
    },
    {
      title: 'Slack',
      done: slackInstalls.length > 0,
      detail:
        slackInstalls.length > 0
          ? `Workspace ${slackInstalls[0]?.hint ?? ''} je připojený.`
          : 'Připoj workspace bot tokenem ze své Slack Appky (scopes chat:write, chat:write.public, channels:read).',
      action: { label: 'Připojit Slack', to: appPath },
    },
    {
      title: 'Kanál',
      done: channels.length > 0,
      detail:
        channels.length > 0
          ? 'Kanál je připojený. Zkušební zprávou ověříš, že do něj bot opravdu dosáhne.'
          : 'Vyber kanál podle jeho ID (C…) a pošli do něj zkušební zprávu.',
      action: { label: 'Připojit kanál', to: appPath },
    },
    {
      title: 'Nastavení',
      done: firstApp != null,
      detail:
        firstApp?.notifyFrom != null
          ? `Do kanálu jdou recenze od ${new Date(firstApp.notifyFrom).toLocaleDateString('cs-CZ', {
              dateStyle: 'medium',
            })}, kdy se appka přidala; starší se ukládají do historie, ale nenotifikují. Zbývá doladit jazyk zpráv, čas přehledu a instrukce pro AI.`
          : 'Recenze začnou do kanálu chodit od chvíle, kdy appku přidáš — historie kanál nezaplaví.',
      action: { label: 'Otevřít nastavení', to: appPath },
    },
  ]

  const remaining = steps.filter((step) => !step.done).length

  return (
    <div className="stack">
      <div>
        <h1>Průvodce nastavením</h1>
        <p className="muted">
          {remaining === 0
            ? 'Všechno je hotové — recenze budou chodit do kanálu automaticky.'
            : `Zbývá ${remaining} ${remaining === 1 ? 'krok' : remaining < 5 ? 'kroky' : 'kroků'}.`}
        </p>
      </div>

      {!me.data?.emailVerified ? (
        <div className="notice">Nezapomeň potvrdit e-mail — odkaz jsme poslali na {me.data?.email}.</div>
      ) : null}
      <ErrorBox error={apps.error ?? credentials.error ?? health.error} />

      <div className="steps">
        {steps.map((step) => (
          <span key={step.title} className={step.done ? 'step done' : 'step'}>
            {step.done ? '✓' : '○'} {step.title}
          </span>
        ))}
      </div>

      {connect ? (
        <ConnectStoreWizard org={org} app={connect.app} platform={connect.platform} onClose={() => setConnect(null)} />
      ) : null}

      {steps.map((step) => (
        <Card key={step.title}>
          <div className="spread">
            <h2 style={{ margin: 0 }}>
              {step.done ? '✓ ' : ''}
              {step.title}
            </h2>
            {step.connect ? (
              <button
                type="button"
                className="link"
                onClick={() => setConnect({ app: firstApp, platform: step.connect!.platform })}
              >
                {step.connect.label}
              </button>
            ) : step.action ? (
              <Link to={step.action.to}>{step.action.label}</Link>
            ) : null}
          </div>
          <p className="muted" style={{ marginTop: '0.5rem', marginBottom: 0 }}>
            {step.detail}
          </p>
        </Card>
      ))}
    </div>
  )
}
