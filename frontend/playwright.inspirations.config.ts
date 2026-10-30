import { defineConfig, devices } from '@playwright/test'

// Dedicated API-mode server. All API calls are intercepted by tests/mocks/inspirations.ts.
export default defineConfig({
  testDir: './tests',
  testMatch: '**/inspirations.api.spec.ts',
  use: { baseURL: 'http://127.0.0.1:5174', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --mode test --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    env: { VITE_DATA_MODE: 'api' },
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
})
