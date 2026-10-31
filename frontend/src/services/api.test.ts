import { afterEach, describe, expect, it, vi } from 'vitest'
import { catalog } from '../data/catalog'
import { productDemo } from './demo'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
  vi.useRealTimers()
})

describe('material estimation API', () => {
  it('uses the real plan endpoint in demo mode and explains a busy model without a fallback', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'demo')
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 'model_busy', message: 'Busy' }), { status: 503 }),
      )
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    await expect(service.extractPlan('aGVsbG8=')).rejects.toThrow('Распознавание уже выполняется')
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0]![0]).toBe('/api/v1/plans/extract')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ imageBase64: 'aGVsbG8=' })
  })
  const product = { ...catalog[0]!, id: '47f9b29f-8972-3c84-9103-6b3281bd9de7' }
  const input = { area: 20, perimeter: 18, height: 2.7, reserve: 10, layers: 2, quantity: 6 }
  function envelope() {
    const estimate = { ...productDemo(product, input), mode: 'api' as const }
    return {
      status: 'ok',
      estimate,
      baseline: estimate,
      correction: { applied: false, version: 'not-used', factor: '1' },
      model: 'gemma4:12b',
      toolCalls: ['calculate_material_cost'],
      explanation: 'Справочный текст модели',
      explanationStatus: 'unverified_model_text',
    }
  }

  it('sends the exact structured fields to the model/tool endpoint and waits beyond 15 seconds', async () => {
    vi.useFakeTimers()
    vi.stubEnv('VITE_DATA_MODE', 'api')
    let respond!: (response: Response) => void
    const fetch = vi.fn().mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          respond = resolve
        }),
    )
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    const pending = service.materialEstimate(product, input)
    await vi.advanceTimersByTimeAsync(16000)
    const [url, options] = fetch.mock.calls[0]!
    expect(url).toBe('/api/v1/materials/estimate')
    expect(JSON.parse(options.body)).toMatchObject({
      productId: product.id,
      input,
      useCorrection: false,
    })
    expect(options.signal.aborted).toBe(false)
    respond(new Response(JSON.stringify(envelope())))
    expect((await pending).estimate.total).toBe('18900.00')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('rejects a successful response without a real tool call', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...envelope(), toolCalls: [] }))),
    )
    const { service } = await import('./api')
    await expect(service.materialEstimate(product, input)).rejects.toThrow('контракту')
  })

  it('rejects a valid envelope containing a different product', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const body = envelope()
    body.estimate.lines[0]!.product = { ...product, id: '30473701-b10b-3d56-a15a-8591c16486c4' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
    const { service } = await import('./api')
    await expect(service.materialEstimate(product, input)).rejects.toThrow('выбранному товару')
  })

  it('preserves a completed estimate when only the explanation failed', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const body = {
      ...envelope(),
      model: 'not-used',
      toolCalls: [],
      explanation: null,
      explanationStatus: 'unavailable',
      explanationError: { code: 'upstream_unavailable', message: 'Ollama unavailable' },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
    const { service } = await import('./api')
    expect((await service.materialEstimate(product, input)).estimate.total).toBe('18900.00')
  })

  it('surfaces a model/tool failure without issuing a fallback Java request', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'tool_call_required',
          message: 'Model must call calculate_material_cost',
        }),
        { status: 502 },
      ),
    )
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    await expect(service.materialEstimate(product, input)).rejects.toThrow(
      'calculate_material_cost',
    )
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

describe('API adapter', () => {
  it('never uses fixtures in production even if an old env file selects them', async () => {
    vi.stubEnv('PROD', true)
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_DATA_MODE', 'demo')
    const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { service, dataMode } = await import('./api')
    expect(dataMode).toBe('api')
    expect(await service.catalog()).toEqual([])
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('uses the backend by default rather than returning synthetic data', async () => {
    vi.stubEnv('VITE_DATA_MODE', '')
    const fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { service, dataMode } = await import('./api')
    expect(dataMode).toBe('api')
    expect(await service.catalog()).toEqual([])
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/products',
      expect.objectContaining({ method: 'GET' }),
    )
  })
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
    expect(await service.catalog()).toEqual([])
    expect((await import('./api')).apiStatus.catalogWarning).toContain('временно недоступен')
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
    expect(await service.catalog()).toEqual([])
    expect((await import('./api')).apiStatus.catalogWarning).toContain('временно недоступен')
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
    expect(await service.catalog()).toEqual([])
    expect((await import('./api')).apiStatus.catalogWarning).toContain('временно недоступен')
  })
})
