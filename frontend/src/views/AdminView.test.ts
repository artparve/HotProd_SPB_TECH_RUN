import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminView from './AdminView.vue'
import { admin, AdminApiError } from '../services/admin'

vi.mock('../services/admin', async (original) => {
  const module = await original<typeof import('../services/admin')>()
  return {
    ...module,
    admin: {
      session: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      list: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      deleteDraft: vi.fn(),
      history: vi.fn(),
    },
  }
})
const card = {
  id: '3c067712-2c5b-4891-aed4-3b87cb342726',
  versionId: '77a8e64f-4c8c-47fe-b1c5-f199dcf55005',
  version: 1,
  status: 'draft' as const,
  sourceNamespace: 'supplier',
  externalId: '42',
  snapshot: { sourceNamespace: 'supplier', externalId: '42', name: 'Saved name' },
  createdAt: '2026-10-30T00:00:00Z',
  updatedAt: '2026-10-30T00:00:00Z',
  createdBy: 'admin',
  updatedBy: 'admin',
}
async function view(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/:rest(.*)*', component: AdminView }],
  })
  // Use the same named id parameter as the real card route.
  router.addRoute({ path: '/admin/products/:id', component: AdminView })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AdminView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(admin.session).mockResolvedValue({
    authenticated: true,
    username: 'admin',
    csrfToken: 'token',
  })
  vi.mocked(admin.get).mockResolvedValue({ data: card, etag: '"etag-1"' })
})

describe('administrative card', () => {
  it('does not replace the next card when an earlier save finishes after navigation', async () => {
    let finishSave!: (result: Awaited<ReturnType<typeof admin.update>>) => void
    vi.mocked(admin.update).mockReturnValue(
      new Promise((resolve) => {
        finishSave = resolve
      }),
    )
    const wrapper = await view(`/admin/products/${card.id}`)
    await wrapper.get('form.admin-form').trigger('submit')
    await flushPromises()
    const nextCard = {
      ...card,
      id: 'a1ef2126-909b-4273-9c6e-783881be7750',
      snapshot: { ...card.snapshot, name: 'Next card' },
    }
    vi.mocked(admin.get).mockResolvedValue({ data: nextCard, etag: '"next-etag"' })
    await wrapper.vm.$router.push(`/admin/products/${nextCard.id}`)
    await flushPromises()
    finishSave({ data: { ...card, version: 2 }, etag: '"saved-etag"' })
    await flushPromises()

    expect(wrapper.text()).toContain(nextCard.id)
    expect(wrapper.findAll('input').some((input) => input.element.value === 'Next card')).toBe(true)
    expect(wrapper.text()).not.toContain('Сохранена версия 2')
    wrapper.unmount()
  })

  it('keeps unsaved fields after a version conflict and reloads only on the explicit action', async () => {
    vi.mocked(admin.update).mockRejectedValue(
      new AdminApiError(412, 'version_mismatch', 'Карточка изменилась.'),
    )
    const wrapper = await view(`/admin/products/${card.id}`)
    const name = wrapper.findAll('input').find((input) => input.element.value === 'Saved name')!
    await name.setValue('My unsaved name')
    await wrapper.get('form.admin-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Ваши поля остаются в форме')
    expect(name.element.value).toBe('My unsaved name')
    expect(admin.get).toHaveBeenCalledTimes(1)
    vi.mocked(admin.get).mockResolvedValue({
      data: { ...card, snapshot: { ...card.snapshot, name: 'Other editor name' }, version: 2 },
      etag: '"etag-2"',
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Перечитать и заменить поля формы')!
      .trigger('click')
    await flushPromises()
    expect(
      wrapper.findAll('input').some((input) => input.element.value === 'Other editor name'),
    ).toBe(true)
    wrapper.unmount()
  })

  it('ignores a failed save from the previous route while the next card is loading', async () => {
    let failSave!: (error: Error) => void
    vi.mocked(admin.update).mockReturnValue(
      new Promise((_, reject) => {
        failSave = reject
      }),
    )
    const wrapper = await view(`/admin/products/${card.id}`)
    await wrapper.get('form.admin-form').trigger('submit')
    await flushPromises()
    let finishLoad!: (result: Awaited<ReturnType<typeof admin.get>>) => void
    vi.mocked(admin.get).mockReturnValue(
      new Promise((resolve) => {
        finishLoad = resolve
      }),
    )
    await wrapper.vm.$router.push('/admin/products/next-card')
    await flushPromises()
    failSave(new AdminApiError(412, 'version_mismatch', 'Old card conflict'))
    await flushPromises()

    expect(wrapper.text()).not.toContain('Old card conflict')
    expect(wrapper.text()).toContain('Выполняем запрос…')
    finishLoad({ data: { ...card, id: 'next-card' }, etag: '"next-etag"' })
    await flushPromises()
    expect(wrapper.text()).toContain('next-card')
    expect(wrapper.text()).not.toContain('Выполняем запрос…')
    wrapper.unmount()
  })

  it('discards history loaded for a card that is no longer open', async () => {
    let finishHistory!: (result: Awaited<ReturnType<typeof admin.history>>) => void
    vi.mocked(admin.history).mockReturnValue(
      new Promise((resolve) => {
        finishHistory = resolve
      }),
    )
    const wrapper = await view(`/admin/products/${card.id}`)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Загрузить историю')!
      .trigger('click')
    await flushPromises()
    vi.mocked(admin.get).mockResolvedValue({ data: { ...card, id: 'next-card' }, etag: '"next"' })
    await wrapper.vm.$router.push('/admin/products/next-card')
    await flushPromises()
    finishHistory({
      items: [
        {
          id: card.versionId,
          version: 1,
          snapshot: card.snapshot,
          createdAt: card.createdAt,
          createdBy: 'admin',
          requestId: 'old-history',
          action: 'create',
        },
      ],
      page: 0,
      size: 10,
      total: 1,
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('old-history')
    expect(wrapper.find('.admin-history details').exists()).toBe(false)
    wrapper.unmount()
  })

  it('confirms deletion and returns to the list only after success', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    vi.mocked(admin.deleteDraft).mockResolvedValue(undefined)
    vi.mocked(admin.list).mockResolvedValue({ items: [], page: 0, size: 20, total: 0 })
    const wrapper = await view(`/admin/products/${card.id}`)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Удалить черновик')!
      .trigger('click')
    await flushPromises()
    expect(admin.deleteDraft).toHaveBeenCalledWith(card.id, '"etag-1"')
    expect(wrapper.text()).toContain('Черновик удалён.')
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('shows a connection failure without a demo card or a working save form', async () => {
    vi.mocked(admin.get).mockRejectedValue(new Error('Сеть недоступна'))
    const wrapper = await view(`/admin/products/${card.id}`)
    expect(wrapper.get('[role="alert"]').text()).toContain('Сеть недоступна')
    expect(wrapper.find('form.admin-form').exists()).toBe(false)
    wrapper.unmount()
  })
})
