import { z } from 'zod'
import { roomSchema, type Room } from './types'

const bboxSchema = z
  .object({
    x: z.number().min(0).max(1),
    y: z.number().min(0).max(1),
    width: z.number().positive().max(1),
    height: z.number().positive().max(1),
  })
  .refine((box) => box.x + box.width <= 1.000001 && box.y + box.height <= 1.000001)
const evidenceSchema = z.object({
  state: z.enum(['found', 'unknown', 'conflict']),
  value: z.string().nullable(),
  label: z.string().nullable(),
  bbox: bboxSchema.nullable(),
})
const measurementSchema = evidenceSchema.extend({ unit: z.enum(['mm', 'cm', 'm']).nullable() })
export const planResultSchema = z.object({
  schemaVersion: z.literal('room-plan-draft-v1'),
  promptVersion: z.string(),
  model: z.string(),
  requiresConfirmation: z.literal(true),
  draft: z.object({
    shape: z.enum(['rectangle', 'non_rectangular', 'multiple', 'unknown']),
    name: evidenceSchema,
    length: measurementSchema,
    width: measurementSchema,
    height: measurementSchema,
    openings: z.array(
      z.object({
        kind: z.enum(['window', 'door', 'unknown']),
        width: measurementSchema,
        height: measurementSchema,
      }),
    ),
    warnings: z.array(z.string()),
  }),
  dimensionsMetres: z.object({
    length: z.number().positive().max(100).nullable(),
    width: z.number().positive().max(100).nullable(),
    height: z.number().min(1).max(10).nullable(),
  }),
  warnings: z.array(z.string()),
  missingFields: z.array(z.string()),
  elapsedSeconds: z.number().nonnegative(),
  image: z.object({
    width: z.number().int().positive(),
    height: z.number().int().positive(),
    coordinates: z.literal('normalized-oriented-image'),
  }),
})
export type PlanResult = z.infer<typeof planResultSchema>
export const dimensionKeys = ['length', 'width', 'height', 'openings'] as const
export type DimensionKey = (typeof dimensionKeys)[number]
export type ConfirmedDimensions = Record<DimensionKey, string>

export function applyPlanDimensions(room: Room, fingerprint: string, values: ConfirmedDimensions) {
  if (JSON.stringify(room) !== fingerprint)
    throw new Error('Комната изменилась. Повторите распознавание для текущей комнаты.')
  const dimensions = Object.fromEntries(
    dimensionKeys.map((key) => {
      const text = values[key].trim().replace(',', '.')
      if (!/^\d+(?:\.\d+)?$/.test(text))
        throw new Error(
          'Заполните все размеры и площадь проёмов числами. Нулевые проёмы укажите явно.',
        )
      return [key, Number(text)]
    }),
  )
  const result = roomSchema.safeParse({ ...room, ...dimensions })
  if (!result.success)
    throw new Error(
      'Проверьте размеры: длина и ширина до 100 м, высота 1–10 м, проёмы меньше площади стен.',
    )
  return result.data
}
