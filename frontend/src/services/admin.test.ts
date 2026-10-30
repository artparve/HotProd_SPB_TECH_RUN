import { afterEach, describe, expect, it, vi } from 'vitest'
import { adminDraftSchema } from '../domain/admin'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})
describe('administrative API', () => {
  it('always uses the server, rotates CSRF and sends the received ETag unchanged', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ authenticated: false, username: null, csrfToken: 'anonymous-token' }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ authenticated: true, username: 'admin', csrfToken: 'session-token' }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: 'version_mismatch',
            message: 'Перечитайте карточку',
            requestId: 'request',
            details: [],
          }),
          { status: 412 },
        ),
      )
    vi.stubGlobal('fetch', fetch)
    const { admin, AdminApiError } = await import('./admin')
    await admin.session()
    await admin.login('admin', 'test-password')
    await expect(
      admin.update(
        'product-id',
        { sourceNamespace: 'supplier', externalId: '42', name: 'Draft' },
        '"version-id"',
      ),
    ).rejects.toBeInstanceOf(AdminApiError)
    expect(fetch.mock.calls[1]![1].headers['X-CSRF-Token']).toBe('anonymous-token')
    expect(fetch.mock.calls[2]![0]).toBe('/api/v1/admin/products/product-id')
    expect(fetch.mock.calls[2]![1]).toMatchObject({
      method: 'PUT',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'X-CSRF-Token': 'session-token', 'If-Match': '"version-id"' },
    })
  })

  it('rejects unsupported values, hidden unknown values and unconfirmed money before network access', () => {
    const base = { sourceNamespace: 'supplier', externalId: '42', name: 'Draft' }
    expect(adminDraftSchema.parse({ ...base, wet: { state: 'unknown' } }).wet).toEqual({
      state: 'unknown',
    })
    expect(
      adminDraftSchema.safeParse({ ...base, wet: { state: 'unknown', value: false } }).success,
    ).toBe(false)
    expect(
      adminDraftSchema.safeParse({
        ...base,
        price: { state: 'confirmed', value: '0', source: 'label' },
      }).success,
    ).toBe(false)
    expect(
      adminDraftSchema.safeParse({ ...base, price: { state: 'confirmed', value: '10.01' } })
        .success,
    ).toBe(false)
    expect(
      adminDraftSchema.safeParse({
        ...base,
        unit: { state: 'confirmed', value: 'unsupported', source: 'label' },
      }).success,
    ).toBe(false)
    expect(
      adminDraftSchema.parse({
        ...base,
        wet: { state: 'confirmed', value: false, source: 'label' },
      }).wet,
    ).toMatchObject({ value: false })
  })
})
