import { test, expect } from '../../../frontend/node_modules/@playwright/test/index.js'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

test.use({ trace: 'off' }) // Never record administrator credentials.
const evidence = join(process.cwd(), 'reports/admin-workflow/2026-10-30-recheck')
const credentials = () => JSON.parse(readFileSync('/tmp/repaircalc-admin-recheck.json', 'utf8'))
async function login(page: any) {
  await page.goto('/admin')
  await page.getByLabel('Имя администратора').fill(credentials().username)
  await page.getByLabel('Пароль', { exact: true }).fill(credentials().password)
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Новая карточка', exact: true })).toBeVisible()
}
async function createPaint(page: any, suffix: string) {
  await page.getByRole('link', { name: 'Новая карточка', exact: true }).click()
  await page.getByLabel('Источник идентификатора', { exact: true }).fill('server-ui-recheck')
  await page.getByLabel('Внешний идентификатор', { exact: true }).fill(suffix)
  await page.getByLabel('Название', { exact: true }).fill('Учебная краска UI ' + suffix)
  await page.getByLabel('Описание', { exact: true }).fill('Краска для стен внутри помещений по бетону и штукатурке. Расход 0.12 л/м² на слой. Банка 2.5 л. Влагостойкость не заявлена.')
  const fields: Record<string, string> = { category: 'Краски', kind: 'paint', price: '1234.50', currency: 'RUB', priceDate: '2026-10-30', unit: 'л', saleUnit: 'бан.', packageSize: '2.5', consumption: '0.12', consumptionUnit: 'л' }
  for (const [key, value] of Object.entries(fields)) {
    await page.locator('#state-' + key).selectOption('confirmed')
    const input = page.locator('#value-' + key)
    if (await input.evaluate((e: Element) => e.tagName === 'SELECT')) await input.selectOption(value)
    else await input.fill(value)
    await page.locator('#source-' + key).fill('Учебные данные повторной приёмки 30.10.2026')
  }
  await page.getByRole('button', { name: 'Добавить источник', exact: true }).click()
  await page.getByLabel('Название источника', { exact: true }).fill('Учебная карточка приёмки')
  await page.getByRole('button', { name: 'Создать черновик', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Карточка · версия 1', exact: true })).toBeVisible()
  return page.url().split('/').pop()
}

test('admin UI creates, searches, versions, handles concurrent editing and logs out', async ({ page, context }, info) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  await login(page)
  const suffix = info.project.name + '-' + Date.now()
  const id = await createPaint(page, suffix)
  const other = await context.newPage()
  await other.goto('/admin/products/' + id)
  await expect(other.getByRole('heading', { name: 'Карточка · версия 1', exact: true })).toBeVisible()
  await page.getByLabel('Название', { exact: true }).fill('Изменена ' + suffix)
  await page.getByRole('button', { name: 'Сохранить новую версию', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Карточка · версия 2', exact: true })).toBeVisible()
  await other.getByLabel('Название', { exact: true }).fill('Несохранённое изменение')
  await other.getByRole('button', { name: 'Сохранить новую версию', exact: true }).click()
  await expect(other.getByRole('alert')).toContainText('Ваши поля остаются в форме')
  await expect(other.getByLabel('Название', { exact: true })).toHaveValue('Несохранённое изменение')
  await other.getByRole('button', { name: 'Перечитать и заменить поля формы' }).click()
  await expect(other.getByLabel('Название', { exact: true })).toHaveValue('Изменена ' + suffix)
  await other.close()
  await page.getByRole('button', { name: 'Загрузить историю', exact: true }).click()
  await expect(page.locator('.admin-history summary')).toHaveCount(2)
  await page.getByRole('button', { name: 'Загрузить задания и готовность' }).click()
  await expect(page.getByRole('heading', { name: 'Публикация заблокирована' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Опубликовать снимок каталога и правил' })).toBeDisabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.locator('.workflow').screenshot({ path: join(evidence, 'admin-panel-' + info.project.name + '.png') })
  await page.screenshot({ path: info.outputPath('admin-panel.png'), fullPage: true })
  await page.getByRole('link', { name: 'Все карточки', exact: true }).click()
  await page.getByLabel('Название или внешний ID').fill(suffix)
  await page.locator('.admin-filters').getByRole('button').click()
  await expect(page.getByRole('link', { name: 'Изменена ' + suffix, exact: true })).toBeVisible()
  await page.getByRole('button', { name: /^Выйти/ }).click()
  await expect(page.getByRole('heading', { name: 'Вход администратора' })).toBeVisible()
  expect(errors).toEqual([])
})

test('admin panel performs real classification, human review, publication and Java calculation', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'Real inference once; mobile panel is tested separately.')
  test.setTimeout(900_000)
  await login(page)
  const resume = process.env.RECHECK_RESUME_WORKFLOW === '1'
  const id = resume ? readFileSync(join(evidence, 'remote-workflow-id.txt'), 'utf8').trim() : await createPaint(page, 'workflow-' + Date.now())
  if (resume) await page.goto('/admin/products/' + id)
  else writeFileSync(join(evidence, 'remote-workflow-id.txt'), id)
  await page.getByRole('button', { name: 'Загрузить задания и готовность' }).click()
  if (!resume) {
    await page.getByRole('button', { name: 'Запустить классификацию' }).click()
    await expect(page.getByRole('button', { name: 'Запустить классификацию' })).toBeDisabled()
  }
  await expect(page.getByRole('button', { name: 'Проверить предложения' })).toBeVisible({ timeout: 750_000 })
  await page.getByRole('button', { name: 'Проверить предложения' }).click()
  const rows = page.locator('.workflow form fieldset')
  expect(await rows.count()).toBeGreaterThan(2)
  for (const row of await rows.all()) {
    const legend = await row.locator('legend').innerText()
    if (legend.startsWith('properties')) {
      await row.getByRole('combobox', { name: 'Решение', exact: true }).selectOption('correct')
      await row.getByRole('combobox', { name: 'Значение', exact: true }).selectOption(legend.includes('interior_suitable') ? 'yes' : 'unknown')
    } else {
      await row.getByRole('combobox', { name: 'Решение', exact: true }).selectOption('accept')
    }
    await row.getByLabel('Основание решения', { exact: true }).fill('Проверено по учебной карточке: внутренние стены, бетон/штукатурка. Остальные свойства не подтверждены.')
  }
  await page.getByLabel('Проверенный профиль расчёта').selectOption('paint-012-v1')
  await page.getByRole('button', { name: 'Сохранить проверку', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Готово к публикации и расчёту' })).toBeVisible()
  await page.screenshot({ path: join(evidence, 'remote-admin-ready.png'), fullPage: true })
  await page.getByRole('button', { name: 'Опубликовать снимок каталога и правил' }).click()
  await expect(page.getByRole('link', { name: 'Рассчитать опубликованный товар' })).toBeVisible()
  await page.getByRole('link', { name: 'Рассчитать опубликованный товар' }).click()
  await page.getByLabel('Добавить пояснение ИИ').uncheck()
  const calc = page.waitForResponse(r => r.url().includes('/calc') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  const response = await calc
  expect(response.status()).toBe(200)
  writeFileSync(join(evidence, 'remote-calculation.json'), JSON.stringify(await response.json(), null, 2))
  await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  await page.getByText('Как рассчитано').click()
  await expect(page.getByText('5,28 л', { exact: true })).toBeVisible()
  await expect(page.getByText(/3\s?703,50/).first()).toBeVisible()
  await page.screenshot({ path: join(evidence, 'remote-calculation.png'), fullPage: true })
})
