import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { productSchema } from '../../../../frontend/src/domain/types.ts'

// Resolve the frontend's own Zod dependency; no second package installation is needed.
const { z } = createRequire(new URL('../../../../frontend/package.json', import.meta.url))('zod')
const [responsePath, fixtureMode, ...extraArguments] = process.argv.slice(2)
if (!responsePath || (fixtureMode && fixtureMode !== '--fixtures') || extraArguments.length) {
  console.error('Usage: node --experimental-strip-types backend/src/test/js/catalog-contract.mjs <products-response.json> [--fixtures]')
  process.exit(2)
}

const products = z.array(productSchema).parse(JSON.parse(await readFile(resolve(responsePath), 'utf8')))
const testMarker = 'ТЕСТОВЫЕ данные, не актуальные цены/нормы; '
for (const product of products) {
  assert.ok(product.source.startsWith(testMarker), `Product ${product.id} must identify its synthetic test data`)
}

// An empty publication is a valid API response. Exact fixture coverage is an explicit extra check.
if (fixtureMode === '--fixtures') {
  const catalog = JSON.parse(await readFile(new URL('../../../data/catalog-v1.json', import.meta.url), 'utf8'))
  const mappingDocument = JSON.parse(await readFile(new URL('../../../data/work-mappings-v1.json', import.meta.url), 'utf8'))
  const worksByProduct = new Map(mappingDocument.mappings.map(({ productId, works }) => [productId, works]))
  assert.equal(catalog.products.length, 12, 'The v1 fixture set must contain all 12 original UI products')
  assert.equal(worksByProduct.size, 12, 'Every fixture product must have a work mapping')
  const expected = z.array(productSchema).parse(catalog.products.map(({ sources, ...product }) => ({
    ...product,
    works: worksByProduct.get(product.id),
  })))
  const byId = (left, right) => left.id.localeCompare(right.id)
  const ordered = (rows) => rows.map((product) => ({ ...product, works: [...product.works].sort() })).sort(byId)
  assert.deepEqual(ordered(products), ordered(expected), 'API products must preserve all v1 fixture values and mappings')
}

console.log(`Catalog contract passed: ${products.length} product(s)${fixtureMode ? ', complete v1 fixture coverage' : ''}.`)
