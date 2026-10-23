import { reactive, watch } from 'vue'
import { z } from 'zod'
import { estimateSchema, newRoom, projectSchema } from '../domain/types'
import type { Estimate, Project } from '../domain/types'

const schema = z.object({
  project: projectSchema,
  saved: z.array(estimateSchema).max(30),
  cart: estimateSchema.nullable(),
})
const initial: { project: Project; saved: Estimate[]; cart: Estimate | null } = {
  project: { object: 'apartment', renovation: 'capital', rooms: [newRoom()] },
  saved: [],
  cart: null,
}
const storageKey = 'repaircalc-workspace-v1'
let storageMessage = ''
try {
  const raw = localStorage.getItem(storageKey)
  if (raw) {
    const parsed = schema.safeParse(JSON.parse(raw))
    if (parsed.success) Object.assign(initial, parsed.data)
    else storageMessage = 'Сохранённые данные устарели. Открыт новый проект.'
  }
} catch {
  storageMessage = 'Локальное хранилище недоступно или повреждено. Работа продолжится в памяти.'
}

export const workspace = reactive({ ...initial, notice: '', storageMessage })
watch(
  () => ({ project: workspace.project, saved: workspace.saved, cart: workspace.cart }),
  (value) => {
    // An incomplete form may be used while typing, but never replaces the last valid draft.
    if (!schema.safeParse(value).success) return
    try {
      localStorage.setItem(storageKey, JSON.stringify(value))
    } catch {
      workspace.storageMessage =
        'Не удалось сохранить данные в браузере. Экспортируйте смету в JSON.'
    }
  },
  { deep: true },
)

let noticeTimer: ReturnType<typeof setTimeout>
export function notify(message: string) {
  workspace.notice = message
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    workspace.notice = ''
  }, 4500)
}
export function saveEstimate(estimate: Estimate) {
  if (workspace.saved.some((saved) => saved.id === estimate.id)) {
    notify('Эта смета уже сохранена')
    return
  }
  workspace.saved.unshift(estimateSchema.parse(estimate))
  workspace.saved = workspace.saved.slice(0, 30)
  notify('Смета сохранена в этом браузере')
}
export function setCart(estimate: Estimate) {
  workspace.cart = estimateSchema.parse(estimate)
  notify('Состав корзины подготовлен. Можно экспортировать JSON.')
}
