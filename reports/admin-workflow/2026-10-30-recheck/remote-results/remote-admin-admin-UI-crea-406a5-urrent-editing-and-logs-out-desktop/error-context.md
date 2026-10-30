# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: remote-admin.spec.ts >> admin UI creates, searches, versions, handles concurrent editing and logs out
- Location: reports/admin-workflow/2026-10-30-recheck/remote-admin.spec.ts:36:5

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('heading', { name: 'Карточка · версия 1', exact: true })
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" getByRole('heading', { name: 'Карточка · версия 1', exact: true }) with timeout 5000ms
  - waiting for getByRole('heading', { name: 'Карточка · версия 1', exact: true })

```

```yaml
- link "Перейти к содержимому":
  - /url: "#main"
- complementary:
  - link "RepairCalc — главная":
    - /url: /
    - text: RepairCalc РЕМОНТ НАЧИНАЕТСЯ С РАСЧЁТА
  - text: ВАШ ПРОЕКТ
  - navigation "Основная навигация":
    - link "Общий расчёт":
      - /url: /
    - link "Подбор для комнаты":
      - /url: /room
    - link "Расчёт товара":
      - /url: /product
    - link "Мои сметы":
      - /url: /saved
  - link "Администрирование":
    - /url: /admin
  - heading "Меньше лишнего. Больше уверенности." [level=3]
  - paragraph: Рассчитайте материалы с запасом, а не на глаз.
  - button "Как это работает"
  - text: ПРОЕКТ ДЛЯ КЕЙСА
  - strong: МАКСИДОМ всё для дома
  - text: SPB TECH RUN · 2026
- banner:
  - text: Мой проект
  - strong: Новая карточка
  - text: Административный API
  - button "Помощь"
  - link "Корзина 0":
    - /url: /cart
- main:
  - region "Каталог · администрирование":
    - heading "Каталог · администрирование" [level=1]
    - paragraph: Рабочие карточки и история изменений.
    - button "Выйти · catalog-admin"
    - alert:
      - paragraph: Запрос не выполнен (HTTP 502).
      - button "Повторить запрос"
    - navigation "Разделы каталога":
      - link "Все карточки":
        - /url: /admin
      - link "Новая карточка":
        - /url: /admin/products/new
    - heading "Новый черновик" [level=2]
    - paragraph: Сохранение создаёт новую рабочую версию. Классификация и проверка относятся только к сохранённой версии; перед запуском сохраните изменения формы.
    - group:
      - heading "Основные сведения" [level=2]
      - text: Источник идентификатора
      - textbox "Источник идентификатора": server-ui-recheck
      - text: Внешний идентификатор
      - textbox "Внешний идентификатор": desktop-1793314605739
      - text: Название
      - textbox "Название": Учебная краска UI desktop-1793314605739
      - text: Артикул
      - textbox "Артикул"
      - text: Описание
      - textbox "Описание": Краска для стен внутри помещений по бетону и штукатурке. Расход 0.12 л/м² на слой. Банка 2.5 л. Влагостойкость не заявлена.
      - text: Область применения
      - textbox "Область применения"
      - text: Ограничения
      - textbox "Ограничения"
      - heading "Проверяемые значения" [level=2]
      - paragraph: «Не заполнено» и «Явно неизвестно» сохраняются отдельно. Для подтверждённого значения укажите источник. Сохранение фиксирует вас как автора подтверждения полей.
      - text: Категория — состояние
      - combobox "Категория — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Категория — значение
      - textbox "Категория — значение": Краски
      - text: Источник для поля «Категория»
      - textbox "Источник для поля «Категория»": Учебные данные повторной приёмки 30.10.2026
      - text: Вид товара — состояние
      - combobox "Вид товара — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Вид товара — значение
      - combobox "Вид товара — значение":
        - option "Выберите значение" [disabled]
        - option "covering"
        - option "paint" [selected]
        - option "primer"
        - option "wallpaper"
        - option "adhesive"
        - option "socket"
        - option "cable"
      - text: Источник для поля «Вид товара»
      - textbox "Источник для поля «Вид товара»": Учебные данные повторной приёмки 30.10.2026
      - text: Цена — состояние
      - combobox "Цена — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Цена — значение
      - textbox "Цена — значение": "1234.50"
      - text: Источник для поля «Цена»
      - textbox "Источник для поля «Цена»": Учебные данные повторной приёмки 30.10.2026
      - text: Валюта — состояние
      - combobox "Валюта — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Валюта — значение
      - combobox "Валюта — значение":
        - option "Выберите значение" [disabled]
        - option "RUB" [selected]
      - text: Источник для поля «Валюта»
      - textbox "Источник для поля «Валюта»": Учебные данные повторной приёмки 30.10.2026
      - text: Дата актуальности цены — состояние
      - combobox "Дата актуальности цены — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Дата актуальности цены — значение
      - textbox "Дата актуальности цены — значение": 2026-10-30
      - text: Источник для поля «Дата актуальности цены»
      - textbox "Источник для поля «Дата актуальности цены»": Учебные данные повторной приёмки 30.10.2026
      - text: Единица расчёта — состояние
      - combobox "Единица расчёта — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Единица расчёта — значение
      - combobox "Единица расчёта — значение":
        - option "Выберите значение" [disabled]
        - option "м²"
        - option "л" [selected]
        - option "кг"
        - option "м"
        - option "шт."
        - option "полотен"
      - text: Источник для поля «Единица расчёта»
      - textbox "Источник для поля «Единица расчёта»": Учебные данные повторной приёмки 30.10.2026
      - text: Единица продажи — состояние
      - combobox "Единица продажи — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Единица продажи — значение
      - combobox "Единица продажи — значение":
        - option "Выберите значение" [disabled]
        - option "м²"
        - option "л"
        - option "кг"
        - option "м"
        - option "шт."
        - option "уп."
        - option "рул."
        - option "бан." [selected]
        - option "меш."
        - option "кан."
        - option "бух."
      - text: Источник для поля «Единица продажи»
      - textbox "Источник для поля «Единица продажи»": Учебные данные повторной приёмки 30.10.2026
      - text: Размер упаковки — состояние
      - combobox "Размер упаковки — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Размер упаковки — значение
      - spinbutton "Размер упаковки — значение": "2.5"
      - text: Источник для поля «Размер упаковки»
      - textbox "Источник для поля «Размер упаковки»": Учебные данные повторной приёмки 30.10.2026
      - text: Расход — состояние
      - combobox "Расход — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Расход — значение
      - spinbutton "Расход — значение": "0.12"
      - text: Источник для поля «Расход»
      - textbox "Источник для поля «Расход»": Учебные данные повторной приёмки 30.10.2026
      - text: Единица расхода — состояние
      - combobox "Единица расхода — состояние":
        - option "Не заполнено"
        - option "Явно неизвестно"
        - option "Подтверждено" [selected]
      - text: Единица расхода — значение
      - combobox "Единица расхода — значение":
        - option "Выберите значение" [disabled]
        - option "м²"
        - option "л" [selected]
        - option "кг"
        - option "м"
        - option "шт."
        - option "полотен"
      - text: Источник для поля «Единица расхода»
      - textbox "Источник для поля «Единица расхода»": Учебные данные повторной приёмки 30.10.2026
      - text: Для влажных помещений — состояние
      - combobox "Для влажных помещений — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Для влажных помещений»
      - textbox "Источник для поля «Для влажных помещений»"
      - text: Для высокой нагрузки — состояние
      - combobox "Для высокой нагрузки — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Для высокой нагрузки»
      - textbox "Источник для поля «Для высокой нагрузки»"
      - text: Для неотапливаемых помещений — состояние
      - combobox "Для неотапливаемых помещений — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Для неотапливаемых помещений»
      - textbox "Источник для поля «Для неотапливаемых помещений»"
      - text: Ширина рулона, м — состояние
      - combobox "Ширина рулона, м — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Ширина рулона, м»
      - textbox "Источник для поля «Ширина рулона, м»"
      - text: Длина рулона, м — состояние
      - combobox "Длина рулона, м — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Длина рулона, м»
      - textbox "Источник для поля «Длина рулона, м»"
      - text: Раппорт, м — состояние
      - combobox "Раппорт, м — состояние":
        - option "Не заполнено" [selected]
        - option "Явно неизвестно"
        - option "Подтверждено"
      - text: Источник для поля «Раппорт, м»
      - textbox "Источник для поля «Раппорт, м»"
      - heading "Характеристики" [level=2]
      - button "Добавить характеристику"
      - heading "Источники" [level=2]
      - text: Название источника
      - textbox "Название источника": Учебная карточка приёмки
      - text: Ссылка источника
      - textbox "Ссылка источника"
      - button "Удалить источник 1": Удалить
      - button "Добавить источник"
      - text: Служебный комментарий
      - textbox "Служебный комментарий"
      - button "Создать черновик"
- contentinfo: RepairCalc · Умный подход к ремонту Расчёты через подключённый API
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
> 32  |   await expect(page.getByRole('heading', { name: 'Карточка · версия 1', exact: true })).toBeVisible()
      |                                                                                         ^ Error: expect(locator).toBeVisible() failed
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
  59  |   await page.screenshot({ path: info.outputPath('admin-panel.png'), fullPage: true })
  60  |   await page.getByRole('link', { name: 'Все карточки', exact: true }).click()
  61  |   await page.getByLabel('Название или внешний ID').fill(suffix)
  62  |   await page.locator('.admin-filters').getByRole('button').click()
  63  |   await expect(page.getByRole('link', { name: 'Изменена ' + suffix, exact: true })).toBeVisible()
  64  |   await page.getByRole('button', { name: 'Выйти', exact: true }).click()
  65  |   await expect(page.getByRole('heading', { name: 'Вход администратора' })).toBeVisible()
  66  |   expect(errors).toEqual([])
  67  | })
  68  | 
  69  | test('admin panel performs real classification, human review, publication and Java calculation', async ({ page }, info) => {
  70  |   test.skip(info.project.name !== 'desktop', 'Real inference once; mobile panel is tested separately.')
  71  |   test.setTimeout(900_000)
  72  |   await login(page)
  73  |   const id = await createPaint(page, 'workflow-' + Date.now())
  74  |   writeFileSync(join(evidence, 'remote-workflow-id.txt'), id)
  75  |   await page.getByRole('button', { name: 'Загрузить задания и готовность' }).click()
  76  |   await page.getByRole('button', { name: 'Запустить классификацию' }).click()
  77  |   await expect(page.getByRole('button', { name: 'Запустить классификацию' })).toBeDisabled()
  78  |   await expect(page.getByRole('button', { name: 'Проверить предложения' })).toBeVisible({ timeout: 750_000 })
  79  |   await page.getByRole('button', { name: 'Проверить предложения' }).click()
  80  |   const rows = page.locator('.workflow form fieldset')
  81  |   expect(await rows.count()).toBeGreaterThan(2)
  82  |   for (const row of await rows.all()) {
  83  |     const legend = await row.locator('legend').innerText()
  84  |     if (legend.startsWith('properties')) {
  85  |       await row.getByLabel('Решение', { exact: true }).selectOption('correct')
  86  |       await row.getByLabel('Значение', { exact: true }).selectOption(legend.includes('interior_suitable') ? 'yes' : 'unknown')
  87  |     } else {
  88  |       await row.getByLabel('Решение', { exact: true }).selectOption('accept')
  89  |     }
  90  |     await row.getByLabel('Основание решения', { exact: true }).fill('Проверено по учебной карточке: внутренние стены, бетон/штукатурка. Остальные свойства не подтверждены.')
  91  |   }
  92  |   await page.getByLabel('Проверенный профиль расчёта').selectOption('paint-012-v1')
  93  |   await page.getByRole('button', { name: 'Сохранить проверку', exact: true }).click()
  94  |   await expect(page.getByRole('heading', { name: 'Готово к публикации и расчёту' })).toBeVisible()
  95  |   await page.screenshot({ path: join(evidence, 'remote-admin-ready.png'), fullPage: true })
  96  |   await page.getByRole('button', { name: 'Опубликовать снимок каталога и правил' }).click()
  97  |   await expect(page.getByRole('link', { name: 'Рассчитать опубликованный товар' })).toBeVisible()
  98  |   await page.getByRole('link', { name: 'Рассчитать опубликованный товар' }).click()
  99  |   await page.getByLabel('Добавить пояснение ИИ').uncheck()
  100 |   const calc = page.waitForResponse(r => r.url().includes('/calc') && r.request().method() === 'POST')
  101 |   await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  102 |   const response = await calc
  103 |   expect(response.status()).toBe(200)
  104 |   writeFileSync(join(evidence, 'remote-calculation.json'), JSON.stringify(await response.json(), null, 2))
  105 |   await expect(page.getByRole('heading', { name: 'Ваша смета готова' })).toBeVisible()
  106 |   await page.getByText('Как рассчитано').click()
  107 |   await expect(page.getByText('5,28 л', { exact: true })).toBeVisible()
  108 |   await expect(page.getByText(/3\s?703,50/).first()).toBeVisible()
  109 |   await page.screenshot({ path: join(evidence, 'remote-calculation.png'), fullPage: true })
  110 | })
  111 | 
```