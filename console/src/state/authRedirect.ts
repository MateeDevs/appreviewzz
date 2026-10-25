/** Sdílené všemi auth větvemi včetně pozdě doběhnuvšího /me. */
export function safeNext(value: string | null): string {
  if (
    !value ||
    !value.startsWith('/') ||
    /^[/\\]{2}|[\\]/.test(value) ||
    [...value].some((c) => c.charCodeAt(0) < 32)
  )
    return '/'
  if (/^\/(?:login|registrace)(?:[/?#]|$)/.test(value)) return '/'
  return value
}
export function authLink(path: string, next: string) {
  return next === '/' ? path : `${path}?next=${encodeURIComponent(next)}`
}
