import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { Platform } from './types'

export const onboardingSteps = [
  'app',
  'connect',
  'preferences',
  'extras',
  'sync',
] as const
export type OnboardingStep = (typeof onboardingSteps)[number]
export interface OnboardingProgress {
  appId: string | null
  step: OnboardingStep
  preferencesConfirmed: boolean
  completed: boolean
  started: boolean
}
export interface OnboardingSync {
  platform: Platform
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED'
  startedAt: string
  finishedAt: string | null
  fetched: number | null
  message: string | null
}
export function useOnboarding(org: string) {
  return useQuery({
    queryKey: ['onboarding', org],
    queryFn: () => api.get<OnboardingProgress>(`/api/orgs/${org}/onboarding`),
    enabled: !!org,
  })
}
export function useSaveOnboarding(org: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: OnboardingProgress) =>
      api.put<OnboardingProgress>(`/api/orgs/${org}/onboarding`, input),
    onSuccess: (data) => client.setQueryData(['onboarding', org], data),
  })
}
export function useOnboardingSync(org: string, appId: string) {
  return useQuery({
    queryKey: ['onboarding-sync', org, appId],
    queryFn: () =>
      api.get<OnboardingSync[]>(`/api/orgs/${org}/onboarding/${appId}/sync`),
    enabled: !!appId,
    refetchInterval: (query) =>
      query.state.data?.some((s) => s.status === 'RUNNING') ? 2000 : false,
  })
}
export function useRunOnboardingSync(org: string, appId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () =>
      api.post<OnboardingSync[]>(`/api/orgs/${org}/onboarding/${appId}/sync`),
    onSuccess: (data) =>
      client.setQueryData(['onboarding-sync', org, appId], data),
    onSettled: () => client.invalidateQueries(),
  })
}
