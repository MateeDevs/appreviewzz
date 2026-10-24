import { useState } from 'react'
type Theme = 'light' | 'dark' | 'system'
export function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const value = localStorage.getItem('arz-theme')
      return value === 'light' || value === 'dark' ? value : 'system'
    } catch {
      return 'system'
    }
  })
  return (
    <div className="theme-switch" role="group" aria-label="Barevný režim">
      {(
        [
          ['light', 'Světlý'],
          ['dark', 'Tmavý'],
          ['system', 'Systém'],
        ] as const
      ).map(([value, label]) => (
        <button
          key={value}
          type="button"
          aria-pressed={theme === value}
          onClick={() => {
            setTheme(value)
            document.documentElement.dataset.theme = value
            try {
              localStorage.setItem('arz-theme', value)
            } catch {
              /* Soukromý režim může úložiště odmítnout. */
            }
          }}
        >
          {label}
        </button>
      ))}
    </div>
  )
}
