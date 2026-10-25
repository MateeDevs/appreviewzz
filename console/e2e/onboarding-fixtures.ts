import type { Page } from '@playwright/test'
import type { App, Credential, Me, Platform } from '../src/api/types'
import type { OnboardingProgress, OnboardingSync } from '../src/api/onboarding'
import { apps as examples, mockApi } from './fixtures'

/** Stavový mock transportu; data žijí mimo dokument, proto refresh simuluje nový browser request. */
export async function onboardingApi(
  page: Page,
  options: {
    platforms?: Platform[]
    signedOut?: boolean
    empty?: boolean
    role?: 'OWNER' | 'MEMBER'
  } = {},
) {
  await mockApi(page)
  const platforms = options.platforms ?? ['ANDROID', 'IOS']
  const state = {
    signedIn: !options.signedOut,
    profile: {
      id: 'onboarding-user',
      email: 'founder@example.test',
      displayName: 'Zakladatel',
      emailVerified: true,
      mfaEnabled: false,
      platformRole: null,
      organizations: options.signedOut
        ? []
        : [
            {
              id: 'org-1',
              slug: 'studio',
              name: 'Testovací prostor',
              role: options.role ?? 'OWNER',
            },
          ],
    } as Me,
    apps: options.empty
      ? []
      : ([
          {
            ...structuredClone(examples[0]!),
            platforms,
            gpPackageName: platforms.includes('ANDROID')
              ? 'cz.example.atlas'
              : null,
            ascAppId: platforms.includes('IOS') ? '123456789' : null,
            setup: {
              ready: false,
              gaps: ['STORE_KEY'],
              platformsWithoutKey: [...platforms],
              platformsWaitingForKey: [],
              credentialIds: [],
            },
          },
        ] as App[]),
    credentials: [] as Credential[],
    progress: {
      appId: options.empty ? null : 'app-1',
      step: options.empty ? 'app' : 'connect',
      preferencesConfirmed: false,
      completed: false,
      started: true,
    } as OnboardingProgress,
    syncs: [] as OnboardingSync[],
    creates: 0,
    validates: 0,
    waiting: false,
    syncFails: false,
    reviewCount: 0,
    syncRuns: 0,
    resendFails: false,
  }
  const updateSetup = () => {
    for (const app of state.apps) {
      const keys = state.credentials.filter((c) =>
        app.setup.credentialIds.includes(c.id),
      )
      app.setup.platformsWithoutKey = app.platforms.filter(
        (p) =>
          !keys.some(
            (c) =>
              c.type ===
              (p === 'ANDROID' ? 'GP_SERVICE_ACCOUNT' : 'ASC_API_KEY'),
          ),
      )
      app.setup.platformsWaitingForKey = app.platforms.filter((p) =>
        keys.some(
          (c) =>
            c.type ===
              (p === 'ANDROID' ? 'GP_SERVICE_ACCOUNT' : 'ASC_API_KEY') &&
            c.validationStatus !== 'VALID',
        ),
      )
      app.setup.ready =
        app.setup.platformsWithoutKey.length +
          app.setup.platformsWaitingForKey.length ===
        0
    }
  }
  await page.route('**/api/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname,
      method = request.method()
    const body = request.postDataJSON() as Record<string, string> | null
    const ok = (json: unknown) => route.fulfill({ json })
    if (path === '/api/auth/me')
      return state.signedIn
        ? ok(state.profile)
        : route.fulfill({ status: 401, json: {} })
    if (path === '/api/auth/register') {
      state.signedIn = true
      state.profile.email = body!.email!
      state.profile.emailVerified = false
      return ok(state.profile)
    }
    if (path === '/api/auth/login') {
      state.signedIn = true
      return ok(state.profile)
    }
    if (path === '/api/auth/logout') {
      state.signedIn = false
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/auth/email/verify') {
      state.profile.emailVerified = true
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/auth/email/resend')
      return state.resendFails
        ? route.fulfill({
            status: 429,
            headers: { 'Retry-After': '2' },
            json: { message: 'Počkejte na další pokus.' },
          })
        : route.fulfill({ status: 202 })
    if (path === '/api/invitations/accept') {
      state.profile.emailVerified = true
      state.profile.organizations = [
        { id: 'org-1', slug: 'studio', name: 'Pozvaný tým', role: 'MEMBER' },
      ]
      return ok(state.profile.organizations[0])
    }
    if (path === '/api/orgs') {
      if (method === 'GET') return ok(state.profile.organizations)
      state.profile.organizations = [
        { id: 'org-1', slug: 'studio', name: body!.name!, role: 'OWNER' },
      ]
      return ok(state.profile.organizations[0])
    }
    if (path.endsWith('/onboarding')) {
      if (method === 'PUT') {
        state.progress = request.postDataJSON() as OnboardingProgress
        state.progress.started = true
      }
      return ok(state.progress)
    }
    if (/\/onboarding\/[^/]+\/sync$/.test(path)) {
      if (method === 'POST') {
        state.syncRuns++
        state.syncs = platforms.map((platform) => ({
          platform,
          status: 'RUNNING',
          startedAt: new Date().toISOString(),
          finishedAt: null,
          fetched: null,
          message: null,
        }))
        await new Promise((resolve) => setTimeout(resolve, 350))
        state.syncs = platforms.map((platform) => ({
          platform,
          status: state.syncFails ? 'FAILED' : 'SUCCEEDED',
          startedAt: new Date().toISOString(),
          finishedAt: new Date().toISOString(),
          fetched: state.syncFails ? null : state.reviewCount,
          message: state.syncFails ? 'Store je dočasně nedostupný.' : null,
        }))
      }
      return ok(state.syncs)
    }
    if (path.endsWith('/apps/resolve'))
      return ok({
        googlePlay: body!.googlePlayUrl
          ? {
              platform: 'ANDROID',
              identifier: 'cz.example.atlas',
              name: 'Atlas',
              error: null,
            }
          : null,
        appStore: body!.appStoreUrl
          ? {
              platform: 'IOS',
              identifier: '123456789',
              name: 'Atlas',
              error: null,
            }
          : null,
      })
    if (path.endsWith('/apps')) {
      if (method === 'POST') {
        state.creates++
        const input = request.postDataJSON() as Partial<App>
        const app = {
          ...structuredClone(examples[0]!),
          ...input,
          id: 'app-1',
          competitor: false,
          platforms,
          setup: {
            ready: false,
            gaps: ['STORE_KEY'],
            platformsWithoutKey: [...platforms],
            platformsWaitingForKey: [],
            credentialIds: [],
          },
        } as App
        state.apps.push(app)
        await new Promise((resolve) => setTimeout(resolve, 150))
        return ok(app)
      }
      updateSetup()
      return ok(state.apps)
    }
    if (/\/apps\/app-1$/.test(path) && method === 'PATCH') {
      Object.assign(state.apps[0]!, body)
      return ok(state.apps[0])
    }
    if (path.endsWith('/credentials/provision-gp')) {
      const existing = state.credentials.find((c) => c.id === 'gp-key')
      if (existing) return ok(existing)
      const key: Credential = {
        id: 'gp-key',
        type: 'GP_SERVICE_ACCOUNT',
        label: 'Google Play',
        origin: 'PROVISIONED',
        hint: 'service@example.test',
        fingerprint: 'synthetic',
        validationStatus: 'UNKNOWN',
        validationError: null,
        validatedAt: null,
      }
      state.credentials.push(key)
      return ok(key)
    }
    if (path.endsWith('/store-apps'))
      return ok([
        {
          identifier: '123456789',
          name: 'Atlas',
          bundleId: 'cz.example.atlas',
        },
        {
          identifier: '987654321',
          name: 'Jiná aplikace',
          bundleId: 'cz.example.other',
        },
      ])
    if (path.endsWith('/validate')) {
      state.validates++
      const key = state.credentials.find((c) => path.includes(c.id))!
      key.validationStatus = state.waiting ? 'INVALID' : 'VALID'
      key.validationError = state.waiting
        ? 'Čekáme na propagaci oprávnění.'
        : null
      updateSetup()
      return ok({ valid: !state.waiting, message: key.validationError })
    }
    if (/\/apps\/[^/]+\/credentials$/.test(path)) {
      if (!state.apps[0]!.setup.credentialIds.includes(body!.credentialId!))
        state.apps[0]!.setup.credentialIds.push(body!.credentialId!)
      updateSetup()
      return route.fulfill({ status: 204 })
    }
    if (path.endsWith('/credentials')) {
      if (method === 'POST') {
        const key: Credential = {
          id: 'asc-key',
          type: 'ASC_API_KEY',
          label: 'App Store',
          origin: 'UPLOADED',
          hint: null,
          fingerprint: 'synthetic',
          validationStatus: 'UNKNOWN',
          validationError: null,
          validatedAt: null,
        }
        state.credentials.push(key)
        return ok(key)
      }
      return ok(state.credentials)
    }
    if (path.endsWith('/health'))
      return ok({
        apps: state.apps.map((a) => ({
          appId: a.id,
          name: a.name,
          enabled: a.enabled,
          competitor: a.competitor,
          pendingReviews: 0,
          pendingUrgent: 0,
          lastReviewAt: null,
          channels: [],
          credentials: state.credentials.filter((c) =>
            a.setup.credentialIds.includes(c.id),
          ),
        })),
        failedJobs: [],
      })
    if (path.endsWith('/reviews/count')) return ok({ count: state.reviewCount })
    if (path.endsWith('/reviews')) return ok([])
    return route.fallback()
  })
  return state
}
