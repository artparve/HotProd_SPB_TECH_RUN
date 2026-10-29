import { z } from 'zod'

function field<T extends z.ZodTypeAny>(value: T) {
  return z.discriminatedUnion('state', [
    z
      .object({
        state: z.literal('unset'),
        source: z.string().max(4000).optional(),
        value: z.null().optional(),
      })
      .strict(),
    z
      .object({
        state: z.literal('unknown'),
        source: z.string().max(4000).optional(),
        value: z.null().optional(),
      })
      .strict(),
    z
      .object({
        state: z.literal('confirmed'),
        value,
        source: z.string().trim().min(1).max(4000),
        confirmedBy: z.string().optional(),
      })
      .strict(),
  ])
}
const text = z.string().min(1).max(4000)
const units = z.enum(['м²', 'л', 'кг', 'м', 'шт.', 'полотен'])
export const adminDraftSchema = z
  .object({
    sourceNamespace: z.string().trim().min(1).max(500),
    externalId: z.string().trim().min(1).max(500),
    name: z.string().trim().min(1).max(500),
    sku: z.string().max(20000).optional(),
    description: z.string().max(20000).optional(),
    application: z.string().max(20000).optional(),
    limitations: z.string().max(20000).optional(),
    comment: z.string().max(20000).optional(),
    attributes: z
      .array(z.object({ name: text, value: text, unit: z.string().max(4000).optional() }).strict())
      .max(200)
      .optional(),
    sources: z
      .array(z.object({ label: text, url: z.string().max(4000).optional() }).strict())
      .max(200)
      .optional(),
    category: field(text).optional(),
    price: field(
      z
        .string()
        .regex(/^[0-9]{1,17}(\.[0-9]{1,2})?$/)
        .refine((n) => Number(n) > 0),
    ).optional(),
    currency: field(z.literal('RUB')).optional(),
    priceDate: field(z.string().date()).optional(),
    unit: field(units).optional(),
    saleUnit: field(
      z.enum(['м²', 'л', 'кг', 'м', 'шт.', 'уп.', 'рул.', 'бан.', 'меш.', 'кан.', 'бух.']),
    ).optional(),
    packageSize: field(z.number().positive()).optional(),
    consumption: field(z.number().positive()).optional(),
    consumptionUnit: field(units).optional(),
    kind: field(
      z.enum(['covering', 'paint', 'primer', 'wallpaper', 'adhesive', 'socket', 'cable']),
    ).optional(),
    wet: field(z.boolean()).optional(),
    highLoad: field(z.boolean()).optional(),
    unheated: field(z.boolean()).optional(),
    rollWidth: field(z.number().positive()).optional(),
    rollLength: field(z.number().positive()).optional(),
    rapport: field(z.number().nonnegative()).optional(),
  })
  .strict()
export type AdminDraft = z.infer<typeof adminDraftSchema>
export const adminProductSchema = z
  .object({
    id: z.string().uuid(),
    sourceNamespace: z.string(),
    externalId: z.string(),
    versionId: z.string().uuid(),
    version: z.number().int().positive(),
    status: z.enum(['draft', 'review', 'published', 'archived']),
    snapshot: adminDraftSchema,
    createdAt: z.string().datetime(),
    createdBy: z.string(),
    updatedAt: z.string().datetime(),
    updatedBy: z.string(),
  })
  .strict()
export type AdminProduct = z.infer<typeof adminProductSchema>
export const adminVersionSchema = z
  .object({
    id: z.string().uuid(),
    version: z.number().int().positive(),
    snapshot: adminDraftSchema,
    createdAt: z.string().datetime(),
    createdBy: z.string(),
    requestId: z.string().nullable(),
    action: z.string().nullable(),
  })
  .strict()
export const adminSessionSchema = z
  .object({
    authenticated: z.boolean(),
    username: z.string().nullable(),
    csrfToken: z.string().min(1),
  })
  .strict()
export function adminPageSchema<T extends z.ZodTypeAny>(item: T) {
  return z
    .object({
      items: z.array(item),
      page: z.number().int().nonnegative(),
      size: z.number().int().positive(),
      total: z.number().int().nonnegative(),
    })
    .strict()
}
export const adminErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  requestId: z.string(),
  details: z.array(z.object({ field: z.string(), code: z.string(), message: z.string() })),
})
