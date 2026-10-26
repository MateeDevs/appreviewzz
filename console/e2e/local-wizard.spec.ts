import { test, expect } from '@playwright/test'
import { randomUUID, generateKeyPairSync } from 'node:crypto'
import { readFileSync } from 'node:fs'

test('lokální wizard: skutečné identity, vault deduplikace, vazba a izolace organizací', async ({
  page,
  context,
}) => {
  test.skip(!process.env.LOCAL_API_SMOKE)
  test.setTimeout(60000)
  const id = randomUUID().slice(0, 8),
    email = `wizard-${id}@example.test`,
    password = randomUUID() + 'Aa9!'
  await page.goto('/registrace')
  await page.getByLabel('Jméno', { exact: true }).fill('Lokální ověření wizardu')
  await page.getByLabel('E-mail', { exact: true }).fill(email)
  await page.getByLabel('Heslo', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Založit účet', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Ještě ověřte svůj e-mail' })).toBeVisible()
  const log = readFileSync(process.env.LOCAL_API_LOG!, 'utf8'),
    token = [
      ...log.matchAll(/http:\/\/(?:localhost|127\.0\.0\.1):\d+\/overeni\?token=([A-Za-z0-9_-]+)/g),
    ].at(-1)![1]
  await page.goto(`/overeni?token=${token}`)
  await expect(page.getByText(/e-mail je potvrzený/i)).toBeVisible()
  await page.goto('/organizace')
  await page.getByLabel('Název pracovního prostoru', { exact: true }).fill(`Wizard ${id}`)
  await page.getByRole('button', { name: 'Založit organizaci', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Začněme vaší aplikací.' })).toBeVisible()
  const org = new URL(page.url()).pathname.split('/')[1]!
  await page.goto(`/${org}/aplikace`)
  await page.getByRole('button', { name: '+ Přidat aplikaci', exact: true }).click()
  await page
    .getByRole('button', { name: 'Pokročilé: zadat identifikátory ručně', exact: true })
    .click()
  await page.getByLabel('Číselné App ID (iOS)', { exact: true }).fill('1234567890')
  await page.getByLabel('Název', { exact: true }).fill('Izolovaná lokální aplikace')
  await page.getByRole('button', { name: 'Přidat aplikaci', exact: true }).dblclick()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím', exact: true }),
  ).toBeVisible()
  const appId = new URL(page.url()).searchParams.get('setup')!
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Otevřete cestu k recenzím', exact: true }),
  ).toBeVisible()
  const cookies = await context.cookies(),
    csrf = decodeURIComponent(cookies.find((c) => c.name === 'arz_csrf')!.value),
    headers = { 'X-CSRF-Token': csrf }
  const content = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
    .privateKey.export({ type: 'pkcs8', format: 'pem' })
    .toString()
  const data = {
    type: 'asc',
    label: 'Lokální testovací klíč',
    content,
    keyId: 'TEST123456',
    issuerId: randomUUID(),
    deduplicate: true,
  }
  const results = await Promise.all(
    [1, 2, 3].map(() => page.request.post(`/api/orgs/${org}/credentials`, { data, headers })),
  )
  const bodies = await Promise.all(
    results.map(async (response) => {
      expect(response.status()).toBe(201)
      return response.json()
    }),
  )
  expect(new Set(bodies.map((b) => b.id)).size).toBe(1)
  const keyId = bodies[0].id
  const attach = await page.request.post(`/api/orgs/${org}/apps/${appId}/credentials`, {
    data: { credentialId: keyId, purpose: 'REVIEWS' },
    headers,
  })
  expect(attach.status()).toBe(204)
  const metadata = await page.request.get(`/api/orgs/${org}/credentials`),
    text = await metadata.text()
  expect(text).not.toContain('PRIVATE KEY')
  expect(text).not.toContain(content)
  const stored = await page.request.get(`/api/orgs/${org}/apps/${appId}`)
  expect((await stored.json()).setup.platformsWaitingForKey).toEqual(['IOS'])
  const progress = await page.request.get(`/api/orgs/${org}/onboarding`)
  expect(progress.status()).toBe(200)
  expect((await progress.json()).appId ?? null).toBeNull()
  const other = await page.request.post('/api/orgs', {
    data: { name: `Jiný prostor ${id}` },
    headers,
  })
  expect(other.status()).toBe(201)
  const otherOrg = await other.json()
  const foreign = await page.request.get(`/api/orgs/${otherOrg.slug}/credentials`)
  expect(await foreign.json()).toEqual([])
  const denied = await page.request.post(`/api/orgs/${otherOrg.slug}/apps/${appId}/credentials`, {
    data: { credentialId: keyId },
    headers,
  })
  expect([403, 404]).toContain(denied.status())
  await page.getByRole('button', { name: 'Zavřít', exact: true }).click()
})
