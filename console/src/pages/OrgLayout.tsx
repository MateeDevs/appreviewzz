import { useState } from 'react'
import { NavLink, Navigate, Outlet, useParams } from 'react-router-dom'
import { useLogout, useMe } from '../api/hooks'
import { Brand } from '../components/ui'
import { SecurityDialog } from './Security'
import {
  IconApps,
  IconAudit,
  IconGuide,
  IconOverview,
  IconReviews,
  IconTeam,
} from '../components/icons'

/**
 * Rám organizace: navigace a přepínač účtu. Organizace se v adrese identifikuje slugem,
 * takže odkaz na konkrétní obrazovku jde poslat kolegovi.
 */
export function OrgLayout() {
  const { org = '' } = useParams()
  const me = useMe()
  const logout = useLogout()
  const [security, setSecurity] = useState(false)

  const membership = me.data?.organizations.find((item) => item.slug === org)
  // Než dorazí profil, nic nepřesměrováváme — jinak by refresh stránky vyhodil ven.
  if (me.isSuccess && me.data && !membership) return <Navigate to="/organizace" replace />

  return (
    <div className="shell">
      <aside className="sidebar">
        <Brand subtitle={membership?.name ?? org} />
        <nav>
          <NavLink to={`/${org}`} end>
            <IconOverview />
            Přehled
          </NavLink>
          <NavLink to={`/${org}/recenze`}>
            <IconReviews />
            Recenze
          </NavLink>
          <NavLink to={`/${org}/aplikace`}>
            <IconApps />
            Aplikace
          </NavLink>
          <NavLink to={`/${org}/tym`}>
            <IconTeam />
            Tým
          </NavLink>
          <NavLink to={`/${org}/audit`}>
            <IconAudit />
            Audit
          </NavLink>
          <NavLink to={`/${org}/onboarding`}>
            <IconGuide />
            Průvodce
          </NavLink>
        </nav>
        <div className="grow" />
        <div className="sidebar-foot">
          <div className="who">{me.data?.displayName ?? me.data?.email}</div>
          {/* Druhý faktor je krátké zařizování — otevře se nad rozdělanou prací, nikam se neodchází. */}
          <button type="button" className="link" onClick={() => setSecurity(true)}>
            Zabezpečení účtu
          </button>
          {/* Odkaz vidí jen správce platformy. Sekce sama si roli ověřuje na serveru. */}
          {me.data?.platformRole === 'SUPERADMIN' ? (
            <NavLink to="/platforma">Správa platformy</NavLink>
          ) : null}
          {me.data && me.data.organizations.length > 1 ? (
            <NavLink to="/organizace">Přepnout organizaci</NavLink>
          ) : null}
          <button type="button" className="link" onClick={() => logout.mutate()}>
            Odhlásit se
          </button>
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
      {security ? <SecurityDialog onClose={() => setSecurity(false)} /> : null}
    </div>
  )
}
