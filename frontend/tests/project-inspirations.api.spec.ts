import { expect, test } from '@playwright/test'
import { newRoom } from '../src/domain/types'
import { mockInspirationApi } from './mocks/inspirations'

test('general calculation applies interiors to the active room and preserves the other rooms and selections', async ({
  page,
}) => {
  const project = {
    object: 'house',
    renovation: 'designer',
    rooms: [
      { ...newRoom(), id: 'room-first', name: 'Первая комната', length: 8 },
      { ...newRoom(2), id: 'room-second', name: 'Вторая комната', length: 6 },
    ],
  }
  await page.addInitScript(
    (value) =>
      localStorage.setItem(
        'repaircalc-workspace-v1',
        JSON.stringify({ project: value, saved: [], cart: null }),
      ),
    project,
  )
  const requests = await mockInspirationApi(page)
  await page.goto('/')
  const gallery = page.locator('.main-panel .room-editor > .inspiration-gallery')
  await expect(gallery).toBeVisible()
  await gallery
    .locator('.inspiration-card')
    .nth(1)
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
  await expect(page).toHaveURL('/')
  let analysis = requests.filter((request) => request.url().endsWith('/analyze')).at(-1)!
  expect(analysis.postDataJSON().project.rooms).toEqual([project.rooms[0]])
  let estimation = requests
    .filter((request) => new URL(request.url()).pathname === '/api/v1/estimate')
    .at(-1)!
    .postDataJSON()
  expect(estimation.project.rooms).toHaveLength(2)
  expect(estimation.selections['room-first:covering']).toBe('demo-tile')
  expect(estimation.project.rooms[1]).toEqual(project.rooms[1])

  await page.getByRole('button', { name: 'Параметры', exact: true }).click()
  await page
    .getByRole('group', { name: 'Помещения проекта' })
    .getByRole('button', { name: 'Вторая комната' })
    .click()
  await page.getByRole('spinbutton', { name: 'Длина м', exact: true }).fill('7')
  await gallery
    .locator('.inspiration-card')
    .nth(2)
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page.getByRole('heading', { name: 'Материалы для вашего проекта' })).toBeVisible()
  analysis = requests.filter((request) => request.url().endsWith('/analyze')).at(-1)!
  expect(analysis.postDataJSON().project.rooms).toHaveLength(1)
  expect(analysis.postDataJSON().project.rooms[0]).toMatchObject({ id: 'room-second', length: 7 })
  estimation = requests
    .filter((request) => new URL(request.url()).pathname === '/api/v1/estimate')
    .at(-1)!
    .postDataJSON()
  expect(estimation.project.rooms).toHaveLength(2)
  expect(estimation.project.rooms[0]).toEqual(project.rooms[0])
  expect(estimation.project.rooms[1]).toMatchObject({
    id: 'room-second',
    length: 7,
    works: ['floor', 'wallpaper'],
  })
  expect(estimation.selections).toMatchObject({
    'room-first:covering': 'demo-tile',
    'room-first:paint': 'demo-paint',
    'room-second:covering': 'demo-laminate',
    'room-second:wallpaper': 'demo-wallpaper',
  })
  const persisted = await page.evaluate(
    () => JSON.parse(localStorage.getItem('repaircalc-workspace-v1')!).project,
  )
  expect(persisted).toEqual(estimation.project)
  expect(
    requests.some((request) => new URL(request.url()).pathname === '/api/v1/recommendations'),
  ).toBe(false)
})

test('a failed full-project recalculation does not partially apply the selected interior', async ({
  page,
}) => {
  const project = {
    object: 'apartment',
    renovation: 'capital',
    rooms: [
      { ...newRoom(), id: 'room-first' },
      { ...newRoom(2), id: 'room-second' },
    ],
  }
  await page.addInitScript(
    (value) =>
      localStorage.setItem(
        'repaircalc-workspace-v1',
        JSON.stringify({ project: value, saved: [], cart: null }),
      ),
    project,
  )
  await mockInspirationApi(page)
  await page.route('**/api/v1/estimate', async (route) => {
    if (route.request().postDataJSON().project.rooms.length > 1)
      await route.fulfill({
        status: 503,
        json: { code: 'unavailable', message: 'Расчёт проекта недоступен' },
      })
    else await route.fallback()
  })
  await page.goto('/')
  await page
    .locator('.inspiration-card')
    .nth(2)
    .getByRole('button', { name: 'Выбрать интерьер' })
    .click()
  await expect(page.getByRole('alert')).toContainText('HTTP 503')
  await expect(page.getByRole('heading', { name: 'Расскажите о вашем проекте' })).toBeVisible()
  expect(
    await page.evaluate(() => JSON.parse(localStorage.getItem('repaircalc-workspace-v1')!).project),
  ).toEqual(project)
  await expect(page.getByRole('checkbox', { name: 'Покраска стен', exact: true })).toBeChecked()
  await expect(
    page.getByRole('checkbox', { name: 'Поклейка обоев', exact: true }),
  ).not.toBeChecked()
})
