# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: remote-admin.spec.ts >> admin panel performs real classification, human review, publication and Java calculation
- Location: reports/admin-workflow/2026-10-30-recheck/remote-admin.spec.ts:69:5

# Error details

```
Error: locator.selectOption: Test ended.
Call log:
  - waiting for locator('.workflow form fieldset').first().getByLabel('Решение', { exact: true })

```

# Test source

```ts
  1   | import { test, expect } from '../../../frontend/node_modules/@playwright/test/index.js'
  2   | import { readFileSync, writeFileSync } from 'node:fs'
  3   | import { join } from 'node:path'
  4   | 
  5   | test.use({ trace: 'off' }) // Never record administrator credentials.
  6   | const evidence = join(process.cwd(), 'reports/admin-workflow/2026-10-30-recheck')
  7   | const credentials = () => JSON.parse(readFileSync('/tmp/repaircalc-admin-recheck.json', 'utf8'))
  8   | async function login(page: any) {
  9   |   await page.goto('/admin')
  10  |   await page.getByLabel('Имя администратора').fill(credentials().username)
  11  |   await page.getByLabel('Пароль', { exact: true }).fill(credentials().password)
  12  |   await page.getByRole('button', { name: 'Войти', exact: true }).click()
  13  |   await expect(page.getByRole('link', { name: 'Новая карточка', exact: true })).toBeVisible()
  14  | }
  15  | async function createPaint(page: any, suffix: string) {
  16  |   await page.getByRole('link', { name: 'Новая карточка', exact: true }).click()
  17  |   await page.getByLabel('Источник идентификатора', { exact: true }).fill('server-ui-recheck')
  18  |   await page.getByLabel('Внешний идентификатор', { exact: true }).fill(suffix)
  19  |   await page.getByLabel('Название', { exact: true }).fill('Учебная краска UI ' + suffix)
  20  |   await page.getByLabel('Описание', { exact: true }).fill('Краска для стен внутри помещений по бетону и штукатурке. Расход 0.12 л/м² на слой. Банка 2.5 л. Влагостойкость не заявлена.')
  21  |   const fields: Record<string, string> = { category: 'Краски', kind: 'paint', price: '1234.50', currency: 'RUB', priceDate: '2026-10-30', unit: 'л', saleUnit: 'бан.', packageSize: '2.5', consumption: '0.12', consumptionUnit: 'л' }
  22  |   for (const [key, value] of Object.entries(fields)) {
  23  |     await page.locator('#state-' + key).selectOption('confirmed')
  24  |     const input = page.locator('#value-' + key)
  25  |     if (await input.evaluate((e: Element) => e.tagName === 'SELECT')) await input.selectOption(value)
  26  |     else await input.fill(value)
  27  |     await page.locator('#source-' + key).fill('Учебные данные повторной приёмки 30.10.2026')
  28  |   }
  29  |   await page.getByRole('button', { name: 'Добавить источник', exact: true }).click()
  30  |   await page.getByLabel('Название источника', { exact: true }).fill('Учебная карточка приёмки')
  31  |   await page.getByRole('button', { name: 'Создать черновик', exact: true }).click()
  32  |   await expect(page.getByRole('heading', { name: 'Карточка · версия 1', exact: true })).toBeVisible()
  33  |   return page.url().split('/').pop()
  34  | }
  35  | 
  36  | test('admin UI creates, searches, versions, handles concurrent editing and logs out', async ({ page, context }, info) => {
  37  |   const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  38  |   await login(page)
  39  |   const suffix = info.project.name + '-' + Date.now()
  40  |   const id = await createPaint(page, suffix)
  41  |   const other = await context.newPage()
  42  |   await other.goto('/admin/products/' + id)
  43  |   await expect(other.getByRole('heading', { name: 'Карточка · версия 1', exact: true })).toBeVisible()
  44  |   await page.getByLabel('Название', { exact: true }).fill('Изменена ' + suffix)
  45  |   await page.getByRole('button', { name: 'Сохранить новую версию', exact: true }).click()
  46  |   await expect(page.getByRole('heading', { name: 'Карточка · версия 2', exact: true })).toBeVisible()
  47  |   await other.getByLabel('Название', { exact: true }).fill('Несохранённое изменение')
  48  |   await other.getByRole('button', { name: 'Сохранить новую версию', exact: true }).click()
  49  |   await expect(other.getByRole('alert')).toContainText('Ваши поля остаются в форме')
  50  |   await expect(other.getByLabel('Название', { exact: true })).toHaveValue('Несохранённое изменение')
  51  |   await other.getByRole('button', { name: 'Перечитать и заменить поля формы' }).click()
  52  |   await expect(other.getByLabel('Название', { exact: true })).toHaveValue('Изменена ' + suffix)
  53  |   await other.close()
  54  |   await page.getByRole('button', { name: 'Загрузить историю', exact: true }).click()
  55  |   await expect(page.locator('.admin-history summary')).toHaveCount(2)
  56  |   await page.getByRole('button', { name: 'Загрузить задания и готовность' }).click()
  57  |   await expect(page.getByRole('heading', { name: 'Публикация заблокирована' })).toBeVisible()
  58  |   await expect(page.getByRole('button', { name: 'Опубликовать снимок каталога и правил' })).toBeDisabled()
  59  |   expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  60  |   await page.locator('.workflow').screenshot({ path: join(evidence, 'admin-panel-' + info.project.name + '.png') })
  61  |   await page.screenshot({ path: info.outputPath('admin-panel.png'), fullPage: true })
  62  |   await page.getByRole('link', { name: 'Все карточки', exact: true }).click()
  63  |   await page.getByLabel('Название или внешний ID').fill(suffix)
  64  |   await page.locator('.admin-filters').getByRole('button').click()
  65  |   await expect(page.getByRole('link', { name: 'Изменена ' + suffix, exact: true })).toBeVisible()
  66  |   await page.getByRole('button', { name: /^Выйти/ }).click()
  67  |   await expect(page.getByRole('heading', { name: 'Вход администратора' })).toBeVisible()
  68  |   expect(errors).toEqual([])
  69  | })
  70  | 
  71  | test('admin panel performs real classification, human review, publication and Java calculation', async ({ page }, info) => {
  72  |   test.skip(info.project.name !== 'desktop', 'Real inference once; mobile panel is tested separately.')
  73  |   test.setTimeout(900_000)
  74  |   await login(page)
  75  |   const id = await createPaint(page, 'workflow-' + Date.now())
  76  |   writeFileSync(join(evidence, 'remote-workflow-id.txt'), id)
  77  |   await page.getByRole('button', { name: 'Загрузить задания и готовность' }).click()
  78  |   await page.getByRole('button', { name: 'Запустить классификацию' }).click()
  79  |   await expect(page.getByRole('button', { name: 'Запустить классификацию' })).toBeDisabled()
  80  |   await expect(page.getByRole('button', { name: 'Проверить предложения' })).toBeVisible({ timeout: 750_000 })
  81  |   await page.getByRole('button', { name: 'Проверить предложения' }).click()
  82  |   const rows = page.locator('.workflow form fieldset')
  83  |   expect(await rows.count()).toBeGreaterThan(2)
  84  |   for (const row of await rows.all()) {
  85  |     const legend = await row.locator('legend').innerText()
  86  |     if (legend.startsWith('properties')) {
  87  |       await row.getByLabel('Решение', { exact: true }).selectOption('correct')
> 88  |       await row.getByLabel('Значение', { exact: true }).selectOption(legend.includes('interior_suitable') ? 'yes' : 'unknown')
      |                                                        ^ Error: locator.selectOption: Test ended.
  89  |     } else {
  90  |       await row.getByLabel('Решение', { exact: true }).selectOption('accept')
  91  |     }
  92  |     await row.getByLabel('Основание решения', { exact: true }).fill('Проверено по учебной карточке: внутренние стены, бетон/штукатурка. Остальные свойства не подтверждены.')
  93  |   }
  94  |   await page.getByLabel('Проверенный профиль расчёта').selectOption('paint-012-v1')
  95  |   await page.getByRole('button', { name: 'Сохранить проверку', exact: true }).click()
  96  |   await expect(page.getByRole('heading', { name: 'Готово к публикации и расчёту' })).toBeVisible()
  97  |   await page.screenshot({ path: join(evidence, 'remote-admin-ready.png'), fullPage: true })
  98  |   await page.getByRole('button', { name: 'Опубликовать снимок каталога и правил' }).click()
  99  |   await expect(page.getByRole('link', { name: 'Рассчитать опубликованный товар' })).toBeVisible()
  100 |   await page.getByRole('link', { name: 'Рассчитать опубликованный товар' }).click()
  101 |   await page.getByLabel('Добавить пояснение ИИ').uncheck()
  102 |   const calc = page.waitForResponse(r => r.url().includes('/calc') && r.request().method() === 'POST')
  103 |   await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  104 |   const response = await calc
  105 |   expect(response.status()).toBe(200)
  106 |   writeFileSync(join(evidence, 'remote-calculation.json'), JSON.stringify(await response.json(), null, 2))
  107 |   await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  108 |   await page.getByText('Как рассчитано').click()
  109 |   await expect(page.getByText('5,28 л', { exact: true })).toBeVisible()
  110 |   await expect(page.getByText(/3\s?703,50/).first()).toBeVisible()
  111 |   await page.screenshot({ path: join(evidence, 'remote-calculation.png'), fullPage: true })
  112 | })
  113 | 
```