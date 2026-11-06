<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { guideEnabled, startupReady } from '../startup'

const emit = defineEmits<{ navigation: [open: boolean] }>()
const route = useRoute()
const dialog = ref<HTMLDialogElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const index = ref(0)
const active = ref(false)
const viewport = ref({ width: window.innerWidth, height: window.innerHeight })
const box = ref({ x: 0, y: 0, width: 0, height: 0 })
const cookieName = 'repaircalc_guide_v1'
const definitions = [
  {
    selector: '.topbar',
    name: 'Ваш помощник по ремонту',
    description:
      'Начните с общего расчёта, подбора для комнаты или расчёта одного товара. На телефоне разделы открываются кнопкой меню слева.',
  },
  {
    selector: '.sidebar nav',
    name: 'Панель меню: выберите сценарий',
    description:
      '«Общий расчёт» — ремонт с несколькими комнатами. «Подбор для комнаты» — материалы с учётом условий. «Расчёт товара» — упаковки одного материала. «Мои сметы» — сохранённые результаты. На телефоне панель открывается кнопкой меню слева вверху.',
  },
  {
    selector: '[data-tour="inspiration"]',
    name: 'Выберите настроение комнаты',
    description:
      'Рассмотрите интерьеры по категориям. Галерея работает без плана: ИИ предложит материалы, а размеры всегда задаёте вы.',
  },
  {
    selector: '.dimensions-grid, .product-grid, #main .panel-heading, #main',
    name: 'От размеров к смете',
    description:
      'Укажите размеры, условия и работы. План можно загрузить по желанию. Проверьте материалы и запас: Java рассчитывает упаковки и стоимость, ИИ только помогает с пояснениями.',
  },
  {
    selector: '.cart-button',
    name: 'Сохраните результат',
    description:
      'После расчёта сохраните смету в «Мои сметы» или состав корзины. Данные сохраняются в этом браузере; отправка заказа в магазин не выполняется.',
  },
  {
    selector: '.help-button',
    name: 'Помощь всегда рядом',
    description:
      'Здесь можно открыть подсказки и повторить знакомство. Начните с комнаты, затем проверьте материалы и получите смету.',
  },
]
const steps = ref(definitions)
const step = computed(() => steps.value[index.value]!)
const position = computed(() => {
  const height = panel.value?.offsetHeight || 280
  const width = Math.min(380, viewport.value.width - 24)
  const below = box.value.y + box.value.height + 16
  const top =
    below + height < viewport.value.height ? below : Math.max(12, box.value.y - height - 16)
  return {
    width: `${width}px`,
    left: `${Math.max(12, Math.min(box.value.x, viewport.value.width - width - 12))}px`,
    top: `${Math.min(top, Math.max(12, viewport.value.height - height - 12))}px`,
  }
})
let target: Element | null = null
let observer: ResizeObserver | undefined
let focusBefore: HTMLElement | null = null
let alive = true
function findTarget(selector: string) {
  return (
    selector
      .split(',')
      .map((part) => document.querySelector(part.trim()))
      .find((element) => element && element.getBoundingClientRect().width > 0) || null
  )
}
function measure() {
  viewport.value = { width: window.innerWidth, height: window.innerHeight }
  const rect = target?.getBoundingClientRect()
  if (rect)
    box.value = {
      x: Math.max(4, rect.left - 6),
      y: Math.max(4, rect.top - 6),
      width: Math.min(rect.width + 12, window.innerWidth - 8),
      height: Math.min(rect.height + 12, window.innerHeight - 8),
    }
}
async function locate() {
  emit(
    'navigation',
    step.value.selector === '.sidebar nav' && window.matchMedia('(max-width: 760px)').matches,
  )
  await nextTick()
  target = findTarget(step.value.selector)
  target?.scrollIntoView({ block: 'center', behavior: 'instant' })
  observer?.disconnect()
  if (target) observer?.observe(target)
  measure()
  await nextTick()
  panel.value?.focus()
}
async function start() {
  if (!guideEnabled || route.path.startsWith('/admin')) return
  await startupReady
  if (!alive || route.path.startsWith('/admin')) return
  focusBefore = document.activeElement as HTMLElement | null
  steps.value = definitions.filter((item) => findTarget(item.selector))
  index.value = 0
  active.value = true
  await nextTick()
  dialog.value?.showModal()
  await locate()
}
function finish() {
  document.cookie = `${cookieName}=done; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === 'https:' ? '; Secure' : ''}`
  active.value = false
  emit('navigation', false)
  dialog.value?.close()
  observer?.disconnect()
  focusBefore?.focus()
}
function move(delta: number) {
  if (index.value + delta >= steps.value.length) finish()
  else {
    index.value = Math.max(0, index.value + delta)
    void locate()
  }
}
watch(
  () => route.path,
  () => {
    if (active.value) finish()
  },
)
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') observer = new ResizeObserver(measure)
  window.addEventListener('resize', measure)
  window.addEventListener('scroll', measure, true)
  window.addEventListener('transitionend', measure, true)
  if (!document.cookie.split(';').some((part) => part.trim() === `${cookieName}=done`)) void start()
})
onBeforeUnmount(() => {
  alive = false
  observer?.disconnect()
  window.removeEventListener('resize', measure)
  window.removeEventListener('scroll', measure, true)
  window.removeEventListener('transitionend', measure, true)
  emit('navigation', false)
  dialog.value?.close()
})
defineExpose({ start })
</script>

<template>
  <dialog
    ref="dialog"
    class="welcome-guide"
    aria-labelledby="guide-title"
    aria-describedby="guide-description"
    @cancel.prevent="finish"
  >
    <template v-if="active">
      <svg
        class="guide-shade"
        :viewBox="`0 0 ${viewport.width} ${viewport.height}`"
        aria-hidden="true"
      >
        <defs>
          <mask id="guide-cutout">
            <rect width="100%" height="100%" fill="white" />
            <rect
              :x="box.x"
              :y="box.y"
              :width="box.width"
              :height="box.height"
              rx="12"
              fill="black"
            />
          </mask>
        </defs>
        <rect width="100%" height="100%" fill="#12251ed9" mask="url(#guide-cutout)" />
        <rect
          :x="box.x"
          :y="box.y"
          :width="box.width"
          :height="box.height"
          rx="12"
          fill="none"
          stroke="#a6d3b0"
          stroke-width="3"
        />
      </svg>
      <section ref="panel" class="guide-card" :style="position" tabindex="-1">
        <button class="icon-button guide-close" aria-label="Пропустить знакомство" @click="finish">
          ×
        </button>
        <span class="eyebrow" aria-live="polite">Шаг {{ index + 1 }}/{{ steps.length }}</span>
        <h2 id="guide-title">{{ step.name }}</h2>
        <p id="guide-description">{{ step.description }}</p>
        <div class="guide-actions">
          <button class="button secondary" :disabled="index === 0" @click="move(-1)">Назад</button>
          <button class="button primary" @click="move(1)">
            {{ index === steps.length - 1 ? 'Начать работу' : 'Далее' }}
          </button>
        </div>
        <button class="text-button" @click="finish">Пропустить знакомство · Esc</button>
      </section>
    </template>
  </dialog>
</template>

<style scoped>
.welcome-guide {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100dvh;
  max-width: none;
  max-height: none;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #20372d;
  overflow: hidden;
}
.welcome-guide::backdrop {
  background: transparent;
}
.guide-shade {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.guide-card {
  position: fixed;
  box-sizing: border-box;
  background: #fff;
  padding: 24px;
  border-radius: 18px;
  box-shadow: 0 16px 64px #0004;
  max-height: calc(100dvh - 24px);
  overflow: auto;
  outline: none;
}
.guide-card h2 {
  font-size: 21px;
  margin: 12px 24px 12px 0;
}
.guide-card p {
  line-height: 1.65;
  margin-bottom: 20px;
}
.guide-close {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 26px;
}
.guide-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
</style>
