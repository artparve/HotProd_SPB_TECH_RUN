<script setup lang="ts">
import type { MaterialEstimate } from '../domain/materials'

defineProps<{ response: MaterialEstimate }>()
</script>

<template>
  <section class="material-explanation" aria-label="Пояснение ИИ">
    <h3>Пояснение ИИ</h3>
    <p class="field-hint">Помощник: {{ response.model }}</p>
    <template v-if="response.explanationStatus === 'unverified_model_text'">
      <p class="explanation-text">{{ response.explanation }}</p>
      <p class="field-hint">
        Текст создан ИИ и может содержать ошибки. Проверенные количество и стоимость указаны в смете
        выше.
      </p>
    </template>
    <p v-else class="alert warning" role="status">
      Смета рассчитана, но пояснение ИИ недоступно. {{ response.explanationError?.message }}
    </p>
  </section>
</template>

<style scoped>
.material-explanation {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #dfe6d6;
}
.explanation-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
}
</style>
