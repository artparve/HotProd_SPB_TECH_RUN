import { z } from 'zod'
import { projectSchema, roomSchema, type Estimate, type Product } from './types'
import { compatible } from './room'

// Same-origin images work with the existing CSP and do not leak visits to third parties.
export const inspirationImageUrlSchema = z
  .string()
  .max(2048)
  .regex(/^\/(?!\/)[^\s\\#]*$/)
export const roomInspirationSchema = z.object({
  id: z.string().min(1).max(128),
  title: z.string().min(1).max(120),
  description: z.string().max(1000),
  thumbnailUrl: inspirationImageUrlSchema,
  imageUrl: inspirationImageUrlSchema,
  alt: z.string().min(1).max(300),
  sourceUrl: z.string().url().startsWith('https://').optional(),
})
export const roomInspirationFeedSchema = z.object({
  status: z.enum(['ok', 'unavailable']).default('ok'),
  warnings: z.array(z.string()).default([]),
  items: z.array(roomInspirationSchema).max(24),
  nextCursor: z.string().min(1).max(512).nullable(),
})
const inspirationProjectSchema = projectSchema.extend({ rooms: z.array(roomSchema).length(1) })
export const roomInspirationRequestSchema = z.object({ project: inspirationProjectSchema })
export const roomInspirationResultSchema = z
  .object({
    status: z.enum(['ok', 'no_matches', 'unavailable']).default('ok'),
    imageId: z.string().min(1).max(128),
    project: inspirationProjectSchema,
    selections: z.record(z.string().min(1).max(128)),
    warnings: z.array(z.string().min(1).max(1000)).max(50),
  })
  .superRefine((value, ctx) => {
    const keys = Object.keys(value.selections)
    const kinds = ['covering', 'paint', 'primer', 'wallpaper', 'adhesive', 'socket', 'cable']
    if ((value.status === 'ok' ? !keys.length : keys.length !== 0) || keys.length > 7)
      ctx.addIssue({ code: 'custom', message: 'Expected 1–7 selections' })
    if (new Set(value.project.rooms.map((room) => room.id)).size !== value.project.rooms.length)
      ctx.addIssue({ code: 'custom', message: 'Duplicate room IDs' })
    for (const key of keys) {
      if (!kinds.some((kind) => key === `${value.project.rooms[0]!.id}:${kind}`))
        ctx.addIssue({ code: 'custom', path: ['selections', key], message: 'Unknown room or kind' })
    }
  })

export type RoomInspiration = z.infer<typeof roomInspirationSchema>
export type RoomInspirationFeed = z.infer<typeof roomInspirationFeedSchema>
export type RoomInspirationResult = z.infer<typeof roomInspirationResultSchema>
export type PreparedInspiration = {
  image: RoomInspiration
  result: RoomInspirationResult
  products: Product[]
  estimate: Estimate
}

export function validateInspirationProducts(result: RoomInspirationResult, products: Product[]) {
  for (const [key, productId] of Object.entries(result.selections)) {
    const product = products.find((item) => item.id === productId)
    const room = result.project.rooms.find((item) => key === `${item.id}:${product?.kind}`)
    if (
      !product ||
      !room ||
      !compatible(product, room) ||
      !product.works.some((work) => room.works.includes(work))
    )
      throw new Error('Распознанный товар недоступен или несовместим с проектом. Повторите подбор.')
  }
}

export function validateInspirationEstimate(result: RoomInspirationResult, estimate: Estimate) {
  for (const [key, id] of Object.entries(result.selections)) {
    if (
      !estimate.lines.some(
        (line) => `${line.roomId}:${line.product.kind}` === key && line.product.id === id,
      )
    )
      throw new Error('Расчёт не сохранил выбранные с изображения товары. Параметры не применены.')
  }
}
