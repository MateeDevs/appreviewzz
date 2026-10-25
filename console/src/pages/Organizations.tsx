import { lazy, Suspense, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  useCreateOrganization,
  useMe,
  useOrganizations,
  useLogout,
} from '../api/hooks'
import { VerificationNotice } from '../components/AuthFields'
import { AuthShell, Card, ErrorBox, Field, Loading } from '../components/ui'
const PlatformDialog = lazy(() =>
  import('./Platform').then((module) => ({ default: module.PlatformDialog })),
)

/**
 * Rozcestník: seznam organizací a založení nové.
 *
 * Zakládat smí jen ověřený e-mail, proto je připomínka potvrzení hned tady — je to
 * první místo, kde na ni člověk narazí, a bez ní by dostal jen odmítnutí formuláře.
 */
export function OrganizationsPage() {
  const me = useMe()
  const organizations = useOrganizations()
  const create = useCreateOrganization()
  const logout = useLogout()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [platform, setPlatform] = useState(false)

  const verified = me.data?.emailVerified ?? false

  return (
    <AuthShell wide>
      <div className="eyebrow">Váš pracovní prostor</div>
      {!verified ? (
        <Card title="Ještě ověřte svůj e-mail">
          <VerificationNotice />
        </Card>
      ) : null}
      {organizations.data?.length !== 0 ? (
        <Card title="Vyberte organizaci">
          {organizations.isPending ? <Loading /> : null}
          <ErrorBox error={organizations.error} />
          <ul className="list">
            {organizations.data?.map((organization) => (
              <li key={organization.id}>
                <Link to={`/${organization.slug}`}>{organization.name}</Link>
                <span className="badge">
                  {
                    { OWNER: 'vlastník', ADMIN: 'správce', MEMBER: 'člen' }[
                      organization.role
                    ]
                  }
                </span>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}

      {verified ? (
        <Card
          title={
            organizations.data?.length === 0
              ? 'Vytvořte svůj pracovní prostor'
              : 'Nová organizace'
          }
        >
          <p className="auth-intro">
            Prostor pro vaše aplikace a tým. Kolegy můžete pozvat později.
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault()
              if (create.isPending) return
              create.mutate(
                { name },
                {
                  onSuccess: (organization) =>
                    navigate(`/${organization.slug}/onboarding`),
                },
              )
            }}
          >
            <Field
              label="Název pracovního prostoru"
              hint="Adresa do console se odvodí z názvu."
            >
              <input
                name="organization"
                autoComplete="organization"
                maxLength={120}
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={create.error} />
              <button type="submit" disabled={create.isPending}>
                {create.isPending ? 'Zakládám…' : 'Založit organizaci'}
              </button>
            </div>
          </form>
        </Card>
      ) : null}
      <p className="small muted">
        Máte pozvánku od kolegy? Otevřete její odkaz z e-mailu a vstoupíte
        rovnou do jeho týmu.
      </p>
      <button
        type="button"
        className="link"
        disabled={logout.isPending}
        onClick={() => logout.mutate()}
      >
        Odhlásit se
      </button>
      {/* Superadmin nemusí být v žádné organizaci — bez tohohle by se do správy platformy
          odsud nedostal. Roli si sekce ověřuje na serveru, tohle je jen dveře. */}
      {me.data?.platformRole === 'SUPERADMIN' ? (
        <p className="small muted">
          <button
            type="button"
            className="link"
            onClick={() => setPlatform(true)}
          >
            Správa platformy
          </button>
        </p>
      ) : null}
      <Suspense fallback={<Loading />}>
        {platform ? (
          <PlatformDialog onClose={() => setPlatform(false)} />
        ) : null}
      </Suspense>
    </AuthShell>
  )
}
