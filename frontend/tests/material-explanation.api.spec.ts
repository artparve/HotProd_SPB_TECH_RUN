import { expect, test } from '@playwright/test'
import { catalog } from '../src/data/catalog'
import { productDemo } from '../src/services/demo'

test('production UI renders Markdown explanation without executing HTML or loading external images', async ({
  page,
}) => {
  const product = {
    ...catalog[0]!,
    id: '47f9b29f-8972-3c84-9103-6b3281bd9de7',
    source: 'Каталог материалов',
    description: 'Напольное покрытие для сухих помещений.',
  }
  const external: string[] = []
  await page.route('https://tracking.invalid/**', async (route) => {
    external.push(route.request().url())
    await route.abort()
  })
  await page.route('**/api/v1/products', (route) => route.fulfill({ json: [product] }))
  await page.route('**/api/v1/materials/estimate', async (route) => {
    expect(route.request().postDataJSON().productId).toBe(product.id)
    const estimate = {
      ...productDemo(product, route.request().postDataJSON().input),
      mode: 'api',
      warnings: ['Перед покупкой проверьте параметры.'],
      versions: { catalog: 'catalog-v1', rules: 'rules-v1', model: 'not-used' },
    }
    await route.fulfill({
      json: {
        status: 'ok',
        estimate,
        baseline: estimate,
        correction: { applied: false, version: 'not-used', factor: '1' },
        model: 'assistant',
        toolCalls: ['calculate_material_cost'],
        explanation:
          '## Расход материала\n\n**Запас:** 10%\n\n- Округляем до упаковок\n\n| Материал | Количество |\n| --- | --- |\n| Покрытие | 11 упаковок |\n\n<script>window.markdownExecuted = true</script>\n\n![Схема](https://tracking.invalid/pixel.png)\n\n[Опасная ссылка](javascript:alert(1))',
        explanationStatus: 'unverified_model_text',
      },
    })
  })
  await page.goto(`/product?id=${product.id}`)
  await page.getByRole('button', { name: 'Рассчитать упаковки' }).click()
  const explanation = page.getByRole('region', { name: 'Пояснение ИИ' })
  await expect(explanation.getByRole('heading', { name: 'Расход материала' })).toBeVisible()
  await expect(explanation.locator('strong')).toHaveText('Запас:')
  await expect(explanation.locator('table')).toBeVisible()
  await expect(explanation.locator('li')).toHaveText('Округляем до упаковок')
  await expect(explanation.locator('script, img, iframe')).toHaveCount(0)
  expect(await page.evaluate(() => 'markdownExecuted' in window)).toBe(false)
  expect(external).toEqual([])
  await expect(page.locator('body')).not.toContainText(/демо|тестовый вариант/i)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})
