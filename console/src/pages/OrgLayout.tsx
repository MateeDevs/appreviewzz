import { useEffect, useState } from 'react'
import { NavLink, Navigate, Outlet, useParams } from 'react-router-dom'
import { useLogout, useMe } from '../api/hooks'
import { Brand } from '../components/ui'
import { PlatformDialog } from './Platform'
import { SecurityDialog } from './Security'
import {
  IconAnalysis,
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
  const [platform, setPlatform] = useState(false)
  // Odhlášení na dvě kliknutí místo `confirm()`: tlačítko sedí pod seznamem odkazů a jedno
  // ukliknutí by zahodilo rozepsanou odpověď. Bez druhého kliknutí se po chvíli vrátí samo.
  const [confirmLogout, setConfirmLogout] = useState(false)
  useEffect(() => {
    if (!confirmLogout) return
    const timer = window.setTimeout(() => setConfirmLogout(false), 4000)
    return () => window.clearTimeout(timer)
  }, [confirmLogout])

  const membership = me.data?.organizations.find((item) => item.slug === org)
  // Po odhlášení se cache zahodí a profil vrátí „nikdo". Rám to vidí dřív než App (jeho
  // `useMe` se o zahozené cache nedozví), takže na login odvede sám — jinak by stránka
  // zůstala stát s prázdnými daty, dokud by ji někdo neobnovil.
  if (me.isSuccess && me.data === null) return <Navigate to="/login" replace />
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
          <NavLink to={`/${org}/rozbory`}>
            <IconAnalysis />
            Rozbory
          </NavLink>
          <NavLink to={`/${org}/aplikace`}>
            <IconApps />
            Aplikace
          </NavLink>
          <NavLink to={`/${org}/organizace`}>
            <IconTeam />
            Organizace
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
          {/* Vidí jen správce platformy. Sekce sama si roli ověřuje na serveru. */}
          {me.data?.platformRole === 'SUPERADMIN' ? (
            <button type="button" className="link" onClick={() => setPlatform(true)}>
              Správa platformy
            </button>
          ) : null}
          {me.data && me.data.organizations.length > 1 ? (
            <NavLink to="/organizace">Přepnout organizaci</NavLink>
          ) : null}
          {confirmLogout ? (
            <span className="logout-confirm">
              <button type="button" className="link" disabled={logout.isPending} onClick={() => logout.mutate()}>
                Opravdu odhlásit?
              </button>
              <button type="button" className="link small" onClick={() => setConfirmLogout(false)}>
                zrušit
              </button>
            </span>
          ) : (
            <button type="button" className="link" onClick={() => setConfirmLogout(true)}>
              Odhlásit se
            </button>
          )}
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
      {security ? <SecurityDialog onClose={() => setSecurity(false)} /> : null}
      {platform ? <PlatformDialog onClose={() => setPlatform(false)} /> : null}
    </div>
  )
}
