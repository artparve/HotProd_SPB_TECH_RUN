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

  it('shows a connection failure without a demo card or a working save form', async () => {
    vi.mocked(admin.get).mockRejectedValue(new Error('Сеть недоступна'))
    const wrapper = await view(`/admin/products/${card.id}`)
    expect(wrapper.get('[role="alert"]').text()).toContain('Сеть недоступна')
    expect(wrapper.find('form.admin-form').exists()).toBe(false)
    wrapper.unmount()
  })
})
