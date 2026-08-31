import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCreateOrganization, useMe, useOrganizations, useResendVerification } from '../api/hooks'
import { AuthShell, Card, ErrorBox, Field, Loading } from '../components/ui'

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
  const resend = useResendVerification()
  const navigate = useNavigate()
  const [name, setName] = useState('')

  const verified = me.data?.emailVerified ?? false

  return (
    <AuthShell wide>
      <Card title="Tvoje organizace">
        {organizations.isPending ? <Loading /> : null}
        <ErrorBox error={organizations.error} />
        {organizations.data?.length === 0 ? (
          <p className="muted">Zatím žádná. Založ první a přizvi si do ní kolegy.</p>
        ) : (
          <ul className="list">
            {organizations.data?.map((organization) => (
              <li key={organization.id}>
                <Link to={`/${organization.slug}`}>{organization.name}</Link>
                <span className="badge">{organization.role.toLowerCase()}</span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Nová organizace">
        {!verified ? (
          <div className="notice">
            Nejdřív potvrď e-mail — poslali jsme ti odkaz na <strong>{me.data?.email}</strong>.{' '}
            <button type="button" className="link" onClick={() => resend.mutate()} disabled={resend.isPending}>
              Poslat znovu
            </button>
            {resend.isSuccess ? <span className="muted"> · odesláno</span> : null}
          </div>
        ) : (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              create.mutate({ name }, { onSuccess: (organization) => navigate(`/${organization.slug}/onboarding`) })
            }}
          >
            <Field label="Název" hint="Adresa do console se odvodí z názvu.">
              <input value={name} onChange={(e) => setName(e.target.value)} required />
            </Field>
            <div className="stack" style={{ marginTop: '1rem' }}>
              <ErrorBox error={create.error} />
              <button type="submit" disabled={create.isPending}>
                Založit organizaci
              </button>
            </div>
          </form>
        )}
      </Card>
  </AuthShell>
  )
}
