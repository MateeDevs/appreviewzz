import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface SelectGroup {
  label: string
  options: SelectOption[]
}

type SelectProps = {
  value: string
  options?: SelectOption[]
  groups?: SelectGroup[]
  onChange: (value: string) => void
  ariaLabel: string
  disabled?: boolean
  fitContent?: boolean
  className?: string
  placeholder?: string
}

/**
 * Jednotný výběr pro celou konzoli.
 *
 * Menu se portáluje mimo scrollovací obsah a pozici počítá z tlačítka. Neusekne ho tedy
 * karta ani dialog s `overflow` a na rozdíl od systémového selectu se vždy otevře přímo
 * pod (nebo při nedostatku místa nad) ovládacím prvkem. Klávesnice kopíruje běžný listbox.
 */
export function Select({
  value,
  options = [],
  groups = [],
  onChange,
  ariaLabel,
  disabled = false,
  fitContent = false,
  className,
  placeholder = 'Vyberte možnost',
}: SelectProps) {
  const [open, setOpen] = useState(false)
  const [menuStyle, setMenuStyle] = useState<CSSProperties>()
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const menuId = useId()
  const allOptions = [...options, ...groups.flatMap((group) => group.options)]
  const selected = allOptions.find((option) => option.value === value)
  // Modální `<dialog>` žije v top layer; obsah portálovaný do `body` by skončil pod ním
  // a prohlížeč by mu navíc zablokoval interakci. Přímý potomek dialogu se neořízne jeho
  // scrollovacím `.modal-body` a pořád zůstane v top layer.
  const portalTarget = rootRef.current?.closest('dialog') ?? document.body

  useLayoutEffect(() => {
    if (!open || !triggerRef.current) return

    const positionMenu = () => {
      const trigger = triggerRef.current
      if (!trigger) return
      const rect = trigger.getBoundingClientRect()
      const viewportPadding = 12
      const gap = 6
      const menuWidth = Math.min(Math.max(rect.width, 176), window.innerWidth - 2 * viewportPadding)
      const left = Math.min(
        Math.max(viewportPadding, rect.left),
        Math.max(viewportPadding, window.innerWidth - menuWidth - viewportPadding),
      )
      const roomBelow = window.innerHeight - rect.bottom - gap - viewportPadding
      const roomAbove = rect.top - gap - viewportPadding
      const openAbove = roomBelow < 220 && roomAbove > roomBelow
      const maxHeight = Math.max(0, openAbove ? roomAbove : roomBelow)

      setMenuStyle({
        left,
        width: menuWidth,
        maxHeight,
        ...(openAbove ? { bottom: window.innerHeight - rect.top + gap } : { top: rect.bottom + gap }),
      })
    }

    positionMenu()
    window.addEventListener('resize', positionMenu)
    window.addEventListener('scroll', positionMenu, true)
    return () => {
      window.removeEventListener('resize', positionMenu)
      window.removeEventListener('scroll', positionMenu, true)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: PointerEvent) => {
      const target = event.target as Node
      if (!rootRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  useEffect(() => {
    if (disabled) setOpen(false)
  }, [disabled])

  const focusOption = (direction: 1 | -1) => {
    const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="option"]:not(:disabled)') ?? [])]
    if (items.length === 0) return
    const current = items.indexOf(document.activeElement as HTMLButtonElement)
    const next = current < 0 ? (direction === 1 ? 0 : items.length - 1) : (current + direction + items.length) % items.length
    items[next]?.focus()
  }

  const focusEdge = (edge: 'first' | 'last') => {
    const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="option"]:not(:disabled)') ?? [])]
    items[edge === 'first' ? 0 : items.length - 1]?.focus()
  }

  const choose = (nextValue: string) => {
    onChange(nextValue)
    setOpen(false)
    triggerRef.current?.focus()
  }

  const optionButtons = (items: SelectOption[]) =>
    items.map((option) => (
      <button
        key={option.value}
        type="button"
        role="option"
        aria-selected={option.value === value}
        className="select-option"
        disabled={option.disabled}
        onClick={() => choose(option.value)}
      >
        <span className="select-check" aria-hidden="true">{option.value === value ? '✓' : ''}</span>
        <span>{option.label}</span>
      </button>
    ))

  return (
    <div className={`select-root${fitContent ? ' fit-content' : ''}${className ? ` ${className}` : ''}`} ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="select-trigger"
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setOpen(true)
            requestAnimationFrame(() => focusOption(event.key === 'ArrowDown' ? 1 : -1))
          } else if (event.key === 'Escape' && open) {
            event.preventDefault()
            setOpen(false)
          }
        }}
      >
        <span>{selected?.label ?? placeholder}</span>
        <svg aria-hidden="true" viewBox="0 0 12 12">
          <path d="M2.5 4.5 6 8l3.5-3.5" />
        </svg>
      </button>
      {open && menuStyle
        ? createPortal(
            <div
              id={menuId}
              ref={menuRef}
              className="select-menu"
              role="listbox"
              aria-label={ariaLabel}
              style={menuStyle}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  event.preventDefault()
                  setOpen(false)
                  triggerRef.current?.focus()
                } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  event.preventDefault()
                  focusOption(event.key === 'ArrowDown' ? 1 : -1)
                } else if (event.key === 'Home' || event.key === 'End') {
                  event.preventDefault()
                  focusEdge(event.key === 'Home' ? 'first' : 'last')
                } else if (event.key === 'Tab') {
                  setOpen(false)
                }
              }}
            >
              {optionButtons(options)}
              {groups.map((group) => (
                <div className="select-group" role="group" aria-label={group.label} key={group.label}>
                  <div className="select-group-label">{group.label}</div>
                  {optionButtons(group.options)}
                </div>
              ))}
            </div>,
            portalTarget,
          )
        : null}
    </div>
  )
}
