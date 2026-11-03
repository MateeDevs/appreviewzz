import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import type { Channel, Credential } from '../src/api/types'
import { onboardingApi } from './onboarding-fixtures'
import { mockApi } from './fixtures'

const install: Credential = {
  id: 'slack-pilot', type: 'SLACK_INSTALL', label: 'Slack Studio', hint: 'Studio (T1)',
  origin: 'UPLOADED', fingerprint: 'test-only', validationStatus: 'UNKNOWN',
  validationError: null, validatedAt: null,
}
const channel: Channel = {
  id: 'slack-channel', type: 'SLACK', targetRef: 'C_REVIEWS', targetLabel: '#app-reviews',
  credentialId: install.id, locale: 'CS', enabled: true, lastCheckedAt: '2026-11-02T12:00:00Z', lastCheckError: null,
  deliverReviews: true, deliverRatings: true, deliverAnalyses: true,
}

test('pilot: jedno tlačítko otevře OAuth, návrat zachová onboarding a připojení samo ověří kanál', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await onboardingApi(page)
  let connected = false
  let messages = 0
  let returnTo = ''
  await page.route('**/slack/install-url?**', (route) => {
    returnTo = new URL(route.request().url()).searchParams.get('returnTo') ?? ''
    return route.fulfill({ json: { url: '/slack/install?state=pilot-test' } })
  })
  await page.route('**/slack/install?state=pilot-test', (route) => {
    state.credentials.push(install)
    return route.fulfill({ status: 302, headers: { location: `/studio/onboarding?step=extras&slack=connected&slackCredential=${install.id}#kanaly` } })
  })
  await page.route('**/slack/channels?**', (route) => route.fulfill({ json: {
    channels: [{ id: channel.targetRef, name: 'app-reviews' }], nextCursor: null,
  } }))
  await page.route('**/apps/app-1/channels', (route) => route.fulfill({ json: connected ? [channel] : [] }))
  await page.route('**/channels/connect-slack', (route) => {
    expect(route.request().postDataJSON()).toEqual({ credentialId: install.id, targetRef: channel.targetRef, label: '#app-reviews' })
    connected = true
    messages++
    return route.fulfill({ status: 201, json: channel })
  })
  await page.goto('/studio/onboarding?step=extras')
  await page.locator('.activation-optional').first().locator(':scope > summary').click()
  await page.getByRole('button', { name: 'Připojit přes Slack', exact: true }).click()
  await expect(page).toHaveURL(/step=extras&slack=connected/)
  expect(returnTo).toBe('/studio/onboarding?step=extras')
  await expect(page.getByText('Workspace je připojený.', { exact: false })).toBeVisible()
  await expect(page.getByLabel('Bot token workspace')).not.toBeVisible()
  await expect(page.getByLabel('ID kanálu', { exact: true })).toHaveCount(0)
  expect(messages).toBe(0)
  await page.getByRole('button', { name: 'Kanál pro recenze', exact: true }).click()
  await page.getByRole('option', { name: '#app-reviews', exact: true }).click()
  await page.getByRole('button', { name: 'Připojit a ověřit kanál' }).click()
  await expect(page.getByText('zpráva doručena', { exact: true })).toBeVisible()
  await expect(page.getByText('Hotovo — kanál je připojený', { exact: false })).toBeVisible()
  expect(messages).toBe(1)
  await expect(page.getByRole('button', { name: 'Připojit a ověřit kanál' })).toHaveCount(0)
  const accessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  expect(accessibility.violations).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('slack-pilot-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.reload()
  await expect(page.getByText('zpráva doručena', { exact: true })).toBeVisible()
  expect(messages).toBe(1)
})

test('prázdná první stránka kanálů a odmítnutí Slackem dovolí pokračovat a opakovat pokus', async ({ page }) => {
  await mockApi(page)
  await page.route('**/credentials', (route) => route.fulfill({ json: [install] }))
  await page.route('**/slack/channels?**', (route) => route.fulfill({ json:
    new URL(route.request().url()).searchParams.get('cursor')
      ? { channels: [{ id: channel.targetRef, name: 'app-reviews' }], nextCursor: null }
      : { channels: [], nextCursor: 'next=' },
  }))
  let attempts = 0
  await page.route('**/channels/connect-slack', (route) => ++attempts === 1
    ? route.fulfill({ status: 400, json: { message: 'Bot nemá přístup. Přidejte Appreviewzz do kanálu.' } })
    : route.fulfill({ status: 201, json: channel }))
  await page.goto('/studio/aplikace/app-1?tab=klice')
  await page.getByRole('button', { name: 'Načíst další kanály', exact: true }).click()
  await page.getByRole('button', { name: 'Kanál pro recenze', exact: true }).click()
  await page.getByRole('option', { name: '#app-reviews', exact: true }).click()
  await page.getByRole('button', { name: 'Připojit a ověřit kanál' }).click()
  await expect(page.getByRole('alert')).toContainText('Bot nemá přístup')
  await expect(page.getByText('Hotovo — kanál je připojený', { exact: false })).toHaveCount(0)
  await page.getByRole('button', { name: 'Připojit a ověřit kanál' }).click()
  await expect(page.getByText('Hotovo — kanál je připojený', { exact: false })).toBeVisible()
  expect(attempts).toBe(2)
})

test('návrat z OAuth vybere právě připojený workspace a chybu oprávnění vysvětlí', async ({ page }) => {
  await mockApi(page)
  await page.route('**/credentials', (route) => route.fulfill({ json: [{ ...install, id: 'old', hint: 'Starý workspace' }, install] }))
  await page.route('**/slack/channels?**', (route) => {
    expect(new URL(route.request().url()).searchParams.get('credentialId')).toBe(install.id)
    return route.fulfill({ status: 400, json: { message: 'Připojte workspace znovu, appce chybí scope.' } })
  })
  await page.goto(`/studio/aplikace/app-1?tab=klice&slack=connected&slackCredential=${install.id}`)
  await expect(page.getByRole('button', { name: 'Slack workspace', exact: true })).toContainText('Studio (T1)')
  await expect(page.getByRole('alert')).toContainText('Připojte workspace znovu')
  await expect(page.getByRole('button', { name: 'Připojit jiný workspace / obnovit oprávnění' })).toBeVisible()
})

test('zrušená instalace se vrátí do konzole a nabídne nový pokus', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/aplikace/app-1?tab=klice&slack=cancelled')
  await expect(page.getByText('Připojení jste zrušili.', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Připojit přes Slack', exact: true })).toBeEnabled()
})
