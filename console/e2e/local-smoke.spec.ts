import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

// Opt-in: skutečný lokální backend, vlastní účet a organizace, bez workeru a kanálů.
test('lokální Ktor/Postgres: registrace, ověření, organizace a přihlášení', async ({ page }) => {
  test.skip(!process.env.LOCAL_API_SMOKE)
  test.setTimeout(60000)
  const id = randomUUID().slice(0, 8),
    email = `aaa-${id}@example.test`,
    password = randomUUID() + 'Aa9!'
  await page.goto('/registrace')
  await page.getByLabel('Jméno', { exact: true }).fill('Lokální ověření redesignu')
  await page.getByLabel('E-mail', { exact: true }).fill(email)
  await page.getByLabel('Heslo', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Založit účet' }).click()
  await expect(page.getByText('Nejdřív potvrď e-mail', { exact: false })).toBeVisible()
  const log = readFileSync('/tmp/appreviewzz-local-api.log', 'utf8')
  const urls = [...log.matchAll(/http:\/\/(?:localhost|127\.0\.0\.1):5173\/overeni\?token=([A-Za-z0-9_-]+)/g)]
  const token = urls.at(-1)?.[1]
  expect(token).toBeTruthy()
  await page.goto(`/overeni?token=${token}`)
  await expect(page.getByText(/e-mail je potvrzený/i)).toBeVisible()
  await page.goto('/organizace')
  await page.getByLabel('Název', { exact: true }).fill(`AAA ${id}`)
  await page.getByRole('button', { name: 'Založit organizaci' }).click()
  await expect(page.getByRole('heading', { name: 'Od aplikace k první recenzi' })).toBeVisible()
  const org = new URL(page.url()).pathname.split('/')[1]
  await page.goto(`/${org}/aplikace`)
  await page.getByRole('button', { name: '+ Přidat aplikaci' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page
    .getByLabel('Odkaz na Google Play', { exact: true })
    .fill(`https://play.google.com/store/apps/details?id=cz.example.aaa${id}`)
  await page.getByLabel('Název', { exact: true }).fill('Lokální testovací aplikace')
  await page.getByRole('button', { name: 'Přidat aplikaci', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Připojit Google Play' })).toBeVisible()
  await expect(page.getByText('Co budeme sledovat?', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../docs/design/aaa/after/local-api-add-app.png' })
  await page.getByRole('button', { name: 'Zavřít', exact: true }).click()
  await expect(page.getByText('čeká na nastavení', { exact: true })).toBeVisible()
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
  await expect(page).toHaveURL(new RegExp(`/${org}$`))
})
