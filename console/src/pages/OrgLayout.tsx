import { NavLink, Navigate, Outlet, useParams } from 'react-router-dom'
import { useLogout, useMe } from '../api/hooks'

/**
 * Rám organizace: navigace a přepínač účtu. Organizace se v adrese identifikuje slugem,
 * takže odkaz na konkrétní obrazovku jde poslat kolegovi.
 */
export function OrgLayout() {
  const { org = '' } = useParams()
  const me = useMe()
  const logout = useLogout()

  const membership = me.data?.organizations.find((item) => item.slug === org)
  // Než dorazí profil, nic nepřesměrováváme — jinak by refresh stránky vyhodil ven.
  if (me.isSuccess && me.data && !membership) return <Navigate to="/organizace" replace />

  return (
    <div className="shell">
      <aside className="sidebar">
        <div>
          <div className="brand">appreviewzz</div>
          <div className="small muted">{membership?.name ?? org}</div>
        </div>
        <nav>
          <NavLink to={`/${org}`} end>
            Přehled
          </NavLink>
          <NavLink to={`/${org}/recenze`}>Recenze</NavLink>
          <NavLink to={`/${org}/aplikace`}>Aplikace</NavLink>
          <NavLink to={`/${org}/tym`}>Tým</NavLink>
          <NavLink to={`/${org}/audit`}>Audit</NavLink>
          <NavLink to={`/${org}/onboarding`}>Průvodce</NavLink>
        </nav>
        <div className="grow" />
        <div className="small muted">
          <div>{me.data?.displayName ?? me.data?.email}</div>
          <div style={{ marginTop: '0.25rem' }}>
            <NavLink to="/zabezpeceni">Zabezpečení účtu</NavLink>
          </div>
          <button type="button" className="link" onClick={() => logout.mutate()}>
            Odhlásit se
          </button>
          {me.data && me.data.organizations.length > 1 ? (
            <div style={{ marginTop: '0.5rem' }}>
              <NavLink to="/organizace">Přepnout organizaci</NavLink>
            </div>
          ) : null}
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
