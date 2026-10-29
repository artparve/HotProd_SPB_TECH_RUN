import {
  classificationJobSchema,
  reviewOptionsSchema,
  readinessSchema,
  reviewDecisionSchema,
} from '../domain/admin'
import { z } from 'zod'
import {
  adminDraftSchema,
  adminErrorSchema,
  adminPageSchema,
  adminProductSchema,
  adminSessionSchema,
  adminVersionSchema,
} from '../domain/admin'
import type { AdminDraft } from '../domain/admin'

export class AdminApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: { field: string; code: string; message: string }[] = [],
    public requestId = '',
  ) {
    super(message)
  }
}
let csrfToken = ''
async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  method = 'GET',
  body?: unknown,
  etag?: string,
): Promise<{ data: T; etag: string }> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (method !== 'GET') headers['X-CSRF-Token'] = csrfToken
    if (etag) headers['If-Match'] = etag
    const response = await fetch(`/api/v1/admin${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) {
      const parsed = adminErrorSchema.safeParse(await response.json().catch(() => null))
      if (parsed.success)
        throw new AdminApiError(
          response.status,
          parsed.data.code,
          parsed.data.message,
          parsed.data.details,
          parsed.data.requestId,
        )
      throw new AdminApiError(
        response.status,
        'request_failed',
        `Запрос не выполнен (HTTP ${response.status}).`,
      )
    }
    const parsed = schema.safeParse(response.status === 204 ? null : await response.json())
    if (!parsed.success) throw new Error('Ответ административного API не соответствует контракту.')
    return { data: parsed.data, etag: response.headers.get('ETag') || '' }
  } catch (error) {
    if (error instanceof TypeError)
      throw new Error('Не удалось связаться с сервером. Проверьте подключение и повторите запрос.')
    if (error instanceof Error && error.name === 'AbortError')
      throw new Error('Сервер не ответил за 15 секунд. Повторите запрос.')
    throw error
  } finally {
    clearTimeout(timer)
  }
}

export const admin = {
  async classifications(id: string) {
    return (
      await request(
        `/products/${encodeURIComponent(id)}/classifications`,
        z.array(classificationJobSchema),
      )
    ).data
  },
  async classify(id: string, etag: string) {
    return (
      await request(
        `/products/${encodeURIComponent(id)}/classifications`,
        classificationJobSchema,
        'POST',
        undefined,
        etag,
      )
    ).data
  },
  async reviewOptions() {
    return (await request('/review-options', reviewOptionsSchema)).data
  },
  async reviews(id: string) {
    return (
      await request(`/products/${encodeURIComponent(id)}/reviews`, z.array(z.record(z.unknown())))
    ).data
  },
  async review(id: string, etag: string, jobId: string, decisions: unknown, profileId: string) {
    return (
      await request(
        `/products/${encodeURIComponent(id)}/reviews`,
        z.record(z.unknown()),
        'POST',
        { jobId, decisions: z.array(reviewDecisionSchema).parse(decisions), profileId },
        etag,
      )
    ).data
  },
  async readiness(id: string) {
    return (await request(`/products/${encodeURIComponent(id)}/readiness`, readinessSchema)).data
  },
  async publish(id: string, etag: string) {
    return (
      await request(
        `/products/${encodeURIComponent(id)}/publish`,
        z.record(z.unknown()),
        'POST',
        undefined,
        etag,
      )
    ).data
  },
  async session() {
    const { data } = await request('/session', adminSessionSchema)
    csrfToken = data.csrfToken
    return data
  },
  async login(username: string, password: string) {
    const { data } = await request('/login', adminSessionSchema, 'POST', { username, password })
    csrfToken = data.csrfToken
    return data
  },
  async logout() {
    await request('/logout', z.null(), 'POST')
    csrfToken = ''
  },
  async list(filters: {
    q: string
    status: string
    category: string
    sort: string
    page: number
    size: number
  }) {
    const query = new URLSearchParams(
      Object.entries(filters).map(([key, value]) => [key, String(value)]),
    )
    return (await request(`/products?${query}`, adminPageSchema(adminProductSchema))).data
  },
  get(id: string) {
    return request(`/products/${encodeURIComponent(id)}`, adminProductSchema)
  },
  create(input: AdminDraft) {
    return request('/products', adminProductSchema, 'POST', adminDraftSchema.parse(input))
  },
  update(id: string, input: AdminDraft, etag: string) {
    return request(
      `/products/${encodeURIComponent(id)}`,
      adminProductSchema,
      'PUT',
      adminDraftSchema.parse(input),
      etag,
    )
  },
  async history(id: string, page = 0) {
    return (
      await request(
        `/products/${encodeURIComponent(id)}/history?page=${page}&size=10`,
        adminPageSchema(adminVersionSchema),
      )
    ).data
  },
}
