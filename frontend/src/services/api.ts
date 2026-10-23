import { z } from 'zod'
import { estimateSchema, productSchema, recommendationSchema } from '../domain/types'
import type { Product, ProductInput, Project, Room } from '../domain/types'

const configuredMode = import.meta.env.VITE_DATA_MODE || 'demo'
if (!['demo', 'api'].includes(configuredMode)) throw new Error('VITE_DATA_MODE must be demo or api')
export const dataMode = configuredMode as 'demo' | 'api'
const base = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

async function request<T>(path: string, schema: z.ZodType<T>, body?: unknown): Promise<T> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`${base}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers:
        body === undefined
          ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      credentials: 'same-origin',
    })
    if (!response.ok)
      throw new Error(
        `API вернул HTTP ${response.status}. Проверьте подключение или повторите запрос.`,
      )
    const parsed = schema.safeParse(await response.json())
    if (!parsed.success)
      throw new Error('Ответ API не соответствует контракту. Проверьте версию backend.')
    return parsed.data
  } catch (error) {
    if (error instanceof SyntaxError)
      throw new Error('API вернул невалидный JSON. Проверьте адрес и маршрут API.')
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError')
      throw new Error('API не ответил за 15 секунд. Повторите запрос.')
    if (error instanceof TypeError)
      throw new Error('Не удалось связаться с API. Проверьте адрес backend и сеть.')
    throw error
  } finally {
    clearTimeout(timeout)
  }
}

export const service = {
  async catalog(): Promise<Product[]> {
    if (dataMode === 'demo') return (await import('./demo')).demoCatalog()
    return request('/products', z.array(productSchema))
  },
  async estimate(project: Project, selections: Record<string, string> = {}) {
    if (dataMode === 'demo') return (await import('./demo')).estimateDemo(project, selections)
    return request('/estimate', estimateSchema, { project, selections })
  },
  async recommendations(room: Room) {
    if (dataMode === 'demo') return (await import('./demo')).recommendDemo(room)
    return request('/recommendations', recommendationSchema, { room })
  },
  async product(product: Product, input: ProductInput) {
    if (dataMode === 'demo') return (await import('./demo')).productDemo(product, input)
    return request(`/product/${encodeURIComponent(product.id)}/calc`, estimateSchema, input)
  },
}
