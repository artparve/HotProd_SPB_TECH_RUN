import { test, expect } from '../../../frontend/node_modules/@playwright/test/index.js'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
test.use({ trace: 'off' })
test('panel rejects old classification after editing a draft with unknown consumption', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'One unused classified fixture from this acceptance run.')
  const auth=JSON.parse(readFileSync('/tmp/repaircalc-admin-recheck.json','utf8'))
  await page.goto('/admin')
  await page.getByLabel('Имя администратора').fill(auth.username)
  await page.getByLabel('Пароль', {exact:true}).fill(auth.password)
  await page.getByRole('button',{name:'Войти',exact:true}).click()
  await expect(page.getByRole('link',{name:'Новая карточка',exact:true})).toBeVisible()
  await page.goto('/admin/products/5ec301c0-196f-4ca6-aba4-c844639c7901')
  await page.locator('#state-consumption').selectOption('unknown')
  await page.getByRole('button',{name:'Сохранить новую версию',exact:true}).click()
  await expect(page.getByRole('status')).toContainText('Сохранена версия')
  await page.getByRole('button',{name:'Загрузить задания и готовность'}).click()
  await expect(page.getByText('Устаревшая версия — использовать нельзя', {exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'Проверить предложения'})).toHaveCount(0)
  await expect(page.locator('#state-consumption')).toHaveValue('unknown')
  await expect(page.locator('.workflow')).toContainText('Нет проверки классификации для текущей версии.')
  await expect(page.getByRole('button',{name:'Опубликовать снимок каталога и правил'})).toBeDisabled()
  await page.locator('.workflow').screenshot({path:join(process.cwd(),'reports/admin-workflow/2026-10-30-recheck/stale-and-no-norm.png')})
})
