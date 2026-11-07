import { z } from 'zod'
import { roomSchema, workTypes } from './types'

export const materialConditionsSchema = z.object({
  humidity: z.enum(['normal', 'high']).nullable(),
  load: z.enum(['normal', 'high']).nullable(),
  heated: z.boolean().nullable(),
  substrate: z.string().min(1).max(100).nullable(),
  waterContact: z.string().min(1).max(100).nullable(),
  floorHeating: z.boolean().nullable(),
  covering: z.string().min(1).max(100).nullable(),
  baseState: z.string().min(1).max(100).nullable(),
})
export const materialRecommendationRequestSchema = z.object({
  works: z.array(z.enum(workTypes)).min(1).max(4),
  conditions: materialConditionsSchema,
  query: z.string().max(500),
  room: roomSchema.optional(),
  designPreferences: z.string().max(500).optional(),
  explain: z.boolean(),
  referenceSearch: z.boolean().optional(),
  renovation: z.enum(['cosmetic', 'capital', 'designer']).optional(),
  object: z.enum(['apartment', 'house', 'studio']).optional(),
})
const evidenceSchema = z.object({
  chunkId: z.string(),
  sourceId: z.string(),
  title: z.string(),
  url: z.string().url().startsWith('https://'),
  version: z.string(),
  sha256: z.string().regex(/^[a-f0-9]{64}$/),
  section: z.string(),
  text: z.string(),
})
const candidateSchema = z.object({
  productId: z.string().uuid(),
  productVersionId: z.string(),
  name: z.string(),
  compatibility: z.enum(['pass', 'unknown']),
  calculationReadiness: z.enum(['ready', 'unavailable']),
  reason: z.string(),
  checks: z.array(
    z.object({
      property: z.string(),
      status: z.enum(['pass', 'fail', 'unknown']),
      reason: z.string(),
    }),
  ),
  sources: z.array(evidenceSchema),
})
export const referenceAnswerSchema = z.object({
  status: z.enum(['disabled', 'unavailable', 'empty', 'deterministic', 'unverified_model_text']),
  indexId: z.string().nullable(),
  corpusMode: z.literal('research_draft'),
  usableForDecisions: z.literal(false),
  fragments: z.array(
    z.object({
      chunkId: z.string(),
      sourceId: z.string(),
      title: z.string(),
      publisher: z.string(),
      url: z.string().url().startsWith('https://').nullable(),
      sha256: z.string().regex(/^[a-f0-9]{64}$/),
      version: z.string(),
      versionNote: z.string(),
      page: z.number().int().positive(),
      text: z.string(),
      reviewStatus: z.string(),
      rightsStatus: z.string(),
      usableForDecisions: z.literal(false),
    }),
  ),
  items: z.array(z.object({ text: z.string(), chunkIds: z.array(z.string()) })),
  model: z.string().nullable(),
  fallbackReason: z.string().nullable(),
})
export const materialRecommendationsSchema = z.object({
  requestId: z.string().uuid(),
  status: z.enum(['ready', 'needs_clarification', 'partial']),
  catalogReleaseId: z.string().uuid(),
  ruleSetId: z.string().uuid(),
  knowledgeReleaseId: z.string().nullish(),
  indexVersion: z.string().nullish(),
  retrievalVersion: z.literal('bm25-v1'),
  references: referenceAnswerSchema.optional(),
  questions: z.array(
    z.object({ field: z.string(), reason: z.string(), options: z.array(z.string()) }),
  ),
  categories: z.array(
    z.object({
      work: z.enum(workTypes),
      kind: z.string(),
      coveringFamily: z.string().nullish(),
      candidates: z.array(candidateSchema),
    }),
  ),
  warnings: z.array(z.string()),
  explanation: z.object({
    status: z.enum(['deterministic', 'unverified_model_text']),
    model: z.string().nullish(),
    fallbackReason: z.string().nullish(),
    items: z.array(
      z.object({ productId: z.string().uuid(), text: z.string(), chunkIds: z.array(z.string()) }),
    ),
  }),
})
export type MaterialRecommendations = z.infer<typeof materialRecommendationsSchema>
export type MaterialRecommendationRequest = z.infer<typeof materialRecommendationRequestSchema>
export const estimateExplanationSchema = z.object({
  status: z.enum(['unverified_model_text', 'unavailable']),
  model: z.string().nullable(),
  explanation: z.string().nullable(),
})
export type EstimateExplanation = z.infer<typeof estimateExplanationSchema>
