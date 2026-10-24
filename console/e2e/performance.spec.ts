import { test } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { mockApi } from './fixtures'

declare global {
  interface Window {
    __aaaMetrics: { lcp: number; cls: number }
  }
}

test('porovnání produkčních assetů na stejných fixtures', async ({ browser }) => {
  test.skip(!process.env.MEASURE_PERF)
  test.setTimeout(180000)
  const results: unknown[] = []
  for (const [revision, origin] of [
    ['before', 'http://127.0.0.1:4175'],
    ['after', 'http://127.0.0.1:4174'],
  ]) {
    for (const [name, path, appCount] of [
      ['overview-1', '/studio', 1],
      ['overview-30', '/studio', 30],
      ['inbox', '/studio/recenze?app=app-1', 1],
      ['analysis', '/studio/rozbory?app=app-1', 1],
    ] as const) {
      for (let run = 0; run < 3; run++) {
        const context = await browser.newContext({
          viewport: { width: 1440, height: 1000 },
          colorScheme: 'light',
        })
        const page = await context.newPage()
        const { requests } = await mockApi(page, { appCount })
        const cdp = await context.newCDPSession(page)
        await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
        await cdp.send('Network.enable')
        await cdp.send('Network.emulateNetworkConditions', {
          offline: false,
          latency: 150,
          downloadThroughput: 1600000 / 8,
          uploadThroughput: 750000 / 8,
        })
        await page.addInitScript(() => {
          window.__aaaMetrics = { lcp: 0, cls: 0 }
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) window.__aaaMetrics.lcp = entry.startTime
          }).observe({ type: 'largest-contentful-paint', buffered: true })
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries() as (PerformanceEntry & {
              hadRecentInput: boolean
              value: number
            })[])
              if (!entry.hadRecentInput) window.__aaaMetrics.cls += entry.value
          }).observe({ type: 'layout-shift', buffered: true })
        })
        await page.goto(`${origin}${path}`)
        await page.getByRole('heading', { level: 1 }).waitFor()
        await page.evaluate(() => document.fonts.ready)
        await page.waitForTimeout(1000)
        const metrics = await page.evaluate(() => window.__aaaMetrics)
        let labOpenDetailMs: number | null = null
        if (name === 'inbox') {
          const started = performance.now()
          if (revision === 'before')
            await page.getByRole('button', { name: 'Odpovědět', exact: true }).first().click()
          else await page.getByRole('button', { name: /Petra Nováková/ }).click()
          await page.locator('textarea').waitFor()
          labOpenDetailMs = Math.round(performance.now() - started)
        }
        results.push({
          revision,
          view: name,
          run: run + 1,
          ...metrics,
          labOpenDetailMs,
          apiRequests: requests.length,
        })
        await context.close()
      }
    }
  }
  writeFileSync(
    fileURLToPath(new URL('../../docs/design/aaa/performance.json', import.meta.url)),
    JSON.stringify(
      {
        environment:
          'Chromium, 1440×1000, production Vite preview, isolated API mocks, cold contexts, CPU ×4, 1.6 Mbps down / 750 Kbps up / 150 ms RTT. Laboratory data, NOT real user INP.',
        results,
      },
      null,
      2,
    ),
  )
})
