<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import ProductSwatch from '../components/ProductSwatch.vue'
import EstimateResult from '../components/EstimateResult.vue'
import MaterialExplanation from '../components/MaterialExplanation.vue'
import { productInputSchema } from '../domain/types'
import type { Estimate, Product, ProductInput } from '../domain/types'
import type { MaterialEstimate } from '../domain/materials'
import { money } from '../domain/format'
import { useAsync } from '../composables/useAsync'
import { dataMode, service } from '../services/api'

const route = useRoute()
const router = useRouter()
const products = ref<Product[]>([])
const selectedId = ref('')
const product = computed(() => products.value.find((p) => p.id === selectedId.value))
const result = ref<Estimate | null>(null)
const assistantResult = ref<MaterialEstimate | null>(null)
const useAssistant = ref(true)
const input = ref<ProductInput>({
  area: 20,
  perimeter: 18,
  height: 2.7,
  reserve: 10,
  layers: 2,
  quantity: 6,
})
const showValidation = ref(false)
const { busy, error, run } = useAsync()
const invalidProduct = ref(false)
async function load() {
  const response = await run(() => service.catalog())
  if (response) {
    products.value = response
    const requested = typeof route.query.id === 'string' ? route.query.id : undefined
    invalidProduct.value = !!requested && !response.some((p) => p.id === requested)
    selectedId.value = response.find((p) => p.id === requested)?.id || response[0]?.id || ''
  }
}
onMounted(load)
watch(
  [selectedId, input, useAssistant],
  () => {
    result.value = null
    assistantResult.value = null
    error.value = ''
  },
  { deep: true },
)
watch(selectedId, (id) => {
  if (id) void router.replace({ query: { id } })
})
const needsArea = computed(
  () => product.value && !['socket', 'cable', 'wallpaper'].includes(product.value.kind),
)
async function calculate() {
  showValidation.value = true
  if (!productInputSchema.safeParse(input.value).success || !product.value) return
  const fingerprint = JSON.stringify([selectedId.value, input.value, useAssistant.value])
  result.value = null
  assistantResult.value = null
  const response = await run(async () => {
    const values = productInputSchema.parse(input.value)
    const assistant =
      dataMode === 'api' && useAssistant.value
        ? await service.materialEstimate(product.value!, values)
        : null
    const result = assistant?.estimate || (await service.product(product.value!, values))
    if (fingerprint !== JSON.stringify([selectedId.value, input.value, useAssistant.value]))
      throw new Error('Параметры изменились. Повторите расчёт.')
    return { result, assistant }
  })
  if (response) {
    result.value = response.result
    assistantResult.value = response.assistant
  }
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">НИ ОДНОЙ ЛИШНЕЙ УПАКОВКИ</span>
      <h1>Сколько нужно именно вам<span class="heading-dot">?</span></h1>
      <p>Калькулятор в карточке товара: потребность, упаковки, стоимость и остаток.</p>
    </div>
  </div>
  <div v-if="invalidProduct" class="alert warning" role="status">
    Товар по ссылке не найден. Выберите доступный товар ниже.
  </div>
  <div v-if="!products.length" class="panel empty-state">
    <AppIcon name="box" :size="36" />
    <h2>{{ busy ? 'Загружаем каталог…' : 'Каталог недоступен или пуст' }}</h2>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="button secondary" :disabled="busy" @click="load">Повторить загрузку</button>
  </div>
  <template v-if="product"
    ><div class="product-layout">
      <article class="panel product-detail">
        <ProductSwatch :product="product" large />
        <div class="product-detail-body">
          <span class="eyebrow">{{ product.category }}</span>
          <h2>{{ product.name }}</h2>
          <p>{{ product.description }}</p>
          <div class="product-detail-price">
            {{ money(product.price) }}<small>/ {{ product.saleUnit }}</small>
          </div>
          <dl>
            <div>
              <dt>В упаковке</dt>
              <dd>{{ product.packageSize || 'Неизвестно' }} {{ product.unit }}</dd>
            </div>
            <div>
              <dt>Норма на один слой</dt>
              <dd>
                {{
                  product.consumption
                    ? `${product.consumption} ${product.unit}/м²`
                    : 'Не применяется / не подтверждена'
                }}
              </dd>
            </div>
            <div>
              <dt>Источник</dt>
              <dd>{{ product.source }}</dd>
            </div>
          </dl>
          <p class="field-hint">
            Перед покупкой уточните актуальную цену, наличие и пригодность материала для вашего
            объекта.
          </p>
        </div>
      </article>
      <section class="panel main-panel">
        <div class="panel-heading">
          <div class="section-title">
            <span class="section-icon"><AppIcon name="calculator" /></span>
            <div>
              <h2>Рассчитать количество</h2>
              <p>Учитываем запас и округление упаковок</p>
            </div>
          </div>
        </div>
        <form @submit.prevent="calculate">
          <label class="field"
            >Товар<select v-model="selectedId">
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select></label
          >
          <div class="dimensions-grid two-col product-inputs">
            <label v-if="needsArea" class="field"
              >Площадь поверхности
              <div class="input-unit">
                <input
                  v-model.number="input.area"
                  type="number"
                  min="0.1"
                  max="10000"
                  step="0.1"
                  required
                /><span>м²</span>
              </div></label
            ><template v-if="product.kind === 'wallpaper'"
              ><label class="field"
                >Периметр стен
                <div class="input-unit">
                  <input
                    v-model.number="input.perimeter"
                    type="number"
                    min="0.1"
                    max="1000"
                    step="0.1"
                    required
                  /><span>м</span>
                </div></label
              ><label class="field"
                >Высота стен
                <div class="input-unit">
                  <input
                    v-model.number="input.height"
                    type="number"
                    min="1"
                    max="10"
                    step="0.1"
                    required
                  /><span>м</span>
                </div></label
              ></template
            ><label v-if="product.kind === 'socket' || product.kind === 'cable'" class="field"
              >{{ product.kind === 'socket' ? 'Количество точек' : 'Длина трассы, м'
              }}<input
                v-model.number="input.quantity"
                type="number"
                min="1"
                max="5000"
                :step="product.kind === 'socket' ? 1 : 0.1"
                required /></label
            ><label v-if="product.kind !== 'socket'" class="field"
              >Запас на потери
              <div class="input-unit">
                <input
                  v-model.number="input.reserve"
                  type="number"
                  min="0"
                  max="30"
                  step="1"
                  required
                /><span>%</span>
              </div></label
            ><label v-if="product.kind === 'paint'" class="field"
              >Количество слоёв<input
                v-model.number="input.layers"
                type="number"
                min="1"
                max="4"
                step="1"
                required
            /></label>
          </div>
          <div v-if="dataMode === 'api'" class="assistant-option">
            <label class="assistant-toggle">
              <input
                v-model="useAssistant"
                type="checkbox"
                :disabled="busy"
                aria-describedby="assistant-hint"
              />
              <span>Добавить пояснение ИИ</span>
            </label>
            <p id="assistant-hint" class="field-hint">
              Помощник вызовет калькулятор и объяснит результат. Первый запрос может занять
              несколько минут.
            </p>
          </div>
          <div class="alert info">
            <AppIcon name="bulb" :size="18" /><span
              >Вы увидите и расчётную потребность, и фактически купленное количество. Работы в
              стоимость товара не входят.</span
            >
          </div>
          <div
            v-if="showValidation && !productInputSchema.safeParse(input).success"
            class="alert error"
            role="alert"
          >
            Проверьте размеры, запас и количество слоёв.
          </div>
          <div v-if="error" class="alert error" role="alert">{{ error }}</div>
          <p v-if="busy && dataMode === 'api' && useAssistant" role="status">
            Рассчитываем смету и готовим пояснение ИИ. Это может занять несколько минут.
          </p>
          <button class="button primary full-width" :disabled="busy">
            {{ busy ? 'Рассчитываем…' : 'Рассчитать упаковки' }}<AppIcon name="arrow" :size="18" />
          </button>
        </form>
      </section>
    </div>
    <section v-if="result" class="panel result-panel">
      <EstimateResult :estimate="result" />
      <MaterialExplanation v-if="assistantResult" :response="assistantResult" />
    </section>
  </template>
</template>

<style scoped>
.assistant-option {
  margin: 16px 0;
}
.assistant-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.assistant-toggle input {
  width: 16px;
  height: 16px;
  margin: 0;
  padding: 0;
  accent-color: #245a48;
}
</style>
