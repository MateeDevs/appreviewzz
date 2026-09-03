import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { ApiError, api } from './client'
import type {
  App,
  AuditEntry,
  Channel,
  ChannelCheck,
  Credential,
  Health,
  Invitation,
  LoginOutcome,
  Me,
  Member,
  MfaStatus,
  OrganizationSummary,
  PlatformApp,
  PlatformAuditEntry,
  PlatformOverview,
  PlatformSecret,
  PlatformSetting,
  RatingsRunResult,
  RatingsSeries,
  Review,
  ReviewDetail,
  ReviewState,
  StoreApp,
  StoreResolution,
  TotpSetup,
} from './types'

/**
 * Přihlášený uživatel. 401 není chyba, ale odpověď „nikdo" — jinak by se na každé
 * načtení odhlášené stránky vypsala červená hláška.
 */
export function useMe(): UseQueryResult<Me | null> {
  return useQuery({
    queryKey: ['me'],
    retry: false,
    queryFn: async () => {
      try {
        return await api.get<Me>('/api/auth/me')
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
  })
}

/**
 * Po přihlášení i odhlášení se zahazuje celá cache — jiný uživatel, jiná data.
 *
 * Se zapnutým druhým faktorem tohle přihlášení nedokončí: server vrátí challenge a zbytek
 * obstará [useVerifySecondFactor].
 */
export function useLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { email: string; password: string }) =>
      api.post<LoginOutcome>('/api/auth/login', input),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useVerifySecondFactor() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { challenge: string; code: string }) => api.post<Me>('/api/auth/mfa/verify', input),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useMfaStatus() {
  return useQuery({ queryKey: ['mfa'], queryFn: () => api.get<MfaStatus>('/api/auth/totp') })
}

export function useStartTotp() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<TotpSetup>('/api/auth/totp/setup'),
    onSuccess: () => client.invalidateQueries({ queryKey: ['mfa'] }),
  })
}

export function useConfirmTotp() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<{ codes: string[] }>('/api/auth/totp/confirm', { code }),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useRegenerateRecoveryCodes() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<{ codes: string[] }>('/api/auth/totp/recovery-codes', { code }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['mfa'] }),
  })
}

export function useDisableTotp() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { password: string; code: string }) => api.post<void>('/api/auth/totp/disable', input),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useRegister() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { email: string; password: string; displayName?: string }) =>
      api.post<Me>('/api/auth/register', input),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/api/auth/logout'),
    onSuccess: () => client.clear(),
  })
}

export function useVerifyEmail() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (token: string) => api.post<void>('/api/auth/email/verify', { token }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useResendVerification() {
  return useMutation({ mutationFn: () => api.post<void>('/api/auth/email/resend') })
}

export function useForgotPassword() {
  return useMutation({ mutationFn: (email: string) => api.post<void>('/api/auth/password/forgot', { email }) })
}

export function useResetPassword() {
  return useMutation({
    mutationFn: (input: { token: string; password: string }) => api.post<void>('/api/auth/password/reset', input),
  })
}

export function useOrganizations() {
  return useQuery({ queryKey: ['orgs'], queryFn: () => api.get<OrganizationSummary[]>('/api/orgs') })
}

export function useCreateOrganization() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string }) => api.post<OrganizationSummary>('/api/orgs', input),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useAcceptInvitation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (token: string) => api.post<OrganizationSummary>('/api/invitations/accept', { token }),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useMembers(org: string) {
  return useQuery({ queryKey: ['members', org], queryFn: () => api.get<Member[]>(`/api/orgs/${org}/members`) })
}

export function useInvitations(org: string) {
  return useQuery({
    queryKey: ['invitations', org],
    queryFn: () => api.get<Invitation[]>(`/api/orgs/${org}/invitations`),
  })
}

export function useInvite(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { email: string; role: string }) =>
      api.post<Invitation>(`/api/orgs/${org}/invitations`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['invitations', org] }),
  })
}

export function useRevokeInvitation(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/orgs/${org}/invitations/${id}`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['invitations', org] }),
  })
}

export function useChangeRole(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { userId: string; role: string }) =>
      api.patch<void>(`/api/orgs/${org}/members/${input.userId}`, { role: input.role }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['members', org] }),
  })
}

export function useRemoveMember(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (userId: string) => api.delete<void>(`/api/orgs/${org}/members/${userId}`),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useApps(org: string) {
  return useQuery({ queryKey: ['apps', org], queryFn: () => api.get<App[]>(`/api/orgs/${org}/apps`) })
}

export function useCreateApp(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: Record<string, unknown>) => api.post<App>(`/api/orgs/${org}/apps`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['apps', org] }),
  })
}

/**
 * Vyčtení appky z odkazů na store. Volá se z dialogu při psaní, takže se výsledek
 * nekešuje — odkaz se mezi dvěma pokusy mění.
 */
export function useResolveStoreLinks(org: string) {
  return useMutation({
    mutationFn: (input: { googlePlayUrl?: string; appStoreUrl?: string }) =>
      api.post<StoreResolution>(`/api/orgs/${org}/apps/resolve`, input),
  })
}

export function useUpdateApp(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; body: Record<string, unknown> }) =>
      api.patch<App>(`/api/orgs/${org}/apps/${input.id}`, input.body),
    onSuccess: () => client.invalidateQueries({ queryKey: ['apps', org] }),
  })
}

export function useCredentials(org: string) {
  return useQuery({
    queryKey: ['credentials', org],
    queryFn: () => api.get<Credential[]>(`/api/orgs/${org}/credentials`),
  })
}

export function useAddCredential(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { type: string; label: string; content: string; keyId?: string; issuerId?: string }) =>
      api.post<Credential>(`/api/orgs/${org}/credentials`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['credentials', org] }),
  })
}

export function useAttachCredential(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { appId: string; credentialId: string; purpose?: string }) =>
      api.post<void>(`/api/orgs/${org}/apps/${input.appId}/credentials`, {
        credentialId: input.credentialId,
        purpose: input.purpose ?? 'REVIEWS',
      }),
    onSuccess: () => client.invalidateQueries(),
  })
}

/**
 * Smazání klíče. Zneplatní se i na straně storu, takže po něm appka spadne zpátky do
 * „čeká na nastavení" — proto se invaliduje všechno, ne jen seznam klíčů.
 */
export function useDeleteCredential(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (credentialId: string) => api.delete<void>(`/api/orgs/${org}/credentials/${credentialId}`),
    onSuccess: () => client.invalidateQueries(),
  })
}

/** Service account pro Google Play vyrobený námi. Idempotentní — druhé volání vrátí týž účet. */
export function useProvisionGooglePlay(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<Credential>(`/api/orgs/${org}/credentials/provision-gp`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['credentials', org] }),
  })
}

/**
 * Aplikace, na které klíč dosáhne. Volá se až po nahrání klíče — a je to zároveň jeho
 * ověření, takže po úspěchu je potřeba přenačíst i seznam klíčů.
 */
export function useStoreApps(org: string, credentialId: string) {
  return useQuery({
    queryKey: ['store-apps', org, credentialId],
    queryFn: () => api.get<StoreApp[]>(`/api/orgs/${org}/credentials/${credentialId}/store-apps`),
    enabled: credentialId !== '',
    retry: false,
  })
}

export function useValidateCredential(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { appId: string; credentialId: string }) =>
      api.post<{ valid: boolean; message: string | null }>(
        `/api/orgs/${org}/apps/${input.appId}/credentials/${input.credentialId}/validate`,
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: ['credentials', org] }),
  })
}

export function useChannels(org: string, appId: string) {
  return useQuery({
    queryKey: ['channels', org, appId],
    queryFn: () => api.get<Channel[]>(`/api/orgs/${org}/apps/${appId}/channels`),
    enabled: appId !== '',
  })
}

export function useCreateChannel(org: string, appId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { targetRef: string; credentialId: string; label?: string; locale?: string }) =>
      api.post<Channel>(`/api/orgs/${org}/apps/${appId}/channels`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['channels', org, appId] }),
  })
}

export function useDeleteChannel(org: string, appId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/orgs/${org}/apps/${appId}/channels/${id}`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['channels', org, appId] }),
  })
}

export function useTestChannels(org: string, appId: string) {
  return useMutation({
    mutationFn: (channelId?: string) =>
      api.post<ChannelCheck[]>(`/api/orgs/${org}/apps/${appId}/channels/test`, { channelId: channelId ?? null }),
  })
}

export function useConnectSlack(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { token: string; label?: string }) => api.post(`/api/orgs/${org}/slack/connect`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['credentials', org] }),
  })
}

export function useReviews(org: string, appId: string, states: ReviewState[]) {
  const filter = states.length > 0 ? `?state=${states.join(',')}` : ''
  return useQuery({
    queryKey: ['reviews', org, appId, filter],
    queryFn: () => api.get<Review[]>(`/api/orgs/${org}/apps/${appId}/reviews${filter}`),
    enabled: appId !== '',
  })
}

export function useReview(org: string, reviewId: string) {
  return useQuery({
    queryKey: ['review', org, reviewId],
    queryFn: () => api.get<ReviewDetail>(`/api/orgs/${org}/reviews/${reviewId}`),
    enabled: reviewId !== '',
  })
}

export function useReply(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { reviewId: string; body: string }) =>
      api.post<{ queued: boolean; message: string }>(`/api/orgs/${org}/reviews/${input.reviewId}/reply`, {
        body: input.body,
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['review', org] }),
  })
}

export function useSetReviewState(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { reviewId: string; state: ReviewState }) =>
      api.patch<Review>(`/api/orgs/${org}/reviews/${input.reviewId}`, { state: input.state }),
    onSuccess: () => client.invalidateQueries(),
  })
}

export function useHealth(org: string) {
  return useQuery({ queryKey: ['health', org], queryFn: () => api.get<Health>(`/api/orgs/${org}/health`) })
}

export function useAudit(org: string) {
  return useQuery({ queryKey: ['audit', org], queryFn: () => api.get<AuditEntry[]>(`/api/orgs/${org}/audit`) })
}

/** Vývoj hodnocení pro graf. Prázdná řada je legitimní stav — appka může být čerstvá. */
export function useRatings(org: string, appId: string, days = 30) {
  return useQuery({
    queryKey: ['ratings', org, appId, days],
    queryFn: () => api.get<RatingsSeries[]>(`/api/orgs/${org}/apps/${appId}/ratings?days=${days}`),
    enabled: appId !== '',
  })
}

/**
 * Ruční spuštění přehledu. Při onboardingu je potřeba vidět, že chodí a jak vypadá,
 * ne čekat do zítřejších 8:30.
 */
export function useRunRatings(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (appId: string) => api.post<RatingsRunResult>(`/api/orgs/${org}/apps/${appId}/ratings/run`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['ratings', org] }),
  })
}

// ---------------------------------------------------------------- správa platformy (F7)

/**
 * Sekce existuje jen pro superadmina se zapnutým druhým faktorem — server na ni jinak
 * odpoví `404`, resp. `403`. Hooky se proto volají až pod routou, která roli ověřila.
 */
export function usePlatformSettings() {
  return useQuery({
    queryKey: ['platform', 'settings'],
    queryFn: () => api.get<PlatformSetting[]>('/api/platform/settings'),
  })
}

/**
 * Uložení změn. `null` u klíče znamená zrušit uložené a vrátit se k prostředí, resp.
 * k výchozí hodnotě — proto `string | null`, ne prázdný řetězec.
 */
export function useUpdatePlatformSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (values: Record<string, string | null>) =>
      api.put<PlatformSetting[]>('/api/platform/settings', { values }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform'] }),
  })
}

export function usePlatformSecrets() {
  return useQuery({
    queryKey: ['platform', 'secrets'],
    queryFn: () => api.get<PlatformSecret[]>('/api/platform/secrets'),
  })
}

export function useSetPlatformSecret() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { key: string; value: string }) =>
      api.put<void>(`/api/platform/secrets/${encodeURIComponent(input.key)}`, { value: input.value }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform'] }),
  })
}

export function useRemovePlatformSecret() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (key: string) => api.delete<void>(`/api/platform/secrets/${encodeURIComponent(key)}`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform'] }),
  })
}

export function usePlatformOverview() {
  return useQuery({
    queryKey: ['platform', 'overview'],
    queryFn: () => api.get<PlatformOverview>('/api/platform/overview'),
  })
}

export function usePlatformAudit() {
  return useQuery({
    queryKey: ['platform', 'audit'],
    queryFn: () => api.get<PlatformAuditEntry[]>('/api/platform/audit'),
  })
}

/** Jen aplikace s udělenou výjimkou — výpis všech appek do platformní sekce nepatří. */
export function usePlatformApps() {
  return useQuery({
    queryKey: ['platform', 'apps'],
    queryFn: () => api.get<PlatformApp[]>('/api/platform/apps'),
  })
}

export function useSetPlatformAppInterval() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { appId: string; minutes: number | null }) =>
      api.patch<PlatformApp>(`/api/platform/apps/${input.appId}`, { minutes: input.minutes }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform'] }),
  })
}
