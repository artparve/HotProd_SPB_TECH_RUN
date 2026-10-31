import { expect, test } from '@playwright/test'

test('real Java estimate, technology replacement and saved result', async ({ page }) => {
  await page.goto('/')
  const first = page.waitForResponse((response) => response.url().endsWith('/api/v1/estimate'))
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  expect((await (await first).json()).mode).toBe('api')
  await page
    .getByLabel(/Альтернатива: Ламинат/)
    .selectOption('ac1f222a-f5e9-3b5d-a91e-c943b053e92e')
  await expect(page.getByRole('heading', { name: /Клей плиточный/ })).toBeVisible()
  await page.getByRole('button', { name: 'Рассчитать смету' }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await page.goto('/saved')
  await expect(page.getByRole('heading', { name: /^Смета от/ })).toBeVisible()
})

test('real product calculation and Python explanation preserve the Java total', async ({
  page,
}) => {
  await page.goto('/product?id=ac1f222a-f5e9-3b5d-a91e-c943b053e92e')
  const response = page.waitForResponse((value) =>
    value.url().endsWith('/api/v1/materials/estimate'),
  )
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  const result = await (await response).json()
  expect(result.estimate.total).toBe('34560.00')
  expect(result.explanationStatus).toBe('unverified_model_text')
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await expect(page.getByText('Тестовое пояснение', { exact: false })).toBeVisible()
})

test('image from real feed goes through Python and keeps room dimensions', async ({ page }) => {
  await page.goto('/room')
  await expect(page.getByRole('link', { name: 'Источник: Pexels' })).toBeVisible()
  await expect
    .poll(() =>
      page
        .locator('.inspiration-card img')
        .evaluate((image) => (image as HTMLImageElement).naturalWidth),
    )
    .toBeGreaterThan(0)
  const length = await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).inputValue()
  const response = page.waitForResponse((value) => value.url().endsWith('/test-interior/analyze'))
  await page.locator('.inspiration-card-body button').click()
  const result = await (await response).json()
  expect(result.status).toBe('ok')
  expect(String(result.project.rooms[0].length)).toBe(length)
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
  expect(page.url()).toContain('/calculator')
})
