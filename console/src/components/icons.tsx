import type { ReactNode } from 'react'

/**
 * Ikonky do navigace. Kreslené rovnou do JSX — pět čar nestojí za balíček ikon
 * a inline SVG se samo obarví podle `currentColor`, takže sedí i v aktivní položce.
 */
function Icon({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export function IconOverview() {
  return (
    <Icon>
      <rect x="3" y="3" width="7.5" height="7.5" rx="2" />
      <rect x="13.5" y="3" width="7.5" height="7.5" rx="2" />
      <rect x="3" y="13.5" width="7.5" height="7.5" rx="2" />
      <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2" />
    </Icon>
  )
}

export function IconReviews() {
  return (
    <Icon>
      <path d="M21 14.5a2.5 2.5 0 0 1-2.5 2.5H8l-4 4V5.5A2.5 2.5 0 0 1 6.5 3h12A2.5 2.5 0 0 1 21 5.5z" />
    </Icon>
  )
}

/** Hvězdička k volbě „i hodnocení" — vyplněná, aby vedle obrysové bubliny nezmizela. */
export function IconStar() {
  return (
    <Icon>
      <path d="M12 3.2l2.7 5.5 6 .9-4.35 4.25 1.03 6-5.38-2.83L6.62 19.85l1.03-6L3.3 9.6l6-.9z" fill="currentColor" />
    </Icon>
  )
}

/** Sloupce v grafu — rozbor je jediná sekce, která ukazuje čísla, ne položky. */
export function IconAnalysis() {
  return (
    <Icon>
      <path d="M3 21h18" />
      <path d="M7 21V11" />
      <path d="M12 21V4" />
      <path d="M17 21v-6" />
    </Icon>
  )
}

export function IconApps() {
  return (
    <Icon>
      <rect x="6" y="2" width="12" height="20" rx="2.5" />
      <path d="M11 18h2" />
    </Icon>
  )
}

export function IconTeam() {
  return (
    <Icon>
      <path d="M16 21v-1.8a3.7 3.7 0 0 0-3.7-3.7H6.7A3.7 3.7 0 0 0 3 19.2V21" />
      <circle cx="9.5" cy="7.5" r="3.7" />
      <path d="M21 21v-1.8a3.7 3.7 0 0 0-2.8-3.6M15.5 4a3.7 3.7 0 0 1 0 7" />
    </Icon>
  )
}

export function IconAudit() {
  return (
    <Icon>
      <path d="M12 21.5s7.5-3.6 7.5-9.3V5.4L12 2.5 4.5 5.4v6.8c0 5.7 7.5 9.3 7.5 9.3z" />
      <path d="m9.2 11.8 2 2 3.6-3.6" />
    </Icon>
  )
}

export function IconGuide() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="9" />
      <path d="m15.6 8.4-2.1 5.1-5.1 2.1 2.1-5.1z" />
    </Icon>
  )
}

/** Logo platformy u recenze — plné, ať je na první pohled jasné, odkud recenze přišla. */
export function IconApple() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701" />
    </svg>
  )
}

export function IconAndroid() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <g stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
        <line x1="7" y1="5" x2="8.8" y2="8" />
        <line x1="17" y1="5" x2="15.2" y2="8" />
      </g>
      <path d="M3 18a9 9 0 0 1 18 0z" fill="currentColor" />
      <circle cx="8.5" cy="13.8" r="1.1" fill="var(--surface)" />
      <circle cx="15.5" cy="13.8" r="1.1" fill="var(--surface)" />
    </svg>
  )
}

export function IconShield() {
  return (
    <Icon>
      <path d="M12 3l7.5 3v5.5c0 4.6-3.2 8.3-7.5 9.5-4.3-1.2-7.5-4.9-7.5-9.5V6z" />
      <path d="M9 12l2 2 4-4" />
    </Icon>
  )
}

export function IconSettings() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
    </Icon>
  )
}

export function IconSwitch() {
  return (
    <Icon>
      <path d="M7 4L3 8l4 4" />
      <path d="M3 8h13" />
      <path d="M17 20l4-4-4-4" />
      <path d="M21 16H8" />
    </Icon>
  )
}

export function IconLogout() {
  return (
    <Icon>
      <path d="M9 21H5.5A2.5 2.5 0 0 1 3 18.5v-13A2.5 2.5 0 0 1 5.5 3H9" />
      <path d="M16 17l5-5-5-5" />
      <path d="M21 12H9" />
    </Icon>
  )
}
