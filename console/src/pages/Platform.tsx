import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  usePlatformApps,
  usePlatformAudit,
  usePlatformOverview,
  usePlatformSecrets,
  usePlatformSettings,
  useRemovePlatformSecret,
  useSetPlatformAppInterval,
  useSetPlatformSecret,
  useUpdatePlatformSettings,
} from '../api/hooks'
import type { PlatformSetting, PlatformSettingSource } from '../api/types'
import { AuthShell, Badge, Card, Empty, ErrorBox, Field, Loading, When } from '../components/ui'

/**
 * Správa platformy (F7, ADR 0018).
 *
 * Mimo rám organizace schválně: superadmin žádnou nemá a k datům klientů se odsud nedostane.
 * Je tu konfigurace, klíče a pár provozních čísel — nic, co by patřilo konkrétnímu tenantovi.
 *
 * O tom, kdo sem smí, rozhoduje server (`404` bez role, `403` bez druhého faktoru); routa
 * v [App] je jen zkratka, aby se odsud nekoukalo na prázdné obrazovky.
 */
export function PlatformPage() {
  return (
    <AuthShell wide>
      <Card title="Správa platformy">
        <p className="small muted">
          Nastavení, které platí pro všechny klienty. Změny se projeví do půl minuty — worker si je
          vyzvedne sám, restart není potřeba.
        </p>
      </Card>
      <OverviewCard />
      <SettingsCard />
      <SecretsCard />
      <AppOverridesCard />
      <AuditCard />
      <p className="small muted" style={{ marginTop: '1.5rem' }}>
        <Link to="/">Zpátky do console</Link>
      </p>
    </AuthShell>
  )
}

function OverviewCard() {
  const overview = usePlatformOverview()

  return (
    <Card title="Přehled">
      {overview.isPending ? <Loading /> : null}
      <ErrorBox error={overview.error} />
      {overview.data ? (
        <table className="table">
          <tbody>
            <tr>
              <td>Organizace</td>
              <td className="small">{overview.data.organizations}</td>
            </tr>
            <tr>
              <td>Uživatelé</td>
              <td className="small">{overview.data.users}</td>
            </tr>
            <tr>
              <td>Aplikace</td>
              <td className="small">
                {overview.data.apps} ({overview.data.enabledApps} sledovaných,{' '}
                {overview.data.appsWithIntervalOverride} s vlastním intervalem)
              </td>
            </tr>
            <tr>
              <td>Stahování recenzí</td>
              <td className="small">
                každých {overview.data.defaultIntervalMinutes} min, nejméně po{' '}
                {overview.data.minIntervalMinutes} min
              </td>
            </tr>
            <tr>
              <td>Nevyřešené úlohy</td>
              <td className="small">
                {overview.data.failedJobs > 0 ? (
                  <Badge tone="bad">{overview.data.failedJobs}</Badge>
                ) : (
                  '0'
                )}
              </td>
            </tr>
          </tbody>
        </table>
      ) : null}
    </Card>
  )
}

/**
 * Formulář se vykresluje z katalogu ze serveru, ne z ručně opsaného seznamu polí — jinak by
 * každý nový klíč znamenal změnu na dvou místech a jedno z nich by se zapomnělo.
 */
function SettingsCard() {
  const settings = usePlatformSettings()
  const update = useUpdatePlatformSettings()
  const [draft, setDraft] = useState<Record<string, string> | null>(null)

  const editable = (settings.data ?? []).filter((item) => item.type !== 'SECRET')
  const original = Object.fromEntries(editable.map((item) => [item.key, item.value ?? '']))
  const values = draft ?? original
  const set = (key: string, value: string) => setDraft({ ...values, [key]: value })

  const sections = [...new Set(editable.map((item) => item.section))]

  return (
    <Card title="Nastavení">
      {settings.isPending ? <Loading /> : null}
      <ErrorBox error={settings.error ?? update.error} />
      {editable.length > 0 ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            // Posílá se **jen to, co se změnilo**. Kdyby šlo všechno, uložila by se do
            // databáze i hodnota, kterou nikdo nezvolil — a tím by se ztratila možnost
            // vrátit se k prostředí, resp. k výchozí hodnotě.
            // Prázdné pole = „zruš uložené", proto `null`, ne ''.
            const payload = Object.fromEntries(
              editable
                .filter((item) => values[item.key] !== original[item.key])
                .map((item) => [item.key, values[item.key]?.trim() ? values[item.key]! : null]),
            )
            update.mutate(payload, { onSuccess: () => setDraft(null) })
          }}
        >
          {sections.map((section) => (
            <div key={section}>
              <h3 className="small" style={{ marginBottom: '0.5rem' }}>
                {section}
              </h3>
              {editable
                .filter((item) => item.section === section)
                .map((item) => (
                  <SettingField
                    key={item.key}
                    setting={item}
                    value={values[item.key] ?? ''}
                    onChange={(value) => set(item.key, value)}
                  />
                ))}
            </div>
          ))}
          <button type="submit" disabled={update.isPending || draft === null}>
            {update.isPending ? 'Ukládám…' : 'Uložit'}
          </button>
        </form>
      ) : null}
    </Card>
  )
}

function SettingField({
  setting,
  value,
  onChange,
}: {
  setting: PlatformSetting
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Field
      label={setting.label}
      hint={
        <>
          {setting.help} <SourceBadge setting={setting} />
        </>
      }
    >
      {setting.type === 'ENUM' ? (
        <select value={value} onChange={(e) => onChange(e.target.value)}>
          {setting.options.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      ) : setting.type === 'BOOL' ? (
        <select value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="true">ano</option>
          <option value="false">ne</option>
        </select>
      ) : setting.type === 'INT' ? (
        <input
          type="number"
          value={value}
          min={setting.min ?? undefined}
          max={setting.max ?? undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <input value={value} onChange={(e) => onChange(e.target.value)} />
      )}
    </Field>
  )
}

/**
 * Odkud hodnota je. `ENV` je ta zajímavá: říká, že se hodnota bere z proměnné prostředí
 * a uložením se přebije — bez toho by se dalo dlouho hledat, proč „to nic nedělá".
 */
function SourceBadge({ setting }: { setting: PlatformSetting }) {
  const label: Record<PlatformSettingSource, string> = {
    DEFAULT: 'výchozí',
    ENV: setting.envName ? `z prostředí (${setting.envName})` : 'z prostředí',
    DB: 'uloženo',
  }
  return <Badge tone={setting.source === 'DB' ? 'ok' : undefined}>{label[setting.source]}</Badge>
}

/** Klíče. Hodnota jde jen dovnitř — ven se vrací otisk, aby šlo poznat, co je uložené. */
function SecretsCard() {
  const settings = usePlatformSettings()
  const secrets = usePlatformSecrets()
  const stored = Object.fromEntries((secrets.data ?? []).map((item) => [item.key, item]))
  const definitions = (settings.data ?? []).filter((item) => item.type === 'SECRET')

  return (
    <Card title="Klíče">
      {secrets.isPending ? <Loading /> : null}
      <ErrorBox error={secrets.error} />
      {definitions.length === 0 ? <Empty>Žádné klíče k nastavení.</Empty> : null}
      {definitions.map((definition) => (
        <SecretRow key={definition.key} setting={definition} stored={stored[definition.key] ?? null} />
      ))}
    </Card>
  )
}

function SecretRow({
  setting,
  stored,
}: {
  setting: PlatformSetting
  stored: { fingerprint: string; hint: string | null; updatedAt: string } | null
}) {
  const save = useSetPlatformSecret()
  const remove = useRemovePlatformSecret()
  const [value, setValue] = useState('')

  return (
    <div style={{ marginBottom: '1.5rem' }}>
      <Field
        label={setting.label}
        hint={
          <>
            {setting.help}{' '}
            {stored ? (
              <>
                <Badge tone="ok">uloženo</Badge> {stored.fingerprint}
                {stored.hint ? ` · ${stored.hint}` : ''} · <When iso={stored.updatedAt} />
              </>
            ) : setting.source === 'ENV' ? (
              <Badge>z prostředí ({setting.envName})</Badge>
            ) : (
              <Badge>nenastaveno</Badge>
            )}
          </>
        }
      >
        <input
          type="password"
          value={value}
          placeholder={stored ? 'Přepsat novým klíčem' : 'Vlož klíč'}
          autoComplete="off"
          onChange={(e) => setValue(e.target.value)}
        />
      </Field>
      <ErrorBox error={save.error ?? remove.error} />
      <button
        type="button"
        disabled={value.trim() === '' || save.isPending}
        onClick={() => save.mutate({ key: setting.key, value }, { onSuccess: () => setValue('') })}
      >
        {save.isPending ? 'Ukládám…' : 'Uložit klíč'}
      </button>
      {stored ? (
        <button
          type="button"
          className="secondary"
          disabled={remove.isPending}
          onClick={() => remove.mutate(setting.key)}
        >
          Zrušit
        </button>
      ) : null}
    </div>
  )
}

/**
 * Výjimky intervalu. Vypisují se **jen** aplikace, které nějakou mají — seznam všech appek
 * napříč klienty do platformní sekce nepatří.
 */
function AppOverridesCard() {
  const apps = usePlatformApps()
  const set = useSetPlatformAppInterval()

  return (
    <Card title="Výjimky intervalu">
      {apps.isPending ? <Loading /> : null}
      <ErrorBox error={apps.error ?? set.error} />
      {apps.data?.length === 0 ? (
        <Empty>Žádná aplikace nemá vlastní interval — všechny jedou na platformní hodnotě.</Empty>
      ) : null}
      {apps.data && apps.data.length > 0 ? (
        <table className="table">
          <tbody>
            {apps.data.map((app) => (
              <tr key={app.id}>
                <td>{app.name}</td>
                <td className="small">každých {app.intervalMinutes} min</td>
                <td className="small">
                  <button
                    type="button"
                    className="secondary"
                    disabled={set.isPending}
                    onClick={() => set.mutate({ appId: app.id, minutes: null })}
                  >
                    Vrátit na platformní
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </Card>
  )
}

function AuditCard() {
  const audit = usePlatformAudit()

  return (
    <Card title="Historie změn">
      {audit.isPending ? <Loading /> : null}
      <ErrorBox error={audit.error} />
      {audit.data?.length === 0 ? <Empty>Zatím nic.</Empty> : null}
      {audit.data && audit.data.length > 0 ? (
        <table className="table">
          <tbody>
            {audit.data.map((entry, index) => (
              <tr key={index}>
                <td className="small">
                  <When iso={entry.createdAt} />
                </td>
                <td className="small">{entry.actorLabel ?? '—'}</td>
                <td className="small">{entry.action}</td>
                <td className="small">
                  {entry.targetKey ?? '—'}
                  {entry.metadata.from || entry.metadata.to
                    ? ` · ${entry.metadata.from ?? '—'} → ${entry.metadata.to ?? '—'}`
                    : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </Card>
  )
}
