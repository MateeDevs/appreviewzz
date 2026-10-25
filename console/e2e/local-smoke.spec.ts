import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

// Opt-in: skutečný lokální backend, vlastní účet a organizace, bez workeru a kanálů.
test('lokální Ktor/Postgres: registrace, ověření, organizace a přihlášení', async ({
  page,
}) => {
  test.skip(!process.env.LOCAL_API_SMOKE)
  test.setTimeout(60000)
  const id = randomUUID().slice(0, 8),
    email = `aaa-${id}@example.test`,
    password = randomUUID() + 'Aa9!'
  await page.goto('/registrace')
  await page
    .getByLabel('Jméno', { exact: true })
    .fill('Lokální ověření redesignu')
  await page.getByLabel('E-mail', { exact: true }).fill(email)
  await page.getByLabel('Heslo', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Založit účet' }).click()
  await expect(
    page.getByRole('heading', { name: 'Ještě ověřte svůj e-mail' }),
  ).toBeVisible()
  const log = readFileSync(
    process.env.LOCAL_API_LOG ?? '/tmp/appreviewzz-local-api.log',
    'utf8',
  )
  const urls = [
    ...log.matchAll(
      /http:\/\/(?:localhost|127\.0\.0\.1):\d+\/overeni\?token=([A-Za-z0-9_-]+)/g,
    ),
  ]
  const token = urls.at(-1)?.[1]
  expect(token).toBeTruthy()
  await page.goto(`/overeni?token=${token}`)
  await expect(page.getByText(/e-mail je potvrzený/i)).toBeVisible()
  await page.goto('/organizace')
  await page
    .getByLabel('Název pracovního prostoru', { exact: true })
    .fill(`AAA ${id}`)
  await page.getByRole('button', { name: 'Založit organizaci' }).click()
  await expect(
    page.getByRole('heading', { name: 'Začněme vaší aplikací.' }),
  ).toBeVisible()
  const org = new URL(page.url()).pathname.split('/')[1]

  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill(`https://play.google.com/store/apps/details?id=cz.example.aaa${id}`)
  await page
    .getByLabel('Název', { exact: true })
    .fill('Lokální testovací aplikace')
  await page
    .getByRole('button', { name: 'Přidat aplikaci', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím.' }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím.' }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: 'Připojit store', exact: true })
    .click()
  await page.getByRole('button', { name: /Jen recenze/ }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: 'Zavřít', exact: true }).last().click()
  await page.getByRole('button', { name: 'Připojení dokončím později' }).click()
  await page
    .getByLabel('Instrukce pro AI návrhy', { exact: true })
    .fill('Izolované lokální ověření, bez odesílání.')
  await page
    .getByRole('button', { name: 'Uložit preference a pokračovat' })
    .click()
  await expect(page.getByRole('heading', { name: 'Přizvěte svůj tým. Až budete chtít.' })).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Přizvěte svůj tým. Až budete chtít.' }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: 'Pokračovat bez dalších integrací' })
    .click()
  await expect(
    page.getByRole('button', { name: 'Načíst první recenze' }),
  ).toBeDisabled()
  await expect(
    page.getByText('Připojení ještě není ověřené', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '← Zpět' }).click()
  for (const path of ['', '/recenze', '/rozbory', '/organizace', '/audit']) {
    await page.goto(`/${org}${path}`)
    await expect(page.locator('main')).not.toBeEmpty()
    await expect(page.getByRole('alert')).toHaveCount(0)
  }
  await page.getByRole('button', { name: 'Účet a odhlášení' }).click()
  await page.getByRole('menuitem', { name: 'Odhlásit se' }).click()
  await page.getByLabel('E-mail', { exact: true }).fill(email)
  await page.getByLabel('Heslo', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Přihlásit se', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/${org}/audit$`))
  await page.goto('/')
  await expect(page).toHaveURL(new RegExp(`/${org}/onboarding$`))
  await expect(
    page.getByRole('heading', { name: 'Přizvěte svůj tým. Až budete chtít.' }),
  ).toBeVisible()
})
