import { defineConfig, devices } from '@playwright/test'

if (!process.env.REPAIRCALC_STACK_URL) throw new Error('Run through scripts/verify-stack.py')

export default defineConfig({
  testDir: './tests/stack',
  testMatch: process.env.REPAIRCALC_OUTAGE ? '**/outages.spec.ts' : '**/calculators.spec.ts',
  workers: 1,
  timeout: 60000,
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.REPAIRCALC_STACK_URL,
    trace: 'retain-on-failure',
  },
})
