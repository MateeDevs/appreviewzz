/** Tvary odpovědí API. Drží se jedna k jedné DTO na serveru — když se rozejdou, spadne build. */

export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER'
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
  organizations: OrganizationSummary[]
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
  ascAppId: string | null
  platforms: Platform[]
  locale: 'CS' | 'EN'
  timezone: string
  notifyFrom: string | null
  aiInstructions: string | null
  ingestIntervalMinutes: number
  dailyDigestAt: string
  enabled: boolean
}

export interface Credential {
  id: string
  type: CredentialType
  label: string
  fingerprint: string
  hint: string | null
  validationStatus: ValidationStatus
  validationError: string | null
  validatedAt: string | null
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
