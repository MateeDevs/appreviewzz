import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { fileURLToPath } from 'node:url'
import { mockApi } from './fixtures'

for (const theme of ['light', 'dark'])
  test(`auth formuláře, popisky a mobil · ${theme}`, async ({ page }) => {
    test.setTimeout(60000)
    await mockApi(page)
    await page.route('**/api/auth/me', (route) =>
      route.fulfill({
        status: 401,
        json: { message: 'Nepřihlášený uživatel.' },
      }),
    )
    await page.addInitScript(
      (value) => localStorage.setItem('arz-theme', value),
      theme,
    )
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 })
      for (const path of [
        '/login',
        '/registrace',
        '/zapomenute-heslo',
        '/obnova-hesla',
        '/pozvanka',
      ]) {
        await page.goto(path)
        await page.locator('.auth .card').waitFor()
        const results = await new AxeBuilder({ page })
          .withTags(['wcag2a', 'wcag2aa'])
          .analyze()
        expect(
          results.violations.map((v) => ({
            id: v.id,
            nodes: v.nodes.map((n) => n.target),
          })),
        ).toEqual([])
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true)
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

test('MFA challenge má samostatný krok a neodesílá heslo znovu', async ({
  page,
}) => {
  await mockApi(page)
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ status: 401, json: {} }),
  )
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 202,
      json: {
        challenge: 'local-mock-challenge',
        expiresAt: '2026-10-24T23:00:00Z',
      },
    }),
  )
  await page.goto('/login')
  await page.getByLabel('E-mail', { exact: true }).fill('example@example.test')
  await page.getByLabel('Heslo', { exact: true }).fill('Dlouhe-testovaci-heslo')
  await page.getByRole('button', { name: 'Přihlásit se', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: 'Kód z autentizační appky' }),
  ).toBeVisible()
  await expect(page.getByLabel('Heslo', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(1)
})

for (const mfa of [false, true])
  test(`přihlášení ${mfa ? 's MFA' : 'heslem'} vrátí přímý odkaz i při pomalém profilu`, async ({
    page,
  }) => {
    await mockApi(page)
    let signedIn = false
    const profile = {
      id: 'user-1',
      email: 'example@example.test',
      displayName: 'Test',
      emailVerified: true,
      mfaEnabled: mfa,
      platformRole: null,
      organizations: [
        { id: 'org-1', slug: 'studio', name: 'Studio', role: 'OWNER' },
      ],
    }
    await page.route('**/api/auth/me', async (route) => {
      if (!signedIn) return route.fulfill({ status: 401, json: {} })
      await new Promise((resolve) => setTimeout(resolve, 600))
      return route.fulfill({ json: profile })
    })
    await page.route('**/api/auth/login', (route) => {
      if (mfa)
        return route.fulfill({
          status: 202,
          json: {
            challenge: 'test-challenge',
            expiresAt: '2026-10-24T23:00:00Z',
          },
        })
      signedIn = true
      return route.fulfill({ json: profile })
    })
    await page.route('**/api/auth/mfa/verify', (route) => {
      signedIn = true
      return route.fulfill({ json: profile })
    })
    await page.goto('/studio/recenze?app=app-1&urgency=HIGH')
    await page
      .getByLabel('E-mail', { exact: true })
      .fill('example@example.test')
    await page
      .getByLabel('Heslo', { exact: true })
      .fill('Dlouhe-testovaci-heslo')
    await page
      .getByRole('button', { name: 'Přihlásit se', exact: true })
      .click()
    if (mfa) {
      await page.getByLabel('Šestimístný kód', { exact: true }).fill('123456')
      await page
        .getByRole('button', { name: 'Pokračovat', exact: true })
        .click()
    }
    await expect(
      page.getByRole('heading', { name: 'Recenze', exact: true }),
    ).toBeVisible()
    await expect(page).toHaveURL('/studio/recenze?app=app-1&urgency=HIGH')
    await expect(
      page.getByRole('button', { name: 'Naléhavost recenze' }),
    ).toContainText('naléhavé')
  })

for (const next of [
  '//example.com',
  '/\\example.com',
  '/login?next=%2Flogin',
  '/registrace',
])
  test(`nebezpečný next se zahodí: ${next}`, async ({ page }) => {
    await mockApi(page)
    await page.goto(`/login?next=${encodeURIComponent(next)}`)
    await expect(page).toHaveURL('/organizace')
  })

test('neplatný reset a ověření mají cestu k novému odkazu, API chyba dovolí opakování', async ({
  page,
}) => {
  await mockApi(page)
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({ status: 401, json: {} }),
  )
  await page.route('**/api/auth/password/reset', (route) =>
    route.fulfill({
      status: 400,
      json: { message: 'Odkaz je neplatný nebo expirovaný.' },
    }),
  )
  await page.goto('/obnova-hesla?token=synthetic-expired')
  await page.getByLabel('Nové heslo').fill('Dlouhe-testovaci-heslo')
  await page
    .getByRole('button', { name: 'Nastavit heslo', exact: true })
    .click()
  await expect(page.getByRole('alert')).toContainText('expirovaný')
  await expect(
    page.getByRole('link', { name: 'Vyžádat nový odkaz' }),
  ).toBeVisible()
  await page.route('**/api/auth/email/verify', (route) =>
    route.fulfill({ status: 400, json: { message: 'Odkaz už nelze použít.' } }),
  )
  await page.goto('/overeni?token=synthetic-expired')
  await expect(page.getByRole('alert')).toContainText('nelze použít')
  await expect(
    page.getByRole('button', { name: 'Zkusit ověření znovu' }),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: 'Přihlaste se', exact: true }),
  ).toBeVisible()
})
