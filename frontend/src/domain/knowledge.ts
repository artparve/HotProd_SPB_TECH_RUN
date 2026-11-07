import { z } from 'zod'
const id = z.string().min(1).max(80)
const uuid = z.string().uuid()
export const knowledgeBundleSchema = z
  .object({
    catalogReleaseId: uuid,
    ruleSetId: uuid,
    sources: z
      .array(
        z
          .object({
            id,
            title: z.string().min(1).max(200),
            publisher: z.string().min(1).max(200),
            url: z.string().url().startsWith('https://').max(1000),
            version: z.string().min(1).max(100),
            retrievedOn: z.string().date(),
            validUntil: z.string().date(),
            sha256: z.string().regex(/^[a-f0-9]{64}$/),
            rightsStatus: z.literal('approved'),
            reviewStatus: z.literal('confirmed'),
          })
          .strict(),
      )
      .min(1)
      .max(100),
    chunks: z
      .array(
        z
          .object({
            id,
            productId: uuid,
            productVersionId: z.string().min(1).max(100),
            sourceId: id,
            section: z.string().min(1).max(200),
            text: z.string().min(1).max(1500),
          })
          .strict(),
      )
      .min(1)
      .max(500),
    claims: z
      .array(
        z
          .object({
            productId: uuid,
            property: z.enum([
              'work',
              'wet',
              'highLoad',
              'unheated',
              'substrate',
              'waterContact',
              'floorHeating',
              'covering',
              'baseState',
            ]),
            value: z.string().min(1).max(100),
            status: z.enum(['confirmed', 'unknown', 'conflict']),
            chunkIds: z.array(id).min(1).max(10),
          })
          .strict(),
      )
      .min(1)
      .max(500),
    requirements: z
      .array(
        z
          .object({
            work: z.enum(['floor', 'paint', 'wallpaper', 'electrical']),
            kind: z.string().min(1).max(50),
            conditions: z
              .array(
                z.enum([
                  'humidity',
                  'load',
                  'heated',
                  'substrate',
                  'waterContact',
                  'floorHeating',
                  'covering',
                  'baseState',
                ]),
              )
              .min(1)
              .max(8),
          })
          .strict(),
      )
      .min(1)
      .max(30),
    combinations: z
      .array(
        z
          .object({
            first: uuid,
            second: uuid,
            status: z.enum(['pass', 'fail', 'unknown']),
            chunkIds: z.array(id).min(1).max(20),
          })
          .strict(),
      )
      .max(500),
  })
  .strict()
export const knowledgeReleaseSchema = z.object({
  id: uuid,
  bundle: knowledgeBundleSchema,
  checksum: z.string(),
  retrievalVersion: z.literal('bm25-v1'),
  publishedAt: z.string().datetime(),
  reviewedBy: z.string(),
  manifest: z.object({
    embeddingModel: z.literal('none'),
    dimension: z.literal(0),
    normalizationVersion: z.literal('unicode-lower-yo-v1'),
    chunkingVersion: z.literal('operator-v1'),
    documentCount: z.number().int().nonnegative(),
    productCount: z.number().int().nonnegative(),
  }),
})
