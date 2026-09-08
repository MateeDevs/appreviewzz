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
  /** ISO den v týdnu (1 = pondělí), kdy chodí týdenní rozbor recenzí. */
  weeklyDigestDay: number
  /** Kolik měsíců zpětné historie recenzí se stahuje. Android to umí jen s reporting bucketem. */
  historyMonths: number
  /** Jak často chodí rozbor recenzí. */
  analysisCadence: 'WEEKLY' | 'MONTHLY'
  /** Prahy, které pro appku právě platí — buď z platformy, nebo z její výjimky. */
  analysisMinReviews: number
  analysisMinTopicCount: number
  analysisThresholdSource: 'PLATFORM' | 'APP'
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
  /** Klíče, které appka používá. Podle nich se pozná, který z klíčů organizace je přiřazený. */
  credentialIds: string[]
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
  /** Týdenní rozbory a alerty na výkyv. */
  deliverAnalyses: boolean
  enabled: boolean
}

export interface ChannelCheck {
  channelId: string
  targetRef: string
  ok: boolean
  error?: string | null
  hint?: string | null
}

/** Jak dopadla zkouška reportingového bucketu. Zrcadlí `ReportingBucketStatus` na serveru. */
export type ReportingBucketStatus = 'OK' | 'NO_EXPORT' | 'DENIED' | 'MISSING' | 'UNAVAILABLE'

export interface ReportingBucketCheck {
  status: ReportingBucketStatus
  /** Uložit hodnotu má smysl i tehdy, když export v bucketu zatím není. */
  worthSaving: boolean
  message: string
}

export interface SlackConnection {
  credentialId: string
  workspace: string
  botUserId: string | null
  scopes: string | null
  missingScopes: string[]
}

export type OverallSentiment = 'POSITIVE' | 'NEGATIVE' | 'MIXED' | 'NEUTRAL'
export type TopicSentiment = 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL'
export type ReviewType = 'BUG' | 'FEATURE_REQUEST' | 'COMPLAINT' | 'PRAISE' | 'QUESTION' | 'OTHER'
export type Urgency = 'LOW' | 'MEDIUM' | 'HIGH'

export interface TopicMention {
  key: string
  /** Český název tématu; u smazaného vlastního tématu zůstane klíč. */
  name: string
  sentiment: TopicSentiment
  /** Doslovný úryvek recenze, ověřený serverem. `null`, když se ověřit nedal. */
  quote: string | null
}

/** Výklad recenze (F8). Chybí, dokud ho AI nespočítá — nebo když AI není nastavená. */
export interface ReviewInsight {
  sentiment: OverallSentiment
  type: ReviewType
  urgency: Urgency
  language: string | null
  translation: string | null
  topics: TopicMention[]
}

/** Téma pro výběr ve filtru a pro nastavení aplikace. */
export interface TopicOption {
  key: string
  name: string
  /** Skupina pro seskupení ve výběru; u vlastních témat `null`. */
  group: string | null
  description: string
  custom: boolean
  enabled: boolean
  recentCount: number
}

export interface AnalysisStatus {
  analyzed: number
  missing: number
  taxonomyVersion: string
  queued: boolean
}

/** Jak se téma vyvíjí proti minulému období — podle toho se barví štítek v tabulce. */
export type TopicStatus = 'NEW' | 'GROWING' | 'STABLE' | 'FALLING'

export interface SentimentShare {
  positive: number
  neutral: number
  negative: number
}

export interface SentimentWeek {
  weekStart: string
  positive: number
  neutral: number
  negative: number
  reviews: number
  avgStars: number | null
}

export interface TopicBreakdown {
  key: string
  name: string
  count: number
  previousCount: number
  share: number
  negativeShare: number
  avgStars: number | null
  status: TopicStatus
  /** Osm bodů pro sparkline; poslední je nejnovější úsek období. */
  trend: number[]
}

export interface ImprovedTopic {
  key: string
  name: string
  before: number
  after: number
}

export interface TerritoryBreakdown {
  territory: string
  reviews: number
  negativeShare: number
  avgStars: number | null
}

export interface LanguageBreakdown {
  language: string
  reviews: number
  negativeShare: number
}

export interface RepliesBreakdown {
  total: number
  replied: number
  share: number
  medianHours: number | null
  /** Kolik recenzí po odpovědi přidalo, resp. ubralo hvězdy. */
  uplifted: number
  dropped: number
}

export interface AnalysisOverview {
  periodStart: string
  periodEnd: string
  dataSince: string | null
  reviews: number
  byPlatform: Record<string, number>
  avgStars: number | null
  sentiment: SentimentShare
  previousSentiment: SentimentShare | null
  weekly: SentimentWeek[]
  topics: TopicBreakdown[]
  improved: ImprovedTopic[]
  territories: TerritoryBreakdown[]
  languages: LanguageBreakdown[]
  replies: RepliesBreakdown
  analyzed: number
  missing: number
  minReviews: number
  tooFewReviews: boolean
}

export interface VersionTopic {
  key: string
  name: string
  count: number
  share: number
}

export interface VersionSlice {
  reviews: number
  avgStars: number | null
  negativeShare: number
  topics: VersionTopic[]
}

/** Co udělalo vydání verze. „Před" je 30 dní před jejím prvním výskytem na téže platformě. */
export interface VersionImpact {
  version: string
  platform: Platform
  firstSeen: string
  after: VersionSlice
  before: VersionSlice
  newTopics: VersionTopic[]
  goneTopics: VersionTopic[]
  starsDelta: number | null
  negativeDelta: number
}

export type AlertKind = 'NEGATIVE_SPIKE' | 'TOPIC_SPIKE'

/** Výkyv v recenzích. `expected` je baseline — bez ní se alert nedá číst. */
export interface AnalysisAlert {
  id: string
  kind: AlertKind
  topicKey: string | null
  topicName: string | null
  windowDate: string
  observed: number
  expected: number
  zScore: number
  createdAt: string
}

/** Výsledek ručního „Poslat rozbor teď". */
export interface AnalysisRunResult {
  skipped: string | null
  reviews: number
  sent: number
  alreadySent: number
  errors: string[]
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
  insight?: ReviewInsight | null
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
