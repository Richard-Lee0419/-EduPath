import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 30_000 },
  reporter: [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: process.env.EDUPATH_BROWSER_URL || 'http://127.0.0.1:5173',
    browserName: 'chromium',
    actionTimeout: 30_000,
    navigationTimeout: 30_000,
    viewport: { width: 1440, height: 1000 },
    video: { mode: process.env.EDUPATH_DEMO_RECORDING === '1' ? 'on' : 'retain-on-failure', size: { width: 1440, height: 1000 } },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
