import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminProductWorkflow from './AdminProductWorkflow.vue'
import { admin } from '../services/admin'
vi.mock('../services/admin', () => ({
  admin: {
    classifications: vi.fn(),
    reviewOptions: vi.fn(),
    reviews: vi.fn(),
    readiness: vi.fn(),
    classify: vi.fn(),
    review: vi.fn(),
    publish: vi.fn(),
  },
}))
const product = {
  id: '3c067712-2c5b-4891-aed4-3b87cb342726',
  versionId: '77a8e64f-4c8c-47fe-b1c5-f199dcf55005',
  version: 2,
  status: 'draft' as const,
  sourceNamespace: 'test',
  externalId: '42',
  snapshot: { name: 'Paint', sourceNamespace: 'test', externalId: '42' },
  createdAt: '',
  updatedAt: '',
  createdBy: 'admin',
  updatedBy: 'admin',
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(admin.reviewOptions).mockResolvedValue({
    profiles: [],
    work_types: [],
    surface_locations: [],
    substrates: [],
    properties: [],
    taxonomyVersion: 'v2',
    mappingVersion: 'v1',
  })
  vi.mocked(admin.reviews).mockResolvedValue([])
  vi.mocked(admin.readiness).mockResolvedValue({
    ready: false,
    blockers: ['Подтвердите норму расхода'],
    reviewId: null,
    baseReleaseId: null,
  })
  vi.mocked(admin.classifications).mockResolvedValue([])
})
describe('classification and publication', () => {
  it('disables using stale inference and publishing a product without a norm', async () => {
    vi.mocked(admin.classifications).mockResolvedValue([
      {
        id: 'old',
        versionId: 'old-version',
        status: 'succeeded',
        attempts: 1,
        input: {},
        result: null,
        errorCode: null,
        createdAt: '',
        finishedAt: '',
      },
    ])
    const wrapper = mount(AdminProductWorkflow, { props: { product, etag: '"current"' } })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Устаревшая версия')
    expect(wrapper.text()).toContain('Подтвердите норму расхода')
    expect(wrapper.findAll('button').some((b) => b.text() === 'Проверить предложения')).toBe(false)
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text().startsWith('Опубликовать'))!
        .attributes('disabled'),
    ).toBeDefined()
    wrapper.unmount()
  })
  it('enqueues the saved version and retains a retry action after a model failure', async () => {
    vi.mocked(admin.classifications).mockResolvedValue([
      {
        id: 'failed',
        versionId: product.versionId,
        status: 'failed',
        attempts: 3,
        input: {},
        result: null,
        errorCode: 'classification_failed',
        createdAt: '',
        finishedAt: '',
      },
    ])
    const wrapper = mount(AdminProductWorkflow, { props: { product, etag: '"current"' } })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Ошибка модели')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Запустить классификацию')!
      .trigger('click')
    await flushPromises()
    expect(admin.classify).toHaveBeenCalledWith(product.id, '"current"')
    wrapper.unmount()
  })
})
