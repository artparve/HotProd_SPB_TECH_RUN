<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { Estimate, Project, Product } from '../domain/types'
import { workLabels } from '../domain/types'
import type {
  MaterialRecommendations,
  MaterialRecommendationRequest,
} from '../domain/materialRecommendations'
import { service } from '../services/api'
import EstimateResult from './EstimateResult.vue'
import ProductSwatch from './ProductSwatch.vue'
import { money } from '../domain/format'
import MarkdownText from './MarkdownText.vue'
import type { EstimateExplanation } from '../domain/materialRecommendations'

const props = defineProps<{ project: Project; products?: Product[] }>()
const loadedProducts = ref<Product[]>([])
const catalog = computed(
  () =>
    new Map(
      (loadedProducts.value.length ? loadedProducts.value : props.products || []).map((product) => [
        product.id,
        product,
      ]),
    ),
)
const expandedCategories = ref<Record<string, boolean>>({})
const hasUnconfirmed = computed(() =>
  result.value?.categories.some((category) =>
    category.candidates.some(
      (candidate) =>
        candidate.compatibility !== 'pass' || candidate.calculationReadiness !== 'ready',
    ),
  ),
)
const hasModelComment = computed(() =>
  Boolean(result.value?.references?.items.length || result.value?.explanation.items.length),
)
function sourceLabel(chunkIds: string[]) {
  return [
    ...new Set(
      (result.value?.references?.fragments || [])
        .filter((fragment) => chunkIds.includes(fragment.chunkId))
        .map((fragment) => `${fragment.title} · стр. ${fragment.page}`),
    ),
  ].join('; ')
}
const conditions = reactive<MaterialRecommendationRequest['conditions']>({
  humidity: props.project.rooms[0]!.humidity,
  load: props.project.rooms[0]!.load,
  heated: props.project.rooms[0]!.heated,
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
const estimateComment = ref<EstimateExplanation | null>(null)
const commentBusy = ref(false)
let commentController: AbortController | null = null
let commentGeneration = 0
function clearEstimateComment() {
  commentGeneration++
  commentController?.abort()
  estimateComment.value = null
  commentBusy.value = false
}
const visibleCategories = computed(() => {
  const family = catalog.value.get(
    selected.value[`${room.value.id}:covering`] || '',
  )?.materialFamily
  return (result.value?.categories || []).filter(
    (category) => !category.coveringFamily || category.coveringFamily === family,
  )
})
const completeSelection = computed(
  () =>
    visibleCategories.value.length > 0 &&
    visibleCategories.value.every((category) =>
      Boolean(selected.value[`${room.value.id}:${category.kind}`]),
    ),
)
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
  evidence: 'Применимость',
  conditions: 'Условия помещения',
}
const coveringLabels: Record<string, string> = {
  tile: 'плитка',
  laminate: 'ламинат',
  vinyl: 'винил',
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
  clearEstimateComment()
}
watch(() => props.project, invalidate, { deep: true })
watch([conditions, query, design, explain, referenceSearch], invalidate, { deep: true })
onBeforeUnmount(() => {
  controller?.abort()
  commentController?.abort()
})
watch(
  () => [room.value.id, room.value.humidity, room.value.load, room.value.heated],
  () => {
    conditions.humidity = room.value.humidity
    conditions.load = room.value.load
    conditions.heated = room.value.heated
  },
)
async function search() {
  controller?.abort()
  controller = new AbortController()
  const current = ++generation
  busy.value = true
  error.value = ''
  result.value = null
  estimate.value = null
  clearEstimateComment()
  selected.value = {}
  confirmed.value = false
  try {
    const normalized = { ...conditions }
    for (const key of ['substrate', 'waterContact', 'covering', 'baseState'] as const)
      normalized[key] = normalized[key]?.trim() || null
    const catalogRequest = props.products?.length
      ? Promise.resolve(props.products)
      : service.catalog(controller.signal).catch(() => [])
    const [response, availableProducts] = await Promise.all([
      service.materialRecommendations(
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
      ),
      catalogRequest,
    ])
    if (current !== generation) return
    loadedProducts.value = availableProducts
    result.value = response
    stale.value = false
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : 'Подбор недоступен.'
  } finally {
    if (current === generation) busy.value = false
  }
}
async function apply() {
  if (!result.value || stale.value || !confirmed.value || !completeSelection.value) return
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
    const chosen = { ...selected.value }
    const response = await service.previewMaterialRecommendations(
      result.value.requestId,
      project,
      chosen,
      controller.signal,
    )
    if (current !== generation) return
    estimate.value = response
    busy.value = false
    if (explain.value) {
      const token = ++commentGeneration
      commentController = new AbortController()
      commentBusy.value = true
      try {
        const comment = await service.explainMaterialPreview(
          result.value.requestId,
          project,
          chosen,
          commentController.signal,
        )
        if (current === generation && token === commentGeneration) estimateComment.value = comment
      } catch {
        if (current === generation && token === commentGeneration)
          estimateComment.value = { status: 'unavailable', model: null, explanation: null }
      } finally {
        if (token === commentGeneration) commentBusy.value = false
      }
    }
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
    clearEstimateComment()
    const visibleKinds = new Set(visibleCategories.value.map((category) => category.kind))
    for (const key of Object.keys(selected.value))
      if (!visibleKinds.has(key.slice(key.lastIndexOf(':') + 1))) delete selected.value[key]
  },
  { deep: true },
)
</script>

<template>
  <section class="material-search" aria-label="Подбор материалов с источниками">
    <h2>Подбор материалов</h2>
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
    <label><input v-model="explain" type="checkbox" /> Добавить комментарий модели</label>
    <p>
      <label
        ><input v-model="referenceSearch" type="checkbox" /> Учитывать инструкции
        производителей</label
      >
    </p>
    <p>
      <button type="button" class="button secondary" :disabled="busy" @click="search">
        {{ busy ? 'Выполняется…' : 'Найти товары' }}
      </button>
    </p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="stale" class="alert warning" role="status">
      Параметры изменились. Повторите подбор перед выбором товаров.
    </p>
    <div v-if="result && !stale" class="search-results" aria-live="polite">
      <div class="results-heading">
        <div>
          <span class="eyebrow">ВАШ ПОДБОР</span>
          <h3>Материалы для комнаты «{{ room.name }}»</h3>
        </div>
        <span class="result-count"
          >{{
            visibleCategories.reduce((total, category) => total + category.candidates.length, 0)
          }}
          товаров</span
        >
      </div>
      <p v-if="hasUnconfirmed" class="selection-notice">
        Можно рассчитать предварительную стоимость выбранных товаров. Их применимость и
        совместимость по документам пока не подтверждены.
      </p>
      <div v-if="result.questions.length" class="clarifications">
        <strong>Уточните условия комнаты</strong>
        <p v-for="question in result.questions" :key="question.field">
          {{ fieldLabels[question.field] || 'Условия применения' }}: {{ question.reason }}
        </p>
      </div>
      <p v-if="!result.categories.length" class="empty-message">
        Подходящие категории не найдены. Уточните задачу.
      </p>
      <section
        v-for="category in visibleCategories"
        :key="`${category.work}:${category.kind}:${category.coveringFamily}`"
        class="product-category"
        :aria-label="kindLabels[category.kind] || 'Материалы'"
      >
        <header class="category-heading">
          <h4>{{ kindLabels[category.kind] || 'Материалы' }}</h4>
          <span>{{ workLabels[category.work] }}</span>
        </header>
        <p v-if="category.coveringFamily" class="muted">
          Для покрытия: {{ coveringLabels[category.coveringFamily] || 'по условиям подбора' }}
        </p>
        <p v-if="!category.candidates.length" class="empty-message">
          Подходящих товаров пока нет. Попробуйте уточнить условия.
        </p>
        <div class="product-grid">
          <article
            v-for="candidate in category.candidates.slice(
              0,
              expandedCategories[`${category.work}:${category.kind}`] ? undefined : 4,
            )"
            :key="candidate.productId"
            class="product-card"
          >
            <div class="product-card-main">
              <ProductSwatch
                v-if="catalog.get(candidate.productId)"
                :product="catalog.get(candidate.productId)!"
              />
              <div class="product-description">
                <span
                  class="compatibility-badge"
                  :class="{
                    ready:
                      candidate.compatibility === 'pass' &&
                      candidate.calculationReadiness === 'ready',
                  }"
                  >{{
                    candidate.compatibility === 'pass' && candidate.calculationReadiness === 'ready'
                      ? 'Подходит для расчёта'
                      : 'Требует проверки'
                  }}</span
                >
                <label class="product-choice"
                  ><input
                    v-model="selected[`${room.id}:${category.kind}`]"
                    type="radio"
                    :name="`${room.id}:${category.kind}`"
                    :value="candidate.productId"
                    :disabled="busy"
                  />{{ candidate.name }}</label
                >
                <p v-if="catalog.get(candidate.productId)" class="product-price">
                  {{ money(catalog.get(candidate.productId)!.price) }}
                  <span>/ {{ catalog.get(candidate.productId)!.saleUnit }}</span>
                </p>
                <p v-else class="muted">Цена уточняется</p>
              </div>
            </div>
            <details
              v-if="candidate.checks.length || candidate.sources.length"
              class="product-details"
            >
              <summary>Условия применения</summary>
              <p v-for="check in candidate.checks" :key="check.property">
                {{ fieldLabels[check.property] || 'Проверка применения' }}: {{ check.reason }}
              </p>
              <p v-for="source in candidate.sources" :key="source.chunkId">
                <a :href="source.url" target="_blank" rel="noopener noreferrer">{{
                  source.title
                }}</a>
                · {{ source.section }}
              </p>
            </details>
          </article>
        </div>
        <button
          v-if="category.candidates.length > 4"
          type="button"
          class="show-more"
          @click="
            expandedCategories[`${category.work}:${category.kind}`] =
              !expandedCategories[`${category.work}:${category.kind}`]
          "
        >
          {{
            expandedCategories[`${category.work}:${category.kind}`]
              ? 'Свернуть список'
              : `Показать ещё ${category.candidates.length - 4}`
          }}
        </button>
      </section>
      <section class="cost-panel" aria-label="Стоимость подбора">
        <h3>Стоимость</h3>
        <template v-if="!estimate"
          ><p>
            {{
              hasUnconfirmed
                ? 'Это предварительный расчёт по каталожным ценам и расчётным профилям. Применимость товаров по документам не подтверждена.'
                : 'Выберите товары и подтвердите условия — рассчитаем количество упаковок и общую стоимость для этой комнаты.'
            }}
          </p>
          <label class="confirmation"
            ><input
              v-model="confirmed"
              type="checkbox"
              :disabled="busy || !completeSelection"
            />Подтверждаю выбранные товары и предварительный расчёт без подтверждения их
            применимости по документам</label
          >
          <button
            type="button"
            class="button primary"
            :disabled="busy || !confirmed || !completeSelection"
            @click="apply"
          >
            Рассчитать предварительную смету
          </button>
        </template>
        <template v-else>
          <p class="selection-notice">
            Предварительная смета. Применимость и совместимость выбранных товаров по документам не
            подтверждены.
          </p>
          <EstimateResult :estimate="estimate" />
          <section v-if="explain" class="estimate-comment" aria-label="Пояснение к смете">
            <h4>Пояснение к смете</h4>
            <p v-if="commentBusy" role="status">Модель готовит пояснение. Смета уже рассчитана.</p>
            <template v-else-if="estimateComment?.status === 'unverified_model_text'">
              <MarkdownText :text="estimateComment.explanation || ''" />
              <p class="muted">
                Текст создан ИИ и может содержать ошибки. Количество и стоимость определены
                Java-калькулятором в смете выше.
              </p>
            </template>
            <p v-else role="status">Смета рассчитана, но пояснение модели сейчас недоступно.</p>
          </section>
        </template>
      </section>
      <section v-if="hasModelComment" class="model-comment" aria-label="Комментарий модели">
        <header>
          <span class="eyebrow">ПОМОЩНИК ПО РЕМОНТУ</span>
          <h3>Комментарий модели</h3>
        </header>
        <p class="model-note">
          Непроверенный текст модели: рекомендации стоит сверить с инструкцией производителя.
          Справочные документы не подтверждают характеристики учебных товаров.
        </p>
        <div v-for="item in result.explanation.items" :key="item.productId" class="comment-item">
          <strong>{{
            result.categories
              .flatMap((category) => category.candidates)
              .find((candidate) => candidate.productId === item.productId)?.name
          }}</strong>
          <p>{{ item.text }}</p>
        </div>
        <div
          v-for="(item, index) in result.references?.items || []"
          :key="index"
          class="comment-item"
        >
          <p>{{ item.text }}</p>
          <small v-if="sourceLabel(item.chunkIds)">{{ sourceLabel(item.chunkIds) }}</small>
        </div>
      </section>
      <p v-else-if="explain && !estimate" class="muted" role="status">
        Комментарий модели пока недоступен. Найденные товары и результаты проверок сохранены.
      </p>
      <details v-if="referenceSearch && result.references" class="sources-panel">
        <summary>
          Источники рекомендаций <span>{{ result.references.fragments.length }} фрагментов</span>
        </summary>
        <p class="muted">
          Документы производителей используются как справочная информация и не подтверждают
          характеристики учебных товаров.
        </p>
        <p v-if="result.references.status === 'disabled'">Справочный поиск отключён на сервере.</p>
        <p v-else-if="result.references.status === 'unavailable'">
          Источники временно недоступны. Попробуйте повторить поиск позже.
        </p>
        <p v-else-if="result.references.status === 'empty'">
          Источники по этому запросу не найдены. Уточните задачу.
        </p>
        <ul class="source-list">
          <li v-for="fragment in result.references.fragments" :key="fragment.chunkId">
            <a v-if="fragment.url" :href="fragment.url" target="_blank" rel="noopener noreferrer">{{
              fragment.title
            }}</a
            ><span v-else>{{ fragment.title }}</span
            ><span class="muted"> · страница {{ fragment.page }}</span>
          </li>
        </ul>
      </details>
      <details v-if="result.warnings.length" class="sources-panel">
        <summary>Что важно учесть</summary>
        <ul>
          <li v-for="warning in result.warnings" :key="warning">{{ warning }}</li>
        </ul>
      </details>
    </div>
  </section>
</template>

<style scoped>
.material-search {
  margin-top: 1.5rem;
  padding: clamp(1rem, 3vw, 2rem);
  border: 1px solid #e1e7de;
  border-radius: 18px;
  background: #fff;
  min-width: 0;
}
.material-search h2 {
  margin: 0 0 0.5rem;
}
.material-search h3,
.material-search h4 {
  margin: 0;
}
.search-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.75rem;
}
.search-results {
  margin-top: 2rem;
  display: grid;
  gap: 1.5rem;
}
.results-heading,
.category-heading {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}
.eyebrow {
  display: block;
  font-size: 0.65rem;
  letter-spacing: 0.12em;
  color: #72816e;
  margin-bottom: 0.5rem;
}
.result-count,
.category-heading > span {
  font-size: 0.8rem;
  color: #71806e;
}
.selection-notice,
.clarifications {
  background: #faf6ed;
  color: #795f32;
  padding: 1rem;
  border-radius: 10px;
  margin: 0;
  line-height: 1.6;
}
.clarifications p {
  margin: 0.5rem 0 0;
}
.product-category {
  min-width: 0;
}
.category-heading {
  margin-bottom: 0.8rem;
}
.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr));
  gap: 0.85rem;
}
.product-card {
  border: 1px solid #e2e7df;
  border-radius: 12px;
  padding: 1rem;
  min-width: 0;
}
.product-card-main {
  display: flex;
  gap: 0.8rem;
  align-items: flex-start;
}
.product-description {
  min-width: 0;
  flex: 1;
}
.product-choice {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  font-size: 0.9rem;
  line-height: 1.5;
  font-weight: 600;
  margin-top: 0.6rem;
  overflow-wrap: anywhere;
}
.product-choice input {
  flex: 0 0 auto;
  margin-top: 0.3rem;
}
.compatibility-badge {
  display: inline-block;
  padding: 0.2rem 0.5rem;
  background: #f6f1e6;
  color: #876b37;
  font-size: 0.65rem;
  border-radius: 5px;
}
.compatibility-badge.ready {
  background: #edf4ea;
  color: #456541;
}
.product-price {
  font-size: 1.1rem;
  font-weight: 700;
  margin: 0.8rem 0 0;
  color: #304e42;
}
.product-price span {
  font-size: 0.75rem;
  color: #768174;
  font-weight: 400;
}
.product-details {
  margin-top: 0.9rem;
  padding-top: 0.7rem;
  border-top: 1px solid #eef0eb;
  font-size: 0.8rem;
  line-height: 1.6;
}
.product-details summary,
.sources-panel summary {
  cursor: pointer;
  color: #526750;
}
.show-more {
  border: 0;
  background: transparent;
  color: #426951;
  padding: 0.8rem 0 0;
  font: inherit;
  font-size: 0.8rem;
  cursor: pointer;
}
.cost-panel {
  padding: 1.25rem;
  background: #f6f8f3;
  border: 1px solid #e4eadd;
  border-radius: 12px;
}
.cost-panel p {
  line-height: 1.7;
  font-size: 0.85rem;
  color: #64705f;
}
.confirmation {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  font-size: 0.8rem;
  line-height: 1.6;
  margin: 1rem 0;
}
.confirmation input {
  flex: 0 0 auto;
  margin-top: 0.3rem;
}
.model-comment {
  border-left: 3px solid #70936e;
  background: #f7f9f4;
  padding: 1.4rem;
  border-radius: 0 12px 12px 0;
  min-width: 0;
}
.model-note {
  font-size: 0.75rem;
  line-height: 1.7;
  color: #75806e;
  margin: 0.75rem 0 1rem;
}
.comment-item {
  display: block;
  margin-top: 1rem;
  max-width: 75ch;
}
.comment-item p {
  font-size: 0.9rem;
  line-height: 1.8;
  margin: 0 0 0.4rem;
  overflow-wrap: anywhere;
}
.comment-item small {
  color: #74836c;
  font-size: 0.7rem;
  line-height: 1.6;
}
.sources-panel {
  border-top: 1px solid #e4e9e0;
  padding-top: 1rem;
  font-size: 0.8rem;
  line-height: 1.7;
  min-width: 0;
}
.sources-panel summary span {
  color: #7d8977;
  margin-left: 0.5rem;
  font-size: 0.7rem;
}
.source-list {
  padding-left: 1.2rem;
}
.source-list li {
  margin: 0.6rem 0;
  overflow-wrap: anywhere;
}
.muted,
.empty-message {
  font-size: 0.8rem;
  line-height: 1.7;
  color: #7d8776;
}
@media (max-width: 700px) {
  .product-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .material-search {
    padding: 1rem;
  }
  .model-comment,
  .cost-panel {
    padding: 1rem;
  }
}
</style>
