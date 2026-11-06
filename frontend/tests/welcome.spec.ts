import { expect, test } from '@playwright/test'

test('first visit guide supports back/next, spotlight, Escape and a persistent cookie', async ({
  page,
  context,
  isMobile,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript(() => {
    let seen = false
    new MutationObserver(() => {
      if (document.getElementById('startup-screen')) seen = true
      else if (seen) {
        ;(window as unknown as { splashEndedAt: number }).splashEndedAt = performance.now()
        seen = false
      }
    }).observe(document, { childList: true, subtree: true })
  })
  await page.goto('/')
  await expect(page.locator('#startup-screen')).toHaveCount(0)
  const guide = page.getByRole('dialog', { name: 'Ваш помощник по ремонту' })
  await expect(guide).toBeVisible()
  expect(
    await page.evaluate(() => (window as unknown as { splashEndedAt: number }).splashEndedAt),
  ).toBeGreaterThanOrEqual(900)
  await expect(guide).toContainText('Шаг 1/6')
  await expect(guide.getByRole('button', { name: 'Назад', exact: true })).toBeDisabled()
  await guide.getByRole('button', { name: 'Далее', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Шаг 2/6')
  await expect(page.getByRole('dialog')).toContainText('Панель меню: выберите сценарий')
  for (const title of ['Общий расчёт', 'Подбор для комнаты', 'Расчёт товара', 'Мои сметы'])
    await expect(page.getByRole('dialog')).toContainText(title)
  if (isMobile) {
    await expect(page.locator('.sidebar')).toHaveClass(/is-open/)
    await expect(page.getByRole('button', { name: 'Открыть навигацию' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await expect
      .poll(() =>
        page.locator('.sidebar nav').evaluate((element) => element.getBoundingClientRect().left),
      )
      .toBeGreaterThanOrEqual(0)
  }
  await page.getByRole('dialog').getByRole('button', { name: 'Далее', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Шаг 3/6')
  await expect(page.getByRole('dialog')).toContainText('без плана')
  await expect(page.locator('.sidebar')).not.toHaveClass(/is-open/)
  expect(await page.locator('mask rect').last().getAttribute('width')).not.toBe('0')
  await page.getByRole('dialog').getByRole('button', { name: 'Назад', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Шаг 2/6')
  await page.getByRole('dialog').getByRole('button', { name: 'Назад', exact: true }).click()
  await expect(guide).toContainText('Шаг 1/6')
  await page.keyboard.press('Escape')
  await expect(page.locator('.welcome-guide')).not.toBeVisible()
  const cookie = (await context.cookies()).find((c) => c.name === 'repaircalc_guide_v1')
  expect(cookie?.value).toBe('done')
  expect(cookie?.sameSite).toBe('Lax')
  await page.reload()
  await expect(page.locator('#startup-screen')).toHaveCount(0)
  await expect(page.locator('.welcome-guide')).not.toBeVisible()
  expect(errors).toEqual([])
})

test('guide can be completed and restarted from help; gallery needs no plan', async ({ page }) => {
  await page.goto('/room')
  await expect(page.locator('.welcome-guide')).toBeVisible()
  for (let i = 0; i < 5; i++)
    await page.getByRole('dialog').getByRole('button', { name: 'Далее', exact: true }).click()
  await page.getByRole('button', { name: 'Начать работу', exact: true }).click()
  await expect(page.locator('.welcome-guide')).not.toBeVisible()
  await expect(page.getByRole('region', { name: 'Вдохновитесь готовой комнатой' })).toBeVisible()
  await expect(page.locator('.room-editor .inspiration-gallery')).toHaveCount(1)
  await expect(page.locator('.plan-import')).not.toHaveAttribute('open', '')
  await page.getByRole('button', { name: 'Помощь', exact: true }).click()
  await page.getByRole('button', { name: 'Повторить знакомство', exact: true }).click()
  await expect(page.locator('.welcome-guide')).toBeVisible()
  await page.getByRole('button', { name: 'Пропустить знакомство', exact: true }).click()
  await expect(page.locator('.welcome-guide')).not.toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})
