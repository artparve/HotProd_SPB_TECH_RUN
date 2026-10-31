import { expect, test } from '@playwright/test'

const outage = process.env.REPAIRCALC_OUTAGE

test(`UI remains usable when ${outage} is unavailable`, async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  if (outage === 'mongo' || outage === 'backend') {
    await page.goto('/product')
    await expect(page.getByRole('heading', { name: 'Каталог недоступен или пуст' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Рассчитать упаковки' })).toHaveCount(0)
    await page.goto('/')
    const response = page.waitForResponse((value) => value.url().endsWith('/api/v1/estimate'))
    await page.getByRole('button', { name: 'Подобрать материалы' }).click()
    expect((await response).status()).toBe(503)
    await expect(page.locator('.main-panel form > [role="alert"]')).toContainText('HTTP 503')
    await expect(page.getByRole('button', { name: 'Сохранить', exact: true })).toHaveCount(0)
  } else {
    await page.goto('/product?id=ac1f222a-f5e9-3b5d-a91e-c943b053e92e')
    const response = page.waitForResponse((value) =>
      value.url().endsWith('/api/v1/materials/estimate'),
    )
    await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
    const result = await (await response).json()
    expect(result.explanationStatus).toBe('unavailable')
    expect(result.estimate.total).toBe('34560.00')
    await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
    await expect(page.getByText(/Смета рассчитана, но пояснение ИИ недоступно/)).toBeVisible()
    await page.goto('/room')
    const length = await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).inputValue()
    const analysis = page.waitForResponse((value) => value.url().endsWith('/test-interior/analyze'))
    await page.locator('.inspiration-card-body button').click()
    expect((await (await analysis).json()).status).toBe('unavailable')
    await expect(page).toHaveURL('/room')
    await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue(length)
  }
  expect(errors).toEqual([])
})
