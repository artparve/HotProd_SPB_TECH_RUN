import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { newRoom } from '../domain/types'
import RoomEditor from './RoomEditor.vue'

describe('RoomEditor', () => {
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
