// @vitest-environment node
import SwaggerParser from '@apidevtools/swagger-parser'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { materialEstimateSchema, materialRequestSchema } from './materials'
import {
  adminDraftSchema,
  adminProductSchema,
  adminSessionSchema,
  adminVersionSchema,
} from './admin'
import {
  estimateSchema,
  lineSchema,
  productInputSchema,
  productSchema,
  projectSchema,
  recommendationSchema,
  roomSchema,
} from './types'

const contractPath = resolve(process.cwd(), '../docs/openapi.yaml')

describe('OpenAPI contract', () => {
  it('keeps the implemented administrative contract aligned with its strict Zod schemas', async () => {
    const api = await SwaggerParser.validate(
      resolve(process.cwd(), '../docs/admin-catalog-api.json'),
    )
    if (!('components' in api)) throw new Error('Expected OpenAPI 3 document')
    for (const [name, shape] of Object.entries({
      AdminDraft: adminDraftSchema.shape,
      AdminProduct: adminProductSchema.shape,
      AdminSession: adminSessionSchema.shape,
      AdminVersion: adminVersionSchema.shape,
    })) {
      const schema = api.components!.schemas![name]
      if ('$ref' in schema) throw new Error(`Unresolved schema: ${name}`)
      expect(Object.keys(schema.properties!).sort(), name).toEqual(Object.keys(shape).sort())
      expect(schema.required!.slice().sort(), name).toEqual(
        Object.entries(shape)
          .filter(([, field]) => !field.isOptional())
          .map(([key]) => key)
          .sort(),
      )
    }
  })
  it('is a valid OpenAPI document with resolvable references and all client endpoints', async () => {
    const api = await SwaggerParser.validate(contractPath)
    if (!('openapi' in api)) throw new Error('Expected OpenAPI 3 document')
    expect(api.openapi).toBe('3.0.3')
    expect(Object.keys(api.paths!).sort()).toEqual([
      '/admin/login',
      '/admin/logout',
      '/admin/products',
      '/admin/products/{id}',
      '/admin/products/{id}/classifications',
      '/admin/products/{id}/history',
      '/admin/products/{id}/publish',
      '/admin/products/{id}/readiness',
      '/admin/products/{id}/reviews',
      '/admin/review-options',
      '/admin/session',
      '/estimate',
      '/materials/calculate',
      '/materials/estimate',
      '/plans/extract',
      '/product/{id}/calc',
      '/products',
      '/recommendations',
    ])
  })

  it('keeps response and request property sets aligned with Zod runtime schemas', async () => {
    const api = await SwaggerParser.validate(contractPath)
    if (!('components' in api)) throw new Error('Expected OpenAPI 3 document')
    const schemas = api.components!.schemas!
    const pairs = {
      Room: roomSchema.innerType().shape,
      Project: projectSchema.shape,
      Product: productSchema.shape,
      ProductInput: productInputSchema.shape,
      EstimateLine: lineSchema.shape,
      Estimate: estimateSchema.shape,
      Recommendations: recommendationSchema.shape,
      MaterialRequest: materialRequestSchema.shape,
      MaterialEstimate: materialEstimateSchema.innerType().shape,
    }
    for (const [name, shape] of Object.entries(pairs)) {
      const schema = schemas[name]
      if ('$ref' in schema) throw new Error(`Unresolved schema: ${name}`)
      expect(Object.keys(schema.properties!).sort(), name).toEqual(Object.keys(shape).sort())
      const required = Object.entries(shape)
        .filter(([, field]) => !field.isOptional())
        .map(([key]) => key)
        .sort()
      expect(schema.required!.slice().sort(), name).toEqual(required)
    }
  })
})
