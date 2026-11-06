import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { catalog } from '../data/catalog'
import { service } from '../services/api'
import { productDemo } from '../services/demo'
import ProductView from './ProductView.vue'

vi.mock('../services/api', () => ({
  dataMode: 'demo',
  service: { catalog: vi.fn(), product: vi.fn() },
}))

async function view(id: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/product', component: ProductView }],
  })
  await router.push({ path: '/product', query: { id } })
  const wrapper = mount(ProductView, {
    global: {
      plugins: [router],
      stubs: {
        EstimateResult: { props: ['estimate'], template: '<div class="result">Calculated</div>' },
      },
    },
  })
  await flushPromises()
  return { router, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(service.catalog).mockResolvedValue(catalog)
  vi.mocked(service.product).mockImplementation(async (product, input) =>
    productDemo(product, input),
  )
})

describe('product navigation', () => {
  it('updates the selected product and clears the estimate when the URL changes', async () => {
    const { router, wrapper } = await view(catalog[0]!.id)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.result').exists()).toBe(true)

    await router.push({ path: '/product', query: { id: catalog[1]!.id } })
    await flushPromises()

    expect(wrapper.get('select').element.value).toBe(catalog[1]!.id)
    expect(wrapper.get('.product-detail h2').text()).toBe(catalog[1]!.name)
    expect(wrapper.find('.result').exists()).toBe(false)
    expect(service.catalog).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('shows an invalid link warning until the user chooses an available product', async () => {
    const { router, wrapper } = await view('missing-product')
    expect(wrapper.text()).toContain('Товар по ссылке не найден')

    await wrapper.get('select').setValue(catalog[1]!.id)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Товар по ссылке не найден')
    expect(router.currentRoute.value.query.id).toBe(catalog[1]!.id)
    wrapper.unmount()
  })
})
