import { describe, expect, it } from 'vitest'
import { applyPlanDimensions } from './plans'
import { geometry } from './room'
import { newRoom } from './types'

describe('confirmed plan dimensions', () => {
  it('applies explicitly entered dimensions and decimal commas with the same geometry as manual entry', () => {
    const room = newRoom()
    const result = applyPlanDimensions(room, JSON.stringify(room), {
      length: '5,32',
      width: '3.32',
      height: '3',
      openings: '0',
    })
    expect(result).toMatchObject({
      id: room.id,
      length: 5.32,
      width: 3.32,
      height: 3,
      openings: 0,
      reserve: room.reserve,
      type: room.type,
    })
    expect(geometry(result)).toEqual(
      geometry({ ...room, length: 5.32, width: 3.32, height: 3, openings: 0 }),
    )
    expect(room.length).toBe(5)
  })

  it('never fills unknown height or openings from the old room', () => {
    const room = newRoom()
    for (const change of [
      { height: '' },
      { openings: '' },
      { height: 'NaN' },
      { openings: '-1' },
      { length: '0' },
      { width: '101' },
      { openings: '500' },
    ]) {
      expect(() =>
        applyPlanDimensions(room, JSON.stringify(room), {
          length: '5',
          width: '4',
          height: '3',
          openings: '0',
          ...change,
        }),
      ).toThrow()
    }
  })

  it('rejects results for a changed or different room', () => {
    const room = newRoom()
    const fingerprint = JSON.stringify(room)
    const values = { length: '5.32', width: '3.32', height: '3', openings: '0' }
    expect(() => applyPlanDimensions({ ...room, name: 'Изменено' }, fingerprint, values)).toThrow(
      'изменилась',
    )
    expect(() => applyPlanDimensions({ ...room, id: 'another' }, fingerprint, values)).toThrow(
      'изменилась',
    )
  })
})
