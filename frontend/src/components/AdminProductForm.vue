<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { adminDraftSchema } from '../domain/admin'
import type { AdminDraft } from '../domain/admin'

const props = defineProps<{ initial: AdminDraft; existing: boolean; busy: boolean }>()
const emit = defineEmits<{ save: [draft: AdminDraft] }>()
type Field = { key: string; label: string; type?: string; choices?: string[] }
const fields: Field[] = [
  { key: 'category', label: 'Категория' },
  {
    key: 'kind',
    label: 'Вид товара',
    choices: ['covering', 'paint', 'primer', 'wallpaper', 'adhesive', 'socket', 'cable'],
  },
  { key: 'price', label: 'Цена', type: 'price' },
  { key: 'currency', label: 'Валюта', choices: ['RUB'] },
  { key: 'priceDate', label: 'Дата актуальности цены', type: 'date' },
  { key: 'unit', label: 'Единица расчёта', choices: ['м²', 'л', 'кг', 'м', 'шт.', 'полотен'] },
  {
    key: 'saleUnit',
    label: 'Единица продажи',
    choices: ['м²', 'л', 'кг', 'м', 'шт.', 'уп.', 'рул.', 'бан.', 'меш.', 'кан.', 'бух.'],
  },
  { key: 'packageSize', label: 'Размер упаковки', type: 'number' },
  { key: 'consumption', label: 'Расход', type: 'number' },
  {
    key: 'consumptionUnit',
    label: 'Единица расхода',
    choices: ['м²', 'л', 'кг', 'м', 'шт.', 'полотен'],
  },
  { key: 'wet', label: 'Для влажных помещений', type: 'boolean' },
  { key: 'highLoad', label: 'Для высокой нагрузки', type: 'boolean' },
  { key: 'unheated', label: 'Для неотапливаемых помещений', type: 'boolean' },
  { key: 'rollWidth', label: 'Ширина рулона, м', type: 'number' },
  { key: 'rollLength', label: 'Длина рулона, м', type: 'number' },
  { key: 'rapport', label: 'Раппорт, м', type: 'number' },
]
const base = reactive({
  sourceNamespace: '',
  externalId: '',
  name: '',
  sku: '',
  description: '',
  application: '',
  limitations: '',
  comment: '',
  attributes: [] as { name: string; value: string; unit?: string }[],
  sources: [] as { label: string; url?: string }[],
})
const values = reactive<
  Record<
    string,
    {
      state: 'unset' | 'unknown' | 'confirmed'
      value: string
      source: string
      confirmedBy?: string
    }
  >
>({})
const issues = ref<string[]>([])
watch(
  () => props.initial,
  (initial) => {
    for (const key of [
      'sourceNamespace',
      'externalId',
      'name',
      'sku',
      'description',
      'application',
      'limitations',
      'comment',
    ] as const)
      base[key] = initial[key] || ''
    base.attributes = (initial.attributes || []).map((item) => ({ ...item }))
    base.sources = (initial.sources || []).map((item) => ({ ...item }))
    for (const field of fields) {
      const stored = initial[field.key as keyof AdminDraft] as
        | {
            state: 'unset' | 'unknown' | 'confirmed'
            value?: unknown
            source?: string
            confirmedBy?: string
          }
        | undefined
      values[field.key] = {
        state: stored?.state || 'unset',
        value: stored?.value === undefined || stored.value === null ? '' : String(stored.value),
        source: stored?.source || '',
        confirmedBy: stored?.confirmedBy,
      }
    }
    issues.value = []
  },
  { immediate: true },
)

function save() {
  const snapshot: Record<string, unknown> = {
    ...base,
    attributes: base.attributes.map((item) => ({ ...item })),
    sources: base.sources.map((item) => ({ ...item })),
  }
  for (const field of fields) {
    const current = values[field.key]!
    snapshot[field.key] =
      current.state === 'confirmed'
        ? {
            state: current.state,
            source: current.source,
            value:
              field.type === 'number'
                ? current.value === ''
                  ? null
                  : Number(current.value)
                : field.type === 'boolean'
                  ? current.value === ''
                    ? null
                    : current.value === 'true'
                  : current.value,
          }
        : { state: current.state, ...(current.source ? { source: current.source } : {}) }
  }
  const result = adminDraftSchema.safeParse(snapshot)
  if (!result.success) {
    issues.value = result.error.issues.map(
      (issue) => `${issue.path.join('.')}: проверьте значение, состояние и источник.`,
    )
    return
  }
  issues.value = []
  emit('save', result.data)
}
</script>

<template>
  <form class="admin-form" @submit.prevent="save">
    <fieldset :disabled="busy">
      <h2>Основные сведения</h2>
      <div class="admin-grid">
        <label
          >Источник идентификатора<input
            v-model="base.sourceNamespace"
            required
            maxlength="500"
            :disabled="existing"
            autocomplete="off"
        /></label>
        <label
          >Внешний идентификатор<input
            v-model="base.externalId"
            required
            maxlength="500"
            :disabled="existing"
            autocomplete="off"
        /></label>
        <label>Название<input v-model="base.name" required maxlength="500" /></label>
        <label>Артикул<input v-model="base.sku" maxlength="20000" /></label>
      </div>
      <label>Описание<textarea v-model="base.description" rows="4" maxlength="20000" /></label>
      <div class="admin-grid">
        <label
          >Область применения<textarea v-model="base.application" rows="2" maxlength="20000" />
        </label>
        <label>Ограничения<textarea v-model="base.limitations" rows="2" maxlength="20000" /></label>
      </div>
      <h2>Проверяемые значения</h2>
      <p>
        «Не заполнено» и «Явно неизвестно» сохраняются отдельно. Для подтверждённого значения
        укажите источник. Сохранение фиксирует вас как автора подтверждения полей.
      </p>
      <div class="admin-grid">
        <div v-for="field in fields" :key="field.key" class="admin-value-field">
          <label :for="`state-${field.key}`">{{ field.label }} — состояние</label>
          <select :id="`state-${field.key}`" v-model="values[field.key]!.state">
            <option value="unset">Не заполнено</option>
            <option value="unknown">Явно неизвестно</option>
            <option value="confirmed">Подтверждено</option>
          </select>
          <template v-if="values[field.key]!.state === 'confirmed'">
            <label :for="`value-${field.key}`">{{ field.label }} — значение</label>
            <select
              v-if="field.choices"
              :id="`value-${field.key}`"
              v-model="values[field.key]!.value"
              required
            >
              <option disabled value="">Выберите значение</option>
              <option v-for="choice in field.choices" :key="choice" :value="choice">
                {{ choice }}
              </option>
            </select>
            <select
              v-else-if="field.type === 'boolean'"
              :id="`value-${field.key}`"
              v-model="values[field.key]!.value"
              required
            >
              <option disabled value="">Выберите значение</option>
              <option value="true">Да</option>
              <option value="false">Нет</option>
            </select>
            <input
              v-else
              :id="`value-${field.key}`"
              v-model="values[field.key]!.value"
              :type="field.type === 'date' ? 'date' : field.type === 'number' ? 'number' : 'text'"
              :inputmode="field.type === 'price' ? 'decimal' : undefined"
              :step="field.type === 'number' ? 'any' : undefined"
              :min="field.type === 'number' ? 0 : undefined"
              maxlength="4000"
              required
            />
          </template>
          <label :for="`source-${field.key}`">Источник для поля «{{ field.label }}»</label>
          <input
            :id="`source-${field.key}`"
            v-model="values[field.key]!.source"
            maxlength="4000"
            :required="values[field.key]!.state === 'confirmed'"
          />
          <small v-if="values[field.key]!.confirmedBy"
            >Последнее подтверждение: {{ values[field.key]!.confirmedBy }}</small
          >
        </div>
      </div>
      <h2>Характеристики</h2>
      <div v-for="(attribute, i) in base.attributes" :key="i" class="admin-row">
        <label
          >Название характеристики<input v-model="attribute.name" required maxlength="4000"
        /></label>
        <label
          >Значение характеристики<input v-model="attribute.value" required maxlength="4000"
        /></label>
        <label>Единица характеристики<input v-model="attribute.unit" maxlength="4000" /></label>
        <button
          type="button"
          :aria-label="`Удалить характеристику ${i + 1}`"
          @click="base.attributes.splice(i, 1)"
        >
          Удалить
        </button>
      </div>
      <button
        type="button"
        :disabled="base.attributes.length >= 200"
        @click="base.attributes.push({ name: '', value: '', unit: '' })"
      >
        Добавить характеристику
      </button>
      <h2>Источники</h2>
      <div v-for="(source, i) in base.sources" :key="i" class="admin-row">
        <label>Название источника<input v-model="source.label" required maxlength="4000" /></label>
        <label>Ссылка источника<input v-model="source.url" maxlength="4000" /></label>
        <button
          type="button"
          :aria-label="`Удалить источник ${i + 1}`"
          @click="base.sources.splice(i, 1)"
        >
          Удалить
        </button>
      </div>
      <button
        type="button"
        :disabled="base.sources.length >= 200"
        @click="base.sources.push({ label: '', url: '' })"
      >
        Добавить источник
      </button>
      <label
        >Служебный комментарий<textarea v-model="base.comment" rows="3" maxlength="20000" />
      </label>
      <ul v-if="issues.length" role="alert">
        <li v-for="issue in issues" :key="issue">{{ issue }}</li>
      </ul>
      <button class="admin-primary" type="submit">
        {{ busy ? 'Сохранение…' : existing ? 'Сохранить новую версию' : 'Создать черновик' }}
      </button>
    </fieldset>
  </form>
</template>
