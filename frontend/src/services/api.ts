import { z } from 'zod'
import { estimateSchema, productSchema, recommendationSchema } from '../domain/types'
import type { Product, ProductInput, Project, Room } from '../domain/types'
import { apiErrorSchema, materialEstimateSchema, materialRequestSchema } from '../domain/materials'
import { planResultSchema } from '../domain/plans'
import {
  roomInspirationFeedSchema,
  roomInspirationRequestSchema,
  roomInspirationResultSchema,
} from '../domain/inspirations'

// Production builds never use synthetic fixtures, even with an old local env file.
const configuredMode = import.meta.env.PROD ? 'api' : import.meta.env.VITE_DATA_MODE || 'api'
if (!['demo', 'api'].includes(configuredMode)) throw new Error('VITE_DATA_MODE must be demo or api')
export const dataMode = configuredMode as 'demo' | 'api'
const base = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  body?: unknown,
  timeoutMs = 15000,
  signal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) controller.abort()
  else signal?.addEventListener('abort', abort, { once: true })
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
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
    if (!response.ok) {
      const error = apiErrorSchema.safeParse(await response.json().catch(() => null))
      if (path === '/plans/extract') {
        const messages: Record<string, string> = {
          model_busy: 'Распознавание уже выполняется. Дождитесь завершения и попробуйте ещё раз.',
          invalid_plan_draft:
            'Модель вернула противоречивые размеры или единицы. Параметры не применены. Попробуйте более чёткий план или введите размеры вручную.',
          invalid_model_response:
            'Модель не завершила распознавание. Попробуйте ещё раз или используйте ручной ввод.',
          upstream_unavailable:
            'Сервис распознавания недоступен или не ответил вовремя. Попробуйте позже.',
          invalid_image_size: 'Изображение слишком большое: до 8 МБ и 12 млн пикселей.',
          invalid_image: 'Не удалось прочитать изображение. Выберите другой файл.',
          unsupported_image: 'Используйте обычное изображение PNG, JPEG или WebP без анимации.',
        }
        throw new Error(
          (error.success && messages[error.data.code]) ||
            'Не удалось распознать план. Проверьте подключение сервиса и повторите попытку.',
        )
      }
      throw new Error(
        error.success
          ? `API вернул HTTP ${response.status}: ${error.data.message}`
          : `API вернул HTTP ${response.status}. Проверьте подключение или повторите запрос.`,
      )
    }
    const parsed = schema.safeParse(await response.json())
    if (!parsed.success)
      throw new Error('Ответ API не соответствует контракту. Проверьте версию backend.')
    return parsed.data
  } catch (error) {
    if (error instanceof SyntaxError)
      throw new Error('API вернул невалидный JSON. Проверьте адрес и маршрут API.')
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError')
      throw new Error(
        signal?.aborted
          ? 'Запрос отменён.'
          : path.startsWith('/room-inspirations/')
            ? 'Распознавание комнаты не завершилось за 2 минуты. Повторите запрос.'
            : path === '/plans/extract'
              ? 'Распознавание не завершилось за 11 минут. Попробуйте снова или введите размеры вручную.'
              : timeoutMs === 15000
                ? 'API не ответил за 15 секунд. Повторите запрос.'
                : 'Расчёт с ИИ не завершился за отведённое время. Повторите запрос.',
      )
    if (error instanceof TypeError)
      throw new Error('Не удалось связаться с API. Проверьте адрес backend и сеть.')
    throw error
  } finally {
    clearTimeout(timeout)
    signal?.removeEventListener('abort', abort)
  }
}

export const service = {
  async roomInspirations(cursor?: string, signal?: AbortSignal) {
    if (import.meta.env.DEV && dataMode === 'demo')
      return roomInspirationFeedSchema.parse(
        (await import('../data/inspirations')).inspirationFeedMock,
      )
    return request(
      `/room-inspirations${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`,
      roomInspirationFeedSchema,
      undefined,
      15000,
      signal,
    )
  },
  async analyzeRoomInspiration(imageId: string, project: Project, signal?: AbortSignal) {
    const body = roomInspirationRequestSchema.parse({ project })
    const result = roomInspirationResultSchema.parse(
      import.meta.env.DEV && dataMode === 'demo'
        ? (await import('../data/inspirations')).analyzeInspirationMock(imageId, body.project)
        : await request(
            `/room-inspirations/${encodeURIComponent(imageId)}/analyze`,
            roomInspirationResultSchema,
            body,
            120000,
            signal,
          ),
    )
    if (result.imageId !== imageId)
      throw new Error('Ответ распознавания относится к другому изображению. Повторите запрос.')
    // A photograph cannot establish dimensions. Only the supplied room's works may change.
    const geometry = (value: Project) =>
      value.rooms.map((room, index) => (index === 0 ? { ...room, works: undefined } : room))
    if (
      result.project.object !== body.project.object ||
      result.project.renovation !== body.project.renovation ||
      JSON.stringify(geometry(result.project)) !== JSON.stringify(geometry(body.project))
    )
      throw new Error(
        'Распознавание изменило параметры проекта. Размеры нельзя определять по фото.',
      )
    return result
  },
  async extractPlan(imageBase64: string) {
    // Recognition always calls the connected model, including when the catalog uses demo data.
    return request('/plans/extract', planResultSchema, { imageBase64 }, 660_000)
  },
  async catalog(): Promise<Product[]> {
    if (import.meta.env.DEV && dataMode === 'demo') return (await import('./demo')).demoCatalog()
    return request('/products', z.array(productSchema))
  },
  async estimate(project: Project, selections: Record<string, string> = {}) {
    if (import.meta.env.DEV && dataMode === 'demo')
      return (await import('./demo')).estimateDemo(project, selections)
    return request('/estimate', estimateSchema, { project, selections })
  },
  async recommendations(room: Room) {
    if (import.meta.env.DEV && dataMode === 'demo')
      return (await import('./demo')).recommendDemo(room)
    return request('/recommendations', recommendationSchema, { room })
  },
  async product(product: Product, input: ProductInput) {
    if (import.meta.env.DEV && dataMode === 'demo')
      return (await import('./demo')).productDemo(product, input)
    return request(`/product/${encodeURIComponent(product.id)}/calc`, estimateSchema, input)
  },
  async materialEstimate(product: Product, input: ProductInput) {
    if (dataMode !== 'api') throw new Error('Для ИИ-помощника подключите API.')
    const body = materialRequestSchema.parse({
      productId: product.id,
      input,
      prompt: 'Объясни расход, стоимость, округление упаковок и остаток для заданных параметров.',
      useCorrection: false,
    })
    // Two Ollama calls (600 s each) and the Java calculator (30 s), plus transport overhead.
    const result = await request('/materials/estimate', materialEstimateSchema, body, 1_260_000)
    if (
      result.model === 'not-used' ||
      result.estimate.lines[0]?.product.id !== product.id ||
      result.baseline.lines[0]?.product.id !== product.id
    )
      throw new Error('Ответ ИИ-сервиса не соответствует выбранному товару или режиму.')
    return result
  },
}
