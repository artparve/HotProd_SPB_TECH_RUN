import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../../', import.meta.url)).replace(/\/$/, '')
const out = fileURLToPath(new URL('.', import.meta.url)).replace(/\/$/, '')
const fixture = JSON.parse(await readFile(`${root}/backend/data/catalog-v1.json`, 'utf8'))
const checks = []
async function request(label, path, expected, { body, raw, method, origin = 'http://127.0.0.1:8080', contentType = 'application/json' } = {}) {
  const response = await fetch(origin + path, {
    method: method || (body !== undefined || raw !== undefined ? 'POST' : 'GET'),
    headers: { Accept: 'application/json', ...(body !== undefined || raw !== undefined ? { 'Content-Type': contentType } : {}) },
    body: raw !== undefined ? raw : body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const text = await response.text()
  assert.equal(response.status, expected, `${label}: ${text}`)
  if (path.startsWith('/api/')) {
    assert.match(response.headers.get('content-type') || '', /application\/json/, label)
    assert.match(response.headers.get('x-request-id') || '', /^[0-9a-f-]{36}$/i, label)
  }
  checks.push({ label, status: response.status, requestId: response.headers.get('x-request-id') })
  return text ? (text.trim().startsWith('{') || text.trim().startsWith('[') ? JSON.parse(text) : text) : null
}
for (const probe of ['liveness', 'readiness']) {
  const health = await request(probe, `/actuator/health/${probe}`, 200, { origin: 'http://127.0.0.1:8000' })
  assert.equal(health.status, 'UP')
}
assert.equal(await request('nginx health', '/healthz', 200), 'ok\n')
const products = await request('catalog through nginx', '/api/v1/products', 200)
assert.equal(products.length, 12)
assert.deepEqual(products, await request('catalog direct API', '/api/v1/products', 200, { origin: 'http://127.0.0.1:8000' }))
await writeFile(`${out}/products-live.json`, JSON.stringify(products, null, 2))
const input = { area: 20, perimeter: 18, height: 2.7, reserve: 10, layers: 2, quantity: 6 }
const estimates = []
const byDemoId = Object.fromEntries(fixture.products.map(p => [p.sources[0].externalId, p.id]))
const examples = {
  'demo-laminate': [10, 22, 22.2, 0.2, '18900.00'],
  'demo-tile': [16, 22, 23.04, 1.04, '34560.00'],
  'demo-vinyl': [10, 22, 22.4, 0.4, '36900.00'],
  'demo-paint': [2, 5.28, 10, 4.72, '4980.00'],
  'demo-wet-paint': [2, 6.16, 10, 3.84, '6580.00'],
  'demo-primer': [1, 2.2, 5, 2.8, '690.00'],
  'demo-wallpaper': [7, 19, 21, 2, '13230.00'],
  'demo-glue': [4, 88, 100, 12, '3160.00'],
  'demo-socket': [6, 6, 6, 0, '2094.00'],
  'demo-wet-socket': [6, 6, 6, 0, '3540.00'],
  'demo-cable': [1, 6.6, 50, 43.4, '5490.00'],
}
for (const [demo, expected] of Object.entries(examples)) {
  const estimate = await request(`calculate ${demo}`, `/api/v1/product/${byDemoId[demo]}/calc`, 200, { body: input })
  assert.equal(estimate.mode, 'api')
  assert.equal(estimate.versions.model, 'not-used')
  const line = estimate.lines[0]
  assert.deepEqual([line.quantity, line.need, line.purchased, line.remainder, estimate.total], expected, demo)
  estimates.push(estimate)
}
await writeFile(`${out}/estimates-live.json`, JSON.stringify(estimates, null, 2))
for (const [script, file, args] of [
  ['catalog-contract.mjs', 'products-live.json', ['--fixtures']],
  ['calculation-contract.mjs', 'estimates-live.json', []],
]) {
  const result = spawnSync(process.execPath, ['--experimental-strip-types', `${root}/backend/src/test/js/${script}`, `${out}/${file}`, ...args], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  console.log(result.stdout.trim())
}
const tilePath = `/api/v1/product/${byDemoId['demo-tile']}/calc`
await request('unconfirmed norm', `/api/v1/product/${byDemoId['demo-unknown']}/calc`, 422, { body: input })
await request('missing product', '/api/v1/product/00000000-0000-0000-0000-000000000000/calc', 404, { body: input })
await request('old demo identifier', '/api/v1/product/demo-laminate/calc', 404, { body: input })
await request('invalid JSON', tilePath, 400, { raw: '{' })
await request('missing input fields', tilePath, 422, { body: {} })
await request('negative area', tilePath, 422, { body: { ...input, area: -1 } })
await request('fractional paint layers', `/api/v1/product/${byDemoId['demo-paint']}/calc`, 422, { body: { ...input, layers: 1.5 } })
await request('fractional socket points', `/api/v1/product/${byDemoId['demo-socket']}/calc`, 422, { body: { ...input, quantity: 1.5 } })
await request('unsupported method', tilePath, 405)
await request('unsupported media type', tilePath, 415, { raw: 'abc', contentType: 'text/plain' })
await request('estimate not implemented', '/api/v1/estimate', 404, { body: {} })
await request('recommendations not implemented', '/api/v1/recommendations', 404, { body: {} })
await writeFile(`${out}/checks.json`, JSON.stringify(checks, null, 2))
console.log(`PASS: ${checks.length} live HTTP checks through nginx/direct API; 11 exact calculations; no LLM.`)
