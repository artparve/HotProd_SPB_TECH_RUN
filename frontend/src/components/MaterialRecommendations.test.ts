import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MaterialRecommendations from './MaterialRecommendations.vue'
import { service } from '../services/api'
import { newRoom, type Project, type Estimate, type Product } from '../domain/types'
import type { MaterialRecommendations as Result } from '../domain/materialRecommendations'

vi.mock('../services/api', () => ({
  service: {
    catalog: vi.fn(),
    materialRecommendations: vi.fn(),
    applyMaterialRecommendations: vi.fn(),
  },
}))
const id = '00000000-0000-0000-0000-000000000001'
const requestId = '00000000-0000-0000-0000-000000000002'
const pricedProduct: Product = {
  id,
  name: 'Краска',
  category: 'Краска',
  kind: 'paint',
  price: '1290.50',
  packageSize: 5,
  unit: 'л',
  saleUnit: 'банка',
  works: ['paint'],
  wet: null,
  highLoad: null,
  unheated: null,
  description: '',
  source: 'fixture',
}
function project(): Project {
  return {
    object: 'apartment',
    renovation: 'cosmetic',
    rooms: [{ ...newRoom(1), works: ['paint'] }],
  }
}
function result(confirmed: boolean): Result {
  return {
    requestId,
    status: confirmed ? 'ready' : 'partial',
    catalogReleaseId: id,
    ruleSetId: id,
    knowledgeReleaseId: null,
    indexVersion: null,
    retrievalVersion: 'bm25-v1',
    questions: [],
    warnings: [],
    categories: [
      {
        work: 'paint',
        kind: 'paint',
        coveringFamily: null,
        candidates: [
          {
            productId: id,
            productVersionId: `release:${id}`,
            name: 'Краска',
            compatibility: confirmed ? 'pass' : 'unknown',
            calculationReadiness: confirmed ? 'ready' : 'unavailable',
            reason: 'Проверьте источник',
            checks: [],
            sources: [],
          },
        ],
      },
    ],
    explanation: {
      status: 'unverified_model_text',
      model: 'test',
      fallbackReason: null,
      items: [{ productId: id, text: '<script>unsafe()</script>', chunkIds: ['c1'] }],
    },
  }
}
async function setup(confirmed: boolean) {
  vi.mocked(service.materialRecommendations).mockResolvedValue(result(confirmed))
  const snapshot = project()
  const wrapper = mount(MaterialRecommendations, {
    props: { project: snapshot, products: [pricedProduct] },
    global: { stubs: { EstimateResult: true } },
  })
  const selects = wrapper.findAll('select')
  await selects[0]!.setValue('normal')
  await selects[1]!.setValue('normal')
  await selects[2]!.setValue('true')
  await wrapper
    .findAll('button')
    .find((b) => b.text().includes('Найти товары'))!
    .trigger('click')
  await flushPromises()
  return { wrapper, snapshot }
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(service.catalog).mockResolvedValue([])
})
describe('Material recommendations', () => {
  it('prevents applying unknown compatibility and renders model text as plain text', async () => {
    const { wrapper } = await setup(false)
    expect(wrapper.find('input[type=radio]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('Непроверенный текст модели')
    expect(wrapper.text()).toContain('<script>unsafe()</script>')
    expect(wrapper.find('.product-price').text()).toMatch(/1\s*290,5/)
    expect(wrapper.find('.product-price').text()).toContain('банка')
    expect(wrapper.text()).toContain('Итоговую стоимость можно рассчитать после проверки')
    expect(service.applyMaterialRecommendations).not.toHaveBeenCalled()
  })
  it('requires explicit selection and confirmation before requesting the Java estimate', async () => {
    const { wrapper, snapshot } = await setup(true)
    const apply = wrapper.findAll('button').find((b) => b.text().includes('Рассчитать'))!
    expect(apply.attributes('disabled')).toBeDefined()
    await wrapper.find('input[type=radio]').setValue()
    const confirmation = wrapper.findAll('input[type=checkbox]').at(-1)!
    await confirmation.setValue(true)
    vi.mocked(service.applyMaterialRecommendations).mockResolvedValue({
      lines: [],
    } as unknown as Estimate)
    await apply.trigger('click')
    await flushPromises()
    expect(service.applyMaterialRecommendations).toHaveBeenCalledWith(
      requestId,
      snapshot,
      { [`${snapshot.rooms[0]!.id}:paint`]: id },
      expect.any(AbortSignal),
    )
  })
  it('shows draft PDF references without enabling an unknown SKU', async () => {
    const response = result(false)
    vi.mocked(service.catalog).mockResolvedValue([pricedProduct])
    response.references = {
      status: 'unverified_model_text',
      indexId: 'a'.repeat(64),
      corpusMode: 'research_draft',
      usableForDecisions: false,
      model: 'test',
      fallbackReason: null,
      items: [{ text: 'Черновое объяснение', chunkIds: ['pdf:p1'] }],
      fragments: [
        {
          chunkId: 'pdf:p1',
          sourceId: 'pdf',
          title: 'Инструкция производителя',
          publisher: 'Test',
          url: null,
          sha256: 'a'.repeat(64),
          version: 'v1',
          versionNote: 'Редакция требует проверки',
          page: 1,
          text: 'Черновой фрагмент',
          reviewStatus: 'draft',
          rightsStatus: 'not_assessed',
          usableForDecisions: false,
        },
      ],
    }
    vi.mocked(service.materialRecommendations).mockResolvedValue(response)
    const wrapper = mount(MaterialRecommendations, {
      props: { project: project() },
      global: { stubs: { EstimateResult: true } },
    })
    await wrapper
      .findAll('label')
      .find((l) => l.text().includes('Учитывать инструкции производителей'))!
      .find('input')
      .setValue(true)
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Найти товары'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Черновое объяснение')
    expect(service.catalog).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(wrapper.find('.product-price').text()).toMatch(/1\s*290,5/)
    expect(wrapper.text()).toContain('не подтверждают характеристики учебных товаров')
    expect(wrapper.text()).toContain('страница 1')
    expect(wrapper.text()).not.toContain('a'.repeat(64))
    expect(wrapper.text()).not.toContain('pdf:p1')
    expect(wrapper.text()).not.toContain('SHA-256')
    expect(wrapper.text()).not.toContain('Исследовательский индекс')
    expect(wrapper.text()).not.toContain('Черновой фрагмент')
    expect(wrapper.find('.model-comment').text()).toContain('Инструкция производителя · стр. 1')
    expect(wrapper.find('.sources-panel').attributes('open')).toBeUndefined()
    expect(wrapper.find('input[type=radio]').attributes('disabled')).toBeDefined()
    expect(service.applyMaterialRecommendations).not.toHaveBeenCalled()
  })
  it('invalidates results after room changes', async () => {
    const { wrapper, snapshot } = await setup(true)
    await wrapper.setProps({
      project: { ...snapshot, rooms: [{ ...snapshot.rooms[0]!, length: 8 }] },
    })
    expect(wrapper.text()).toContain('Параметры изменились')
    expect(wrapper.find('input[type=radio]').exists()).toBe(false)
    expect(service.applyMaterialRecommendations).not.toHaveBeenCalled()
  })
})
