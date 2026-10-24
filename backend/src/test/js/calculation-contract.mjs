import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { estimateSchema } from '../../../../frontend/src/domain/types.ts'

// Use the same schemas and decimal arithmetic packages as the frontend.
const requireFrontend = createRequire(new URL('../../../../frontend/package.json', import.meta.url))
const { z } = requireFrontend('zod')
const Decimal = requireFrontend('decimal.js').clone({ precision: 100 })
const [responsePath, ...extraArguments] = process.argv.slice(2)
if (!responsePath || extraArguments.length) {
  console.error('Usage: node --experimental-strip-types backend/src/test/js/calculation-contract.mjs <product-estimates-response.json>')
  process.exit(2)
}

// Zod normally strips unknown keys. Reject them here to catch persistence/internal fields in responses.
const lineSchema = estimateSchema.shape.lines.element
const strictEstimateSchema = estimateSchema.strict().extend({
  lines: z.array(lineSchema.strict().extend({ product: lineSchema.shape.product.strict() })),
  versions: estimateSchema.shape.versions.strict(),
})
const estimates = z.array(strictEstimateSchema).parse(JSON.parse(await readFile(resolve(responsePath), 'utf8')))
const catalog = JSON.parse(await readFile(new URL('../../../data/catalog-v1.json', import.meta.url), 'utf8'))
const mappings = JSON.parse(await readFile(new URL('../../../data/work-mappings-v1.json', import.meta.url), 'utf8'))
const rules = JSON.parse(await readFile(new URL('../../../data/rules-v1.json', import.meta.url), 'utf8'))
const catalogById = new Map(catalog.products.map((product) => [product.id, product]))
const worksById = new Map(mappings.mappings.map((mapping) => [mapping.productId, [...mapping.works].sort()]))
const supportedIds = rules.assignments.filter((assignment) => assignment.profileId !== null).map((assignment) => assignment.productId)
const blockedIds = new Set(rules.assignments.filter((assignment) => assignment.blockedReason !== null).map((assignment) => assignment.productId))

assert.equal(supportedIds.length, 11, 'The prepared rules must explicitly support 11 fixture products')
assert.equal(new Set(supportedIds).size, 11, 'Supported fixture product IDs must be unique')
assert.equal(blockedIds.size, 1, 'The fixture without a confirmed norm must remain blocked')
assert.equal(estimates.length, supportedIds.length, 'Export one real successful calculation per supported product')

const cents = (value, label) => {
  assert.equal(typeof value, 'string', `${label} must be a decimal money string`)
  assert.match(value, /^\d+(\.\d{1,2})?$/, `${label} must contain at most two fractional digits`)
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
const amount = (value, label) => {
  assert.equal(typeof value, 'number', `${label} must be a JSON number`)
  assert.ok(Number.isFinite(value), `${label} must be finite`)
  return new Decimal(value.toString())
}
const actualIds = []

for (const estimate of estimates) {
  assert.equal(estimate.mode, 'api')
  assert.equal(estimate.lines.length, 1, 'Single-product calculations must return exactly one line')
  assert.equal(estimate.versions.catalog, catalog.version)
  assert.equal(estimate.versions.rules, rules.version)
  assert.equal(estimate.versions.model, 'not-used')
  const line = estimate.lines[0]
  const product = line.product
  actualIds.push(product.id)
  assert.ok(!blockedIds.has(product.id), 'An explicitly blocked product cannot produce a successful estimate')
  const prepared = catalogById.get(product.id)
  assert.ok(prepared, `Unknown fixture product ${product.id}`)
  const { sources, ...expectedProduct } = prepared
  expectedProduct.works = worksById.get(product.id)
  assert.deepEqual({ ...product, works: [...product.works].sort() }, expectedProduct,
    `Calculated product ${product.id} must preserve its published card and nullable/optional fields`)
  assert.ok(product.source.startsWith('ТЕСТОВЫЕ данные, не актуальные цены/нормы; '))
  assert.ok(line.formula.trim().length > 0, 'The arithmetic must have an explanation')
  assert.ok(line.reason.trim().length > 0, 'The chosen sourced profile must have a reason')

  assert.ok(Number.isSafeInteger(line.quantity) && line.quantity > 0,
    'The number of sale units must be a positive JavaScript safe integer')
  const need = amount(line.need, 'need')
  const purchased = amount(line.purchased, 'purchased')
  const remainder = amount(line.remainder, 'remainder')
  assert.ok(need.gt(0), 'A successful fixture calculation must have a positive need')
  assert.ok(purchased.gte(need), 'Purchased material must cover the need')
  assert.ok(remainder.gte(0), 'Remainder cannot be negative')
  assert.ok(purchased.minus(need).eq(remainder), 'Remainder must equal purchased minus need exactly')

  let packageSize
  if (product.kind === 'wallpaper') {
    assert.equal(product.packageSize, null, 'Wallpaper packaging is derived from its roll geometry')
    assert.ok(need.isInteger() && purchased.isInteger() && remainder.isInteger(),
      'Wallpaper quantities are expressed as whole strips')
    packageSize = purchased.div(line.quantity)
    assert.ok(packageSize.isInteger() && packageSize.gt(0), 'Each sold roll must yield a positive whole strip count')
  } else {
    packageSize = amount(product.packageSize, 'product.packageSize')
    assert.ok(packageSize.gt(0), 'A calculable fixture must have a positive package size')
    assert.ok(packageSize.times(line.quantity).eq(purchased), 'Purchased material must equal package size times sale units')
    if (product.kind === 'socket') assert.ok(need.isInteger(), 'Socket need must be a whole point count')
  }
  assert.ok(need.div(packageSize).ceil().eq(line.quantity), 'Use the minimum whole number of sale units covering the need')
  assert.ok(remainder.lt(packageSize), 'A whole additional package must not remain unused')

  const lineTotal = cents(line.total, 'line.total')
  assert.equal(lineTotal, cents(product.price, 'product.price') * BigInt(line.quantity),
    'Line total must equal the exact sale price times sale units')
  const materials = cents(estimate.materials, 'materials')
  const labor = cents(estimate.labor, 'labor')
  const total = cents(estimate.total, 'total')
  assert.equal(estimate.labor, '0.00', 'Single-product calculation does not price labor')
  assert.equal(labor, 0n)
  assert.equal(materials, lineTotal)
  assert.equal(total, materials + labor)
}

assert.deepEqual([...actualIds].sort(), [...supportedIds].sort(), 'Export all 11 supported fixture IDs exactly once')
assert.equal(new Set(actualIds).size, supportedIds.length)
console.log(`Calculation contract passed: ${estimates.length} real single-product estimates, exact totals and packaging invariants.`)
