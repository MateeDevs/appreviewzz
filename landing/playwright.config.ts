import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: 'docs/reports/browser.json' }]],
  use: {
    baseURL: 'http://127.0.0.1:4327',
    browserName: 'chromium',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run preview -- --ignore-lock',
    url: 'http://127.0.0.1:4327',
    reuseExistingServer: true,
  },
});
