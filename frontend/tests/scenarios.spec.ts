import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.reload()
})

test('complete estimate, alternatives, saved estimate, cart and export', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Калькулятор ремонта')
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
  await page.getByLabel(/Альтернатива: Ламинат/).selectOption('demo-tile')
  await expect(page.getByRole('heading', { name: /Клей плиточный/ })).toBeVisible()
  await page.getByRole('button', { name: 'Рассчитать смету' }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await page.getByRole('button', { name: 'Подготовить корзину' }).click()
  await page.goto('/cart')
  await expect(page.getByText('Материалы к покупке')).toBeVisible()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Экспорт корзины в JSON' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/^repaircalc-cart-.*\.json$/)
  const stream = await download.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  const json = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  expect(json.mode).toBe('demo')
  expect(json.items.length).toBe(4)
  expect(json.items.every((item: { quantity: number }) => Number.isInteger(item.quantity))).toBe(
    true,
  )
  await page.goto('/saved')
  await expect(page.getByRole('heading', { name: /^Смета от/ })).toBeVisible()
  await page.getByRole('button', { name: 'Открыть', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: /^Смета от/ })).toBeVisible()
  expect(errors).toEqual([])
})

test('bathroom recommendations exclude non-waterproof fixtures', async ({ page }) => {
  await page.goto('/room')
  await page.getByRole('button', { name: 'Найти материалы' }).click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашей комнаты' })).toBeVisible()
  const cards = page.locator('.recommendation-card')
  await expect(cards.filter({ hasText: 'Керамогранит' })).toHaveCount(1)
  await expect(cards.filter({ hasText: 'Ламинат' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Добавить комнату в проект' }).click()
  await page.goto('/')
  await expect(
    page.getByRole('group', { name: 'Помещения проекта' }).getByRole('button', { name: 'Санузел' }),
  ).toBeVisible()
})

test('product calculator rounds packages and refuses an unknown consumption', async ({ page }) => {
  await page.goto('/product?id=demo-tile')
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.getByText('Как рассчитано').click()
  await expect(page.getByText('23,04 м²', { exact: true })).toBeVisible()
  await expect(page.getByText('1,04 м²', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: 'Товар', exact: true }).selectOption('demo-unknown')
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  await expect(page.getByRole('alert')).toContainText('нормы расхода')
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toHaveCount(0)
})

test('invalid wall geometry does not advance the wizard', async ({ page }) => {
  await page.getByLabel('Окна и двери').fill('100')
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await expect(page.getByRole('alert')).toContainText('Площадь проёмов')
  await expect(page.getByRole('heading', { name: 'Расскажите о вашем проекте' })).toBeVisible()
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('')
  await expect(page.getByRole('heading', { name: 'Расскажите о вашем проекте' })).toBeVisible()
})

test('layout fits the viewport and help is keyboard dismissible', async ({ page }) => {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)
  expect(overflow).toBe(false)
  if (await page.getByRole('button', { name: 'Открыть навигацию' }).isVisible()) {
    await page.getByRole('button', { name: 'Открыть навигацию' }).click()
    await page.getByRole('button', { name: 'Как это работает' }).click()
  } else await page.getByRole('button', { name: 'Помощь', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
})

test('demo needs no external requests after local startup', async ({ page }) => {
  const external: string[] = []
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.hostname !== '127.0.0.1') {
      external.push(url.href)
      await route.abort()
    } else await route.continue()
  })
  await page.reload()
  await page.getByRole('button', { name: 'Подобрать материалы' }).click()
  await page.getByRole('button', { name: 'Рассчитать смету' }).click()
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  expect(external).toEqual([])
})

test('a corrupted local draft recovers without a blank screen', async ({ page }) => {
  await page.evaluate(() => localStorage.setItem('repaircalc-workspace-v1', '{broken-json'))
  await page.reload()
  await expect(page.getByRole('status')).toContainText('хранилище')
  await expect(page.getByRole('heading', { name: 'Расскажите о вашем проекте' })).toBeVisible()
})
