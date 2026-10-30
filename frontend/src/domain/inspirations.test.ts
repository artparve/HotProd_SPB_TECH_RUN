import { describe, expect, it } from 'vitest'
import { catalog } from '../data/catalog'
import { analyzeInspirationMock, inspirationFeedMock } from '../data/inspirations'
import { newRoom, type Project } from './types'
import { estimateDemo } from '../services/demo'
import {
  roomInspirationFeedSchema,
  roomInspirationResultSchema,
  validateInspirationEstimate,
  validateInspirationProducts,
} from './inspirations'

describe('inspiration fixtures and calculator invariants', () => {
  it('validates mock feeds and every result with the runtime contract', () => {
    expect(roomInspirationFeedSchema.parse(inspirationFeedMock)).toEqual(inspirationFeedMock)
    for (const restricted of [false, true]) {
      for (const image of inspirationFeedMock.items) {
        const project: Project = {
          object: 'apartment',
          renovation: 'capital',
          rooms: [
            {
              ...newRoom(),
              id: 'room-1',
              humidity: restricted ? 'high' : 'normal',
              heated: !restricted,
            },
            { ...newRoom(2), id: 'room-2' },
          ],
        }
        const original = structuredClone(project)
        const result = roomInspirationResultSchema.parse(analyzeInspirationMock(image.id, project))
        validateInspirationProducts(result, catalog)
        validateInspirationEstimate(result, estimateDemo(result.project, result.selections))
        expect(project).toEqual(original)
        expect(result.project.rooms[1]).toEqual(original.rooms[1])
        expect(result.project.rooms[0]!.length).toBe(original.rooms[0]!.length)
      }
    }
  })

  it('rejects an unknown product, a wrong product kind, and a calculation that substitutes a selected product', () => {
    const project: Project = {
      object: 'house',
      renovation: 'cosmetic',
      rooms: [{ ...newRoom(), id: 'room-1' }],
    }
    const result = analyzeInspirationMock(inspirationFeedMock.items[0]!.id, project)
    expect(() =>
      validateInspirationProducts(
        { ...result, selections: { 'room-1:covering': 'missing' } },
        catalog,
      ),
    ).toThrow('недоступен')
    expect(() =>
      validateInspirationProducts(
        { ...result, selections: { 'room-1:paint': 'demo-laminate' } },
        catalog,
      ),
    ).toThrow('несовместим')
    const wrong = estimateDemo(project, { 'room-1:covering': 'demo-tile' })
    expect(() => validateInspirationEstimate(result, wrong)).toThrow('не сохранил')
  })
})
