import type { Page, Request } from '@playwright/test'
import { catalog } from '../../src/data/catalog'
import { inspirationFeedMock, analyzeInspirationMock } from '../../src/data/inspirations'
import { estimateDemo } from '../../src/services/demo'
import type { Project } from '../../src/domain/types'

export async function mockInspirationApi(
  page: Page,
  options: {
    empty?: boolean
    failAnalysis?: boolean
    corruptDimensions?: boolean
    paginate?: boolean
  } = {},
) {
  const requests: Request[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    requests.push(request)
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/room-inspirations') {
      const items = options.empty
        ? []
        : options.paginate && !url.searchParams.has('cursor')
          ? inspirationFeedMock.items.slice(0, 1)
          : inspirationFeedMock.items
      await route.fulfill({
        json: {
          items,
          nextCursor: options.paginate && !url.searchParams.has('cursor') ? 'next/+page' : null,
        },
      })
    } else if (/\/room-inspirations\/[^/]+\/analyze$/.test(url.pathname)) {
      if (options.failAnalysis) {
        await route.fulfill({
          status: 503,
          json: { code: 'model_busy', message: 'Распознавание временно недоступно' },
        })
        return
      }
      const id = decodeURIComponent(url.pathname.split('/').at(-2)!)
      const result = analyzeInspirationMock(id, request.postDataJSON().project as Project)
      if (options.corruptDimensions) result.project.rooms[0]!.length = 88
      await route.fulfill({ json: result })
    } else if (url.pathname === '/api/v1/products') {
      await route.fulfill({ json: catalog })
    } else if (url.pathname === '/api/v1/estimate') {
      const { project, selections } = request.postDataJSON()
      await route.fulfill({ json: { ...estimateDemo(project, selections), mode: 'api' } })
    } else {
      await route.fulfill({
        status: 404,
        json: { code: 'unexpected_mock_request', message: url.pathname },
      })
    }
  })
  return requests
}
