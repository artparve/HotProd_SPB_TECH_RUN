import { expect, test } from '@playwright/test'
import { mockInspirationApi } from './mocks/inspirations'

test('backend feed, large preview and photo selection lead to the ordinary calculator', async ({
  page,
  isMobile,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const requests = await mockInspirationApi(page)
  await page.goto('/')
  const gallery = page.getByRole('region', { name: 'Вдохновитесь готовой комнатой' })
  await expect(gallery.locator('.inspiration-card')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  const inspect = gallery.getByRole('button', { name: 'Рассмотреть: Тёплая гостиная' })
  if (!isMobile) {
    await inspect.hover()
    await expect(page.locator('.inspiration-hover-preview')).toBeVisible()
    const width = await page
      .locator('.inspiration-hover-preview img')
      .evaluate((image) => image.getBoundingClientRect().width)
    expect(width).toBeGreaterThan(500)
  }
  await inspect.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await gallery
    .locator('.inspiration-card')
    .nth(1)
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page).toHaveURL('/calculator')
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Подбор по изображению' })).toContainText(
    'Синий акцент',
  )
  await expect(page.getByRole('heading', { name: /Керамогранит/ })).toBeVisible()
  const analysis = requests.find((request) => request.url().endsWith('/analyze'))!
  expect(analysis.method()).toBe('POST')
  const room = analysis.postDataJSON().project.rooms[0]
  expect(room).toMatchObject({ length: 5, width: 4, height: 2.7 })
  const calculation = requests.find(
    (request) => new URL(request.url()).pathname === '/api/v1/estimate',
  )!
  expect(calculation.postDataJSON().selections[`${room.id}:covering`]).toBe('demo-tile')
  await page.getByLabel(/Альтернатива: Керамогранит/).selectOption('demo-laminate')
  await expect(page.getByRole('heading', { name: /Ламинат/ })).toBeVisible()
  await page.getByRole('button', { name: 'Рассчитать смету' }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  const lastCalculation = requests
    .filter((request) => new URL(request.url()).pathname === '/api/v1/estimate')
    .at(-1)!
  expect(lastCalculation.postDataJSON().selections[`${room.id}:covering`]).toBe('demo-laminate')
  expect(errors).toEqual([])
})

test('failed recognition preserves the project and can be retried', async ({ page }) => {
  const options = { failAnalysis: true }
  await mockInspirationApi(page, options)
  await page.goto('/')
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('6')
  const select = page
    .locator('.inspiration-card')
    .first()
    .getByRole('button', { name: 'Выбрать интерьер' })
  await select.click()
  await expect(page.getByRole('alert')).toContainText('HTTP 503')
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue('6')
  options.failAnalysis = false
  await select.click()
  await expect(page).toHaveURL('/calculator')
  await page.getByRole('button', { name: 'Параметры', exact: true }).click()
  await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue('6')
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('7')
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await expect(page.getByRole('heading', { name: /Ламинат/ })).toBeVisible()
})

test('rejects photo-derived dimensions without replacing the existing calculator', async ({
  page,
}) => {
  await mockInspirationApi(page, { corruptDimensions: true })
  await page.goto('/')
  await page
    .locator('.inspiration-card')
    .first()
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page.getByRole('alert')).toContainText('Размеры нельзя определять по фото')
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue('5')
})

test('empty feed leaves the manual calculator usable', async ({ page }) => {
  await mockInspirationApi(page, { empty: true })
  await page.goto('/')
  await expect(page.getByText('Изображений пока нет.', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
})

test('loads more rooms with a cursor and no duplicate cards', async ({ page }) => {
  const requests = await mockInspirationApi(page, { paginate: true })
  await page.goto('/')
  await expect(page.locator('.inspiration-card')).toHaveCount(1)
  await page.getByRole('button', { name: 'Ещё комнаты' }).click()
  await expect(page.locator('.inspiration-card')).toHaveCount(3)
  expect(
    requests.some((request) => new URL(request.url()).searchParams.get('cursor') === 'next/+page'),
  ).toBe(true)
  await expect(page.getByRole('button', { name: 'Ещё комнаты' })).toHaveCount(0)
})
