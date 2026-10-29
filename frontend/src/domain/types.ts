import { z } from 'zod'
import { newId } from './id'

export const roomTypes = ['living', 'bathroom', 'kitchen', 'children', 'balcony'] as const
export const workTypes = ['floor', 'paint', 'wallpaper', 'electrical'] as const
export const roomLabels: Record<RoomType, string> = {
  living: 'Жилая комната',
  bathroom: 'Санузел',
  kitchen: 'Кухня',
  children: 'Детская',
  balcony: 'Балкон',
}
export const workLabels: Record<Work, string> = {
  floor: 'Напольное покрытие',
  paint: 'Покраска стен',
  wallpaper: 'Поклейка обоев',
  electrical: 'Электрика',
}
export const roomSchema = z
  .object({
    id: z.string(),
    name: z.string().min(1).max(60),
    type: z.enum(roomTypes),
    length: z.number().positive().max(100),
    width: z.number().positive().max(100),
    height: z.number().min(1).max(10),
    openings: z.number().min(0).max(2000),
    humidity: z.enum(['normal', 'high']),
    load: z.enum(['normal', 'high']),
    heated: z.boolean(),
    works: z.array(z.enum(workTypes)).min(1),
    reserve: z.number().min(0).max(30),
    layers: z.number().int().min(1).max(4),
    sockets: z.number().int().min(0).max(200),
    cableLength: z.number().min(0).max(5000),
  })
  .superRefine((room, ctx) => {
    if (room.openings >= 2 * (room.length + room.width) * room.height) {
      ctx.addIssue({
        code: 'custom',
        path: ['openings'],
        message: 'Площадь проёмов должна быть меньше площади стен.',
      })
    }
    if (room.works.includes('paint') && room.works.includes('wallpaper')) {
      ctx.addIssue({
        code: 'custom',
        path: ['works'],
        message: 'Выберите одну отделку стен: краску или обои.',
      })
    }
  })
export const projectSchema = z.object({
  object: z.enum(['apartment', 'house', 'studio']),
  renovation: z.enum(['cosmetic', 'capital', 'designer']),
  rooms: z.array(roomSchema).min(1).max(20),
})
export type RoomType = (typeof roomTypes)[number]
export type Work = (typeof workTypes)[number]
export type Room = z.infer<typeof roomSchema>
export type Project = z.infer<typeof projectSchema>
export const productSchema = z.object({
  id: z.string(),
  name: z.string(),
  category: z.string(),
  kind: z.enum(['covering', 'paint', 'primer', 'wallpaper', 'adhesive', 'socket', 'cable']),
  price: z.string().regex(/^\d+(\.\d{1,2})?$/),
  packageSize: z.number().positive().nullable(),
  unit: z.string(),
  saleUnit: z.string(),
  works: z.array(z.enum(workTypes)),
  wet: z.boolean().nullable(),
  highLoad: z.boolean().nullable(),
  unheated: z.boolean().nullable(),
  description: z.string(),
  source: z.string(),
  consumption: z.number().positive().optional(),
  rollWidth: z.number().positive().optional(),
  rollLength: z.number().positive().optional(),
  rapport: z.number().nonnegative().optional(),
})
export type Product = z.infer<typeof productSchema>
export const lineSchema = z.object({
  id: z.string(),
  roomId: z.string(),
  roomName: z.string(),
  product: productSchema,
  quantity: z.number().int().positive(),
  need: z.number().nonnegative(),
  purchased: z.number().nonnegative(),
  remainder: z.number().nonnegative(),
  total: z.string().regex(/^\d+(\.\d{1,2})?$/),
  formula: z.string(),
  reason: z.string(),
})
export type EstimateLine = z.infer<typeof lineSchema>
const amountSchema = z.string().regex(/^\d+(\.\d{1,2})?$/)
export const estimateSchema = z.object({
  id: z.string(),
  createdAt: z.string().datetime(),
  mode: z.enum(['demo', 'api']),
  lines: z.array(lineSchema),
  materials: amountSchema,
  labor: amountSchema,
  total: amountSchema,
  warnings: z.array(z.string()),
  versions: z.object({ catalog: z.string(), rules: z.string(), model: z.string() }),
})
export type Estimate = z.infer<typeof estimateSchema>
export const recommendationSchema = z.object({
  products: z.array(z.object({ product: productSchema, reason: z.string() })),
  warnings: z.array(z.string()),
})
export type Recommendations = z.infer<typeof recommendationSchema>
export const productInputSchema = z.object({
  area: z.number().positive().max(10000),
  perimeter: z.number().positive().max(1000),
  height: z.number().min(1).max(10),
  reserve: z.number().min(0).max(30),
  layers: z.number().int().min(1).max(4),
  quantity: z.number().positive().max(5000),
})
export type ProductInput = z.infer<typeof productInputSchema>

export function newRoom(index = 1): Room {
  return {
    id: newId(),
    name: `Комната ${index}`,
    type: 'living',
    length: 5,
    width: 4,
    height: 2.7,
    openings: 3.5,
    humidity: 'normal',
    load: 'normal',
    heated: true,
    works: ['floor', 'paint'],
    reserve: 10,
    layers: 2,
    sockets: 6,
    cableLength: 25,
  }
}
