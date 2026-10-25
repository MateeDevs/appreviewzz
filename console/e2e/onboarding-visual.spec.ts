import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { onboardingApi } from './onboarding-fixtures'
import { onboardingSteps } from '../src/api/onboarding'

for (const theme of ['light', 'dark'])
  test(`aktivace, auth a přístupnost: pět šířek · ${theme}`, async ({
    page,
  }) => {
    test.setTimeout(180000)
    const state = await onboardingApi(page)
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.addInitScript(
      (value) => localStorage.setItem('arz-theme', value),
      theme,
    )
    await page.emulateMedia({ reducedMotion: 'reduce' })
    for (const width of [1440, 1024, 768, 390, 360]) {
      await page.setViewportSize({ width, height: 1000 })
      state.signedIn = false
      for (const path of [
        '/login',
        '/registrace',
        '/zapomenute-heslo',
        '/obnova-hesla?token=invalid-test-link',
        '/pozvanka?token=invalid-test-link',
      ]) {
        await page.goto(path)
        await page.locator('.auth .card').waitFor()
        const axe = await new AxeBuilder({ page })
          .withTags(['wcag2a', 'wcag2aa'])
          .analyze()
        expect(
          axe.violations.map((v) => ({
            id: v.id,
            nodes: v.nodes.map((n) => ({ target: n.target, reason: n.failureSummary })),
          })),
        ).toEqual([])
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true)
        if (process.env.CAPTURE_MATRIX && path === '/login')
          await page.screenshot({
            path: `../docs/design/auth-onboarding/after/login-${width}-${theme}.png`,
            fullPage: true,
          })
      }
      state.signedIn = true
      for (const step of onboardingSteps) {
        const savedApps = state.apps
        if (step === 'app') state.apps = []
        await page.goto(`/studio/onboarding?step=${step}`)
        await page.locator('.activation-heading').waitFor()
        await page.evaluate(async () => { await document.fonts.ready; await new Promise(requestAnimationFrame) })
      await page.evaluate(async () => { await document.fonts.ready; await new Promise(requestAnimationFrame) })
        const axe = await new AxeBuilder({ page })
          .withTags(['wcag2a', 'wcag2aa'])
          .analyze()
        expect(
          axe.violations.map((v) => ({
            id: v.id,
            nodes: v.nodes.map((n) => ({ target: n.target, reason: n.failureSummary })),
          })),
        ).toEqual([])
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true)
        if (process.env.CAPTURE_MATRIX)
          await page.screenshot({
            path: `../docs/design/auth-onboarding/after/${step}-${width}-${theme}.png`,
            fullPage: true,
          })
        state.apps = savedApps
      }
    }
    expect(errors).toEqual([])
  })

test('klávesnice, fokus a 200% zvětšení vstupu a průchodu', async ({
  page,
}) => {
  const state = await onboardingApi(page)
  state.signedIn = false
  await page.goto('/login')
  await page.getByLabel('E-mail', { exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('Heslo', { exact: true })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(
    page.getByRole('button', { name: 'Zobrazit heslo' }),
  ).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByLabel('Heslo', { exact: true })).toHaveAttribute(
    'type',
    'text',
  )
  for (const path of [
    '/login',
    '/studio/onboarding',
    '/studio/onboarding?step=preferences',
    '/studio/onboarding?step=sync',
  ]) {
    state.signedIn = path !== '/login'
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(path)
    await page
      .locator(state.signedIn ? '.activation-heading' : '.auth .card')
      .waitFor()
    await page.evaluate(() => {
      document.documentElement.style.zoom = '2'
    })
    expect(
      await page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    ).toBe(true)
    const axe = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze()
    expect(axe.violations).toEqual([])
  }
  await page.evaluate(() => {
    document.documentElement.style.zoom = '1'
  })
  await page.setViewportSize({ width: 360, height: 1000 })
  await page.goto('/studio/onboarding?step=sync')
  await page.locator('.activation-heading').waitFor()
  await expect.poll(() => page.locator('.activation-steps [aria-current="step"]').evaluate((active) => {
    const box = active.getBoundingClientRect()
    const bar = active.closest('ol')!.getBoundingClientRect()
    return box.left >= bar.left && box.right <= bar.right + 1
  })).toBe(true)
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojení dokončím později' }).click()
  await expect(page.locator('.activation-heading')).toBeFocused()
  await page.getByRole('button', { name: '← Zpět' }).click()
  await page
    .getByRole('button', { name: 'Připojit store', exact: true })
    .first()
    .click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: 'Připojit store', exact: true }).first(),
  ).toBeFocused()
})

for (const theme of ['light', 'dark']) test(`čekání, import a první hodnota · ${theme}`, async ({ page }) => {
  test.setTimeout(120000)
  const state = await onboardingApi(page)
  await page.addInitScript((value) => localStorage.setItem('arz-theme', value), theme)
  state.apps[0]!.setup.credentialIds = ['gp-test', 'asc-test']
  state.credentials = [
    { id: 'gp-test', type: 'GP_SERVICE_ACCOUNT', label: 'Google Play', hint: 'service@example.test', origin: 'PROVISIONED', fingerprint: 'synthetic', validationStatus: 'INVALID', validationError: 'Čekáme na propagaci oprávnění v Google Play.', validatedAt: null },
    { id: 'asc-test', type: 'ASC_API_KEY', label: 'App Store', hint: null, origin: 'UPLOADED', fingerprint: 'synthetic', validationStatus: 'VALID', validationError: null, validatedAt: null },
  ]
  for (const width of [1440, 1024, 768, 390, 360]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const scenario of ['waiting', 'running', 'empty', 'failed', 'ready']) {
      state.credentials[0]!.validationStatus = scenario === 'waiting' ? 'INVALID' : 'VALID'
      state.progress.preferencesConfirmed = true
      state.reviewCount = scenario === 'ready' ? 3 : 0
      state.syncs = state.apps[0]!.platforms.map((platform) => ({ platform, status: scenario === 'running' ? 'RUNNING' : scenario === 'failed' ? 'FAILED' : 'SUCCEEDED', startedAt: new Date().toISOString(), finishedAt: null, fetched: scenario === 'ready' ? 3 : scenario === 'empty' ? 0 : null, message: scenario === 'failed' ? 'Store je dočasně nedostupný. Zkuste synchronizaci znovu.' : null }))
      await page.goto(`/studio/onboarding?step=${scenario === 'waiting' ? 'connect' : 'sync'}`)
      await page.locator('.activation-heading').waitFor()
      await page.evaluate(async () => { await document.fonts.ready; await new Promise(requestAnimationFrame) })
      await expect(page.locator('.loading')).toHaveCount(0)
      const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
      expect(axe.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => ({ target: n.target, reason: n.failureSummary })) }))).toEqual([])
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      if (process.env.CAPTURE_MATRIX && [1440, 390].includes(width)) await page.screenshot({ path: `../docs/design/auth-onboarding/after/state-${scenario}-${width}-${theme}.png`, fullPage: true })
    }
  }
})
