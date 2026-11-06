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
  it('filters the loaded gallery by category without changing the project', async () => {
    const input = project()
    const original = structuredClone(input)
    vi.mocked(service.roomInspirations).mockResolvedValueOnce({
      items: [
        { ...image, id: 'kitchen-1', title: 'Кухня 1', category: 'kitchen' },
        { ...image, id: 'kitchen-2', title: 'Кухня 2', category: 'kitchen' },
        { ...image, id: 'bedroom-1', title: 'Спальня 1', category: 'bedroom' },
      ],
      nextCursor: null,
      status: 'ok',
      warnings: [],
    })
    const wrapper = await render(input)
    const group = wrapper.get('[aria-label="Категории интерьеров"]')
    const select = (label: string) =>
      group.findAll('button').find((button) => button.text() === label)!
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(3)
    await select('Кухни').trigger('click')
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(2)
    expect(select('Кухни').attributes('aria-pressed')).toBe('true')
    await select('Спальни').trigger('click')
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(1)
    expect(wrapper.get('.inspiration-card').text()).toContain('Спальня 1')
    await select('Все').trigger('click')
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(3)
    expect(input).toEqual(original)
    expect(service.analyzeRoomInspiration).not.toHaveBeenCalled()
  })

  it('credits the actual photo source and keeps its original link', async () => {
    const sourceUrl = 'https://www.pexels.com/photo/modern-bedroom-interior-8089163/'
    vi.mocked(service.roomInspirations).mockResolvedValueOnce({
      items: [{ ...image, sourceUrl, description: 'Фото: Max Vakhtbovych / Pexels.' }],
      nextCursor: null,
      status: 'ok',
      warnings: [],
    })
    const wrapper = await render()
    const source = wrapper.get('.inspiration-card-body a')
    expect(source.text()).toBe('Источник: Pexels')
    expect(source.attributes('href')).toBe(sourceUrl)
    expect(source.attributes('rel')).toContain('noopener')
    expect(wrapper.text()).toContain('Max Vakhtbovych')
    expect(wrapper.text()).not.toContain('Источник: Pinterest')
  })

  it.each(['unavailable', 'no_matches'] as const)(
    'keeps input and skips calculation for %s',
    async (status) => {
      const input = project()
      vi.mocked(service.analyzeRoomInspiration).mockResolvedValueOnce({
        status,
        imageId: image.id,
        project: structuredClone(input),
        selections: {},
        warnings: ['Продолжите вручную.'],
      })
      const wrapper = await render(input)
      await wrapper.get('.inspiration-card-body button').trigger('click')
      await flushPromises()
      expect(service.catalog).not.toHaveBeenCalled()
      expect(service.estimate).not.toHaveBeenCalled()
      expect(wrapper.emitted('prepared')).toBeUndefined()
      expect(wrapper.get('[role="alert"]').text()).toContain('Продолжите вручную')
      expect(input.rooms[0]!.length).toBe(5)
    },
  )
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
    expect(
      wrapper.findAll('button').every((button) => button.attributes('type') === 'button'),
    ).toBe(true)
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
      expect.any(AbortSignal),
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

  it('retries the failed next page without losing loaded cards or its cursor', async () => {
    vi.mocked(service.roomInspirations)
      .mockResolvedValueOnce({ items: [image], nextCursor: 'page-2' })
      .mockResolvedValueOnce({
        status: 'unavailable',
        items: [],
        nextCursor: null,
        warnings: ['Подборка временно недоступна.'],
      })
    const wrapper = await render()
    await wrapper.get('button.button.ghost').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.inspiration-card')).toHaveLength(1)
    await wrapper.get('[role="alert"] button').trigger('click')
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

  it.each(['catalog', 'estimate'] as const)(
    'cancels during %s, allows retry and ignores the late result',
    async (phase) => {
      const input = project()
      const oldResult = analyzeInspirationMock(image.id, input)
      let finish!: () => void
      if (phase === 'catalog')
        vi.mocked(service.catalog).mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finish = () => resolve(catalog)
            }),
        )
      else
        vi.mocked(service.estimate).mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finish = () => resolve(estimateDemo(oldResult.project, oldResult.selections))
            }),
        )
      const wrapper = await render(input)
      await wrapper.get('.inspiration-card-body button').trigger('click')
      await flushPromises()
      const signal =
        phase === 'catalog'
          ? vi.mocked(service.catalog).mock.calls[0]![0]
          : vi.mocked(service.estimate).mock.calls[0]![2]
      await wrapper
        .findAll('button')
        .find((b) => b.text() === 'Отменить распознавание')!
        .trigger('click')
      expect(signal?.aborted).toBe(true)
      expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeUndefined()
      let finishRetry!: () => void
      vi.mocked(service.estimate).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishRetry = () => resolve(estimateDemo(oldResult.project, oldResult.selections))
          }),
      )
      await wrapper.get('.inspiration-card-body button').trigger('click')
      await flushPromises()
      finish()
      await flushPromises()
      expect(wrapper.emitted('prepared')).toBeUndefined()
      expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeDefined()
      finishRetry()
      await flushPromises()
      expect(wrapper.emitted('prepared')).toHaveLength(1)
    },
  )

  it('cancels selection when switching rooms and ignores errors from the previous room', async () => {
    let finish!: (value: ReturnType<typeof analyzeInspirationMock>) => void
    vi.mocked(service.analyzeRoomInspiration).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const input = project()
    const wrapper = await render(input)
    await wrapper.get('.inspiration-card-body button').trigger('click')
    const signal = vi.mocked(service.analyzeRoomInspiration).mock.calls[0]![2]
    await wrapper.setProps({ project: { ...input, rooms: [{ ...input.rooms[0]!, id: 'other' }] } })
    expect(signal?.aborted).toBe(true)
    expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeUndefined()
    finish({
      status: 'no_matches',
      imageId: image.id,
      project: input,
      selections: {},
      warnings: ['Старый ответ'],
    })
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.emitted('prepared')).toBeUndefined()
  })

  it('recovers a thumbnail after a temporary image failure', async () => {
    const wrapper = await render()
    await wrapper.get('.inspiration-image img').trigger('error')
    expect(wrapper.get('.inspiration-image-fallback').text()).toContain('Изображение недоступно')
    expect(wrapper.get('.inspiration-card-body .primary').attributes('disabled')).toBeDefined()
    await wrapper.get('.inspiration-card-body .ghost').trigger('click')
    expect(wrapper.find('.inspiration-image img').exists()).toBe(true)
    expect(wrapper.get('.inspiration-card-body .primary').attributes('disabled')).toBeUndefined()
  })

  it('allows retrying a failed full-size preview', async () => {
    const wrapper = await render()
    await wrapper.get('.inspiration-image').trigger('click')
    await wrapper.get('dialog img').trigger('error')
    expect(wrapper.find('dialog img').exists()).toBe(false)
    await wrapper
      .findAll('dialog button')
      .find((b) => b.text() === 'Повторить загрузку изображения')!
      .trigger('click')
    expect(wrapper.get('dialog img').attributes('src')).toBe(image.imageUrl)
  })

  it('does not submit another selection while the parent is recalculating the project', async () => {
    const wrapper = await render()
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('.inspiration-card-body button').attributes('disabled')).toBeDefined()
    await wrapper.get('.inspiration-card-body button').trigger('click')
    expect(service.analyzeRoomInspiration).not.toHaveBeenCalled()
  })
})
