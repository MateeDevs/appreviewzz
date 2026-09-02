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
