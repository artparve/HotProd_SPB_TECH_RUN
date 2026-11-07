<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { admin } from '../services/admin'
import type { AdminProduct, ClassificationJob } from '../domain/admin'
const props = defineProps<{ product: AdminProduct; etag: string }>()
const emit = defineEmits<{ published: [] }>()
const jobs = ref<ClassificationJob[]>([])
const currentJobs = computed(() =>
  jobs.value.filter((job) => job.versionId === props.product.versionId),
)
const oldJobs = computed(() =>
  jobs.value.filter((job) => job.versionId !== props.product.versionId),
)
const reviews = ref<Awaited<ReturnType<typeof admin.reviews>>>([])
const readiness = ref<Awaited<ReturnType<typeof admin.readiness>> | null>(null)
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
const labels = {
  queued: 'В очереди',
  running: 'Модель обрабатывает',
  succeeded: 'Классификация выполнена',
  failed: 'Ошибка модели — можно запустить заново',
}
async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Запрос не выполнен.'
  } finally {
    busy.value = false
  }
}
async function refreshJobs() {
  jobs.value = await admin.classifications(props.product.id)
  clearTimeout(timer)
  if (loaded.value) readiness.value = await admin.readiness(props.product.id)
  if (!disposed && jobs.value.some((j) => j.status === 'queued' || j.status === 'running')) {
    timer = setTimeout(() => {
      refreshJobs().catch(() => {
        error.value = 'Не удалось обновить очередь. Нажмите «Обновить».'
      })
    }, 3000)
  }
}
async function load() {
  await run(async () => {
    await refreshJobs()
    reviews.value = await admin.reviews(props.product.id)
    readiness.value = await admin.readiness(props.product.id)
    loaded.value = true
  })
}
async function classify() {
  await run(async () => {
    await admin.classify(props.product.id, props.etag)
    await refreshJobs()
    notice.value = 'Задание сохранено. Worker выполнит его, когда доступен.'
  })
}
async function publish() {
  await run(async () => {
    await admin.publish(props.product.id, props.etag)
    emit('published')
  })
}
</script>

<template>
  <section class="workflow" aria-label="Классификация, проверка и публикация">
    <h2>Классификация → публикация</h2>
    <p>
      Работаем с сохранённой версией {{ product.version }}. Метки модели сопоставляются с расчётом
      автоматически. Нормы и цены задаются в карточке.
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button :disabled="busy" @click="load">
      {{ loaded ? 'Обновить' : 'Загрузить задания и готовность' }}
    </button>
    <RouterLink v-if="product.status === 'published'" :to="`/product?id=${product.id}`"
      >Рассчитать опубликованный товар</RouterLink
    >
    <template v-if="loaded">
      <button
        :disabled="
          busy ||
          jobs.some(
            (j) => j.versionId === product.versionId && ['queued', 'running'].includes(j.status),
          )
        "
        @click="classify"
      >
        Запустить классификацию
      </button>
      <article v-for="job in currentJobs" :key="job.id">
        <p>{{ labels[job.status] }} · попыток {{ job.attempts }} · {{ job.createdAt }}</p>
        <p v-if="job.result?.classification.needs_review">
          Модель отметила спорные или недостаточные данные:
          {{ job.result.classification.review_reasons.join('; ') }}
        </p>
        <details>
          <summary>Сохранённый вход и ответ модели</summary>
          <pre>{{
            JSON.stringify({ input: job.input, result: job.result, error: job.errorCode }, null, 2)
          }}</pre>
        </details>
      </article>
      <details v-if="oldJobs.length">
        <summary>Предыдущие версии и их задания ({{ oldJobs.length }})</summary>
        <article v-for="job in oldJobs" :key="job.id">
          <p>
            {{ labels[job.status] }} · попыток {{ job.attempts }} · {{ job.createdAt }}
            <strong>Устаревшая версия — использовать нельзя</strong>
          </p>
          <details>
            <summary>Сохранённый вход и ответ модели</summary>
            <pre>{{
              JSON.stringify(
                { input: job.input, result: job.result, error: job.errorCode },
                null,
                2,
              )
            }}</pre>
          </details>
        </article>
      </details>
      <details v-if="reviews.length">
        <summary>История сопоставлений ({{ reviews.length }})</summary>
        <pre>{{ JSON.stringify(reviews, null, 2) }}</pre>
      </details>
      <div v-if="readiness">
        <h3>
          {{ readiness.ready ? 'Готово к публикации и расчёту' : 'Публикация заблокирована' }}
        </h3>
        <ul>
          <li v-for="blocker in readiness.blockers" :key="blocker">{{ blocker }}</li>
        </ul>
        <button
          :disabled="busy || !readiness.ready || product.status === 'published'"
          @click="publish"
        >
          Опубликовать снимок каталога и правил
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.workflow {
  margin: 32px 0;
  padding: 24px;
  border: 1px solid #dce2ea;
  border-radius: 16px;
}
article,
fieldset {
  margin: 16px 0;
  padding: 16px;
  border: 1px solid #dce2ea;
}
label {
  display: block;
  margin: 12px 0;
}
input,
select {
  max-width: 100%;
  padding: 8px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 500px;
  overflow: auto;
}
button {
  margin: 8px;
  padding: 8px 16px;
}
[role='alert'],
strong {
  color: #a42323;
}
</style>
