import type { PlanResult } from '../../src/domain/plans'

// A synthetic recognition response; the tiny PNG only exercises browser file loading.
export const planImageBase64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII='
const evidence = { state: 'unknown' as const, value: null, label: null, bbox: null }
const measurement = { ...evidence, unit: null }
export const roomPlanResultMock: PlanResult = {
  schemaVersion: 'room-plan-draft-v1',
  promptVersion: 'test',
  model: 'mock-model',
  requiresConfirmation: true,
  draft: {
    shape: 'rectangle',
    name: evidence,
    length: measurement,
    width: measurement,
    height: measurement,
    openings: [],
    warnings: [],
  },
  dimensionsMetres: { length: 5.32, width: 3.32, height: null },
  warnings: ['Проверьте размеры по плану.'],
  missingFields: ['height', 'openingsArea'],
  elapsedSeconds: 1,
  image: { width: 1, height: 1, coordinates: 'normalized-oriented-image' },
}
