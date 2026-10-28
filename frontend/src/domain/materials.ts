import { z } from 'zod'
import { estimateSchema, productInputSchema } from './types'

export const apiErrorSchema = z.object({
  code: z.string(),
  message: z.string().min(1).max(2000),
  fields: z.array(z.string()).optional(),
})

export const materialRequestSchema = z.object({
  productId: z.string().uuid(),
  input: productInputSchema,
  prompt: z.string().min(1).max(8000).optional(),
  useCorrection: z.boolean().optional(),
})

export const materialEstimateSchema = z
  .object({
    status: z.literal('ok'),
    estimate: estimateSchema,
    baseline: estimateSchema,
    correction: z.object({
      applied: z.boolean(),
      version: z.string(),
      factor: z.string().regex(/^\d+(\.\d+)?$/),
      originalReserve: z.string().optional(),
      effectiveReserve: z.string().optional(),
    }),
    model: z.string().min(1),
    toolCalls: z.tuple([z.literal('calculate_material_cost')]),
    explanation: z.string().nullable(),
    explanationStatus: z.enum(['unverified_model_text', 'unavailable']),
    explanationError: apiErrorSchema.optional(),
  })
  .superRefine((value, context) => {
    if (
      value.estimate.mode !== 'api' ||
      value.estimate.lines.length !== 1 ||
      value.baseline.mode !== 'api' ||
      value.baseline.lines.length !== 1
    )
      context.addIssue({ code: 'custom', message: 'Expected a single-product API calculation' })
    if (value.explanationStatus === 'unverified_model_text' && !value.explanation?.trim())
      context.addIssue({ code: 'custom', path: ['explanation'], message: 'Explanation is empty' })
    if (value.explanationStatus === 'unavailable' && !value.explanationError)
      context.addIssue({ code: 'custom', message: 'Missing explanation error' })
  })

export type MaterialEstimate = z.infer<typeof materialEstimateSchema>
