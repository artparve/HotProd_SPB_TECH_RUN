import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { newRoom } from '../domain/types'
import RoomEditor from './RoomEditor.vue'
import RoomPlanImport from './RoomPlanImport.vue'
import { workspace } from '../composables/useWorkspace'
import { dimensionKeys } from '../domain/plans'

describe('RoomEditor', () => {
  beforeEach(() => {
    workspace.roomInputs = {}
  })

  it('shows interiors only after all room dimensions are entered and valid', async () => {
    const wrapper = mount(RoomEditor, {
      props: { room: newRoom() },
      slots: { inspiration: '<section class="test-interiors">Интерьеры</section>' },
    })
    expect(wrapper.find('.test-interiors').exists()).toBe(false)
    const fields = wrapper.findAll('.dimensions-grid input')
    for (const field of fields.slice(0, 3)) await field.trigger('input')
    expect(wrapper.find('.test-interiors').exists()).toBe(false)
    await fields[3]!.setValue('0')
    const feed = wrapper.get('.test-interiors').element
    expect(feed.previousElementSibling?.classList.contains('geometry-strip')).toBe(true)
    await fields[0]!.setValue('')
    expect(wrapper.find('.test-interiors').exists()).toBe(false)
    await fields[0]!.setValue('5')
    expect(wrapper.find('.test-interiors').exists()).toBe(true)
    wrapper.unmount()
  })
  it('remembers entered dimensions per room and accepts applied plan dimensions', async () => {
    const room = newRoom()
    const wrapper = mount(RoomEditor, {
      props: { room },
      slots: { inspiration: '<section class="test-interiors">Интерьеры</section>' },
    })
    expect(wrapper.find('.test-interiors').exists()).toBe(false)
    wrapper.findComponent(RoomPlanImport).vm.$emit('applied')
    await wrapper.vm.$nextTick()
    expect(workspace.roomInputs[room.id]).toEqual([...dimensionKeys])
    expect(wrapper.find('.test-interiors').exists()).toBe(true)
    await wrapper.setProps({ room: newRoom(2) })
    expect(wrapper.find('.test-interiors').exists()).toBe(false)
    await wrapper.setProps({ room })
    expect(wrapper.find('.test-interiors').exists()).toBe(true)
    wrapper.unmount()
  })
  it('updates contextual defaults when a bathroom is selected', async () => {
    const room = newRoom()
    const wrapper = mount(RoomEditor, { props: { room } })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Санузел')!
      .trigger('click')
    expect(room.humidity).toBe('high')
    expect(room.type).toBe('bathroom')
  })
  it('shows a useful error for excessive openings', () => {
    const wrapper = mount(RoomEditor, {
      props: { room: { ...newRoom(), openings: 100 }, showValidation: true },
    })
    expect(wrapper.get('[role="alert"]').text()).toContain('проёмов')
  })
  it('can switch wall finish without calculating both surfaces twice', async () => {
    const room = newRoom()
    const wrapper = mount(RoomEditor, { props: { room } })
    await wrapper
      .findAll('input[type="checkbox"]')
      .find((field) => field.attributes('value') === 'wallpaper')!
      .setValue(true)
    expect(room.works).toContain('wallpaper')
    expect(room.works).not.toContain('paint')
  })
})
