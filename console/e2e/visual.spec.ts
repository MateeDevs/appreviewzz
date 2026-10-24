import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { fileURLToPath } from 'node:url'
import { mockApi } from './fixtures'

const artifact = (name: string) =>
  fileURLToPath(new URL(`../../docs/design/aaa/after/${name}`, import.meta.url))
const views = [
  ['overview', '/studio'],
  ['inbox', '/studio/recenze?app=app-1&review=review-1'],
  ['analysis', '/studio/rozbory?app=app-1'],
] as const
for (const theme of ['light', 'dark'] as const) {
  test(`hlavní plochy: kontrast, přístupnost a responzivita · ${theme}`, async ({ page }, testInfo) => {
    test.setTimeout(120000)
    await mockApi(page)
    await page.addInitScript((value) => localStorage.setItem('arz-theme', value), theme)
    const faults: string[] = []
    page.on('pageerror', (error) => faults.push(error.message))
    for (const width of [1440, 1280, 1024, 768, 390, 360]) {
      await page.setViewportSize({ width, height: width < 768 ? 844 : 1000 })
      for (const [name, path] of views) {
        await page.goto(path)
        await page.getByRole('heading', { level: 1 }).waitFor()
        if (name === 'inbox') await page.getByRole('textbox', { name: 'Koncept odpovědi' }).waitFor()
        if (name === 'analysis') await page.getByRole('heading', { name: 'Nálada v čase' }).waitFor()
        await page.evaluate(() => document.fonts.ready)
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          `${name} / ${width} / ${theme}`,
        ).toBe(true)
        if (process.env.CAPTURE_MATRIX) {
          await page.evaluate(() => { window.scrollTo(0, 0); (document.activeElement as HTMLElement)?.blur() })
          await page.waitForTimeout(80)
          await page.screenshot({ path: artifact(`${name}-${width}-${theme}.png`), fullPage: true })
        }
        if (width === 1440 || width === 390) {
          const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
          await testInfo.attach(`${name}-${width}-${theme}-axe`, {
            body: JSON.stringify(results.violations, null, 2),
            contentType: 'application/json',
          })
          expect(
            results.violations.map((item) => ({ id: item.id, nodes: item.nodes.map((n) => n.target) })),
          ).toEqual([])
        }
      }
    }
    expect(faults).toEqual([])
  })
}

test('vedlejší plochy, dialog, chyby a nulové stavy', async ({ page }) => {
  await mockApi(page)
  for (const path of [
    '/studio/aplikace',
    '/studio/aplikace/app-1',
    '/studio/onboarding',
    '/studio/organizace',
    '/studio/audit',
  ]) {
    await page.goto(path)
    await page.getByRole('heading', { level: 1 }).waitFor()
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    expect(results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }))).toEqual([])
    if (process.env.CAPTURE_MATRIX)
      await page.screenshot({
        path: artifact(`secondary-${path.split('/').pop()}-1440-light.png`),
        fullPage: true,
      })
  }
  await page.getByRole('button', { name: 'Účet a odhlášení' }).click()
  await page.getByRole('menuitem', { name: 'Zabezpečení účtu' }).click()
  await expect(page.getByRole('dialog', { name: 'Zabezpečení účtu' })).toBeVisible()
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  expect(results.violations).toEqual([])
  await page.keyboard.press('Tab')
  expect(await page.evaluate(() => Boolean(document.activeElement?.closest('dialog')))).toBeTruthy()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('nulová organizace má jasný další krok a bez overflow na mobilu', async ({ page }) => {
  await mockApi(page, { empty: true })
  await page.setViewportSize({ width: 360, height: 800 })
  for (const path of [
    '/studio',
    '/studio/aplikace',
    '/studio/recenze',
    '/studio/rozbory',
    '/studio/onboarding',
  ]) {
    await page.goto(path)
    await expect(page.locator('main')).not.toBeEmpty()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})

test('neplatné URL hodnoty nedoputují do API', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.goto('/studio/recenze?app=nesmysl&urgency=foo&sort=foo&type=foo&platform=foo&page=-5')
  await page.getByRole('button', { name: /Petra Nováková/ }).waitFor()
  expect(
    requests
      .filter((r) => r.includes('/reviews'))
      .some((r) => r.includes('foo') || r.includes('nesmysl') || r.includes('offset=-')),
  ).toBe(false)
})

test('velké portfolio, dlouhý text, neúplná AI a chyby', async ({ page }) => {
  await mockApi(page, { appCount: 30 })
  await page.goto('/studio')
  await expect(page.locator('.portfolio-row:not(.header)')).toHaveCount(30)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.route('**/api/orgs/studio/reviews/review-1', async (route) => {
    const { reviews } = await import('./fixtures')
    await route.fulfill({
      json: {
        review: {
          ...reviews[0],
          authorName: 'Velmi dlouhé jméno '.repeat(12),
          body: 'Dlouhý text recenze s diakritikou. '.repeat(100),
          insight: null,
        },
        replies: [],
        messages: [],
      },
    })
  })
  await page.setViewportSize({ width: 360, height: 844 })
  await page.goto('/studio/recenze?app=app-1&review=review-1')
  await expect(page.getByText('Recenze zatím nemá AI výklad.', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.route('**/api/orgs/studio/health', (route) =>
    route.fulfill({ status: 503, json: { message: 'Služba je dočasně nedostupná. Zkuste to později.' } }),
  )
  await page.goto('/studio')
  await expect(page.getByRole('alert')).toContainText('Služba je dočasně nedostupná')
})

test('ekvivalent 200% zoomu: pracovní plochy a dosažitelné akce', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 720, height: 500 }, deviceScaleFactor: 2 })
  const page = await context.newPage()
  await mockApi(page)
  for (const [name, path] of views) {
    // CSS px viewport 720 corresponds to 1440 physical px at browser 200% zoom.
    await page.setViewportSize({ width: 720, height: 500 })
    await page.goto(path)
    await page.getByRole('heading', { level: 1 }).waitFor()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const focusable = await page.locator('main button').all()
    for (const button of focusable.slice(0, 5))
      if (await button.isVisible()) {
        await button.focus()
        await expect(button).toBeFocused()
      }
    if (process.env.CAPTURE_MATRIX)
      await page.screenshot({ path: artifact(`zoom-200-${name}.png`), fullPage: true })
  }
  await context.close()
})
