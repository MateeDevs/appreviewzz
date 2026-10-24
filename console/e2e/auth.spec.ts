import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { fileURLToPath } from 'node:url'
import { mockApi } from './fixtures'

for (const theme of ['light', 'dark'])
  test(`auth formuláře, popisky a mobil · ${theme}`, async ({ page }) => {
    test.setTimeout(60000)
    await mockApi(page)
    await page.route('**/api/auth/me', (route) =>
      route.fulfill({ status: 401, json: { message: 'Nepřihlášený uživatel.' } }),
    )
    await page.addInitScript((value) => localStorage.setItem('arz-theme', value), theme)
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 })
      for (const path of ['/login', '/registrace', '/zapomenute-heslo', '/obnova-hesla', '/pozvanka']) {
        await page.goto(path)
        await page.locator('.auth .card').waitFor()
        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
        expect(results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }))).toEqual([])
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
        if (process.env.CAPTURE_MATRIX)
          await page.screenshot({
            path: fileURLToPath(
              new URL(
                `../../docs/design/aaa/after/auth-${path.slice(1)}-${width}-${theme}.png`,
                import.meta.url,
              ),
            ),
            fullPage: true,
          })
      }
    }
  })

test('MFA challenge má samostatný krok a neodesílá heslo znovu', async ({ page }) => {
  await mockApi(page)
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 401, json: {} }))
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 202,
      json: { challenge: 'local-mock-challenge', expiresAt: '2026-10-24T23:00:00Z' },
    }),
  )
  await page.goto('/login')
  await page.getByLabel('E-mail', { exact: true }).fill('example@example.test')
  await page.getByLabel('Heslo', { exact: true }).fill('Dlouhe-testovaci-heslo')
  await page.getByRole('button', { name: 'Přihlásit se', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Kód z autentizační appky' })).toBeVisible()
  await expect(page.getByLabel('Heslo', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(1)
})
