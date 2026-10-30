import { afterEach, describe, expect, it, vi } from 'vitest'
import { inspirationFeedMock, analyzeInspirationMock } from '../data/inspirations'
import { newRoom, type Project } from '../domain/types'

const project = (): Project => ({
  object: 'apartment',
  renovation: 'capital',
  rooms: [{ ...newRoom(), id: 'room-1' }],
})
const imageId = inspirationFeedMock.items[0]!.id
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.resetModules()
  vi.useRealTimers()
})

describe('room inspiration API', () => {
  it('loads the backend feed with an encoded opaque cursor', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const fetch = vi.fn().mockResolvedValue(response(inspirationFeedMock))
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    expect(await service.roomInspirations('next/+?')).toEqual(inspirationFeedMock)
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/room-inspirations?cursor=next%2F%2B%3F',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('sends only the image ID in the route and the current project as JSON', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const input = project()
    const result = analyzeInspirationMock(imageId, input)
    const fetch = vi.fn().mockResolvedValue(response(result))
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    expect(await service.analyzeRoomInspiration(imageId, input)).toEqual(result)
    expect(fetch.mock.calls[0]![0]).toBe(`/api/v1/room-inspirations/${imageId}/analyze`)
    expect(fetch.mock.calls[0]![1].method).toBe('POST')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ project: input })
  })

  it.each([
    'https://tracker.example/photo.jpg',
    '//tracker.example/photo.jpg',
    '/\\tracker.example/photo.jpg',
  ])('rejects an unsafe image URL: %s', async (url) => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          ...inspirationFeedMock,
          items: [{ ...inspirationFeedMock.items[0], imageUrl: url }],
        }),
      ),
    )
    const { service } = await import('./api')
    await expect(service.roomInspirations()).rejects.toThrow('контракту')
  })

  it.each([404, 422, 503])(
    'does not replace a failed analysis with demo data (HTTP %s)',
    async (status) => {
      vi.stubEnv('VITE_DATA_MODE', 'api')
      const fetch = vi
        .fn()
        .mockResolvedValue(response({ code: 'unavailable', message: 'Not available' }, status))
      vi.stubGlobal('fetch', fetch)
      const { service } = await import('./api')
      await expect(service.analyzeRoomInspiration(imageId, project())).rejects.toThrow(
        `HTTP ${status}`,
      )
      expect(fetch).toHaveBeenCalledTimes(1)
    },
  )

  it.each(['image', 'dimensions', 'empty', 'unknown-room', 'additional-room'] as const)(
    'rejects inconsistent analysis: %s',
    async (mutation) => {
      vi.stubEnv('VITE_DATA_MODE', 'api')
      const input = project()
      const result = analyzeInspirationMock(imageId, input)
      if (mutation === 'image') result.imageId = 'wrong-image'
      if (mutation === 'dimensions') result.project.rooms[0]!.length = 99
      if (mutation === 'empty') result.selections = {}
      if (mutation === 'unknown-room') result.selections = { 'unknown:paint': 'demo-paint' }
      if (mutation === 'additional-room') result.project.rooms.push({ ...newRoom(2), id: 'room-2' })
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(result)))
      const { service } = await import('./api')
      await expect(service.analyzeRoomInspiration(imageId, input)).rejects.toThrow()
    },
  )

  it('waits up to two minutes for recognition and cleans up its timer', async () => {
    vi.useFakeTimers()
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const fetch = vi.fn().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          )
        }),
    )
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    const pending = expect(service.analyzeRoomInspiration(imageId, project())).rejects.toThrow(
      '2 минуты',
    )
    await vi.advanceTimersByTimeAsync(119999)
    expect(fetch.mock.calls[0]![1].signal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    await pending
    expect(vi.getTimerCount()).toBe(0)
  })

  it('supports cancellation without applying any fallback', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(
        (_url, options) =>
          new Promise((_resolve, reject) => {
            options.signal.addEventListener('abort', () =>
              reject(new DOMException('Aborted', 'AbortError')),
            )
          }),
      ),
    )
    const { service } = await import('./api')
    const controller = new AbortController()
    const pending = expect(
      service.analyzeRoomInspiration(imageId, project(), controller.signal),
    ).rejects.toThrow('отменён')
    controller.abort()
    await pending
  })

  it('uses explicitly labelled fixtures without network calls in demo mode', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'demo')
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { service } = await import('./api')
    expect((await service.roomInspirations()).items).toHaveLength(3)
    const result = await service.analyzeRoomInspiration(imageId, project())
    expect(result.warnings.join(' ')).toContain('без распознавания')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not accept a multi-room project as the target of room interior selection', async () => {
    vi.stubEnv('VITE_DATA_MODE', 'api')
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const input = project()
    input.rooms.push({ ...newRoom(2), id: 'room-2' })
    const { service } = await import('./api')
    await expect(service.analyzeRoomInspiration(imageId, input)).rejects.toThrow()
    expect(fetch).not.toHaveBeenCalled()
  })
})
