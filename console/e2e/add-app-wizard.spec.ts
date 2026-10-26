import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { wizardApi, syntheticP8 } from './add-app-fixtures'

const dialog = (page: Page) => page.getByRole('dialog')
const action = (page: Page, name: string) => dialog(page).getByRole('button', { name, exact: true })
async function captureState(page: Page, name: string) {
  if (!process.env.CAPTURE_ADD_WIZARD) return
  const viewport = page.viewportSize()!,
    theme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
  for (const value of ['light', 'dark'])
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 })
      await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), value)
      await page.screenshot({
        path: `../docs/design/add-app-wizard/after/${name}-${width}-${value}.png`,
      })
    }
  await page.setViewportSize(viewport)
  await page.evaluate((t) => {
    if (t) document.documentElement.setAttribute('data-theme', t)
    else document.documentElement.removeAttribute('data-theme')
  }, theme)
}
async function addIdentity(page: Page, both = true) {
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill('https://play.google.com/store/apps/details?id=cz.example.atlas')
  if (both)
    await page
      .getByLabel('Odkaz na App Store', { exact: true })
      .fill('https://apps.apple.com/app/id123456789')
  await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Atlas')
  await action(page, 'Přidat aplikaci').dblclick()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím', exact: true }),
  ).toBeVisible()
}
async function appleUpload(page: Page) {
  await action(page, 'Klíč už mám').click()
  await dialog(page).locator('input[type=file]').setInputFiles(syntheticP8)
  await page.getByLabel('Issuer ID', { exact: true }).fill('69a6de70-0000-47e3-e053-5b8c7c11a4d1')
  await action(page, 'Nahrát klíč').click()
}

test('obě platformy: jeden rámec, dvojklik, serverový refresh, oddělená validace a souhrn', async ({
  page,
}) => {
  const { state, extra } = await wizardApi(page, { empty: true })
  await addIdentity(page)
  expect(state.creates).toBe(1)
  await action(page, 'Pokračovat v připojení').click()
  await dialog(page)
    .getByRole('button', { name: /Jen recenze/ })
    .click()
  await expect(page.getByLabel('Hodnota ke zkopírování')).toHaveValue(
    'synthetic-service@example.test',
  )
  await action(page, 'Ověřit přístup').dblclick()
  await expect(page.getByText('Přístup k aplikaci je ověřený', { exact: true })).toBeVisible()
  await action(page, 'Zobrazit souhrn').click()
  await expect(
    dialog(page).locator('.setup-platform-row .badge').filter({ hasText: 'Přístup ověřený' }),
  ).toHaveCount(1)
  await action(page, 'Pokračovat: App Store Connect').click()
  await appleUpload(page)
  await action(page, 'Sledovat vybrané').dblclick()
  await expect(page.getByRole('heading', { name: 'Přístup k aplikacím je ověřený' })).toBeVisible()
  await action(page, 'Zobrazit souhrn').click()
  await expect(
    dialog(page).locator('.setup-platform-row .badge').filter({ hasText: 'Přístup ověřený' }),
  ).toHaveCount(2)
  await captureState(page, 'result-both')
  expect(extra.provisions).toBe(1)
  expect(extra.uploads).toBe(1)
  expect(state.creates).toBe(1)
  expect(state.progress.appId).toBeNull()
  await page.reload()
  await expect(
    dialog(page).locator('.setup-platform-row .badge').filter({ hasText: 'Přístup ověřený' }),
  ).toHaveCount(2)
  await action(page, 'Dokončit později').click()
  await action(page, 'Hotovo').click()
  await expect(dialog(page)).toHaveCount(0)
  expect(
    await page.evaluate(() => Object.keys(localStorage).filter((k) => !k.includes('theme'))),
  ).toEqual([])
})

test('Google: chyba oprávnění, single flight kontrola, návrat, clipboard fallback a zastavení po odchodu', async ({
  page,
}) => {
  const { extra } = await wizardApi(page, { platforms: ['ANDROID'] })
  extra.kind = 'AUTH'
  extra.validationDelay = 500
  await page.addInitScript(() =>
    Object.defineProperty(navigator, 'clipboard', { value: undefined }),
  )
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await dialog(page)
    .getByRole('button', { name: /Jen recenze/ })
    .click()
  await action(page, 'Kopírovat').click()
  await expect(dialog(page).getByRole('status')).toContainText(
    'Automatické kopírování není dostupné',
  )
  await action(page, 'Ověřit přístup').dblclick()
  await expect(page.getByText('Přístup zatím není potvrzený', { exact: true })).toBeVisible()
  expect(extra.maxValidationConcurrency).toBe(1)
  await action(page, '← Zpět').click()
  const checks = extra.checks
  await page.clock.install()
  await page.clock.fastForward(31000)
  expect(extra.checks).toBe(checks)
  extra.kind = null
  await action(page, 'Ověřit přístup').click()
  await expect(page.getByText('Přístup k aplikaci je ověřený', { exact: true })).toBeVisible()
  await action(page, 'Zobrazit souhrn').click()
  await action(page, 'Zpět do aktivace').click()
  await expect(dialog(page)).toHaveCount(0)
  expect(extra.provisions).toBe(1)
})

for (const status of ['OK', 'NO_EXPORT', 'DENIED', 'MISSING', 'UNAVAILABLE'] as const)
  test(`Google reporting: ${status}, skutečný PATCH s názvem a pravdivý stav`, async ({ page }) => {
    const { state, extra } = await wizardApi(page, { platforms: ['ANDROID'] })
    extra.report = status
    await page.goto('/studio/onboarding')
    await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
    await dialog(page)
      .getByRole('button', { name: /Recenze i hodnocení/ })
      .click()
    await expect(dialog(page).getByText(/globální/)).not.toHaveCount(0)
    await action(page, 'Ověřit přístup').click()
    await expect(page.getByText('Přístup k aplikaci je ověřený', { exact: true })).toBeVisible()
    await action(page, 'Pokračovat k exportům').click()
    await page
      .getByLabel('Cloud Storage URI', { exact: true })
      .fill('gs://pubsite_prod_rev_01234567890123456789')
    await action(page, 'Ověřit a uložit exporty').dblclick()
    if (status === 'OK' || status === 'NO_EXPORT') {
      await expect(dialog(page).getByText('Adresa uložena.', { exact: false })).toBeVisible()
      expect(extra.patches).toHaveLength(1)
      expect(extra.patches[0]!.name).toBe('Atlas')
      expect(state.apps[0]!.gpReportingBucket).toContain('gs://')
    } else {
      await expect(dialog(page).getByRole('status')).toContainText(
        status === 'MISSING'
          ? 'Bucket nebyl nalezen'
          : status === 'DENIED'
            ? 'K exportům zatím nemáme přístup'
            : 'Cloud Storage je dočasně nedostupné',
      )
      expect(extra.patches).toHaveLength(0)
      if (status === 'DENIED') await captureState(page, 'reporting-denied')
      if (status === 'MISSING') await expect(action(page, 'Uložit i tak')).toHaveCount(0)
      else {
        await action(page, 'Uložit i tak').click()
        await expect(dialog(page).getByText('Adresa uložena.', { exact: false })).toBeVisible()
        expect(extra.patches).toHaveLength(1)
      }
    }
  })

test('Apple: soubor a identifikátory, zpět zachová draft, refresh použije uložený klíč', async ({
  page,
}) => {
  const { extra } = await wizardApi(page, { platforms: ['IOS'] })
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await action(page, 'Klíč už mám').click()
  const file = dialog(page).locator('input[type=file]')
  await file.setInputFiles({
    name: 'wrong.p8',
    mimeType: 'text/plain',
    buffer: Buffer.from('{"not":"apple"}'),
  })
  await expect(dialog(page).getByRole('alert')).toContainText('JSON')
  await captureState(page, 'upload-error')
  await file.setInputFiles(syntheticP8)
  await expect(page.getByLabel('Key ID', { exact: true })).toHaveValue('TEST123456')
  await file.setInputFiles({ ...syntheticP8, name: 'renamed.p8' })
  await expect(page.getByLabel('Key ID', { exact: true })).toHaveValue('')
  await file.setInputFiles({ ...syntheticP8, buffer: Buffer.alloc(65 * 1024) })
  await expect(dialog(page).getByRole('alert')).toContainText('64 kB')
  await file.setInputFiles(syntheticP8)
  await page.getByLabel('Issuer ID', { exact: true }).fill('wrong')
  await expect(action(page, 'Nahrát klíč')).toBeDisabled()
  await page.getByLabel('Issuer ID', { exact: true }).fill('69a6de70-0000-47e3-e053-5b8c7c11a4d1')
  await action(page, '← Zpět').click()
  await action(page, 'Klíč už mám').click()
  await expect(page.getByLabel('Key ID', { exact: true })).toHaveValue('TEST123456')
  await action(page, 'Nahrát klíč').click()
  await expect(action(page, 'Sledovat vybrané')).toBeEnabled()
  await action(page, 'Zavřít').click()
  await page.reload()
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await dialog(page)
    .getByRole('button', { name: /App Store · TEST123456/ })
    .click()
  await action(page, 'Sledovat vybrané').click()
  await expect(page.getByRole('heading', { name: 'Přístup k aplikacím je ověřený' })).toBeVisible()
  expect(extra.uploads).toBe(1)
})

test('Apple více aplikací: částečný úspěch a retry pouze neúspěšné bez nového uploadu', async ({
  page,
}) => {
  const { state, extra } = await wizardApi(page, { empty: true, platforms: ['IOS'] })
  extra.failApps.add('987654321')
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page
    .getByLabel('Odkaz na App Store', { exact: true })
    .fill('https://apps.apple.com/app/id123456789')
  await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Atlas')
  await action(page, 'Přidat aplikaci').click()
  await action(page, 'Pokračovat v připojení').click()
  await appleUpload(page)
  await dialog(page).getByRole('checkbox', { name: /Orbit/ }).check()
  await action(page, 'Sledovat vybrané').dblclick()
  await expect(
    page.getByRole('heading', { name: 'Část připojení vyžaduje kontrolu' }),
  ).toBeVisible()
  await captureState(page, 'apple-partial')
  expect(state.creates).toBe(2)
  expect(extra.checks).toBe(2)
  extra.failApps.clear()
  await action(page, 'Zkusit znovu neúspěšné').click()
  await expect(page.getByRole('heading', { name: 'Přístup k aplikacím je ověřený' })).toBeVisible()
  expect(state.creates).toBe(2)
  expect(extra.checks).toBe(3)
  expect(extra.uploads).toBe(1)
})

test('identity: stará odpověď, ruční název, rozdílné názvy, částečná kolize a konkurence', async ({
  page,
}) => {
  const { state } = await wizardApi(page, { empty: true })
  let finish: (() => void) | undefined
  await page.route('**/apps/resolve', async (route) => {
    const old = route.request().postDataJSON().googlePlayUrl.includes('old')
    if (old)
      await new Promise<void>((r) => {
        finish = r
      })
    return route.fulfill({
      json: {
        googlePlay: {
          platform: 'ANDROID',
          identifier: old ? 'cz.old.app' : 'cz.new.app',
          name: old ? 'Starý název' : 'Nový název',
          error: null,
        },
        appStore: route.request().postDataJSON().appStoreUrl
          ? { platform: 'IOS', identifier: '123456789', name: 'Jiný název', error: null }
          : null,
      },
    })
  })
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill('https://play.google.com/store/apps/details?id=old')
  await expect.poll(() => !!finish).toBe(true)
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill('https://play.google.com/store/apps/details?id=new')
  await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Nový název')
  await page.getByLabel('Název', { exact: true }).fill('Vlastní název')
  finish!()
  await page
    .getByLabel('Odkaz na App Store', { exact: true })
    .fill('https://apps.apple.com/app/id123456789')
  await expect(dialog(page).getByRole('checkbox', { name: /Potvrzuji/ })).toBeVisible()
  await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Vlastní název')
  await expect(action(page, 'Přidat aplikaci')).toBeDisabled()
  await dialog(page)
    .getByRole('checkbox', { name: /Potvrzuji/ })
    .check()
  await dialog(page)
    .getByRole('radio', { name: /Sledovat konkurenci/ })
    .check()
  await action(page, 'Přidat konkurenci').click()
  await expect(page.getByRole('heading', { name: 'Konkurence je přidaná' })).toBeVisible()
  expect(state.apps[0]!.competitor).toBe(true)
  await expect(
    dialog(page).getByRole('button', { name: 'Připojit store', exact: true }),
  ).toHaveCount(0)
})

test('neuložená práce: backdrop, Escape, návrat do formuláře a potvrzené zavření s návratem fokusu', async ({
  page,
}) => {
  await wizardApi(page, { empty: true })
  await page.goto('/studio/aplikace')
  const trigger = page.getByRole('button', { name: '+ Přidat aplikaci', exact: true })
  await trigger.click()
  await page.getByLabel('Název', { exact: true }).fill('Rozpracovaná')
  await page.mouse.click(1, 1)
  await expect(dialog(page)).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('heading', { name: 'Opustit rozpracované nastavení?' })).toBeVisible()
  await action(page, 'Pokračovat v nastavení').click()
  await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Rozpracovaná')
  await action(page, 'Zavřít').click()
  await action(page, 'Zahodit neuložené údaje a zavřít').click()
  await expect(dialog(page)).toHaveCount(0)
  await expect(trigger).toBeFocused()
})

for (const theme of ['light', 'dark'])
  test(`vizuální a axe matice všech šířek: ${theme}`, async ({ page }) => {
    test.setTimeout(180000)
    await page.addInitScript((value) => localStorage.setItem('arz-theme', value), theme)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    const { state } = await wizardApi(page)
    for (const width of [1440, 1024, 768, 390, 360]) {
      await page.setViewportSize({ width, height: width === 1024 ? 620 : 900 })
      for (const step of ['app', 'google', 'apple']) {
        await page.goto('/studio/aplikace')
        if (step === 'app')
          await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
        else {
          await page.goto('/studio/onboarding')
          const store = page.locator('.store-connection').filter({
            has: page.getByRole('heading', {
              name: step === 'google' ? 'Google Play' : 'App Store Connect',
              exact: true,
            }),
          })
          await store.getByRole('button', { name: 'Připojit store', exact: true }).click()
          if (step === 'google')
            await dialog(page)
              .getByRole('button', { name: /Jen recenze/ })
              .click()
        }
        await expect(dialog(page)).toBeVisible()
        await page.evaluate(async () => {
          await document.fonts.ready
        })
        const footer = await dialog(page).locator('.setup-wizard-footer').boundingBox()
        expect(footer!.y + footer!.height).toBeLessThanOrEqual(
          (await dialog(page).boundingBox())!.y + (await dialog(page).boundingBox())!.height,
        )
        const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
        expect(
          axe.violations.map((v) => ({
            id: v.id,
            nodes: v.nodes.map((n) => ({ target: n.target, summary: n.failureSummary })),
          })),
        ).toEqual([])
        expect(
          await dialog(page).evaluate((node) => node.scrollWidth <= node.clientWidth + 1),
        ).toBe(true)
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
          true,
        )
        if (process.env.CAPTURE_ADD_WIZARD)
          await page.screenshot({
            path: `../docs/design/add-app-wizard/after/${step}-${width}-${theme}.png`,
          })
      }
    }
    expect(state.creates).toBe(0)
  })

test('existující aplikace: částečná shoda se nespojí, globální VALID klíč stále vyžaduje ověření cíle', async ({
  page,
}) => {
  const { state, extra, credential } = await wizardApi(page, { platforms: ['ANDROID'] })
  const key = credential('GP_SERVICE_ACCOUNT', 'existing-gp')
  key.validationStatus = 'VALID'
  state.credentials.push(key)
  extra.kind = 'AUTH'
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill('https://play.google.com/store/apps/details?id=cz.example.atlas')
  await page
    .getByLabel('Odkaz na App Store', { exact: true })
    .fill('https://apps.apple.com/app/id123456789')
  await expect(dialog(page).getByText(/Shoduje se jen část/)).toBeVisible()
  await expect(action(page, 'Přidat aplikaci')).toBeDisabled()
  await page.getByLabel('Odkaz na App Store', { exact: true }).fill('')
  await action(page, 'Pokračovat s existující aplikací').click()
  await action(page, 'Pokračovat v připojení').click()
  await dialog(page).getByText('Použít účet uložený v organizaci', { exact: true }).click()
  await dialog(page)
    .getByRole('button', { name: /Spravovaný Google Play · synthetic/ })
    .click()
  await action(page, 'Ověřit přístup').click()
  await expect(
    dialog(page).getByText('Přístup zatím není potvrzený', { exact: true }),
  ).toBeVisible()
  await action(page, 'Zobrazit souhrn').click()
  await expect(
    dialog(page).locator('.setup-platform-row .badge').filter({ hasText: 'Přístup ověřený' }),
  ).toHaveCount(0)
  expect(state.creates).toBe(0)
  expect(extra.provisions).toBe(0)
  expect(extra.checks).toBe(1)
})

test('ztracená úspěšná odpověď vytvoření se obnoví ze serveru bez druhého POST', async ({
  page,
}) => {
  const { state } = await wizardApi(page, { empty: true, platforms: ['ANDROID'] })
  await page.route('**/api/orgs/studio/apps', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON()
    const { apps } = await import('./fixtures')
    state.creates++
    state.apps.push({
      ...structuredClone(apps[0]!),
      ...body,
      id: 'saved-before-response',
      platforms: ['ANDROID'],
      setup: {
        ready: false,
        gaps: ['STORE_KEY'],
        platformsWithoutKey: ['ANDROID'],
        platformsWaitingForKey: [],
        credentialIds: [],
      },
    })
    await route.abort('connectionclosed')
  })
  await addIdentity(page, false)
  expect(state.creates).toBe(1)
  expect(state.apps).toHaveLength(1)
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím', exact: true }),
  ).toBeVisible()
  expect(state.creates).toBe(1)
})

test('klávesnice, fokus, dlouhé hodnoty, nízký mobil a 200% zvětšení', async ({ page }) => {
  await wizardApi(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.setViewportSize({ width: 390, height: 500 })
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).first().click()
  await dialog(page)
    .getByRole('button', { name: /Jen recenze/ })
    .click()
  await expect(
    page.getByRole('heading', { name: 'Pozvěte účet do Google Play Console' }),
  ).toBeFocused()
  const bounds = await action(page, 'Ověřit přístup').boundingBox()
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(500)
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press('Tab')
    expect(await dialog(page).evaluate((node) => node.contains(document.activeElement))).toBe(true)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.evaluate(() => {
    document.documentElement.style.zoom = '2'
  })
  expect(
    await dialog(page).evaluate((node) => {
      const box = node.getBoundingClientRect()
      return box.left >= 0 && box.right <= innerWidth + 1 && box.bottom <= innerHeight + 1
    }),
  ).toBe(true)
  await page.evaluate(() => {
    document.documentElement.style.zoom = '1'
  })
  await action(page, '← Zpět').click()
  await action(page, '← Zpět').click()
  await expect(
    dialog(page).getByRole('heading', { name: 'Otevřete cestu k recenzím' }),
  ).toBeFocused()
})

test('member a změna organizace neposkytne mutace ani předchozí citlivý draft', async ({
  page,
}) => {
  const { state } = await wizardApi(page, { role: 'MEMBER' })
  await page.goto('/studio/aplikace')
  await expect(page.getByRole('button', { name: '+ Přidat aplikaci', exact: true })).toHaveCount(0)
  await page.goto('/studio/aplikace?setup=app-1')
  await expect(
    page.getByRole('heading', { name: 'Připojení spravuje administrátor' }),
  ).toBeVisible()
  expect(state.creates).toBe(0)
})

test('neuložený Apple soubor chrání i odchod přes interní odkaz v souhrnu', async ({ page }) => {
  await wizardApi(page, { platforms: ['IOS'] })
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await action(page, 'Klíč už mám').click()
  await dialog(page).locator('input[type=file]').setInputFiles(syntheticP8)
  await action(page, '← Zpět').click()
  await action(page, 'Dokončit později').click()
  await dialog(page).getByRole('link', { name: 'Nastavení aplikace', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Opustit rozpracované nastavení?' })).toBeVisible()
  await action(page, 'Pokračovat v nastavení').click()
  await dialog(page).getByRole('link', { name: 'Nastavení aplikace', exact: true }).click()
  await action(page, 'Zahodit neuložené údaje a pokračovat').click()
  await expect(page).toHaveURL('/studio/aplikace/app-1')
  await expect(dialog(page)).toHaveCount(0)
})

test('timeout ověření lze zopakovat bez překrytí a pozdní odpověď nepřepíše výsledek', async ({
  page,
}) => {
  await wizardApi(page, { platforms: ['ANDROID'] })
  let release: () => void = () => {},
    first = true
  await page.route('**/credentials/gp-key/validate', async (route) => {
    if (!first) return route.fallback()
    first = false
    await new Promise<void>((resolve) => {
      release = resolve
    })
    await route.fulfill({ json: { valid: false, kind: 'AUTH' } }).catch(() => {})
  })
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await dialog(page)
    .getByRole('button', { name: /Jen recenze/ })
    .click()
  await page.clock.install()
  await action(page, 'Ověřit přístup').click()
  await expect.poll(() => first).toBe(false)
  await page.clock.fastForward(26000)
  await expect(
    dialog(page).getByText('Přístup zatím není potvrzený', { exact: true }),
  ).toBeVisible()
  release()
  await action(page, 'Ověřit přístup znovu').click()
  await page.clock.fastForward(100)
  await expect(
    dialog(page).getByText('Přístup k aplikaci je ověřený', { exact: true }),
  ).toBeVisible()
})

test('neplatný odkaz a nedostupný lookup lze opravit ručními identifikátory', async ({ page }) => {
  const { state } = await wizardApi(page, { empty: true })
  await page.route('**/apps/resolve', (route) =>
    route.request().postDataJSON().googlePlayUrl.includes('invalid')
      ? route.fulfill({
          json: {
            googlePlay: {
              platform: 'ANDROID',
              identifier: '',
              name: null,
              error: 'Neplatný odkaz na Google Play',
            },
            appStore: null,
          },
        })
      : route.fulfill({
          status: 503,
          json: { message: 'Vyhledání ve storu je dočasně nedostupné.' },
        }),
  )
  await page.goto('/studio/aplikace')
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page.getByLabel('Odkaz na Google Play', { exact: true }).fill('invalid')
  await expect(
    dialog(page).getByText('Neplatný odkaz na Google Play', { exact: true }),
  ).toBeVisible()
  await expect(action(page, 'Přidat aplikaci')).toBeDisabled()
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill('https://play.google.com/store/apps/details?id=cz.example.atlas')
  await expect(dialog(page).getByRole('alert')).toContainText('dočasně nedostupné')
  await action(page, 'Pokročilé: zadat identifikátory ručně').click()
  await page.getByLabel('Package name (Android)', { exact: true }).fill('cz.example.atlas')
  await page.getByLabel('Název', { exact: true }).fill('Ručně přidaná')
  await action(page, 'Přidat aplikaci').click()
  await expect(
    dialog(page).getByRole('heading', { name: 'Otevřete cestu k recenzím', exact: true }),
  ).toBeVisible()
  expect(state.creates).toBe(1)
})
