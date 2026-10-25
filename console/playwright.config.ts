import { defineConfig } from '@playwright/test'
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'
export default defineConfig({
  testDir: './e2e',
  workers: process.env.CI ? 2 : undefined,
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL,
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${new URL(baseURL).port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
  },
})
