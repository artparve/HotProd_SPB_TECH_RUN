import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { newRoom } from '../domain/types'
import RoomEditor from './RoomEditor.vue'

describe('RoomEditor', () => {
  it('places the interior feed beside plan import, before room dimensions', () => {
    const wrapper = mount(RoomEditor, {
      props: { room: newRoom() },
      slots: { inspiration: '<section class="test-interiors">Интерьеры</section>' },
    })
    const feed = wrapper.get('.test-interiors').element
    expect(feed.previousElementSibling?.classList.contains('plan-import')).toBe(true)
    expect(feed.nextElementSibling?.textContent).toContain('Размеры помещения')
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
