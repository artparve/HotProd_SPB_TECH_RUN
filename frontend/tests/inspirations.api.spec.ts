import { expect, test } from '@playwright/test'
import { mockInspirationApi } from './mocks/inspirations'
import { planImageBase64 } from './mocks/roomPlan'
import { newRoom } from '../src/domain/types'

test('backend feed, large preview and photo selection lead to the ordinary calculator', async ({
  page,
  isMobile,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const requests = await mockInspirationApi(page)
  await page.goto('/')
  await expect(page.locator('.inspiration-gallery')).toHaveCount(1)
  await page.goto('/room')
  await page.getByRole('button', { name: 'Жилая комната', exact: true }).click()
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('5')
  await page.getByRole('spinbutton', { name: 'Ширина м', exact: true }).fill('4')
  const gallery = page.getByRole('region', { name: 'Вдохновитесь готовой комнатой' })
  await expect(page.locator('.main-panel .room-editor > .inspiration-gallery')).toHaveCount(1)
  expect(
    await gallery.evaluate((element) =>
      element.previousElementSibling?.classList.contains('plan-import'),
    ),
  ).toBe(true)
  await expect(gallery.locator('.inspiration-card')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  const inspect = gallery.getByRole('button', { name: 'Рассмотреть: Тёплая гостиная' })
  if (!isMobile) {
    await inspect.hover()
    await expect(page.locator('.inspiration-hover-preview')).toBeVisible()
    const width = await page
      .locator('.inspiration-hover-preview img')
      .evaluate((image) => image.getBoundingClientRect().width)
    expect(width).toBeGreaterThan(300)
    expect(width).toBeLessThanOrEqual(400)
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
  expect(
    requests.some((request) => new URL(request.url()).pathname === '/api/v1/recommendations'),
  ).toBe(false)
  expect(errors).toEqual([])
})

test('failed recognition preserves the project and can be retried', async ({ page }) => {
  const options = { failAnalysis: true }
  await mockInspirationApi(page, options)
  await page.goto('/room')
  await page.getByRole('button', { name: 'Жилая комната', exact: true }).click()
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('6')
  const select = page
    .locator('.inspiration-card')
    .first()
    .getByRole('button', { name: 'Выбрать интерьер' })
  await select.click()
  await expect(page.getByRole('alert')).toContainText('HTTP 503')
  await expect(page).toHaveURL('/room')
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
  await page.goto('/room')
  await page
    .locator('.inspiration-card')
    .first()
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page.getByRole('alert')).toContainText('Размеры нельзя определять по фото')
  await expect(page).toHaveURL('/room')
  await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue('3')
})

test('empty feed leaves the manual calculator usable', async ({ page }) => {
  await mockInspirationApi(page, { empty: true })
  await page.goto('/room')
  await expect(page.getByText('Изображений пока нет.', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Найти материалы' }).click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашей комнаты' })).toBeVisible()
})

test('loads more rooms with a cursor and no duplicate cards', async ({ page }) => {
  const requests = await mockInspirationApi(page, { paginate: true })
  await page.goto('/room')
  await expect(page.locator('.inspiration-card')).toHaveCount(1)
  await page.getByRole('button', { name: 'Ещё комнаты' }).click()
  await expect(page.locator('.inspiration-card')).toHaveCount(3)
  expect(
    requests.some((request) => new URL(request.url()).searchParams.get('cursor') === 'next/+page'),
  ).toBe(true)
  await expect(page.getByRole('button', { name: 'Ещё комнаты' })).toHaveCount(0)
})

for (const mode of ['uploaded-only', 'applied', 'edited-after-apply'] as const) {
  test(`interior uses current room fields, not unapplied plan or another project: ${mode}`, async ({
    page,
  }) => {
    const stored = {
      project: {
        object: 'house',
        renovation: 'designer',
        rooms: [
          { ...newRoom(), id: 'project-first', name: 'Другая комната', length: 9 },
          { ...newRoom(2), id: 'project-second', length: 8 },
        ],
      },
      saved: [],
      cart: null,
    }
    await page.addInitScript(
      (value) => localStorage.setItem('repaircalc-workspace-v1', JSON.stringify(value)),
      stored,
    )
    const requests = await mockInspirationApi(page)
    await page.goto('/room')
    await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('6')
    await page.getByRole('spinbutton', { name: 'Ширина м', exact: true }).fill('4')
    await page.getByText('Заполнить по плану', { exact: false }).click()
    await page.getByLabel('Выбрать изображение', { exact: true }).setInputFiles({
      name: 'room-plan.png',
      mimeType: 'image/png',
      buffer: Buffer.from(planImageBase64, 'base64'),
    })
    await expect(page.getByAltText('Загруженный план комнаты')).toBeVisible()
    await page.getByRole('button', { name: 'Распознать размеры', exact: true }).click()
    await expect(page.getByLabel('Длина по плану', { exact: true })).toHaveValue('5.32')
    if (mode !== 'uploaded-only') {
      await page.getByLabel('Высота потолка по плану', { exact: true }).fill('3')
      await page.getByLabel('Площадь окон и дверей по плану', { exact: true }).fill('1')
      await page
        .getByRole('checkbox', { name: 'Я проверил размеры, единицы и площадь проёмов' })
        .check()
      await page.getByRole('button', { name: 'Применить параметры', exact: true }).click()
      await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue(
        '5.32',
      )
    }
    if (mode === 'edited-after-apply')
      await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('7')
    await page
      .locator('.inspiration-card')
      .first()
      .getByRole('button', { name: 'Выбрать интерьер' })
      .click()
    await expect(page).toHaveURL('/calculator')
    const recognition = requests.find((request) => request.url().endsWith('/plans/extract'))!
    expect(recognition.postDataJSON()).toEqual({ imageBase64: planImageBase64 })
    const body = requests.find((request) => request.url().endsWith('/analyze'))!.postDataJSON()
    expect(Object.keys(body)).toEqual(['project'])
    expect(body.project.rooms).toHaveLength(1)
    const room = body.project.rooms[0]
    expect(room.id).not.toBe('project-first')
    expect(room).toMatchObject(
      mode === 'uploaded-only'
        ? { length: 6, width: 4, height: 2.7, openings: 3.5 }
        : { length: mode === 'applied' ? 5.32 : 7, width: 3.32, height: 3, openings: 1 },
    )
    const estimation = requests
      .find((request) => new URL(request.url()).pathname === '/api/v1/estimate')!
      .postDataJSON()
    expect(estimation.project.rooms[0]).toMatchObject(room)
    expect(Object.keys(estimation.selections).every((key) => key.startsWith(`${room.id}:`))).toBe(
      true,
    )
    expect(
      await page.evaluate(
        () => JSON.parse(localStorage.getItem('repaircalc-workspace-v1')!).project,
      ),
    ).toEqual(stored.project)
    const menu = page.getByRole('button', { name: 'Открыть навигацию' })
    if (await menu.isVisible()) await menu.click()
    await page.getByRole('link', { name: 'RepairCalc — главная' }).click()
    await expect(page).toHaveURL('/')
    await expect(page.locator('.inspiration-gallery')).toHaveCount(1)
    await expect(page.getByRole('spinbutton', { name: 'Длина м', exact: true })).toHaveValue('9')
    await expect(page.getByRole('region', { name: 'Подбор по изображению' })).toHaveCount(0)
  })
}
