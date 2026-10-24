import { expect, test } from '@playwright/test'
import { mockApi, reviews } from './fixtures'

test('urgency, stav, typ, řazení a detail přežijí refresh a Back', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&urgency=HIGH&state=all&type=BUG&sort=OLDEST')
  await expect(page.getByRole('button', { name: 'Naléhavost recenze' })).toContainText('naléhavé')
  await page.getByRole('button', { name: /Petra Nováková/ }).click()
  await expect(page).toHaveURL(/review=review-1/)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Po aktualizaci se nepřihlásím' })).toBeVisible()
  expect(
    requests.some(
      (url) => url.includes('urgency=HIGH') && url.includes('type=BUG') && url.includes('sort=OLDEST'),
    ),
  ).toBeTruthy()
  await page.getByRole('button', { name: /Alex/ }).click()
  await page.goBack()
  await expect(page).toHaveURL(/review=review-1/)
})

test('koncept se zachová mezi recenzemi a při přechodu na jinou plochu', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&review=review-1')
  await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Rozpracovaná odpověď Petře.')
  await page.getByRole('button', { name: /Martin Dvořák/ }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toBeEmpty()
  await page.getByRole('button', { name: /Petra Nováková/ }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue(
    'Rozpracovaná odpověď Petře.',
  )
  await page.getByRole('link', { name: 'Rozbory', exact: true }).click()
  await page.goBack()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue(
    'Rozpracovaná odpověď Petře.',
  )
})

test('AI a překlad dovolují vrátit původní koncept', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&review=review-3')
  await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Můj původní koncept.')
  await page.getByRole('button', { name: 'Nahradit návrhem' }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue(
    'Děkujeme za upozornění. Problém s přihlášením prověřujeme.',
  )
  await page.getByRole('button', { name: 'Vrátit původní' }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue('Můj původní koncept.')
  await page.getByRole('button', { name: /Přeložit do jazyka/ }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue(/Thank you/)
  await page.getByRole('button', { name: 'Vrátit původní' }).click()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveValue('Můj původní koncept.')
})

test('fronta není publikace a nedovolí druhé odeslání', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&review=review-1')
  await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Bezpečný test pouze v mock transportu.')
  await page.getByRole('button', { name: 'Zařadit k odeslání' }).click()
  await expect(page.getByText('ve frontě', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Čeká na publikaci' })).toBeDisabled()
  await expect(page.getByText('publikováno', { exact: true })).toHaveCount(0)
  expect(requests.filter((url) => url.startsWith('POST') && url.endsWith('/reply'))).toHaveLength(1)
})

for (const status of ['PUBLISHED', 'FAILED'] as const)
  test(`historie pravdivě ukazuje ${status}`, async ({ page }) => {
    const { history } = await mockApi(page)
    history['review-1'] = [
      {
        id: 'reply',
        body: 'Předchozí odpověď',
        status,
        error: status === 'FAILED' ? 'Store odmítl odpověď.' : null,
        source: 'CONSOLE',
        authorDisplayName: 'Tým',
        createdAt: '2026-10-24T10:00:00Z',
        publishedAt: status === 'PUBLISHED' ? '2026-10-24T10:01:00Z' : null,
      },
    ]
    await page.goto('/studio/recenze?app=app-1&review=review-1')
    await expect(
      page.getByText(status === 'PUBLISHED' ? 'publikováno' : 'selhalo · Store odmítl odpověď.', {
        exact: true,
      }),
    ).toBeVisible()
    if (status === 'FAILED') {
      await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Opravená odpověď')
      await expect(page.getByRole('button', { name: 'Zařadit k odeslání' })).toBeEnabled()
    }
  })

test('klávesy prochází detail, psaní je neaktivuje, Escape vrací seznam', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/recenze')
  await page.getByRole('heading', { name: 'Recenze', exact: true }).waitFor()
  await page.keyboard.press('j')
  await expect(page).toHaveURL(/app=app-1&review=review-1/)
  await page.keyboard.press('r')
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toBeFocused()
  await page.keyboard.type('jkri')
  await expect(page).toHaveURL(/review=review-1/)
  await page.keyboard.press('Escape')
  await expect(page).not.toHaveURL(/review=/)
  await page.keyboard.press('j')
  await page.keyboard.press('j')
  await expect(page).toHaveURL(/review=review-2/)
  await page.keyboard.press('k')
  await expect(page).toHaveURL(/review=review-1/)
})

test('mobilní detail zachová filtry a návrat', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&urgency=HIGH')
  await page.getByRole('button', { name: /Petra Nováková/ }).click()
  await expect(page.getByRole('region', { name: 'Seznam recenzí' })).toBeHidden()
  await page.getByRole('button', { name: '← Zpět na seznam' }).click()
  await expect(page.getByRole('button', { name: /Petra Nováková/ })).toBeVisible()
  await expect(page).toHaveURL(/urgency=HIGH/)
})

test('konkurence i smazaná recenze jsou ke čtení včetně historie', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/recenze?app=app-3&review=review-1')
  await expect(page.getByText(/Konkurenční aplikace ·/)).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveCount(0)
  await page.goto('/studio/recenze?app=app-1&state=all&review=review-5')
  await expect(page.getByText(/Autor recenzi ve storu smazal/)).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveCount(0)
})

test('analytické téma i verze předávají rozsah a Back vrací kontext', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.goto('/studio/rozbory?app=app-1&days=90&platform=IOS&territory=CZE')
  await page.getByRole('link', { name: 'Přihlášení', exact: true }).click()
  await expect(page).toHaveURL(/state=all/)
  await expect(page).toHaveURL(/from=2026-09-25/)
  await expect(page).toHaveURL(/platform=IOS/)
  await expect(page).toHaveURL(/territory=CZE/)
  await page.getByRole('heading', { name: 'Recenze', exact: true }).waitFor()
  expect(
    requests.some(
      (url) => url.includes('/reviews?') && url.includes('from=2026-09-25') && url.includes('textOnly=true'),
    ),
  ).toBeTruthy()
  await page.goBack()
  await expect(page).toHaveURL(/days=90&platform=IOS&territory=CZE/)
  await page.getByRole('button', { name: 'Rozbalit', exact: true }).click()
  await page.getByRole('link', { name: 'Ukázat recenze verze 4.8.0' }).click()
  await expect(page).toHaveURL(/platform=IOS&version=4.8.0/)
  await page.goBack()
  await expect(page.getByRole('link', { name: 'Ukázat recenze verze 4.8.0' })).toBeVisible()
})

test('přepnutí organizace nezobrazí předchozí seznam ani koncept', async ({ page }) => {
  await mockApi(page)
  await page.goto('/studio/recenze?app=app-1&review=review-1')
  await page.getByRole('textbox', { name: 'Koncept odpovědi' }).fill('Soukromý koncept organizace Studio')
  await page.getByRole('link', { name: /Studio • ukázková/ }).click()
  await page.getByRole('link', { name: 'Druhá organizace', exact: true }).click()
  await page.getByRole('link', { name: 'Recenze', exact: true }).click()
  await expect(page.getByText(/Napřed je potřeba přidat aplikaci/)).toBeVisible()
  await expect(page.getByText('Petra Nováková')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Koncept odpovědi' })).toHaveCount(0)
})

test('částečné selhání hromadného odložení má zprávu a nezahodí úspěchy', async ({ page }) => {
  const { requests } = await mockApi(page)
  await page.route('**/api/orgs/studio/reviews/*', async (route) => {
    if (route.request().method() !== 'PATCH') return route.fallback()
    if (route.request().url().endsWith('review-2'))
      return route.fulfill({ status: 503, json: { message: 'Test selhání' } })
    return route.fulfill({ json: { ...reviews[0], state: 'IGNORED' } })
  })
  await page.goto('/studio/recenze?app=app-1')
  await page.getByRole('checkbox', { name: 'Vybrat stránku' }).check()
  await page.getByRole('button', { name: 'Odložit vybrané' }).click()
  await expect(page.getByRole('alert')).toContainText('Úspěšné změny zůstaly uložené')
  await expect(page.getByRole('checkbox', { name: 'Vybrat recenzi od Petra Nováková' })).not.toBeChecked()
  expect(requests.length).toBeGreaterThan(0)
})
