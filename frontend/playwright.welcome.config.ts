import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  testMatch: '**/welcome.spec.ts',
  use: { baseURL: 'http://127.0.0.1:5176', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --mode test --port 5176',
    url: 'http://127.0.0.1:5176',
    reuseExistingServer: false,
    env: { VITE_DATA_MODE: 'demo', VITE_GUIDE_ENABLED: 'true', VITE_SPLASH_ENABLED: 'true' },
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
})
