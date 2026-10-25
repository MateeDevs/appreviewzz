import type { Page } from '@playwright/test'
import type { App, AnalysisOverview, Review, Reply, RatingsSeries } from '../src/api/types'

// Syntetická, izolovaná data pouze pro browser regresní testy. Nikdy se nebundlují do produktu.
export const apps: App[] = ['Atlas', 'Orbit', 'Kompas'].map((name, i) => ({
  id: `app-${i + 1}`,
  name,
  gpPackageName: `cz.example.${name.toLowerCase()}`,
  ascAppId: '123456789',
  gpReportingBucket: null,
  platforms: ['ANDROID', 'IOS'],
  locale: 'CS',
  timezone: 'Europe/Prague',
  notifyFrom: null,
  aiInstructions: null,
  ingestIntervalMinutes: 15,
  ingestIntervalSource: 'PLATFORM',
  dailyDigestAt: '08:00',
  weeklyDigestDay: 1,
  historyMonths: 12,
  analysisCadence: 'WEEKLY',
  analysisMinReviews: 10,
  analysisMinTopicCount: 3,
  autoThanksEnabled: false,
  autoThanksTemplate: null,
  competitor: i === 2,
  analysisThresholdSource: 'PLATFORM',
  enabled: true,
  setup: {
    ready: i !== 1,
    gaps: i === 1 ? ['STORE_KEY'] : [],
    platformsWithoutKey: i === 1 ? ['IOS'] : [],
    platformsWaitingForKey: [],
    credentialIds: [],
  },
}))
export const reviews: Review[] = [
  {
    id: 'review-1',
    authorName: 'Petra Nováková',
    title: 'Po aktualizaci se nepřihlásím',
    body: 'Od poslední aktualizace aplikace padá při přihlášení. Potřebuji se dostat ke svým údajům, ale ani přeinstalování nepomohlo.',
    starRating: 1,
    appVersion: '4.8.0',
    state: 'NEW',
    platform: 'IOS',
  },
  {
    id: 'review-2',
    authorName: 'Martin Dvořák',
    title: 'Skvělý přehled, chybí mi export',
    body: 'Používám každý den. Bylo by skvělé přidat export přehledu do PDF. Jinak jsem moc spokojený.',
    starRating: 4,
    appVersion: '4.7.2',
    state: 'NOTIFIED',
    platform: 'ANDROID',
  },
  {
    id: 'review-3',
    authorName: 'Alex',
    title: 'Login still fails',
    body: 'I cannot sign in after updating. Please fix this issue.',
    starRating: 2,
    appVersion: '4.8.0',
    state: 'UPDATED',
    platform: 'ANDROID',
  },
  {
    id: 'review-4',
    authorName: 'Jana',
    title: null,
    body: 'Konečně přehledná aplikace. Děkuji!',
    starRating: 5,
    appVersion: '4.7.2',
    state: 'REPLIED',
    platform: 'IOS',
  },
  {
    id: 'review-5',
    authorName: null,
    title: 'Smazaná recenze',
    body: 'Původní text odstraněné recenze.',
    starRating: 2,
    appVersion: '4.6',
    state: 'IGNORED',
    platform: 'IOS',
    removedAt: '2026-10-23T12:00:00Z',
  },
].map((r, i) => ({
  storeReviewId: r.id,
  territory: 'CZE',
  submittedAt: `2026-10-${24 - i}T08:00:00Z`,
  developerResponseBody: i === 3 ? 'Děkujeme za zpětnou vazbu.' : null,
  developerResponseAt: i === 3 ? '2026-10-22T10:00:00Z' : null,
  insight: {
    sentiment: i === 1 || i === 3 ? 'POSITIVE' : 'NEGATIVE',
    type: i === 1 ? 'FEATURE_REQUEST' : i === 3 ? 'PRAISE' : 'BUG',
    urgency: i === 0 || i === 2 ? 'HIGH' : 'LOW',
    language: i === 2 ? 'en' : 'cs',
    translation: i === 2 ? 'Po aktualizaci se nemohu přihlásit.' : null,
    topics: [
      {
        key: i === 1 ? 'features' : i === 3 ? 'praise' : 'login',
        name: i === 1 ? 'Nové funkce' : i === 3 ? 'Pochvala' : 'Přihlášení',
        sentiment: i === 1 || i === 3 ? 'POSITIVE' : 'NEGATIVE',
        quote: null,
      },
    ],
  },
  ...r,
})) as Review[]
const weekly = [35, 40, 31, 46, 52, 39, 45, 33].map((positive, i) => ({
  weekStart: `2026-09-${String(i * 3 + 1).padStart(2, '0')}`,
  positive,
  negative: 60 - positive,
  neutral: 10,
  reviews: 70,
  avgStars: 3.8 + i * 0.05,
}))
export const analysis: AnalysisOverview = {
  periodStart: '2026-09-25',
  periodEnd: '2026-10-24',
  dataSince: '2026-01-01',
  reviews: 560,
  byPlatform: { IOS: 240, ANDROID: 320 },
  avgStars: 4.15,
  sentiment: { positive: 0.73, neutral: 0.12, negative: 0.15 },
  previousSentiment: { positive: 0.78, neutral: 0.12, negative: 0.1 },
  weekly,
  allReviewsMood: {
    reviews: 1280,
    avgStars: 4.28,
    sentiment: { positive: 0.81, neutral: 0.08, negative: 0.11 },
    previousSentiment: { positive: 0.84, neutral: 0.08, negative: 0.08 },
    weekly,
  },
  topics: [
    {
      key: 'login',
      name: 'Přihlášení',
      count: 84,
      previousCount: 41,
      share: 0.15,
      negativeShare: 0.86,
      avgStars: 1.8,
      status: 'GROWING',
      trend: [2, 3, 2, 4, 7, 5, 9, 12],
    },
    {
      key: 'speed',
      name: 'Rychlost aplikace',
      count: 56,
      previousCount: 62,
      share: 0.1,
      negativeShare: 0.63,
      avgStars: 2.6,
      status: 'FALLING',
      trend: [9, 8, 5, 6, 5, 4, 4, 3],
    },
    {
      key: 'features',
      name: 'Nové funkce',
      count: 39,
      previousCount: 30,
      share: 0.07,
      negativeShare: 0.2,
      avgStars: 4,
      status: 'STABLE',
      trend: [2, 3, 3, 4, 3, 5, 4, 5],
    },
  ],
  improved: [{ key: 'speed', name: 'Rychlost', before: 62, after: 56 }],
  territories: [
    { territory: 'CZE', reviews: 320, negativeShare: 0.12, avgStars: 4.2 },
    { territory: 'SVK', reviews: 180, negativeShare: 0.2, avgStars: 3.9 },
  ],
  languages: [{ language: 'cs', reviews: 320, negativeShare: 0.12 }],
  replies: { total: 560, replied: 412, share: 412 / 560, medianHours: 5.4, uplifted: 12, dropped: 2 },
  analyzed: 560,
  missing: 18,
  minReviews: 10,
  tooFewReviews: false,
}
export async function mockApi(
  page: Page,
  options: { appCount?: number; empty?: boolean; role?: 'OWNER' | 'MEMBER' } = {},
) {
  const dataApps = options.empty
    ? []
    : Array.from({ length: options.appCount ?? 3 }, (_, i) => ({
        ...apps[i % 3]!,
        id: `app-${i + 1}`,
        name: i < 3 ? apps[i]!.name : `Aplikace ${i + 1}`,
      }))
  const history: Record<string, Reply[]> = {}
  const requests: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const req = route.request(),
        url = new URL(req.url()),
        path = url.pathname
      requests.push(`${req.method()} ${url.pathname}${url.search}`)
      let data: unknown = []
      if (path === '/api/auth/me')
        data = {
          id: 'user-1',
          email: 'design@example.test',
          displayName: 'Designový tým',
          emailVerified: true,
          mfaEnabled: true,
          platformRole: 'SUPERADMIN',
          organizations: [
            { id: 'org-1', slug: 'studio', name: 'Studio • ukázková data', role: options.role ?? 'OWNER' },
            { id: 'org-2', slug: 'other', name: 'Druhá organizace', role: 'MEMBER' },
          ],
        }
      else if (path === '/api/orgs')
        data = [
          { id: 'org-1', slug: 'studio', name: 'Studio • ukázková data', role: 'OWNER' },
          { id: 'org-2', slug: 'other', name: 'Druhá organizace', role: 'MEMBER' },
        ]
      else if (path.endsWith('/onboarding')) data = { appId: null, step: 'app', preferencesConfirmed: false, completed: false, started: false }
      else if (/\/onboarding\/[^/]+\/sync$/.test(path)) data = []
      else if (path.endsWith('/health'))
        data = {
          apps: dataApps.map((a, i) => ({
            appId: a.id,
            name: a.name,
            enabled: true,
            competitor: a.competitor,
            pendingReviews: a.competitor ? 0 : 28 - (i % 3) * 4,
            pendingUrgent: a.competitor ? 0 : 3 - (i % 3),
            lastReviewAt: reviews[0]!.submittedAt,
            credentials:
              i === 1
                ? []
                : [{ id: 'key', label: 'Store', validationStatus: 'VALID', validationError: null }],
            channels: [{ id: 'ch', targetRef: 'reviews', enabled: true, hasCredential: true }],
          })),
          failedJobs: [],
        }
      else if (path.endsWith('/apps')) data = path.includes('/other/') ? [] : dataApps
      else if (path.endsWith('/analysis/status'))
        data = { analyzed: 560, missing: 18, queued: false, taxonomyVersion: '1' }
      else if (path.endsWith('/analysis/alerts'))
        data = [
          {
            id: 'alert-1',
            kind: 'TOPIC_SPIKE',
            topicKey: 'login',
            topicName: 'Přihlášení',
            windowDate: '2026-10-24',
            observed: 12,
            expected: 3.2,
            zScore: 3.8,
            createdAt: '2026-10-24T09:00:00Z',
          },
        ]
      else if (path.endsWith('/analysis/versions'))
        data = [
          {
            version: '4.8.0',
            platform: 'IOS',
            firstSeen: '2026-10-20',
            after: {
              reviews: 84,
              avgStars: 3.2,
              negativeShare: 0.34,
              topics: [{ key: 'login', name: 'Přihlášení', count: 30, share: 0.36 }],
            },
            before: { reviews: 250, avgStars: 4.2, negativeShare: 0.12, topics: [] },
            newTopics: [{ key: 'login', name: 'Přihlášení', count: 30, share: 0.36 }],
            goneTopics: [],
            starsDelta: -1,
            negativeDelta: 0.22,
            assessable: true,
            minReviews: 10,
          },
        ]
      else if (path.endsWith('/analysis')) data = analysis
      else if (path.endsWith('/ratings'))
        data = (['IOS', 'ANDROID'] as const).map((platform) => ({
          platform,
          territory: 'CZE',
          change: 0.04,
          points: [4.21, 4.2, 4.24, 4.23, 4.26, 4.25, 4.25].map((average, i) => ({
            date: `2026-10-${18 + i}`,
            average,
            totalCount: 12000 + i * 18,
            newCount: 18,
            histogram: {},
            source: 'STORE',
          })),
        })) satisfies RatingsSeries[]
      else if (/\/reviews(\/count)?$/.test(path)) {
        const filtered = reviews.filter(
          (r) =>
            (!url.searchParams.get('urgency') || r.insight?.urgency === url.searchParams.get('urgency')) &&
            (!url.searchParams.get('state') || url.searchParams.get('state')!.split(',').includes(r.state)) &&
            (!url.searchParams.get('topic') ||
              r.insight?.topics.some((t) => t.key === url.searchParams.get('topic'))) &&
            (!url.searchParams.get('version') || r.appVersion === url.searchParams.get('version')),
        )
        data = path.endsWith('/count')
          ? { count: options.empty ? 0 : filtered.length }
          : options.empty
            ? []
            : filtered
      } else if (/\/reviews\/[^/]+$/.test(path)) {
        const id = path.split('/').pop()!
        data = {
          review: reviews.find((r) => r.id === id) ?? reviews[0],
          replies: history[id] ?? [],
          messages: [],
        }
      } else if (path.endsWith('/suggest'))
        data = {
          text: 'Děkujeme za upozornění. Problém s přihlášením prověřujeme.',
          model: 'test',
          message: null,
        }
      else if (path.endsWith('/translate'))
        data = { text: 'Thank you. We are investigating the login issue.', model: 'test', message: null }
      else if (path.endsWith('/reply')) {
        const id = path.split('/').at(-2)!
        history[id] = [
          {
            id: 'reply-1',
            body: req.postDataJSON().body,
            source: 'CONSOLE',
            status: 'PENDING',
            error: null,
            authorDisplayName: 'Designový tým',
            createdAt: '2026-10-24T10:00:00Z',
            publishedAt: null,
          },
        ]
        data = { queued: true, message: 'Odpověď čeká ve frontě.' }
      } else if (path.endsWith('/topics'))
        data = analysis.topics.map((t) => ({
          key: t.key,
          name: t.name,
          description: '',
          group: 'Produkt',
          custom: false,
          enabled: true,
          recentCount: t.count,
        }))
      else if (path.endsWith('/reply-templates'))
        data = [
          {
            id: 'template-1',
            name: 'Poděkování',
            body: 'Děkujeme, {jmeno}, za recenzi aplikace {appka}.',
            updatedAt: '2026-10-24T10:00:00Z',
          },
        ]
      else if (path === '/api/orgs/studio')
        data = { id: 'org-1', slug: 'studio', name: 'Studio', plan: 'REGULAR', role: options.role ?? 'OWNER' }
      else if (path.endsWith('/totp'))
        data = { enabled: true, setupPending: false, remainingRecoveryCodes: 10 }
      await route.fulfill({ json: data })
    },
  )
  return { requests, history }
}
