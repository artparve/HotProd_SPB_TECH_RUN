<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Room } from '../domain/types'
import {
  applyPlanDimensions,
  dimensionKeys,
  type ConfirmedDimensions,
  type DimensionKey,
  type PlanResult,
} from '../domain/plans'
import { service } from '../services/api'

const props = defineProps<{ room: Room }>()
const preview = ref('')
const previewDialog = ref<HTMLDialogElement | null>(null)
const fileName = ref('')
const imageBase64 = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')
const result = ref<PlanResult | null>(null)
const verified = ref(false)
const selected = ref<DimensionKey>('length')
const values = ref<ConfirmedDimensions>({ length: '', width: '', height: '', openings: '' })
const fingerprint = ref('')
let generation = 0
const labels: Record<DimensionKey, string> = {
  length: 'Длина',
  width: 'Ширина',
  height: 'Высота потолка',
  openings: 'Площадь окон и дверей',
}
const stale = computed(
  () =>
    (busy.value || !!result.value) &&
    !!fingerprint.value &&
    JSON.stringify(props.room) !== fingerprint.value,
)
const unsupported = computed(() => result.value && result.value.draft.shape !== 'rectangle')
const box = computed(() =>
  selected.value === 'openings' ? null : result.value?.draft[selected.value].bbox,
)
const canApply = computed(() => {
  if (!result.value || busy.value || stale.value || unsupported.value || !verified.value)
    return false
  try {
    applyPlanDimensions(props.room, fingerprint.value, values.value)
    return true
  } catch {
    return false
  }
})

function reset() {
  generation++
  preview.value = ''
  fileName.value = ''
  imageBase64.value = ''
  result.value = null
  error.value = ''
  success.value = ''
  fingerprint.value = ''
  verified.value = false
  loading.value = false
  busy.value = false
  values.value = { length: '', width: '', height: '', openings: '' }
}
watch(() => props.room.id, reset)
onBeforeUnmount(() => {
  generation++
})

async function loadFile(file?: File) {
  if (!file || busy.value) return
  reset()
  const attempt = generation
  loading.value = true
  try {
    if (!file.size || file.size > 8 * 1024 * 1024)
      throw new Error('Выберите изображение размером до 8 МБ.')
    const url = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(new Error('Не удалось прочитать файл. Выберите его ещё раз.'))
      reader.readAsDataURL(file)
    })
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const image = new Image()
      image.onload = () => resolve(image)
      image.onerror = () =>
        reject(
          new Error('Файл не удалось открыть как изображение. Используйте PNG, JPEG или WebP.'),
        )
      image.src = url
    })
    if (image.naturalWidth * image.naturalHeight > 12_000_000)
      throw new Error('Изображение слишком большое: максимум 12 млн пикселей.')
    if (attempt !== generation) return
    // Use the decoded canvas for preview only: it applies the same EXIF orientation as the server.
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const context = canvas.getContext('2d')
    if (!context) throw new Error('Не удалось подготовить предпросмотр изображения.')
    context.fillStyle = 'white'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0)
    preview.value = canvas.toDataURL('image/png')
    imageBase64.value = url.slice(url.indexOf(',') + 1)
    fileName.value = file.name || 'Изображение из буфера'
  } catch (cause) {
    if (attempt === generation)
      error.value = cause instanceof Error ? cause.message : 'Не удалось открыть изображение.'
  } finally {
    if (attempt === generation) loading.value = false
  }
}
function choose(event: Event) {
  const input = event.target as HTMLInputElement
  void loadFile(input.files?.[0])
  input.value = ''
}
function paste(event: ClipboardEvent) {
  const image = Array.from(event.clipboardData?.items || []).find((item) =>
    item.type.startsWith('image/'),
  )
  if (image) {
    event.preventDefault()
    void loadFile(image.getAsFile() || undefined)
  }
}
function drop(event: DragEvent) {
  void loadFile(event.dataTransfer?.files[0])
}
async function extract() {
  if (!imageBase64.value || busy.value || loading.value) return
  const attempt = generation
  fingerprint.value = JSON.stringify(props.room)
  result.value = null
  error.value = ''
  success.value = ''
  verified.value = false
  busy.value = true
  try {
    const response = await service.extractPlan(imageBase64.value)
    if (attempt !== generation) return
    result.value = response
    values.value = {
      length: response.dimensionsMetres.length?.toString() || '',
      width: response.dimensionsMetres.width?.toString() || '',
      height: response.dimensionsMetres.height?.toString() || '',
      openings: '',
    }
  } catch (cause) {
    if (attempt === generation)
      error.value =
        cause instanceof Error ? cause.message : 'Распознавание не удалось. Повторите попытку.'
  } finally {
    if (attempt === generation) busy.value = false
  }
}
function apply() {
  if (!canApply.value) return
  try {
    Object.assign(props.room, applyPlanDimensions(props.room, fingerprint.value, values.value))
    fingerprint.value = JSON.stringify(props.room)
    result.value = null
    success.value = 'Проверенные размеры применены к этой комнате.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Проверьте размеры.'
  }
}
</script>

<template>
  <details class="plan-import" @paste="paste">
    <summary>Заполнить по плану <span>Изображение → проверка размеров</span></summary>
    <div class="plan-import-body">
      <p class="field-hint">
        Загрузите план одной прямоугольной комнаты с внутренними размерами в мм, см или метрах. Если
        комнат несколько, сначала вырежьте нужную.
      </p>
      <p class="field-hint">
        Загрузка и распознавание сами не меняют параметры комнаты. Нажмите «Применить параметры»,
        чтобы перенести проверенные размеры в поля. Подбор интерьера использует текущие значения
        этих полей, включая последующие ручные правки; файл плана повторно не отправляется.
      </p>
      <div
        class="plan-dropzone"
        tabindex="0"
        role="group"
        aria-label="Вставка или перетаскивание плана"
        @dragover.prevent
        @drop.prevent="drop"
      >
        <label class="plan-file-label"
          >Выбрать изображение
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp"
            :disabled="busy || loading"
            @change="choose"
          />
        </label>
        <span>или перетащите файл сюда / вставьте Ctrl+V</span>
        <small>PNG, JPEG, WebP · до 8 МБ и 12 млн пикселей</small>
      </div>
      <p v-if="loading" role="status">Подготавливаем изображение…</p>
      <div v-if="preview" class="plan-review-grid">
        <div>
          <p class="plan-file-name">{{ fileName }}</p>
          <div class="plan-image">
            <img :src="preview" alt="Загруженный план комнаты" />
            <span
              v-if="box"
              class="plan-evidence"
              :style="{
                left: `${box.x * 100}%`,
                top: `${box.y * 100}%`,
                width: `${box.width * 100}%`,
                height: `${box.height * 100}%`,
              }"
            />
          </div>
          <button type="button" class="text-button plan-zoom" @click="previewDialog?.showModal()">
            Увеличить план
          </button>
          <p v-if="result" class="field-hint">
            Выберите поле справа, чтобы увидеть предложенную область подписи. Координаты могут быть
            неточными.
          </p>
        </div>
        <div class="plan-review-fields">
          <button
            type="button"
            class="button secondary"
            :disabled="busy || loading"
            @click="extract"
          >
            {{ busy ? 'Распознаём план…' : result ? 'Распознать повторно' : 'Распознать размеры' }}
          </button>
          <p v-if="busy" role="status">
            Gemma читает план. На CPU это может занять несколько минут. Можно продолжить работу, но
            изменение комнаты потребует повторного распознавания.
          </p>
          <p v-if="stale" class="alert error" role="alert">
            Комната изменилась. Результат нельзя применить. Повторите распознавание.
          </p>
          <template v-if="result">
            <p class="field-hint">
              {{ result.model }} · {{ Math.round(result.elapsedSeconds) }} с. Проверьте каждое
              значение по плану.
            </p>
            <p v-if="unsupported" class="alert error" role="alert">
              Нужна одна прямоугольная комната. Выделите её на исходном плане или используйте ручной
              ввод ниже.
            </p>
            <label v-for="key in dimensionKeys" :key="key" class="field plan-dimension">
              {{ labels[key] }}{{ key === 'openings' ? ', м²' : ', м' }}
              <input
                v-model="values[key]"
                type="text"
                inputmode="decimal"
                :aria-label="`${labels[key]} по плану`"
                :placeholder="
                  key === 'openings' ? 'Введите явно, включая 0' : 'Неизвестно — введите значение'
                "
                :disabled="!!unsupported || stale"
                @focus="selected = key"
                @input="verified = false"
              />
              <small v-if="key !== 'openings'"
                >{{ result.draft[key].label || 'Подпись не найдена' }} ·
                {{
                  { found: 'найдено', unknown: 'неизвестно', conflict: 'конфликт' }[
                    result.draft[key].state
                  ]
                }}</small
              >
            </label>
            <p class="field-hint">
              Площадь проёмов не определена автоматически. Укажите суммарную площадь окон и дверей
              вручную; отсутствие проёмов подтвердите числом 0.
            </p>
            <label class="plan-confirm"
              ><input v-model="verified" type="checkbox" :disabled="stale || !!unsupported" /> Я
              проверил размеры, единицы и площадь проёмов</label
            >
            <button type="button" class="button primary" :disabled="!canApply" @click="apply">
              Применить параметры
            </button>
          </template>
        </div>
      </div>
      <ul v-if="result?.warnings.length" class="plan-warnings">
        <li v-for="warning in result.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <p v-if="success" class="alert" role="status">{{ success }}</p>
      <dialog
        v-if="preview"
        ref="previewDialog"
        class="plan-fullscreen"
        aria-label="План комнаты крупнее"
      >
        <button type="button" class="button secondary" @click="previewDialog?.close()">
          Закрыть изображение
        </button>
        <img :src="preview" alt="Увеличенный план комнаты" />
      </dialog>
    </div>
  </details>
</template>

<style scoped>
.plan-import {
  border: 1px solid #d5e1d6;
  border-radius: 12px;
  background: #f5f8f2;
  margin: 18px 0;
}
summary {
  cursor: pointer;
  padding: 16px;
  color: #285c49;
  font-weight: 650;
}
summary span {
  display: block;
  margin: 6px 0 0 18px;
  color: #68776b;
  font-size: 12px;
  font-weight: 400;
}
.plan-import-body {
  padding: 0 16px 18px;
}
.plan-dropzone {
  padding: 18px;
  border: 1px dashed #95ad96;
  border-radius: 8px;
  background: white;
  display: grid;
  gap: 10px;
  text-align: center;
}
.plan-file-label {
  position: relative;
  border-radius: 6px;
  padding: 10px;
  background: #edf3e8;
  color: #285c49;
  cursor: pointer;
  font-weight: 600;
  display: grid;
  gap: 10px;
  justify-items: center;
}
.plan-file-label input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.plan-file-label:focus-within {
  outline: 3px solid #9ebd7d;
}
.plan-zoom {
  margin-top: 12px;
}
.plan-fullscreen {
  width: min(1100px, 95vw);
  max-height: 92vh;
  border: 1px solid #d5e1d6;
  border-radius: 12px;
  padding: 16px;
}
.plan-fullscreen::backdrop {
  background: #14291f99;
}
.plan-fullscreen img {
  display: block;
  width: 100%;
  height: auto;
  margin-top: 12px;
}
.plan-fullscreen .button {
  position: sticky;
  top: 0;
}
.plan-dropzone span,
.plan-dropzone small,
.plan-dimension small {
  color: #68776b;
  font-size: 12px;
}
.plan-review-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 20px;
  margin-top: 18px;
  align-items: start;
}
.plan-image {
  position: relative;
  background: white;
  border: 1px solid #e7ebe5;
}
.plan-image img {
  display: block;
  width: 100%;
  height: auto;
}
.plan-evidence {
  position: absolute;
  border: 2px solid #ce6815;
  background: #ffb95733;
  pointer-events: none;
}
.plan-file-name {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.plan-review-fields {
  display: grid;
  gap: 14px;
}
.plan-dimension {
  gap: 6px;
}
.plan-confirm {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.5;
}
.plan-confirm input {
  margin-top: 3px;
}
.plan-warnings {
  padding-left: 20px;
  font-size: 12px;
  line-height: 1.6;
  color: #6f572f;
}
@media (max-width: 700px) {
  .plan-review-grid {
    grid-template-columns: 1fr;
  }
}
</style>
