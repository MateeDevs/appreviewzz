/** Tvary odpovědí API. Drží se jedna k jedné DTO na serveru — když se rozejdou, spadne build. */

export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER'
/** Správa platformy — osa kolmá k členství, ne vyšší role v organizaci. */
export type PlatformRole = 'SUPERADMIN'
export type Platform = 'ANDROID' | 'IOS'
export type ValidationStatus = 'UNKNOWN' | 'VALID' | 'INVALID'
export type ReviewState = 'NEW' | 'NOTIFIED' | 'REPLIED' | 'UPDATED' | 'IGNORED' | 'SUPPRESSED'
export type ReplyStatus = 'PENDING' | 'PUBLISHED' | 'FAILED'
export type ReplySource = 'SLACK' | 'TEAMS' | 'CONSOLE'
export type MessageStatus = 'PENDING' | 'SENT' | 'FAILED'
export type CredentialType = 'GP_SERVICE_ACCOUNT' | 'ASC_API_KEY' | 'SLACK_INSTALL' | 'TEAMS_BOT_REF'

export interface OrganizationSummary {
  id: string
  slug: string
  name: string
  role: OrgRole
}

export interface Me {
  id: string
  email: string
  displayName: string | null
  emailVerified: boolean
  mfaEnabled: boolean
  /** `null` u drtivé většiny účtů. Odkaz na sekci se podle toho jen ukazuje — rozhoduje server. */
  platformRole: PlatformRole | null
  organizations: OrganizationSummary[]
}

/**
 * Přihlášení, které ještě není hotové: heslo prošlo, chybí kód z autentizační appky.
 * Server ho vrací s `202 Accepted` a relace zatím žádná není.
 */
export interface SecondFactorChallenge {
  challenge: string
  expiresAt: string
}

export type LoginOutcome = Me | SecondFactorChallenge

export function needsSecondFactor(outcome: LoginOutcome): outcome is SecondFactorChallenge {
  return 'challenge' in outcome
}

export interface MfaStatus {
  enabled: boolean
  setupPending: boolean
  remainingRecoveryCodes: number
}

export interface TotpSetup {
  secret: string
  provisioningUri: string
}

export interface Member {
  userId: string
  email: string
  displayName: string | null
  role: OrgRole
  since: string
}

export interface Invitation {
  id: string
  email: string
  role: OrgRole
  expiresAt: string
  delivered?: boolean
}

export interface App {
  id: string
  name: string
  gpPackageName: string | null
  gpReportingBucket: string | null
  ascAppId: string | null
  platforms: Platform[]
  locale: 'CS' | 'EN'
  timezone: string
  notifyFrom: string | null
  aiInstructions: string | null
  /** Efektivní interval. Nastavuje ho provozovatel platformy, klient ho jen vidí. */
  ingestIntervalMinutes: number
  ingestIntervalSource: 'PLATFORM' | 'APP'
  dailyDigestAt: string
  enabled: boolean
  /** Co appce chybí, aby recenze tekly — počítá server, console to jen ukazuje. */
  setup: AppSetup
}

/** Chybějící nastavení appky. Prázdné `gaps` znamenají, že appka doopravdy běží. */
export interface AppSetup {
  ready: boolean
  gaps: SetupGap[]
  platformsWithoutKey: Platform[]
  /** Store má klíč, ale ten ještě neprošel ověřením — čeká se na store, ne na klienta. */
  platformsWaitingForKey: Platform[]
}

export type SetupGap = 'STORE_KEY' | 'STORE_KEY_WAITING' | 'CHANNEL'

/** Co server vyčetl z jednoho odkazu na store. `name` chybí, když store neodpověděl. */
export interface ResolvedStore {
  platform: Platform
  identifier: string
  name: string | null
  error: string | null
}

export interface StoreResolution {
  googlePlay: ResolvedStore | null
  appStore: ResolvedStore | null
}

/** PROVISIONED = service account jsme vyrobili my, klient ho jen pozval do Play Console. */
export type CredentialOrigin = 'UPLOADED' | 'PROVISIONED'

export interface Credential {
  id: string
  type: CredentialType
  label: string
  fingerprint: string
  hint: string | null
  origin: CredentialOrigin
  validationStatus: ValidationStatus
  validationError: string | null
  validatedAt: string | null
}

/** Aplikace, kterou klíč vidí ve storu — položka výběru v dialogu napojení. */
export interface StoreApp {
  identifier: string
  name: string
  bundleId: string | null
}

export interface Channel {
  id: string
  type: 'SLACK' | 'TEAMS'
  targetRef: string
  targetLabel: string | null
  credentialId: string | null
  locale: 'CS' | 'EN'
  deliverReviews: boolean
  deliverRatings: boolean
  enabled: boolean
}

export interface ChannelCheck {
  channelId: string
  targetRef: string
  ok: boolean
  error?: string | null
  hint?: string | null
}

export interface SlackConnection {
  credentialId: string
  workspace: string
  botUserId: string | null
  scopes: string | null
  missingScopes: string[]
}

export interface Review {
  id: string
  platform: Platform
  storeReviewId: string
  authorName: string | null
  starRating: number
  title: string | null
  body: string | null
  appVersion: string | null
  territory: string | null
  submittedAt: string
  state: ReviewState
  developerResponseBody: string | null
  developerResponseAt: string | null
}

export interface Reply {
  id: string
  body: string
  source: ReplySource
  status: ReplyStatus
  error: string | null
  authorDisplayName: string | null
  publishedAt: string | null
  createdAt: string
}

export interface ReviewDetail {
  review: Review
  messages: { channelId: string; status: MessageStatus; error: string | null; sentAt: string | null }[]
  replies: Reply[]
}

export interface AppHealth {
  appId: string
  name: string
  enabled: boolean
  lastReviewAt: string | null
  pendingReviews: number
  channels: { id: string; targetRef: string; enabled: boolean; hasCredential: boolean }[]
  credentials: { id: string; label: string; validationStatus: ValidationStatus; validationError: string | null }[]
}

export interface Health {
  apps: AppHealth[]
  failedJobs: { task: string; attempts: number; error: string | null; firstFailedAt: string; lastFailedAt: string }[]
}

export interface AuditEntry {
  action: string
  actor: string | null
  targetType: string | null
  targetId: string | null
  metadata: Record<string, string>
  at: string | null
}

export interface RatingsPoint {
  date: string
  average: number | null
  totalCount: number | null
  /** Přírůstek proti předchozímu bodu; u nejstaršího není co odečíst. */
  newCount: number | null
  histogram: Record<string, number>
  source: string
}

export interface RatingsSeries {
  platform: Platform
  territory: string
  points: RatingsPoint[]
  /** Změna průměru za zobrazené období. */
  change: number | null
}

export interface RatingsRunResult {
  platforms: number
  sent: number
  alreadySent: number
  errors: string[]
}

// ---------------------------------------------------------------- správa platformy (F7)

export type PlatformSettingType = 'INT' | 'TEXT' | 'BOOL' | 'ENUM' | 'SECRET'

/** Odkud je hodnota, která právě platí. Bez toho se dlouho hledá, proč uložení nic neudělalo. */
export type PlatformSettingSource = 'DEFAULT' | 'ENV' | 'DB'

export interface PlatformSetting {
  key: string
  type: PlatformSettingType
  section: string
  label: string
  help: string
  /** U tajemství vždy `null` — hodnota se z API nevrací. */
  value: string | null
  source: PlatformSettingSource
  default: string | null
  envName: string | null
  options: string[]
  min: number | null
  max: number | null
}

export interface PlatformSecret {
  key: string
  label: string
  fingerprint: string
  hint: string | null
  updatedAt: string
}

export interface PlatformOverview {
  organizations: number
  users: number
  apps: number
  enabledApps: number
  failedJobs: number
  appsWithIntervalOverride: number
  defaultIntervalMinutes: number
  minIntervalMinutes: number
}

export interface PlatformAuditEntry {
  actorLabel: string | null
  action: string
  targetKey: string | null
  metadata: Record<string, string>
  createdAt: string | null
}

export interface PlatformApp {
  id: string
  name: string
  orgId: string
  intervalMinutes: number
  /** `null` znamená, že appka jede na platformní výchozí hodnotě. */
  overrideMinutes: number | null
  enabled: boolean
}
