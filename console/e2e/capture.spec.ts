import { fileURLToPath } from 'node:url'
import { test } from '@playwright/test'
import { mockApi } from './fixtures'
// Explicit opt-in: these images are mock UI renders, never staging evidence.
test('capture local fixtures', async ({ page }) => {
  test.skip(!process.env.CAPTURE_UI)
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message))
  await mockApi(page)
  const folder = process.env.CAPTURE_UI === 'before' ? 'before' : 'after'
  for (const [name, path] of [
    ['overview', '/studio'],
    ['inbox', '/studio/recenze?app=app-1'],
    ['analysis', '/studio/rozbory?app=app-1'],
    ['apps', '/studio/aplikace'],
    ['inbox-detail', '/studio/recenze?app=app-1&review=review-1'],
  ]) {
    await page.goto(path!)
    await page.getByRole('heading', { level: 1 }).waitFor()
    await page.waitForTimeout(300)
    await page.screenshot({
      path: fileURLToPath(new URL(`../../docs/design/aaa/${folder}/${name}-1440-light.png`, import.meta.url)),
      fullPage: true,
    })
  }
})
