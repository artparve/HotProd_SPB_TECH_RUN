import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { catalog } from '../data/catalog'
import { inspirationFeedMock, analyzeInspirationMock } from '../data/inspirations'
import { newRoom, type Project } from '../domain/types'
import { service } from '../services/api'
import { estimateDemo } from '../services/demo'
import RoomInspirationGallery from './RoomInspirationGallery.vue'

vi.mock('../services/api', () => ({
  dataMode: 'api',
  service: {
    roomInspirations: vi.fn(),
    analyzeRoomInspiration: vi.fn(),
    catalog: vi.fn(),
    estimate: vi.fn(),
  },
}))
const image = inspirationFeedMock.items[0]!
const project = (): Project => ({
  object: 'apartment',
  renovation: 'capital',
  rooms: [{ ...newRoom(), id: 'room-1' }],
})
const wrappers: ReturnType<typeof mount>[] = []
async function render(input = project()) {
  const wrapper = mount(RoomInspirationGallery, { props: { project: input } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.mocked(service.roomInspirations).mockResolvedValue(structuredClone(inspirationFeedMock))
  vi.mocked(service.analyzeRoomInspiration).mockImplementation(async (id, input) =>
    analyzeInspirationMock(id, input),
  )
  vi.mocked(service.catalog).mockResolvedValue(catalog)
  vi.mocked(service.estimate).mockImplementation(async (input, selections) =>
    estimateDemo(input, selections),
  )
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '')
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open')
  })
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers.length = 0
  vi.resetAllMocks()
})

describe('RoomInspirationGallery', () => {
  it('shows the backend feed and loads a full-size preview on hover', async () => {
    const wrapper = await render()
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(3)
    await wrapper.get('.inspiration-image').trigger('mouseenter')
    expect(wrapper.get('.inspiration-hover-preview img').attributes('src')).toBe(image.imageUrl)
    await wrapper.get('.inspiration-image').trigger('mouseleave')
    expect(wrapper.find('.inspiration-hover-preview').exists()).toBe(false)
    await wrapper.get('.inspiration-image').trigger('click')
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    expect(wrapper.get('dialog img').attributes('alt')).toBe(image.alt)
    await wrapper.get('dialog').trigger('cancel')
    expect(wrapper.find('dialog img').exists()).toBe(false)
    expect(service.analyzeRoomInspiration).not.toHaveBeenCalled()
  })

  it('prepares the same project/selections used by the ordinary calculator without mutating the input', async () => {
    const input = project()
    const original = structuredClone(input)
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    await flushPromises()
    const prepared = wrapper.emitted('prepared')![0]![0] as {
      result: ReturnType<typeof analyzeInspirationMock>
    }
    expect(prepared.result.selections).toEqual({
      'room-1:covering': 'demo-laminate',
      'room-1:paint': 'demo-paint',
    })
    expect(service.estimate).toHaveBeenCalledWith(
      prepared.result.project,
      prepared.result.selections,
    )
    expect(input).toEqual(original)
  })

  it('shows empty, failure and retry states', async () => {
    vi.mocked(service.roomInspirations).mockRejectedValueOnce(new Error('Лента недоступна'))
    const wrapper = await render()
    expect(wrapper.get('[role="alert"]').text()).toContain('Лента недоступна')
    vi.mocked(service.roomInspirations).mockResolvedValueOnce({ items: [], nextCursor: null })
    await wrapper.get('button.text-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Изображений пока нет')
  })

  it('appends pages and deduplicates image IDs', async () => {
    vi.mocked(service.roomInspirations).mockResolvedValueOnce({
      items: [image],
      nextCursor: 'page-2',
    })
    const wrapper = await render()
    await wrapper.get('button.button.ghost').trigger('click')
    await flushPromises()
    expect(service.roomInspirations).toHaveBeenLastCalledWith('page-2', expect.any(AbortSignal))
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(3)
  })

  it('prevents duplicate submissions and ignores a response after project edits', async () => {
    let resolve!: (value: ReturnType<typeof analyzeInspirationMock>) => void
    vi.mocked(service.analyzeRoomInspiration).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const input = project()
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeDefined()
    await wrapper.get('.inspiration-card-body button').trigger('click')
    expect(service.analyzeRoomInspiration).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ project: { ...input, rooms: [{ ...input.rooms[0]!, length: 7 }] } })
    resolve(analyzeInspirationMock(image.id, input))
    await flushPromises()
    expect(wrapper.emitted('prepared')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('Проект изменился')
  })

  it.each([
    'analysis',
    'catalog',
    'estimate',
    'missing-selection',
    'incompatible-product',
  ] as const)('preserves the project on failure: %s', async (failure) => {
    const input = project()
    const original = structuredClone(input)
    if (failure === 'analysis')
      vi.mocked(service.analyzeRoomInspiration).mockRejectedValueOnce(
        new Error('Ошибка распознавания'),
      )
    if (failure === 'catalog')
      vi.mocked(service.catalog).mockRejectedValueOnce(new Error('Ошибка каталога'))
    if (failure === 'estimate')
      vi.mocked(service.estimate).mockRejectedValueOnce(new Error('Ошибка расчёта'))
    if (failure === 'missing-selection')
      vi.mocked(service.estimate).mockResolvedValueOnce({ ...estimateDemo(input), lines: [] })
    if (failure === 'incompatible-product') vi.mocked(service.catalog).mockResolvedValueOnce([])
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('prepared')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('не изменён')
    expect(input).toEqual(original)
  })

  it('does not apply results after navigation/unmount', async () => {
    let resolve!: (value: ReturnType<typeof analyzeInspirationMock>) => void
    let signal: AbortSignal | undefined
    vi.mocked(service.analyzeRoomInspiration).mockImplementation((_id, _project, currentSignal) => {
      signal = currentSignal
      return new Promise((done) => {
        resolve = done
      })
    })
    const input = project()
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    wrapper.unmount()
    wrappers.length = 0
    expect(signal?.aborted).toBe(true)
    resolve(analyzeInspirationMock(image.id, input))
    await flushPromises()
    expect(wrapper.emitted('prepared')).toBeUndefined()
    expect(service.catalog).not.toHaveBeenCalled()
  })

  it('rejects an invalid project before making an analysis request', async () => {
    const input = project()
    input.rooms[0]!.length = 0
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    expect(service.analyzeRoomInspiration).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('проверьте размеры')
  })

  it('cancels recognition without applying a late response', async () => {
    let resolve!: (value: ReturnType<typeof analyzeInspirationMock>) => void
    let signal: AbortSignal | undefined
    vi.mocked(service.analyzeRoomInspiration).mockImplementation((_id, _project, currentSignal) => {
      signal = currentSignal
      return new Promise((done) => {
        resolve = done
      })
    })
    const input = project()
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    const cancel = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Отменить распознавание')!
    await cancel.trigger('click')
    expect(signal?.aborted).toBe(true)
    resolve(analyzeInspirationMock(image.id, input))
    await flushPromises()
    expect(wrapper.emitted('prepared')).toBeUndefined()
    expect(service.catalog).not.toHaveBeenCalled()
    expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeUndefined()
  })

  it('shows a fallback and disables selection when a thumbnail cannot be loaded', async () => {
    const wrapper = await render()
    await wrapper.get('.inspiration-image img').trigger('error')
    expect(wrapper.get('.inspiration-image-fallback').text()).toContain('Изображение недоступно')
    expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeDefined()
  })
})
