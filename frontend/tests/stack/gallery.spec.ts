import { expect, test } from '@playwright/test'

test('production gallery serves five photos per category from the bundled image', async ({
  page,
}) => {
  test.skip(process.env.REPAIRCALC_PRODUCTION_GALLERY !== '1', 'Requires the production manifest')
  await page.goto('/room')
  const length = page.getByRole('spinbutton', { name: 'Длина м', exact: true })
  const originalLength = await length.inputValue()
  await expect(page.locator('.inspiration-card')).toHaveCount(20)
  const categories = page.getByRole('group', { name: 'Категории интерьеров' })
  for (const [label, prefix] of [
    ['Гостиные', 'living-room-'],
    ['Спальни', 'bedroom-'],
    ['Кухни', 'kitchen-'],
    ['Ванные', 'bathroom-'],
  ] as const) {
    await categories.getByRole('button', { name: label, exact: true }).click()
    await expect(page.locator('.inspiration-card')).toHaveCount(5)
    await expect
      .poll(async () =>
        page
          .locator('.inspiration-card img')
          .evaluateAll(
            (images, idPrefix) =>
              images.every((image) => image.getAttribute('src')?.includes('/' + idPrefix)),
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
  await categories.getByRole('button', { name: 'Все', exact: true }).click()
  await expect(page.locator('.inspiration-card')).toHaveCount(20)
  await expect(length).toHaveValue(originalLength)
})
