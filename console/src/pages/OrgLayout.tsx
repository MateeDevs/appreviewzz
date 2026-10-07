import { useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink, Navigate, Outlet, useLocation, useParams } from 'react-router-dom'
import { useLogout, useMe } from '../api/hooks'
import { Brand } from '../components/ui'
import { PlatformDialog } from './Platform'
import { SecurityDialog } from './Security'
import {
  IconAnalysis,
  IconApps,
  IconAudit,
  IconGuide,
  IconLogout,
  IconOverview,
  IconReviews,
  IconSettings,
  IconShield,
  IconSwitch,
  IconTeam,
} from '../components/icons'

/**
 * Rám organizace: navigace a nabídka účtu. Organizace se v adrese identifikuje slugem,
 * takže odkaz na konkrétní obrazovku jde poslat kolegovi.
 */
export function OrgLayout() {
  const { org = '' } = useParams()
  const me = useMe()
  const [security, setSecurity] = useState(false)
  const [platform, setPlatform] = useState(false)
  const nav = useRef<HTMLElement>(null)
  const { pathname } = useLocation()

  // Na telefonu je navigace posuvný řádek a sekce vpravo (Audit, Průvodce) jsou mimo obraz.
  // Aktivní položku do něj dorolujeme, ať je vidět, kde člověk je. Jen vodorovně —
  // `scrollIntoView` by umělo pohnout i celou stránkou.
  useEffect(() => {
    const bar = nav.current
    const active = bar?.querySelector<HTMLElement>('a.active')
    if (!bar || !active || bar.scrollWidth <= bar.clientWidth) return
    const left = active.getBoundingClientRect().left - bar.getBoundingClientRect().left + bar.scrollLeft
    bar.scrollLeft = left - (bar.clientWidth - active.offsetWidth) / 2
  }, [pathname])

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
        <nav ref={nav}>
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
        <AccountMenu
          name={me.data?.displayName ?? null}
          email={me.data?.email ?? ''}
          superadmin={me.data?.platformRole === 'SUPERADMIN'}
          multipleOrgs={(me.data?.organizations.length ?? 0) > 1}
          onSecurity={() => setSecurity(true)}
          onPlatform={() => setPlatform(true)}
        />
      </aside>
      <main className="content">
        <Outlet />
      </main>
      {security ? <SecurityDialog onClose={() => setSecurity(false)} /> : null}
      {platform ? <PlatformDialog onClose={() => setPlatform(false)} /> : null}
    </div>
  )
}

/** Iniciály do avataru: z celého jména první písmena dvou slov, jinak začátek e-mailu. */
function initials(name: string | null, email: string) {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean)
  const first = words[0] ?? email
  const last = words.length >= 2 ? words[words.length - 1]!.slice(0, 1) : ''
  return (first.slice(0, 1) + last).toUpperCase()
}

/**
 * Účet za avatarem. Na desktopu je to řádek v patě sidebaru a nabídka se otevře nahoru,
 * na telefonu jen kolečko vpravo v horní liště a nabídka spadne dolů — jako v každé
 * mobilní aplikaci. Odhlášení je schované v nabídce, takže už nepotřebuje druhé potvrzení:
 * otevřít nabídku a sáhnout na poslední položku omylem nejde.
 */
function AccountMenu({
  name,
  email,
  superadmin,
  multipleOrgs,
  onSecurity,
  onPlatform,
}: {
  name: string | null
  email: string
  superadmin: boolean
  multipleOrgs: boolean
  onSecurity: () => void
  onPlatform: () => void
}) {
  const logout = useLogout()
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const menuId = useId()
  const location = useLocation()

  // Přechod na jinou stránku nabídku zavře — jinak by po „Přepnout organizaci" visela dál.
  useEffect(() => setOpen(false), [location.pathname])

  useEffect(() => {
    if (!open) return
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      root.current?.querySelector<HTMLButtonElement>('.account-trigger')?.focus()
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    // Fokus na první položku, ať se dá pokračovat šipkami i Tabem.
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const pick = (action: () => void) => () => {
    setOpen(false)
    action()
  }

  return (
    <div className="account" ref={root}>
      <button
        type="button"
        className="account-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        aria-label="Účet a odhlášení"
        onClick={() => setOpen((value) => !value)}
      >
        <span className="avatar" aria-hidden="true">
          {initials(name, email)}
        </span>
        <span className="account-who">
          <span className="account-name">{name ?? email}</span>
          {name ? <span className="account-email">{email}</span> : null}
        </span>
      </button>
      {open ? (
        <div
          className="account-menu"
          id={menuId}
          role="menu"
          onKeyDown={(event) => {
            if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
            event.preventDefault()
            const items = [...(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
            const at = items.indexOf(document.activeElement as HTMLElement)
            const next = event.key === 'ArrowDown' ? at + 1 : at - 1
            items[(next + items.length) % items.length]?.focus()
          }}
        >
          <div className="account-menu-head">
            <span className="avatar large" aria-hidden="true">
              {initials(name, email)}
            </span>
            <span className="account-who">
              <span className="account-name">{name ?? email}</span>
              {name ? <span className="account-email">{email}</span> : null}
            </span>
          </div>
          {/* Druhý faktor je krátké zařizování — otevře se nad rozdělanou prací, nikam se neodchází. */}
          <button type="button" role="menuitem" onClick={pick(onSecurity)}>
            <IconShield />
            Zabezpečení účtu
          </button>
          {/* Vidí jen správce platformy. Sekce sama si roli ověřuje na serveru. */}
          {superadmin ? (
            <button type="button" role="menuitem" onClick={pick(onPlatform)}>
              <IconSettings />
              Správa platformy
            </button>
          ) : null}
          {multipleOrgs ? (
            <Link to="/organizace" role="menuitem">
              <IconSwitch />
              Přepnout organizaci
            </Link>
          ) : null}
          <hr />
          <button
            type="button"
            role="menuitem"
            className="account-logout"
            disabled={logout.isPending}
            onClick={() => logout.mutate()}
          >
            <IconLogout />
            Odhlásit se
          </button>
        </div>
      ) : null}
    </div>
  )
}
