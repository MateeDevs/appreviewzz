import { test, expect } from '@playwright/test'
import { mockApi, reviews } from './fixtures'
import type { Reply } from '../src/api/types'

test('testovací odpověď je před odesláním i v historii jasně odlišená od publikace', async ({ page }) => {
  await mockApi(page)
  let stored: Reply[] = []
  await page.route('**/reviews/review-1', (route) => route.fulfill({ json: {
    review: reviews[0], messages: [], revisions: [], replies: stored, replySimulationEnabled: true,
  } }))
  await page.route('**/reviews/review-1/reply', (route) => {
    expect(route.request().postDataJSON().simulateOnly).toBe(true)
    stored = [{
      id: 'test-reply', body: route.request().postDataJSON().body, source: 'CONSOLE',
      status: 'PUBLISHED', simulated: true, publishedAt: null, createdAt: '2026-11-03T12:00:00Z',
      error: null, authorDisplayName: 'Designový tým',
    }]
    return route.fulfill({ status: 202, json: { queued: true, message: 'Testovací odpověď je ve frontě.' } })
  })
  await page.goto('/studio/recenze?app=app-1&review=review-1')
  await expect(page.getByText('Testovací režim:', { exact: false })).toBeVisible()
  await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Odpověď pouze pro test.')
  await page.getByRole('button', { name: 'Uložit testovací odpověď', exact: true }).click()
  await expect(page.getByText('test – do storu neodesláno', { exact: true })).toBeVisible()
  await expect(page.getByText('publikováno', { exact: true })).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('test – do storu neodesláno', { exact: true })).toBeVisible()
})

test('správce uloží stagingový přepínač a po obnovení uvidí platnou hodnotu', async ({ page }) => {
  await mockApi(page)
  let value = 'true'
  const setting = () => [{
    key: 'replies.simulation_enabled', type: 'BOOL', section: 'Testování odpovědí',
    label: 'Odpovědi ukládat pouze u nás', help: 'Pouze staging. Do storů se nic neodesílá.',
    value, source: 'DB', default: 'true', envName: null, options: [], min: null, max: null,
  }]
  await page.route('**/api/platform/settings', (route) => {
    if (route.request().method() === 'PUT') {
      expect(route.request().postDataJSON()).toEqual({ values: { 'replies.simulation_enabled': 'false' } })
      value = 'false'
    }
    return route.fulfill({ json: setting() })
  })
  await page.route('**/api/platform/overview', (route) => route.fulfill({ json: {
    organizations: 1, users: 1, apps: 1, enabledApps: 1, appsWithIntervalOverride: 0,
    defaultIntervalMinutes: 30, minIntervalMinutes: 15, failedJobs: 0,
  } }))
  await page.goto('/studio')
  await page.getByRole('button', { name: 'Účet a odhlášení' }).click()
  await page.getByRole('menuitem', { name: 'Správa platformy' }).click()
  const dialog = page.getByRole('dialog', { name: 'Správa platformy' })
  await dialog.getByRole('button', { name: 'Odpovědi ukládat pouze u nás' }).click()
  await page.getByRole('option', { name: 'ne', exact: true }).click()
  await dialog.getByRole('button', { name: 'Uložit', exact: true }).click()
  await expect(dialog.getByRole('button', { name: 'Uložit', exact: true })).toBeDisabled()
  await page.reload()
  await page.getByRole('button', { name: 'Účet a odhlášení' }).click()
  await page.getByRole('menuitem', { name: 'Správa platformy' }).click()
  await expect(page.getByRole('button', { name: 'Odpovědi ukládat pouze u nás' })).toContainText('ne')
})
