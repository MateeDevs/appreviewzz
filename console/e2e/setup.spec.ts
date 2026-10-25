import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mockApi } from './fixtures'

const gpKey = {
  id: 'test-key',
  type: 'GP_SERVICE_ACCOUNT',
  label: 'Testovací klíč',
  fingerprint: 'test-only',
  hint: 'test@example.test',
  origin: 'PROVISIONED',
  validationStatus: 'UNKNOWN',
  validationError: null,
  validatedAt: null,
}

test('Google Play: provisionovaný klíč, čekání na oprávnění, dostupný odchod', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.route('**/api/orgs/studio/credentials/provision-gp', (route) => route.fulfill({ json: gpKey }))
  await page.route('**/api/orgs/studio/apps/app-1/credentials/test-key/validate', (route) =>
    route.fulfill({ json: { valid: false, message: 'Čekáme na udělení oprávnění ve storu.' } }),
  )
  await page.goto('/studio/onboarding?step=connect')
  await page.getByRole('button', { name: 'Upravit připojení', exact: true }).first().click()
  await page.getByRole('button', { name: /Jen recenze/ }).click()
  await expect(page.getByText('test@example.test', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Pozvánku jsem odeslal', exact: true }).click()
  await expect(page.getByText('Čekáme na udělení oprávnění ve storu.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Zavřít', exact: true }).last().click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(requests.some((url) => url.includes('channels/test'))).toBe(false)
})

test('App Store: nápověda, validace chybného souboru a popisky', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: /klíč k App Storu/ }).click()
  await page.getByRole('button', { name: 'Klíč mám, pokračovat', exact: true }).click()
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'wrong.p8', mimeType: 'text/plain', buffer: Buffer.from('{"wrong":true}') })
  await expect(page.locator('.error')).toContainText(/Google|JSON|klíč/i)
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  expect(results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }))).toEqual([])
})

test('member vidí obsah týmu, nemá správu rolí ani pozvánky', async ({ page }) => {
  await mockApi(page, { role: 'MEMBER' })
  await page.goto('/studio/organizace')
  await expect(page.getByRole('heading', { name: 'Členové', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Pozvat', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Role uživatele/ })).toHaveCount(0)
})
