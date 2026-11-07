<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { Estimate, Project } from '../domain/types'
import { workLabels } from '../domain/types'
import type {
  MaterialRecommendations,
  MaterialRecommendationRequest,
} from '../domain/materialRecommendations'
import { service } from '../services/api'
import EstimateResult from './EstimateResult.vue'

const props = defineProps<{ project: Project }>()
const conditions = reactive<MaterialRecommendationRequest['conditions']>({
  humidity: null,
  load: null,
  heated: null,
  substrate: null,
  waterContact: null,
  floorHeating: null,
  covering: null,
  baseState: null,
})
const query = ref('')
const design = ref('')
const explain = ref(true)
const referenceSearch = ref(false)
const busy = ref(false)
const error = ref('')
const result = ref<MaterialRecommendations | null>(null)
const estimate = ref<Estimate | null>(null)
const selected = ref<Record<string, string>>({})
const confirmed = ref(false)
const stale = ref(false)
let controller: AbortController | null = null
let generation = 0
const room = computed(() => props.project.rooms[0]!)
const fieldLabels: Record<string, string> = {
  humidity: 'Влажность',
  load: 'Нагрузка',
  heated: 'Отопление',
  substrate: 'Основание',
  waterContact: 'Контакт с водой',
  floorHeating: 'Тёплый пол',
  covering: 'Покрытие',
  baseState: 'Состояние основания',
}
const kindLabels: Record<string, string> = {
  covering: 'Напольное покрытие',
  paint: 'Краска',
  primer: 'Грунтовка',
  adhesive: 'Клей',
  wallpaper: 'Обои',
  socket: 'Розетки',
  cable: 'Кабель',
}
function invalidate() {
  generation++
  controller?.abort()
  busy.value = false
  stale.value = Boolean(result.value)
  selected.value = {}
  confirmed.value = false
  estimate.value = null
}
watch(() => props.project, invalidate, { deep: true })
watch([conditions, query, design, explain, referenceSearch], invalidate, { deep: true })
onBeforeUnmount(() => controller?.abort())
async function search() {
  controller?.abort()
  controller = new AbortController()
  const current = ++generation
  busy.value = true
  error.value = ''
  result.value = null
  estimate.value = null
  selected.value = {}
  confirmed.value = false
  try {
    const normalized = { ...conditions }
    for (const key of ['substrate', 'waterContact', 'covering', 'baseState'] as const)
      normalized[key] = normalized[key]?.trim() || null
    const response = await service.materialRecommendations(
      {
        works: [...room.value.works],
        renovation: props.project.renovation,
        object: props.project.object,
        conditions: normalized,
        query: query.value,
        room: structuredClone(JSON.parse(JSON.stringify(room.value))),
        designPreferences: design.value,
        explain: explain.value,
        referenceSearch: referenceSearch.value,
      },
      controller.signal,
    )
    if (current !== generation) return
    result.value = response
    stale.value = false
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : 'Подбор недоступен.'
  } finally {
    if (current === generation) busy.value = false
  }
}
async function apply() {
  if (!result.value || stale.value || !confirmed.value) return
  controller = new AbortController()
  const current = ++generation
  busy.value = true
  error.value = ''
  try {
    // Confirmed search conditions become explicit calculator inputs only at this action.
    const project = JSON.parse(JSON.stringify(props.project)) as Project
    if (conditions.humidity === null || conditions.load === null || conditions.heated === null)
      throw new Error('Подтвердите влажность, нагрузку и отопление и повторите подбор.')
    const target = project.rooms[0]!
    if (
      target.humidity !== conditions.humidity ||
      target.load !== conditions.load ||
      target.heated !== conditions.heated
    )
      throw new Error(
        'Условия подбора отличаются от параметров комнаты. Исправьте параметры комнаты и повторите подбор.',
      )
    const response = await service.applyMaterialRecommendations(
      result.value.requestId,
      project,
      selected.value,
      controller.signal,
    )
    if (current === generation) estimate.value = response
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : 'Не удалось применить выбор.'
  } finally {
    if (current === generation) busy.value = false
  }
}
watch(
  selected,
  () => {
    confirmed.value = false
    estimate.value = null
  },
  { deep: true },
)
</script>

<template>
  <section class="material-search" aria-label="Подбор материалов с источниками">
    <h2>Подбор с источниками и ИИ-объяснением</h2>
    <p>
      Поиск для комнаты «{{ room.name }}». Расчёт включает только это помещение и поддержанные
      работы. Неизвестные условия можно оставить без ответа.
    </p>
    <div class="search-fields">
      <label class="field"
        >Влажность<select v-model="conditions.humidity">
          <option :value="null">Неизвестно</option>
          <option value="normal">Обычная</option>
          <option value="high">Повышенная</option>
        </select></label
      >
      <label class="field"
        >Нагрузка<select v-model="conditions.load">
          <option :value="null">Неизвестно</option>
          <option value="normal">Обычная</option>
          <option value="high">Высокая</option>
        </select></label
      >
      <label class="field"
        >Отопление<select v-model="conditions.heated">
          <option :value="null">Неизвестно</option>
          <option :value="true">Есть</option>
          <option :value="false">Нет</option>
        </select></label
      >
      <label
        v-for="field in ['substrate', 'waterContact', 'covering', 'baseState'] as const"
        :key="field"
        class="field"
        >{{ fieldLabels[field]
        }}<input v-model="conditions[field]" maxlength="100" placeholder="Неизвестно"
      /></label>
      <label class="field"
        >Тёплый пол<select v-model="conditions.floorHeating">
          <option :value="null">Неизвестно</option>
          <option :value="true">Есть</option>
          <option :value="false">Нет</option>
        </select></label
      >
    </div>
    <label class="field"
      >Что ищете?<input
        v-model="query"
        maxlength="500"
        placeholder="Название товара или описание задачи"
    /></label>
    <label class="field">Пожелания к дизайну<input v-model="design" maxlength="500" /></label>
    <label
      ><input v-model="explain" type="checkbox" /> Объяснить проверенные факты локальной
      моделью</label
    >
    <p>
      <label
        ><input v-model="referenceSearch" type="checkbox" /> Искать в справочных PDF
        (исследовательские черновики без подтверждения товаров)</label
      >
    </p>
    <p>
      <button type="button" class="button secondary" :disabled="busy" @click="search">
        {{ busy ? 'Выполняется…' : 'Найти товары и источники' }}
      </button>
    </p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="stale" class="alert warning" role="status">
      Параметры изменились. Повторите подбор перед выбором товаров.
    </p>
    <div v-if="result && !stale" aria-live="polite">
      <p v-for="warning in result.warnings" :key="warning" class="alert warning">{{ warning }}</p>
      <p v-for="question in result.questions" :key="question.field">
        Уточните {{ fieldLabels[question.field] || question.field }}: {{ question.reason }}
      </p>
      <p v-if="!result.categories.length">Поддержанные категории не найдены.</p>
      <section
        v-if="result.references && referenceSearch"
        aria-label="Справочные PDF"
        class="reference-results"
      >
        <h3>Справочные документы производителей</h3>
        <p class="alert warning">
          Черновые источники не подтверждают характеристики учебных товаров и не разрешают их
          применение или расчёт.
        </p>
        <p v-if="result.references.status === 'disabled'">Справочный поиск отключён на сервере.</p>
        <p v-else-if="result.references.status === 'unavailable'">
          Индекс справочных документов недоступен или не прошёл проверку.
        </p>
        <p v-else-if="result.references.status === 'empty'">
          Релевантные фрагменты не найдены. Уточните запрос.
        </p>
        <div v-if="result.references.items.length" class="alert info">
          <p>Непроверенный текст модели по черновым источникам:</p>
          <p v-for="(item, index) in result.references.items" :key="index">
            {{ item.text }} <small>Фрагменты: {{ item.chunkIds.join(', ') }}</small>
          </p>
        </div>
        <p v-if="result.references.status === 'deterministic'">
          Показаны найденные фрагменты. Модельное объяснение не получено или выключено.
        </p>
        <details v-for="fragment in result.references.fragments" :key="fragment.chunkId">
          <summary>{{ fragment.title }} · страница {{ fragment.page }}</summary>
          <p class="reference-text">{{ fragment.text }}</p>
          <p>
            {{ fragment.publisher }} · {{ fragment.version }} · статус: {{ fragment.reviewStatus }}
          </p>
          <p v-if="fragment.versionNote">{{ fragment.versionNote }}</p>
          <a v-if="fragment.url" :href="fragment.url" target="_blank" rel="noopener noreferrer"
            >Ссылка на источник</a
          >
          <p v-else>Ссылка на фактически полученный PDF не подтверждена.</p>
          <small>{{ fragment.chunkId }} · SHA-256: {{ fragment.sha256 }}</small>
        </details>
        <small v-if="result.references.indexId"
          >Исследовательский индекс: {{ result.references.indexId }}</small
        >
      </section>
      <fieldset
        v-for="category in result.categories"
        :key="`${category.work}:${category.kind}:${category.coveringFamily}`"
      >
        <legend>
          {{ workLabels[category.work] }} · {{ kindLabels[category.kind] || category.kind }}
        </legend>
        <p v-if="category.coveringFamily">Условие: покрытие {{ category.coveringFamily }}</p>
        <p v-if="!category.candidates.length">
          Кандидатов нет. Совместимые товары не подтверждены.
        </p>
        <article
          v-for="candidate in category.candidates"
          :key="candidate.productId"
          class="search-candidate"
        >
          <label
            ><input
              v-model="selected[`${room.id}:${category.kind}`]"
              type="radio"
              :name="`${room.id}:${category.kind}`"
              :value="candidate.productId"
              :disabled="
                busy ||
                candidate.compatibility !== 'pass' ||
                candidate.calculationReadiness !== 'ready'
              "
            />
            {{ candidate.name }}</label
          >
          <p>
            {{ candidate.reason }}
            {{
              candidate.calculationReadiness === 'unavailable'
                ? 'Расчёт этой позиции недоступен.'
                : ''
            }}
          </p>
          <ul>
            <li v-for="check in candidate.checks" :key="check.property">
              {{ fieldLabels[check.property] || check.property }}: {{ check.reason }}
            </li>
          </ul>
          <details v-if="candidate.sources.length">
            <summary>Проверенные источники и ограничения</summary>
            <div v-for="source in candidate.sources" :key="source.chunkId">
              <p>{{ source.text }}</p>
              <a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }}</a>
              · {{ source.version }} · {{ source.section }}
            </div>
          </details>
        </article>
      </fieldset>
      <div v-if="result.explanation.items.length" class="alert info">
        <p>
          {{
            result.explanation.status === 'unverified_model_text'
              ? 'Непроверенный текст модели. Характеристики сверяйте с источниками выше.'
              : 'Объяснение по результатам серверных проверок.'
          }}
        </p>
        <p v-for="item in result.explanation.items" :key="item.productId">
          {{ item.text }} <small>Фрагменты: {{ item.chunkIds.join(', ') }}</small>
        </p>
      </div>
      <p v-if="explain && result.explanation.status === 'deterministic'" role="status">
        ИИ-объяснение не получено. Результаты проверок сохранены.
      </p>
      <label
        ><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy || !Object.keys(selected).length"
        />
        Подтверждаю условия и явно выбранные товары для расчёта этой комнаты</label
      >
      <p>
        <button type="button" class="button primary" :disabled="busy || !confirmed" @click="apply">
          Рассчитать выбранные товары
        </button>
      </p>
      <small
        >Каталог: {{ result.catalogReleaseId }} · Правила: {{ result.ruleSetId }} · Знания:
        {{ result.knowledgeReleaseId || 'не опубликованы' }}</small
      >
    </div>
    <EstimateResult v-if="estimate" :estimate="estimate" />
  </section>
</template>

<style scoped>
.material-search {
  margin-top: 1.5rem;
  padding: 1rem;
  border: 1px solid var(--border, #ddd);
  border-radius: 12px;
}
.search-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.75rem;
}
.reference-text {
  white-space: pre-wrap;
}
.search-candidate {
  padding: 0.75rem 0;
  border-bottom: 1px solid #ddd;
}
fieldset {
  margin: 1rem 0;
  padding: 1rem;
}
small {
  overflow-wrap: anywhere;
}
</style>
