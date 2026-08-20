/**
 * Klient nad API console.
 *
 * Session drží httpOnly cookie, kterou JavaScript nevidí — proto se sem nedá „uložit token"
 * a proto každé volání jde s `credentials: same-origin`. Proti CSRF se ke každé měnící
 * metodě přikládá hodnota z cookie `arz_csrf` v hlavičce; server obojí porovná.
 */
const CSRF_COOKIE = 'arz_csrf'
const CSRF_HEADER = 'X-CSRF-Token'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${CSRF_COOKIE}=([^;]*)`))
  return match?.[1] ? decodeURIComponent(match[1]) : null
}

/** Server cookie vydá na tomhle endpointu; console si o ni řekne dřív, než ukáže formulář. */
export async function ensureCsrf(): Promise<void> {
  if (csrfToken()) return
  await fetch('/api/auth/csrf', { credentials: 'same-origin' })
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  if (method !== 'GET') await ensureCsrf()

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = csrfToken()
  if (token) headers[CSRF_HEADER] = token

  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 204) return undefined as T
  const text = await response.text()
  const payload: unknown = text ? JSON.parse(text) : null

  if (!response.ok) {
    const detail = payload as { error?: string; message?: string } | null
    throw new ApiError(
      response.status,
      detail?.error ?? 'unknown_error',
      detail?.message ?? popisChyby(response.status),
    )
  }
  return payload as T
}

/** Když server větu nepošle (nemá ji ke každé chybě), musí ji console umět sama. */
function popisChyby(status: number): string {
  switch (status) {
    case 401:
      return 'Nejsi přihlášený.'
    case 403:
      return 'Na tuhle akci nemáš oprávnění.'
    case 404:
      return 'Tohle tu není.'
    case 409:
      return 'Tohle už existuje.'
    default:
      return `Nepovedlo se to (HTTP ${status}).`
  }
}

export const api = {
  get: <T,>(path: string) => request<T>('GET', path),
  post: <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T,>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T,>(path: string, body: unknown) => request<T>('PATCH', path, body),
  delete: <T,>(path: string) => request<T>('DELETE', path),
}
