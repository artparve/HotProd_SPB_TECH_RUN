import { expect, test } from '@playwright/test'

test('production gallery is always available and follows room type', async ({ page }) => {
  test.skip(process.env.REPAIRCALC_PRODUCTION_GALLERY !== '1', 'Requires the production manifest')
  const firstPage = await (await page.request.get('/api/v1/room-inspirations')).json()
  expect(firstPage.items).toHaveLength(24)
  expect(firstPage.nextCursor).toBeTruthy()
  const lastPage = await (
    await page.request.get(
      '/api/v1/room-inspirations?cursor=' + encodeURIComponent(firstPage.nextCursor),
    )
  ).json()
  expect(lastPage.items).toHaveLength(8)
  expect(lastPage.nextCursor).toBeNull()
  expect(new Set([...firstPage.items, ...lastPage.items].map((item) => item.id)).size).toBe(32)
  await page.goto('/room')
  await expect(page.locator('.inspiration-gallery')).toBeVisible()
  const length = page.getByRole('spinbutton', { name: 'Длина м', exact: true })
  const originalLength = await length.inputValue()
  await expect(page.locator('.inspiration-card')).toHaveCount(7)
  for (const [label, prefix, count] of [
    ['Жилая комната', 'living-room-|bedroom-', 14],
    ['Кухня', 'kitchen-', 7],
    ['Санузел', 'bathroom-', 7],
    ['Детская', 'children-', 2],
    ['Балкон', 'balcony-', 2],
  ] as const) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect(page.locator('.inspiration-card')).toHaveCount(count)
    await expect
      .poll(async () =>
        page
          .locator('.inspiration-card img')
          .evaluateAll(
            (images, idPrefix) =>
              images.every((image) =>
                new RegExp('/(' + idPrefix + ')').test(image.getAttribute('src') || ''),
              ),
            prefix,
          ),
      )
      .toBe(true)
    const image = page.locator('.inspiration-card img').first()
    await image.scrollIntoViewIfNeeded()
    await expect
      .poll(() => image.evaluate((value) => (value as HTMLImageElement).naturalWidth))
      .toBeGreaterThan(0)
    await expect(page.locator('.inspiration-card-body a').first()).toHaveText('Источник: Pexels')
  }
  await expect(length).toHaveValue(originalLength)
})
