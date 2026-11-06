import { defineConfig, devices } from '@playwright/test'

if (!process.env.REPAIRCALC_STACK_URL) throw new Error('Run through scripts/verify-stack.py')

export default defineConfig({
  testDir: './tests/stack',
  testMatch: process.env.REPAIRCALC_OUTAGE
    ? '**/outages.spec.ts'
    : process.env.REPAIRCALC_PRODUCTION_GALLERY === '1'
      ? '**/gallery.spec.ts'
      : '**/calculators.spec.ts',
  workers: 1,
  timeout: 60000,
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.REPAIRCALC_STACK_URL,
    storageState: {
      cookies: [
        {
          name: 'repaircalc_guide_v1',
          value: 'done',
          domain: new URL(process.env.REPAIRCALC_STACK_URL).hostname,
          path: '/',
          expires: -1,
          httpOnly: false,
          secure: false,
          sameSite: 'Lax',
        },
      ],
      origins: [],
    },
    trace: 'retain-on-failure',
  },
})
