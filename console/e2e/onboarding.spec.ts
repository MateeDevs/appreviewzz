import { test, expect } from '@playwright/test'
import { onboardingApi } from './onboarding-fixtures'
import type { Platform } from '../src/api/types'

for (const condition of ['ready', 'revoked', 'disabled'] as const)
  test(`dokončený průvodce lze znovu upravit: ${condition}`, async ({
    page,
  }) => {
    const state = await onboardingApi(page, { platforms: ['ANDROID'] })
    state.progress = {
      ...state.progress,
      step: 'sync',
      preferencesConfirmed: true,
      completed: true,
    }
    state.apps[0]!.enabled = condition !== 'disabled'
    state.apps[0]!.setup.credentialIds = ['existing-key']
    state.credentials.push({
      id: 'existing-key',
      type: 'GP_SERVICE_ACCOUNT',
      label: 'Test',
      hint: null,
      origin: 'UPLOADED',
      fingerprint: 'synthetic',
      validationStatus: condition === 'revoked' ? 'INVALID' : 'VALID',
      validationError: null,
      validatedAt: null,
    })
    // Stejné pravidlo jako skutečné API: neplatné nastavení nelze označit za dokončené.
    await page.route('**/onboarding', (route) => {
      if (
        route.request().method() === 'PUT' &&
        route.request().postDataJSON().completed &&
        condition !== 'ready'
      )
        return route.fulfill({
          status: 400,
          json: {
            message: 'Nejdřív ověřte alespoň jeden store a potvrďte preference',
          },
        })
      return route.fallback()
    })
    await page.goto('/studio/onboarding')
    await page
      .getByRole('button', { name: 'Připojení storů', exact: true })
      .click()
    await expect(
      page.getByRole('heading', { name: 'Otevřete cestu k recenzím.' }),
    ).toBeVisible()
    expect(state.progress.completed).toBe(condition === 'ready')
  })

test('ASC klíč bez přístupu k vybrané aplikaci nenabízí falešné dokončení', async ({
  page,
}) => {
  const state = await onboardingApi(page, { platforms: ['IOS'] })
  let accessible = false
  await page.route('**/store-apps', (route) =>
    route.fulfill({
      json: accessible
        ? [{ identifier: '123456789', name: 'Atlas', bundleId: 'cz.example.atlas' }]
        : [{ identifier: '987654321', name: 'Jiná aplikace', bundleId: 'cz.example.other' }],
    }),
  )
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojit store', exact: true }).click()
  await page
    .getByRole('button', { name: 'Klíč mám, pokračovat', exact: true })
    .click()
  await page
    .getByRole('dialog')
    .locator('input[type=file]')
    .setInputFiles({
      name: 'AuthKey_TEST123456.p8',
      mimeType: 'text/plain',
      buffer: Buffer.from(
        '-----BEGIN PRIVATE KEY-----\nsynthetic-test-only\n-----END PRIVATE KEY-----',
      ),
    })
  await page
    .getByLabel('Issuer ID', { exact: true })
    .fill('69a6de70-0000-47e3-e053-5b8c7c11a4d1')
  await page.getByRole('button', { name: 'Nahrát klíč', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Klíč nevidí aplikaci')
  await expect(
    page.getByRole('button', { name: 'Sledovat vybrané', exact: true }),
  ).toBeDisabled()
  expect(state.validates).toBe(0)
  accessible = true
  await page
    .getByRole('button', { name: 'Zkusit načíst aplikace znovu', exact: true })
    .click()
  await page
    .getByRole('button', { name: 'Sledovat vybrané', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: 'Hotovo', exact: true }),
  ).toBeVisible()
  expect(state.validates).toBe(1)
})

for (const platforms of [
  ['ANDROID'],
  ['IOS'],
  ['ANDROID', 'IOS'],
] as Platform[][]) {
  test(`zakladatel: ${platforms.join('+')}, refresh, preference, přeskočení a první import`, async ({
    page,
  }) => {
    const state = await onboardingApi(page, { platforms, empty: true })
    await page.goto('/studio/onboarding')
    if (platforms.includes('ANDROID'))
      await page
        .getByLabel('Odkaz na Google Play', { exact: true })
        .fill('https://play.google.com/store/apps/details?id=cz.example.atlas')
    if (platforms.includes('IOS'))
      await page
        .getByLabel('Odkaz na App Store', { exact: true })
        .fill('https://apps.apple.com/app/id123456789')
    await expect(page.getByLabel('Název', { exact: true })).toHaveValue('Atlas')
    await page
      .getByRole('button', { name: 'Přidat aplikaci', exact: true })
      .dblclick()
    await expect(
      page.getByRole('heading', { name: 'Otevřete cestu k recenzím.' }),
    ).toBeVisible()
    expect(state.creates).toBe(1)
    await page.reload()
    await expect(
      page.getByRole('heading', { name: 'Otevřete cestu k recenzím.' }),
    ).toBeVisible()
    for (const platform of platforms) {
      const store = page.locator('.store-connection').filter({
        has: page.getByRole('heading', {
          name: platform === 'ANDROID' ? 'Google Play' : 'App Store Connect',
          exact: true,
        }),
      })
      await store
        .getByRole('button', { name: 'Připojit store', exact: true })
        .click()
      if (platform === 'ANDROID') {
        await page.getByRole('button', { name: /Jen recenze/ }).click()
        await page
          .getByRole('button', { name: 'Pozvánku jsem odeslal', exact: true })
          .click()
        await page.getByRole('button', { name: 'Hotovo', exact: true }).click()
      } else {
        await page
          .getByRole('button', { name: 'Klíč mám, pokračovat', exact: true })
          .click()
        await page
          .getByRole('dialog')
          .locator('input[type=file]')
          .setInputFiles({
            name: 'AuthKey_TEST123456.p8',
            mimeType: 'text/plain',
            buffer: Buffer.from(
              '-----BEGIN PRIVATE KEY-----\nsynthetic-test-only\n-----END PRIVATE KEY-----',
            ),
          })
        await page
          .getByLabel('Issuer ID', { exact: true })
          .fill('69a6de70-0000-47e3-e053-5b8c7c11a4d1')
        await page
          .getByRole('button', { name: 'Nahrát klíč', exact: true })
          .click()
        await expect(
          page.getByText('Jiná aplikace', { exact: true }),
        ).toHaveCount(0)
        await page
          .getByRole('button', { name: 'Sledovat vybrané', exact: true })
          .click()
        await page
          .getByRole('button', { name: 'Zavřít', exact: true })
          .last()
          .click()
      }
      await expect(
        store.getByText('Připojení ověřeno', { exact: true }),
      ).toBeVisible()
    }
    await page
      .getByRole('button', { name: 'Pokračovat k preferencím', exact: true })
      .click()
    await page
      .getByLabel('Instrukce pro AI návrhy', { exact: true })
      .fill('Piš vstřícně a stručně. Testovací preference.')
    await page
      .getByRole('button', {
        name: 'Uložit preference a pokračovat',
        exact: true,
      })
      .click()
    await expect(
      page.getByRole('heading', {
        name: 'Přizvěte svůj tým. Až budete chtít.',
      }),
    ).toBeVisible()
    await page.reload()
    await expect(
      page.getByRole('heading', {
        name: 'Přizvěte svůj tým. Až budete chtít.',
      }),
    ).toBeVisible()
    expect(state.apps[0]?.aiInstructions).toContain('Testovací preference')
    await page
      .getByRole('button', {
        name: 'Pokračovat bez dalších integrací',
        exact: true,
      })
      .click()
    await page
      .getByRole('button', { name: 'Načíst první recenze', exact: true })
      .click()
    await expect(
      page.getByText('Store odpověděl bez recenzí v dostupném rozsahu', {
        exact: true,
      }),
    ).toHaveCount(platforms.length)
    await page.reload()
    expect(state.syncRuns).toBe(1)
    await expect(
      page.getByText('Store odpověděl bez recenzí v dostupném rozsahu', {
        exact: true,
      }),
    ).toHaveCount(platforms.length)
    await page
      .getByRole('button', { name: 'Pokračovat do přehledu →', exact: true })
      .click()
    await expect(page).toHaveURL('/studio')
    expect(state.progress.completed).toBe(true)
    expect(state.creates).toBe(1)
    expect(
      await page.evaluate(() =>
        Object.keys(localStorage).filter((k) => !k.includes('theme')),
      ),
    ).toEqual([])
  })
}

test('čekající oprávnění nejsou úspěch; refresh, opakování a chyba synchronizace', async ({
  page,
}) => {
  const state = await onboardingApi(page, { platforms: ['ANDROID'] })
  state.waiting = true
  await page.goto('/studio/onboarding')
  await page
    .getByRole('button', { name: 'Připojit store', exact: true })
    .click()
  await page.getByRole('button', { name: /Jen recenze/ }).click()
  await page
    .getByRole('button', { name: 'Pozvánku jsem odeslal', exact: true })
    .click()
  await expect(
    page
      .getByRole('dialog')
      .getByText('Čekáme na propagaci oprávnění.', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Zavřít', exact: true }).last().click()
  await page.reload()
  await expect(
    page.getByText('Oprávnění nebo údaje vyžadují kontrolu', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText('Připojení ověřeno', { exact: true }),
  ).toHaveCount(0)
  state.waiting = false
  await page
    .getByRole('button', { name: 'Ověřit přístup znovu', exact: true })
    .click()
  await expect(
    page.getByText('Připojení ověřeno', { exact: true }),
  ).toBeVisible()
  await page.goto('/studio/onboarding?step=sync')
  state.syncFails = true
  await page
    .getByRole('button', { name: 'Načíst první recenze', exact: true })
    .click()
  await expect(
    page.getByText('Stažení se nepodařilo', { exact: true }),
  ).toBeVisible()
  state.syncFails = false
  state.reviewCount = 3
  await page
    .getByRole('button', { name: 'Zkusit synchronizaci znovu', exact: true })
    .click()
  await expect(
    page.getByText('3 recenze jsou připravené', { exact: true }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: 'Otevřít recenze →', exact: true })
    .click()
  await expect(page).toHaveURL('/studio/recenze?app=app-1')
  expect(state.progress.completed).toBe(false)
})

test('konkurence ani cizí klíč neplní aktivaci, člen nemá správu', async ({
  page,
}) => {
  const state = await onboardingApi(page, { empty: true })
  state.apps.push({ ...(await import('./fixtures')).apps[2]! })
  await page.goto('/studio/onboarding')
  await expect(
    page.getByLabel('Odkaz na Google Play', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: /konkurenci/ })).toHaveCount(0)
  state.profile.organizations[0]!.role = 'MEMBER'
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Vítejte v týmu', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Přidat aplikaci', exact: true }),
  ).toHaveCount(0)
})

for (const newUser of [true, false])
  test(`pozvánka: ${newUser ? 'nový účet' : 'existující účet'} zachová cíl`, async ({
    page,
  }) => {
    const state = await onboardingApi(page, { signedOut: true, empty: true })
    await page.goto('/pozvanka?token=isolated-invitation')
    await page
      .getByRole('link', {
        name: newUser ? 'Založit účet' : 'Přihlásit se',
        exact: true,
      })
      .click()
    await page.getByLabel('E-mail', { exact: true }).fill('invite@example.test')
    await page
      .getByLabel('Heslo', { exact: true })
      .fill('Dlouhe-testovaci-heslo')
    await page
      .getByRole('button', {
        name: newUser ? 'Založit účet' : 'Přihlásit se',
        exact: true,
      })
      .click()
    await expect(page).toHaveURL('/pozvanka?token=isolated-invitation')
    await page
      .getByRole('button', { name: 'Přijmout pozvánku', exact: true })
      .click()
    await expect(page).toHaveURL('/studio')
    expect(state.profile.organizations).toHaveLength(1)
  })

test('registrace, resend limit, ověření v jiné záložce a založení prostoru', async ({
  page,
  context,
}) => {
  const state = await onboardingApi(page, { signedOut: true, empty: true })
  await page.goto('/registrace')
  await page.getByLabel('E-mail', { exact: true }).fill('founder@example.test')
  await page.getByLabel('Heslo', { exact: true }).fill('Dlouhe-testovaci-heslo')
  await page
    .getByRole('button', { name: 'Zobrazit heslo', exact: true })
    .click()
  await expect(page.getByLabel('Heslo', { exact: true })).toHaveAttribute(
    'type',
    'text',
  )
  await page.getByLabel('Heslo', { exact: true }).press('Enter')
  await expect(
    page.getByRole('heading', { name: 'Ještě ověřte svůj e-mail' }),
  ).toBeVisible()
  await expect(
    page.getByText('founder@example.test', { exact: true }),
  ).toBeVisible()
  state.resendFails = true
  await page
    .getByRole('button', { name: 'Poslat ověřovací e-mail znovu' })
    .click()
  await expect(page.getByRole('alert')).toContainText('Počkejte')
  await expect(
    page.getByRole('button', { name: /Poslat znovu za/ }),
  ).toBeDisabled()
  state.profile.emailVerified = true
  const other = await context.newPage()
  await other.goto('about:blank')
  await page.bringToFront()
  await page.getByRole('button', { name: 'Už jsem e-mail ověřil/a' }).click()
  await page.getByLabel('Název pracovního prostoru').fill('Moje studio')
  await page
    .getByRole('button', { name: 'Založit organizaci', exact: true })
    .click()
  await expect(page).toHaveURL('/studio/onboarding')
  await expect(
    page.getByLabel('Odkaz na Google Play', { exact: true }),
  ).toBeVisible()
})

test('rozpracovaný postup přežije odhlášení a nový login; deep link má přednost', async ({
  page,
}) => {
  const state = await onboardingApi(page)
  state.progress.step = 'extras'
  await page.goto('/login')
  await expect(page).toHaveURL('/studio/onboarding')
  await expect(
    page.getByRole('heading', { name: 'Přizvěte svůj tým. Až budete chtít.' }),
  ).toBeVisible()
  state.signedIn = false
  await page.goto('/login')
  await page.getByLabel('E-mail', { exact: true }).fill('founder@example.test')
  await page.getByLabel('Heslo', { exact: true }).fill('Dlouhe-testovaci-heslo')
  await page.getByRole('button', { name: 'Přihlásit se', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: 'Přizvěte svůj tým. Až budete chtít.' }),
  ).toBeVisible()
  await page.goto('/login?next=%2Fstudio%2Frecenze%3Fapp%3Dapp-1')
  await expect(page).toHaveURL('/studio/recenze?app=app-1')
})

test('volitelný Slack používá OAuth, připojený kanál neznamená ověřené doručení', async ({
  page,
}) => {
  const state = await onboardingApi(page)
  await page.route('**/slack/install-url', (route) =>
    route.fulfill({
      json: {
        url: 'http://127.0.0.1:5173/slack/install?state=synthetic-state',
      },
    }),
  )
  await page.goto('/studio/onboarding?step=extras')
  await page
    .locator('.activation-optional')
    .first()
    .locator(':scope > summary')
    .click()
  await page
    .getByRole('button', { name: 'Připojit přes Slack', exact: true })
    .click()
  await expect(
    page.getByRole('link', { name: 'Pokračovat ve Slacku ↗', exact: true }),
  ).toHaveAttribute('href', /slack\/install\?state=/)
  await expect(
    page.getByLabel('Bot token workspace', { exact: true }),
  ).not.toBeVisible()
  state.credentials.push({
    id: 'slack-test',
    type: 'SLACK_INSTALL',
    label: 'Test workspace',
    hint: 'Izolovaný workspace',
    origin: 'UPLOADED',
    fingerprint: 'synthetic',
    validationStatus: 'VALID',
    validationError: null,
    validatedAt: null,
  })
  await page.route('**/apps/app-1/channels', (route) =>
    route.fulfill({
      json: [
        {
          id: 'test-channel',
          type: 'SLACK',
          targetRef: 'C_TEST',
          credentialId: 'slack-test',
          enabled: true,
          locale: 'CS',
          targetLabel: 'Ukázkový kanál',
          deliverReviews: true,
          deliverRatings: true,
          deliverAnalyses: true,
        },
      ],
    }),
  )
  let messages = 0
  await page.route('**/channels/test', (route) => {
    messages++
    return route.fulfill({
      json: [
        {
          channelId: 'test-channel',
          targetRef: 'C_TEST',
          ok: false,
          error: 'Bot nemá přístup',
          hint: 'Pozvěte bota do kanálu.',
        },
      ],
    })
  })
  await page.reload()
  await page
    .locator('.activation-optional')
    .first()
    .locator(':scope > summary')
    .click()
  await expect(
    page.getByText('doručení neověřeno', { exact: true }),
  ).toBeVisible()
  expect(messages).toBe(0)
  await page
    .getByRole('button', { name: 'Poslat zkušební zprávu', exact: true })
    .click()
  await expect(page.getByText(/Bot nemá přístup/)).toBeVisible()
  expect(messages).toBe(1)
  await page
    .getByRole('button', {
      name: 'Pokračovat bez dalších integrací',
      exact: true,
    })
    .click()
  await expect(
    page.getByRole('heading', { name: 'Vaše první recenze na jednom místě.' }),
  ).toBeVisible()
})

test('síťová chyba uložení průchodu dovolí opakovat a nezaloží další aplikaci', async ({
  page,
}) => {
  const state = await onboardingApi(page)
  let attempts = 0
  await page.route('**/onboarding', async (route) => {
    if (route.request().method() === 'PUT' && ++attempts === 1)
      return route.fulfill({
        status: 503,
        json: { message: 'Dočasně se nepodařilo uložit postup.' },
      })
    return route.fallback()
  })
  await page.goto('/studio/onboarding')
  await page.getByRole('button', { name: 'Připojení dokončím později' }).click()
  await expect(page.getByRole('alert')).toContainText('Dočasně')
  await page.getByRole('button', { name: 'Připojení dokončím později' }).click()
  await expect(
    page.getByRole('heading', { name: 'Ať odpovědi znějí jako vy.' }),
  ).toBeVisible()
  expect(state.creates).toBe(0)
})

test('obnovení stránky během importu sleduje běžící server a nevytváří další běh', async ({
  page,
}) => {
  const state = await onboardingApi(page, { platforms: ['ANDROID'] })
  state.apps[0]!.setup.credentialIds = ['running-key']
  state.credentials.push({
    id: 'running-key',
    type: 'GP_SERVICE_ACCOUNT',
    label: 'Test',
    hint: null,
    origin: 'UPLOADED',
    fingerprint: 'synthetic',
    validationStatus: 'VALID',
    validationError: null,
    validatedAt: null,
  })
  state.syncs = [
    {
      platform: 'ANDROID',
      status: 'RUNNING',
      startedAt: new Date().toISOString(),
      finishedAt: null,
      fetched: null,
      message: null,
    },
  ]
  await page.goto('/studio/onboarding?step=sync')
  await expect(
    page.getByText('Synchronizace probíhá', { exact: true }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('button', { name: 'Synchronizace běží…' }),
  ).toBeDisabled()
  expect(state.syncRuns).toBe(0)
  state.syncs[0]!.status = 'SUCCEEDED'
  state.syncs[0]!.fetched = 0
  await expect(
    page.getByText('Store odpověděl bez recenzí v dostupném rozsahu', {
      exact: true,
    }),
  ).toBeVisible()
})
