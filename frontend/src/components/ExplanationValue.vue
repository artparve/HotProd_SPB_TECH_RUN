<script setup lang="ts">
import { computed } from 'vue'
import { explanationLabel } from '../domain/explanation'
import MarkdownText from './MarkdownText.vue'

const props = withDefaults(defineProps<{ value: unknown; depth?: number }>(), { depth: 0 })
const entries = computed(() =>
  props.value && typeof props.value === 'object' ? Object.entries(props.value).slice(0, 100) : [],
)
const limited = computed(
  () => props.value && typeof props.value === 'object' && Object.keys(props.value).length > 100,
)
</script>

<template>
  <p v-if="depth >= 8" class="field-hint">Слишком глубокая структура ответа.</p>
  <ol v-else-if="Array.isArray(value)" class="explanation-list">
    <li v-for="[key, item] in entries" :key="key">
      <ExplanationValue :value="item" :depth="depth + 1" />
    </li>
    <li v-if="!entries.length" class="muted">Нет записей</li>
    <li v-if="limited" class="muted">Показаны первые 100 записей</li>
  </ol>
  <dl v-else-if="value !== null && typeof value === 'object'" class="explanation-fields">
    <div v-for="[key, item] in entries" :key="key" class="explanation-field">
      <dt>{{ explanationLabel(key) }}</dt>
      <dd><ExplanationValue :value="item" :depth="depth + 1" /></dd>
    </div>
    <div v-if="!entries.length" class="muted">Нет данных</div>
    <div v-if="limited" class="muted">Показаны первые 100 полей</div>
  </dl>
  <MarkdownText v-else-if="typeof value === 'string'" :text="value" />
  <span v-else>{{
    value === null ? 'Не указано' : value === true ? 'Да' : value === false ? 'Нет' : String(value)
  }}</span>
</template>

<style scoped>
.explanation-fields {
  margin: 0;
}
.explanation-field {
  padding: 10px 12px;
  border-left: 2px solid #dfe6d6;
  margin: 8px 0;
  background: #f8faf6;
  overflow-wrap: anywhere;
}
dt {
  font-weight: 650;
  margin-bottom: 4px;
}
dd {
  margin: 0;
  min-width: 0;
}
.explanation-list {
  padding-left: 24px;
}
.explanation-list > li {
  margin: 8px 0;
}
</style>
