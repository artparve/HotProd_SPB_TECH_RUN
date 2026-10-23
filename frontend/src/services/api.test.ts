import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('API adapter', () => {
  it('calls the agreed API, not the external raw catalog', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    expect(await service.catalog()).toEqual([])
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/products',
      expect.objectContaining({ method: 'GET' }),
    )
  })
  it('never silently switches to fixtures on API failure', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('down', { status: 503 })))
    const { service } = await import('./api')
    await expect(service.catalog()).rejects.toThrow('HTTP 503')
  })
  it('validates unknown response structures at runtime', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"items":[]}', { status: 200 })))
    const { service } = await import('./api')
    await expect(service.catalog()).rejects.toThrow('контракту')
  })
  it('gives an actionable network error', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const { service } = await import('./api')
    await expect(service.catalog()).rejects.toThrow('Проверьте адрес backend')
  })
  it('does not make external calls in demo mode', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'demo')
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    expect((await service.catalog()).length).toBeGreaterThan(5)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('explains an HTML or invalid JSON response', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>SPA</html>')))
    const { service } = await import('./api')
    await expect(service.catalog()).rejects.toThrow('невалидный JSON')
  })

  it('provides a retry message when a request times out', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('Aborted', 'AbortError')))
    const { service } = await import('./api')
    await expect(service.catalog()).rejects.toThrow('15 секунд')
  })
})
