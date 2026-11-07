<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { knowledgeBundleSchema } from '../domain/knowledge'
import { admin } from '../services/admin'

const input = ref('')
const approved = ref(false)
const releaseId = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const preview = computed(() => {
  try {
    return knowledgeBundleSchema.safeParse(JSON.parse(input.value))
  } catch {
    return null
  }
})
watch(input, () => {
  approved.value = false
  notice.value = ''
})
async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Операция не выполнена.'
  } finally {
    busy.value = false
  }
}
async function publish() {
  if (!approved.value || !preview.value?.success) return
  const bundle = preview.value.data
  await run(async () => {
    const release = await admin.publishKnowledge(bundle)
    releaseId.value = release.id
    notice.value = `Опубликована база знаний ${release.id}. Индекс: ${release.checksum}.`
    approved.value = false
  })
}
async function activate() {
  await run(async () => {
    await admin.activateKnowledge(releaseId.value)
    notice.value = 'Версия базы знаний активирована.'
  })
}
async function revoke() {
  await run(async () => {
    await admin.revokeKnowledge(releaseId.value)
    notice.value = 'Версия отозвана и больше не подтверждает подбор.'
  })
}
</script>
<template>
  <details class="knowledge-admin">
    <summary>База знаний для подбора материалов</summary>
    <p>
      Публикация допускает только вручную проверенные источники и точные ID версий товаров. Черновые
      коллекции data/rag перед публикацией должны пройти проверку технического эксперта и прав
      использования.
    </p>
    <label class="field"
      >Проверенный пакет знаний JSON<textarea
        v-model="input"
        rows="10"
        maxlength="2000000"
        spellcheck="false"
        :disabled="busy"
      />
    </label>
    <p v-if="input && !preview?.success" role="status">
      Пакет не соответствует формату. Нужны версии каталога и правил, источники, фрагменты,
      утверждения, требования и совместимость связок.
    </p>
    <div v-if="preview?.success">
      <p>
        Источников: {{ preview.data.sources.length }} · Фрагментов:
        {{ preview.data.chunks.length }} · Утверждений: {{ preview.data.claims.length }}
      </p>
      <ul>
        <li v-for="source in preview.data.sources" :key="source.id">
          {{ source.title }} · {{ source.version }} · действует до {{ source.validUntil }}
        </li>
      </ul>
      <label
        ><input v-model="approved" type="checkbox" :disabled="busy" /> Подтверждаю техническую
        проверку фактов, правил и связей с товарами, актуальность и права использования
        источников</label
      >
    </div>
    <p>
      <button
        class="button secondary"
        type="button"
        :disabled="busy || !approved || !preview?.success"
        @click="publish"
      >
        Опубликовать проверенную базу знаний
      </button>
    </p>
    <label class="field"
      >ID сохранённой версии для отката или отзыва<input
        v-model="releaseId"
        maxlength="36"
        :disabled="busy"
    /></label>
    <button type="button" :disabled="busy || !/^[a-f0-9-]{36}$/.test(releaseId)" @click="activate">
      Активировать сохранённую версию
    </button>
    <button type="button" :disabled="busy || !/^[a-f0-9-]{36}$/.test(releaseId)" @click="revoke">
      Отозвать версию
    </button>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert info" role="status">{{ notice }}</p>
  </details>
</template>
<style scoped>
.knowledge-admin {
  margin: 1rem 0;
  padding: 1rem;
  border: 1px solid #ddd;
}
textarea {
  width: 100%;
  font-family: monospace;
}
</style>
