import { test, expect } from '../../../frontend/node_modules/@playwright/test/index.js'

test('public server uses API and calculates original paint without LLM', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const catalog = await page.request.get('/api/v1/products')
  expect(catalog.status()).toBe(200)
  const payload = await catalog.json()
  const products = Array.isArray(payload) ? payload : payload.items
  expect(products.length).toBeGreaterThanOrEqual(12)
  const paint = products.find((p: any) => p.kind === 'paint' && Number(p.consumption) > 0)
  expect(paint).toBeTruthy()
  await page.goto('/product?id=' + paint.id)
  await expect(page.getByRole('combobox', { name: 'Товар', exact: true })).toHaveValue(paint.id)
  await page.getByLabel('Добавить пояснение ИИ').uncheck()
  const response = page.waitForResponse(r => r.url().includes('/calc') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  const calculated = await response
  expect(calculated.status()).toBe(200)
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.getByText('Как рассчитано').click()
  await page.screenshot({ path: info.outputPath('paint.png'), fullPage: true })
  await info.attach('calculation', { body: await calculated.body(), contentType: 'application/json' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
})

test('public server refuses unknown consumption and recovers invalid product link', async ({ page }) => {
  const response = await page.request.get('/api/v1/products')
  const payload = await response.json()
  const products = Array.isArray(payload) ? payload : payload.items
  const unknown = products.find((p: any) => p.consumption == null && p.kind === 'paint')
  expect(unknown).toBeTruthy()
  await page.goto('/product?id=' + unknown.id)
  await page.getByLabel('Добавить пояснение ИИ').uncheck()
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  await expect(page.getByRole('alert')).toContainText(/норм|расход/)
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toHaveCount(0)
  await page.goto('/product?id=not-an-existing-product')
  await expect(page.getByRole('status')).toContainText('Товар по ссылке не найден')
})

test('public layout, navigation and invalid geometry', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Калькулятор ремонта')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByLabel('Окна и двери').fill('100')
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await expect(page.getByRole('alert')).toContainText('Площадь проёмов')
})
