import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { newRoom } from '../domain/types'
import { service } from '../services/api'
import RoomPlanImport from './RoomPlanImport.vue'

vi.mock('../services/api', () => ({ service: { extractPlan: vi.fn() } }))
const unknown = { state: 'unknown', value: null, unit: null, label: null, bbox: null }
function response() {
  return {
    schemaVersion: 'room-plan-draft-v1',
    promptVersion: 'test',
    model: 'test-model',
    requiresConfirmation: true,
    dimensionsMetres: { length: 5.32, width: 3.32, height: null },
    draft: {
      shape: 'rectangle',
      name: unknown,
      length: unknown,
      width: unknown,
      height: unknown,
      openings: [],
      warnings: [],
    },
    warnings: [],
    missingFields: ['height', 'openingsArea'],
    elapsedSeconds: 1,
    image: { width: 100, height: 80, coordinates: 'normalized-oriented-image' },
  }
}
function prepareBrowser() {
  vi.stubGlobal(
    'FileReader',
    class {
      result = 'data:image/png;base64,aGVsbG8='
      onload?: () => void
      readAsDataURL() {
        this.onload?.()
      }
    },
  )
  vi.stubGlobal(
    'Image',
    class {
      naturalWidth = 100
      naturalHeight = 80
      onload?: () => void
      set src(_value: string) {
        this.onload?.()
      }
    },
  )
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    fillRect: vi.fn(),
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(
    'data:image/png;base64,aGVsbG8=',
  )
}
async function load(wrapper: ReturnType<typeof mount>) {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    value: [new File(['image'], 'plan.png', { type: 'image/png' })],
  })
  await input.trigger('change')
  await flushPromises()
}
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.mocked(service.extractPlan).mockReset()
})

describe('RoomPlanImport', () => {
  it('requires explicit missing values and confirmation before applying', async () => {
    prepareBrowser()
    vi.mocked(service.extractPlan).mockResolvedValue(response() as never)
    const room = newRoom()
    const wrapper = mount(RoomPlanImport, { props: { room } })
    await load(wrapper)
    await wrapper.get('.plan-review-fields > button').trigger('click')
    await flushPromises()
    const fields = wrapper.findAll('input[type="text"]')
    expect(fields.map((field) => (field.element as HTMLInputElement).value)).toEqual([
      '5.32',
      '3.32',
      '',
      '',
    ])
    expect(wrapper.get('button.button.primary').attributes('disabled')).toBeDefined()
    await fields[2]!.setValue('3')
    await fields[3]!.setValue('0')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('button.button.primary').trigger('click')
    expect(room).toMatchObject({ length: 5.32, width: 3.32, height: 3, openings: 0 })
    expect(wrapper.text()).toContain('Проверенные размеры применены')
  })

  it('does not apply a late response after edits or a room switch', async () => {
    prepareBrowser()
    let resolve!: (value: never) => void
    vi.mocked(service.extractPlan).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const room = newRoom()
    const wrapper = mount(RoomPlanImport, { props: { room } })
    await load(wrapper)
    await wrapper.get('.plan-review-fields > button').trigger('click')
    await wrapper.setProps({ room: { ...room, height: 3.1 } })
    resolve(response() as never)
    await flushPromises()
    expect(wrapper.text()).toContain('Комната изменилась')
    expect(wrapper.get('button.button.primary').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ room: { ...room, id: 'second-room' } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(0)
  })

  it('accepts pasted images and shows model errors without changing the room', async () => {
    prepareBrowser()
    vi.mocked(service.extractPlan).mockRejectedValue(
      new Error('Модель вернула противоречивые размеры'),
    )
    const room = newRoom()
    const wrapper = mount(RoomPlanImport, { props: { room } })
    await wrapper.get('details').trigger('paste', {
      clipboardData: {
        items: [{ type: 'image/png', getAsFile: () => new File(['image'], 'clipboard.png') }],
      },
    })
    await flushPromises()
    expect(wrapper.get('img').attributes('alt')).toBe('Загруженный план комнаты')
    await wrapper.get('.plan-review-fields > button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('противоречивые')
    expect(room).toEqual(newRoomSnapshot(room))
    expect(wrapper.findAll('input[type="text"]')).toHaveLength(0)
  })
})

function newRoomSnapshot(room: ReturnType<typeof newRoom>) {
  return { ...newRoom(), id: room.id }
}
