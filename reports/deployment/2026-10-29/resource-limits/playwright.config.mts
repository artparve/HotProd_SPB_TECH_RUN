import base from '../../../../frontend/playwright.config.ts'
import { fileURLToPath } from 'node:url'

const local = (path: string) => fileURLToPath(new URL(path, import.meta.url))
export default {
  ...base,
  testDir: local('../../../../frontend/tests'),
  outputDir: local('./e2e-artifacts'),
  workers: 1,
  reporter: [['list'], ['json', { outputFile: local('./e2e-result.json') }]],
  use: {
    ...base.use,
    launchOptions: { executablePath: '/opt/google/chrome/chrome' },
  },
  webServer: {
    ...base.webServer,
    cwd: local('../../../../frontend'),
  },
}
