<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { service, dataMode } from '../services/api'
import { projectSchema, type Project } from '../domain/types'
import {
  validateInspirationEstimate,
  validateInspirationProducts,
  type PreparedInspiration,
  type RoomInspiration,
} from '../domain/inspirations'

const props = defineProps<{ project: Project }>()
const emit = defineEmits<{ prepared: [value: PreparedInspiration] }>()
const items = ref<RoomInspiration[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const loaded = ref(false)
const feedError = ref('')
const selectionError = ref('')
const selecting = ref<string | null>(null)
const hovered = ref<RoomInspiration | null>(null)
const preview = ref<RoomInspiration | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const broken = ref(new Set<string>())
let alive = true
let feedController: AbortController | undefined
let selectionController: AbortController | undefined

async function load(more = false) {
  if (loading.value) return
  loading.value = true
  feedError.value = ''
  feedController = new AbortController()
  try {
    const previous = more ? cursor.value : null
    const result = await service.roomInspirations(previous || undefined, feedController.signal)
    if (!alive) return
    const merged = [...(more ? items.value : []), ...result.items]
    items.value = [...new Map(merged.map((item) => [item.id, item])).values()]
    cursor.value = result.nextCursor === previous ? null : result.nextCursor
    loaded.value = true
  } catch (error) {
    if (alive)
      feedError.value = error instanceof Error ? error.message : 'Не удалось загрузить комнаты.'
  } finally {
    if (alive) loading.value = false
  }
}

async function inspect(image: RoomInspiration) {
  hovered.value = null
  preview.value = image
  await nextTick()
  dialog.value?.showModal()
}
function closePreview() {
  dialog.value?.close()
  preview.value = null
}
async function choose(image: RoomInspiration) {
  if (selecting.value) return
  closePreview()
  hovered.value = null
  selectionError.value = ''
  const parsed = projectSchema.safeParse(props.project)
  if (!parsed.success) {
    selectionError.value = 'Сначала проверьте размеры и параметры комнаты в форме выше.'
    return
  }
  const snapshot = parsed.data
  const fingerprint = JSON.stringify(props.project)
  selecting.value = image.id
  const controller = new AbortController()
  selectionController = controller
  try {
    const result = await service.analyzeRoomInspiration(image.id, snapshot, controller.signal)
    if (!alive || controller.signal.aborted) return
    const products = await service.catalog()
    validateInspirationProducts(result, products)
    const estimate = await service.estimate(result.project, result.selections)
    validateInspirationEstimate(result, estimate)
    if (!alive || controller.signal.aborted) return
    if (fingerprint !== JSON.stringify(props.project))
      throw new Error('Проект изменился во время распознавания. Выберите изображение ещё раз.')
    emit('prepared', { image, result, products, estimate })
  } catch (error) {
    if (alive && !controller.signal.aborted)
      selectionError.value = error instanceof Error ? error.message : 'Не удалось подобрать товары.'
  } finally {
    if (alive) selecting.value = null
  }
}

onMounted(() => load())
onBeforeUnmount(() => {
  alive = false
  feedController?.abort()
  selectionController?.abort()
  dialog.value?.close()
})
</script>

<template>
  <section
    class="inspiration-gallery panel"
    aria-labelledby="inspiration-title"
    :aria-busy="loading || !!selecting"
  >
    <div class="inspiration-heading">
      <div>
        <span class="eyebrow">ОТ ИДЕИ К РАСЧЁТУ</span>
        <h2 id="inspiration-title">Вдохновитесь готовой комнатой</h2>
        <p>
          Наведите на фото, чтобы рассмотреть. Выберите интерьер — подберём материалы для указанной
          выше комнаты.
        </p>
      </div>
      <span v-if="dataMode === 'demo'" class="tag">ДЕМО · ИЛЛЮСТРАЦИИ</span>
    </div>
    <p class="muted">
      Используем текущие параметры комнаты выше. Размеры из плана учитываются только после
      «Применить параметры»; последующие ручные правки имеют приоритет. Мебель и декор могут не
      входить в подбор.
    </p>
    <p v-if="loading && !items.length" role="status">Загружаем изображения комнат…</p>
    <div v-if="feedError" class="alert error" role="alert">
      {{ feedError }}
      <button class="text-button" :disabled="loading" @click="load(!!items.length)">
        Повторить загрузку
      </button>
    </div>
    <p v-if="loaded && !items.length && !feedError" role="status">
      Изображений пока нет. Вы можете заполнить калькулятор вручную.
    </p>
    <ul v-if="items.length" class="inspiration-strip" aria-label="Изображения комнат">
      <li v-for="image in items" :key="image.id" class="inspiration-card">
        <button
          class="inspiration-image"
          :aria-label="`Рассмотреть: ${image.title}`"
          @mouseenter="hovered = image"
          @mouseleave="hovered = null"
          @click="inspect(image)"
        >
          <img
            v-if="!broken.has(image.id)"
            :src="image.thumbnailUrl"
            :alt="image.alt"
            loading="lazy"
            width="480"
            height="300"
            @error="broken.add(image.id)"
          />
          <span v-else class="inspiration-image-fallback">Изображение недоступно</span>
          <span class="inspiration-zoom">Рассмотреть ⤢</span>
        </button>
        <div class="inspiration-card-body">
          <h3>{{ image.title }}</h3>
          <p>{{ image.description }}</p>
          <button
            class="button primary"
            :disabled="!!selecting || broken.has(image.id)"
            @click="choose(image)"
          >
            {{ selecting === image.id ? 'Распознаём…' : 'Выбрать интерьер' }}
          </button>
        </div>
      </li>
    </ul>
    <button
      v-if="cursor"
      class="button ghost"
      :disabled="loading || !!selecting"
      @click="load(true)"
    >
      {{ loading ? 'Загружаем…' : 'Ещё комнаты' }}
    </button>
    <p v-if="selecting" role="status">
      Подбираем товары с изображения. Это может занять до двух минут. Ваш проект пока не изменён.
    </p>
    <button v-if="selecting" class="text-button" @click="selectionController?.abort()">
      Отменить распознавание
    </button>
    <div v-if="selectionError" class="alert error" role="alert">
      {{ selectionError }} Ваш проект не изменён.
    </div>
    <div
      v-if="hovered && !selecting && !preview && !broken.has(hovered.id)"
      class="inspiration-hover-preview"
      aria-hidden="true"
    >
      <img :src="hovered.imageUrl" alt="" />
      <strong>{{ hovered.title }}</strong>
    </div>
    <dialog
      ref="dialog"
      class="inspiration-dialog"
      aria-labelledby="inspiration-preview-title"
      @cancel="preview = null"
      @close="preview = null"
      @click.self="closePreview"
    >
      <template v-if="preview">
        <div class="inspiration-dialog-heading">
          <h2 id="inspiration-preview-title">{{ preview.title }}</h2>
          <button class="button ghost" autofocus @click="closePreview">Закрыть просмотр</button>
        </div>
        <img :src="preview.imageUrl" :alt="preview.alt" @error="broken.add(preview.id)" />
        <p>{{ preview.description }}</p>
        <button
          class="button primary"
          :disabled="!!selecting || broken.has(preview.id)"
          @click="choose(preview)"
        >
          Выбрать интерьер
        </button>
      </template>
    </dialog>
  </section>
</template>

<style scoped lang="scss">
.inspiration-gallery {
  padding: 24px;
  margin-bottom: 28px;
  min-width: 0;
}
.inspiration-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
  h2 {
    margin: 8px 0;
  }
  p {
    max-width: 680px;
  }
}
.inspiration-strip {
  display: flex;
  gap: 18px;
  overflow-x: auto;
  list-style: none;
  padding: 12px 0 18px;
  margin: 12px 0;
  scroll-snap-type: x proximity;
}
.inspiration-card {
  flex: 0 0 min(310px, 85%);
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  overflow: hidden;
  scroll-snap-align: start;
}
.inspiration-image {
  display: block;
  width: 100%;
  position: relative;
  border: 0;
  padding: 0;
  background: #eee9e1;
  cursor: zoom-in;
  img {
    display: block;
    width: 100%;
    height: 185px;
    object-fit: cover;
  }
}
.inspiration-image-fallback {
  display: grid;
  place-items: center;
  min-height: 185px;
}
.inspiration-zoom {
  position: absolute;
  bottom: 10px;
  right: 10px;
  background: #fff;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
}
.inspiration-card-body {
  padding: 16px;
  h3 {
    margin: 0;
  }
  p {
    font-size: 13px;
    min-height: 3.5em;
  }
  .button {
    width: 100%;
  }
}
.inspiration-hover-preview {
  display: none;
  pointer-events: none;
}
@media (hover: hover) and (pointer: fine) {
  .inspiration-hover-preview {
    display: grid;
    position: fixed;
    z-index: 30;
    inset: 50% auto auto 50%;
    transform: translate(-50%, -50%);
    width: min(850px, 75vw);
    max-height: 75vh;
    padding: 12px;
    border: 1px solid #dedbd5;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 16px 80px #17251a55;
    img {
      width: 100%;
      max-height: 65vh;
      object-fit: contain;
    }
    strong {
      padding: 8px;
    }
  }
}
.inspiration-dialog {
  width: min(980px, 94vw);
  max-height: 92vh;
  padding: 22px;
  border: 1px solid #ddd;
  border-radius: 16px;
  overflow: auto;
  &::backdrop {
    background: #101c20b3;
  }
  > img {
    display: block;
    width: 100%;
    max-height: 60vh;
    object-fit: contain;
  }
}
.inspiration-dialog-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  h2 {
    margin: 0;
  }
}
@media (max-width: 600px) {
  .inspiration-gallery {
    padding: 16px;
  }
  .inspiration-heading {
    flex-direction: column;
  }
  .inspiration-dialog-heading {
    align-items: start;
    flex-direction: column;
  }
}
@media (prefers-reduced-motion: reduce) {
  .inspiration-strip {
    scroll-behavior: auto;
  }
}
</style>
