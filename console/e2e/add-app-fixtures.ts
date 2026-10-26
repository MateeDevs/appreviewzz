import type { Page } from '@playwright/test'
import type { App, Credential, Platform, ReportingBucketCheck, StoreApp } from '../src/api/types'
import { onboardingApi } from './onboarding-fixtures'
import { apps as examples } from './fixtures'

export async function wizardApi(
  page: Page,
  options: { empty?: boolean; platforms?: Platform[]; role?: 'OWNER' | 'MEMBER' } = {},
) {
  const state = await onboardingApi(page, options)
  const extra = {
    provisions: 0,
    uploads: 0,
    attaches: 0,
    checks: 0,
    report: 'OK' as ReportingBucketCheck['status'],
    failApps: new Set<string>(),
    kind: null as string | null,
    validationDelay: 0,
    validationConcurrency: 0,
    maxValidationConcurrency: 0,
    storeApps: [
      { identifier: '123456789', name: 'Atlas', bundleId: 'cz.example.atlas' },
      { identifier: '987654321', name: 'Orbit', bundleId: 'cz.example.orbit' },
    ] as StoreApp[],
    scoped: new Map<string, boolean>(),
    patches: [] as Record<string, unknown>[],
  }
  const refresh = () => {
    for (const app of state.apps) {
      const keys = state.credentials.filter((c) => app.setup.credentialIds.includes(c.id))
      app.setup.platformsWithoutKey = app.platforms.filter(
        (p) =>
          !keys.some((c) => c.type === (p === 'ANDROID' ? 'GP_SERVICE_ACCOUNT' : 'ASC_API_KEY')),
      )
      app.setup.platformsWaitingForKey = app.platforms.filter((p) =>
        keys.some(
          (c) =>
            c.type === (p === 'ANDROID' ? 'GP_SERVICE_ACCOUNT' : 'ASC_API_KEY') &&
            !extra.scoped.get(`${app.id}:${c.id}`),
        ),
      )
      app.setup.ready =
        !app.setup.platformsWithoutKey.length && !app.setup.platformsWaitingForKey.length
    }
  }
  const credential = (type: Credential['type'], id: string): Credential => ({
    id,
    type,
    label: type === 'ASC_API_KEY' ? 'App Store · TEST123456' : 'Spravovaný Google Play',
    hint: type === 'ASC_API_KEY' ? 'Key ID TEST123456' : 'synthetic-service@example.test',
    origin: type === 'ASC_API_KEY' ? 'UPLOADED' : 'PROVISIONED',
    fingerprint: 'synthetic-only',
    validationStatus: 'UNKNOWN',
    validationError: null,
    validatedAt: null,
  })
  await page.route('**/api/orgs/studio/**', async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname,
      method = request.method(),
      body = request.postDataJSON() as Record<string, unknown> | null
    const ok = (json: unknown) => route.fulfill({ json })
    if (path.endsWith('/apps/resolve')) return route.fallback()
    if (path.endsWith('/apps')) {
      if (method === 'POST') {
        if (
          state.apps.some(
            (a) =>
              (body?.gpPackageName && a.gpPackageName === body.gpPackageName) ||
              (body?.ascAppId && a.ascAppId === body.ascAppId),
          )
        )
          return route.fulfill({ status: 409, json: { message: 'Aplikace již existuje' } })
        state.creates++
        const platforms: Platform[] = []
        if (body!.gpPackageName) platforms.push('ANDROID')
        if (body!.ascAppId) platforms.push('IOS')
        const app = {
          ...structuredClone(examples[0]!),
          ...body,
          id: `app-${state.apps.length + 1}`,
          platforms,
          competitor: !!body!.competitor,
          setup: {
            ready: false,
            gaps: ['STORE_KEY'],
            credentialIds: [],
            platformsWithoutKey: platforms,
            platformsWaitingForKey: [],
          },
        } as App
        state.apps.push(app)
        await new Promise((r) => setTimeout(r, 100))
        return ok(app)
      }
      refresh()
      return ok(state.apps)
    }
    if (/\/apps\/[^/]+$/.test(path) && method === 'PATCH') {
      if (!body!.name) return route.fulfill({ status: 400, json: { message: 'name is required' } })
      extra.patches.push(body!)
      const target = state.apps.find((a) => path.endsWith('/' + a.id))!
      Object.assign(target, body)
      return ok(target)
    }
    if (path.endsWith('/credentials/provision-gp')) {
      extra.provisions++
      let key = state.credentials.find((c) => c.id === 'gp-key')
      if (!key) {
        key = credential('GP_SERVICE_ACCOUNT', 'gp-key')
        state.credentials.push(key)
      }
      return ok(key)
    }
    if (path.endsWith('/store-apps')) return ok(extra.storeApps)
    if (path.endsWith('/validate')) {
      extra.checks++
      extra.validationConcurrency++
      extra.maxValidationConcurrency = Math.max(
        extra.maxValidationConcurrency,
        extra.validationConcurrency,
      )
      await new Promise((r) => setTimeout(r, extra.validationDelay))
      extra.validationConcurrency--
      const app = state.apps.find((a) => path.includes('/apps/' + a.id + '/'))!,
        key = state.credentials.find((c) => path.includes('/credentials/' + c.id + '/'))!
      const valid = !extra.kind && !extra.failApps.has(app.ascAppId ?? '')
      extra.scoped.set(`${app.id}:${key.id}`, valid)
      refresh()
      return ok({
        valid,
        kind: extra.kind ?? (valid ? null : 'AUTH'),
        message: valid ? null : 'Synthetic connector detail must not be shown',
      })
    }
    if (path.endsWith('/reporting-bucket/check'))
      return ok({
        status: extra.report,
        worthSaving: ['OK', 'NO_EXPORT'].includes(extra.report),
        message: 'Synthetic upstream detail',
      })
    if (/\/apps\/[^/]+\/credentials$/.test(path)) {
      extra.attaches++
      const app = state.apps.find((a) => path.includes('/apps/' + a.id + '/'))!
      const key = state.credentials.find((c) => c.id === body!.credentialId)!
      app.setup.credentialIds = app.setup.credentialIds.filter(
        (id) => !state.credentials.some((c) => c.id === id && c.type === key.type),
      )
      app.setup.credentialIds.push(key.id)
      extra.scoped.set(`${app.id}:${key.id}`, false)
      refresh()
      return route.fulfill({ status: 204 })
    }
    if (path.endsWith('/credentials')) {
      if (method === 'POST') {
        extra.uploads++
        const key = credential(
          body!.type === 'asc' ? 'ASC_API_KEY' : 'GP_SERVICE_ACCOUNT',
          body!.type === 'asc' ? 'asc-key' : 'custom-gp',
        )
        if (!state.credentials.some((c) => c.id === key.id)) state.credentials.push(key)
        return ok(key)
      }
      return ok(state.credentials)
    }
    if (path.endsWith('/health')) {
      refresh()
      return ok({
        apps: state.apps.map((a) => ({
          appId: a.id,
          name: a.name,
          enabled: true,
          competitor: a.competitor,
          pendingReviews: 0,
          pendingUrgent: 0,
          lastReviewAt: null,
          channels: [],
          credentials: state.credentials
            .filter((c) => a.setup.credentialIds.includes(c.id))
            .map((c) => ({
              ...c,
              validationStatus: extra.scoped.get(`${a.id}:${c.id}`) ? 'VALID' : 'INVALID',
              validationError: null,
            })),
        })),
        failedJobs: [],
      })
    }
    return route.fallback()
  })
  return { state, extra, refresh, credential }
}
export const syntheticP8 = {
  name: 'AuthKey_TEST123456.p8',
  mimeType: 'text/plain',
  buffer: Buffer.from(
    '-----BEGIN PRIVATE KEY-----\nc3ludGhldGljVGVzdE9ubHk=\n-----END PRIVATE KEY-----',
  ),
}
