import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { catalog } from '../data/catalog'
import { newRoom } from '../domain/types'
import { productDemo } from '../services/demo'

const storageKey = 'repaircalc-workspace-v1'
const estimate = productDemo(catalog[0]!, {
  area: 20,
  perimeter: 18,
  height: 2.7,
  reserve: 10,
  layers: 2,
  quantity: 6,
})

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers()
  localStorage.clear()
})
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('workspace persistence', () => {
  it('saves estimates and cart while preserving the last valid project during incomplete input', async () => {
    const initial = {
      project: { object: 'apartment', renovation: 'capital', rooms: [newRoom()] },
      saved: [],
      cart: null,
      roomInputs: {},
    }
    localStorage.setItem(storageKey, JSON.stringify(initial))
    const { workspace, saveEstimate, setCart } = await import('./useWorkspace')
    const { nextTick } = await import('vue')
    workspace.project.rooms[0]!.length = '' as unknown as number
    await nextTick()
    saveEstimate(estimate)
    setCart(estimate)
    await nextTick()

    const stored = JSON.parse(localStorage.getItem(storageKey)!)
    expect(stored.project).toEqual(initial.project)
    expect(stored.saved).toEqual([estimate])
    expect(stored.cart).toEqual(estimate)
    expect(workspace.project.rooms[0]!.length).toBe('')
  })

  it('resumes project persistence after incomplete dimensions become valid', async () => {
    const { workspace } = await import('./useWorkspace')
    const { nextTick } = await import('vue')
    workspace.project.rooms[0]!.length = 6
    await nextTick()
    workspace.project.rooms[0]!.length = '' as unknown as number
    await nextTick()
    expect(JSON.parse(localStorage.getItem(storageKey)!).project.rooms[0].length).toBe(6)
    workspace.project.rooms[0]!.length = 7
    await nextTick()
    expect(JSON.parse(localStorage.getItem(storageKey)!).project.rooms[0].length).toBe(7)
  })
})
