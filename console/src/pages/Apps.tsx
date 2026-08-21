import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
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
  useRunRatings,
  useTestChannels,
  useUpdateApp,
  useValidateCredential,
} from '../api/hooks'
import { Badge, Card, ErrorBox, Field, Loading, When } from '../components/ui'
import { RatingsChart } from '../components/RatingsChart'
import type { ChannelCheck, RatingsSeries } from '../api/types'

export function AppsPage() {
  const { org = '' } = useParams()
  const apps = useApps(org)
  const create = useCreateApp(org)
  const [name, setName] = useState('')
  const [gpPackage, setGpPackage] = useState('')
  const [ascAppId, setAscAppId] = useState('')

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
                <th>Ingest</th>
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
                  <td className="small">každých {app.ingestIntervalMinutes} min</td>
                  <td>{app.enabled ? <Badge tone="ok">zapnutá</Badge> : <Badge tone="warn">vypnutá</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Card>

      <Card title="Přidat aplikaci">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            create.mutate(
              {
                name,
                gpPackageName: gpPackage.trim() === '' ? null : gpPackage.trim(),
                ascAppId: ascAppId.trim() === '' ? null : ascAppId.trim(),
              },
              {
                onSuccess: () => {
                  setName('')
                  setGpPackage('')
                  setAscAppId('')
                },
              },
            )
          }}
        >
          <Field label="Název">
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="Google Play — package name" hint="Např. cz.matee.islegrow. Nech prázdné, když appka na Androidu není.">
            <input value={gpPackage} onChange={(e) => setGpPackage(e.target.value)} placeholder="cz.matee.appka" />
          </Field>
          <Field label="App Store — číselné App ID" hint="Najdeš ho v App Store Connect v adrese appky.">
            <input value={ascAppId} onChange={(e) => setAscAppId(e.target.value)} placeholder="1234567890" />
          </Field>
          <div className="stack" style={{ marginTop: '1rem' }}>
            <ErrorBox error={create.error} />
            <button type="submit" disabled={create.isPending}>
              Přidat
            </button>
          </div>
        </form>
      </Card>
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
    ingestIntervalMinutes: String(app.ingestIntervalMinutes),
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
                ingestIntervalMinutes: Number(values.ingestIntervalMinutes),
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
        <Field label="Jak často stahovat recenze (minuty)">
          <input
            type="number"
            min={5}
            max={1440}
            value={values.ingestIntervalMinutes}
            onChange={(e) => set('ingestIntervalMinutes', e.target.value)}
          />
        </Field>
        <Field label="Čas denního přehledu">
          <input type="time" value={values.dailyDigestAt} onChange={(e) => set('dailyDigestAt', e.target.value)} />
        </Field>
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
    <Card title="Klíče ke storu">
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
    <Card title="Kanály">
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
