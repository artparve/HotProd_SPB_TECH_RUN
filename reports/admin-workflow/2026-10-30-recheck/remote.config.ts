import { defineConfig, devices } from '../../../frontend/node_modules/@playwright/test/index.js'
export default defineConfig({
  testDir: '.',
  testMatch: 'remote*.spec.ts',
  timeout: 60_000,
  workers: 1,
  outputDir: './remote-results',
  reporter: [['list'], ['json', { outputFile: './remote-results.json' }]],
  use: { baseURL: 'http://194.67.95.67', trace: 'retain-on-failure', actionTimeout: 15_000 },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
})
