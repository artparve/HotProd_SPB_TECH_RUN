<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { admin } from '../services/admin'
import type { AdminProduct, ClassificationJob, ReviewDecision } from '../domain/admin'
const props = defineProps<{ product: AdminProduct; etag: string }>()
const emit = defineEmits<{ published: [] }>()
const jobs = ref<ClassificationJob[]>([])
const options = ref<Awaited<ReturnType<typeof admin.reviewOptions>> | null>(null)
const reviews = ref<Awaited<ReturnType<typeof admin.reviews>>>([])
const readiness = ref<Awaited<ReturnType<typeof admin.readiness>> | null>(null)
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const profileId = ref('')
const selectedId = ref('')
type Row = Omit<ReviewDecision, 'action'> & {
  action: ReviewDecision['action'] | ''
  evidence: { field: string; quote: string }[]
  proposed: string
}
const decisions = ref<Row[]>([])
const selected = computed(() => jobs.value.find((j) => j.id === selectedId.value))
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
const labels = {
  queued: 'В очереди',
  running: 'Модель обрабатывает',
  succeeded: 'Готово к проверке',
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
    options.value = await admin.reviewOptions()
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
function choose(job: ClassificationJob) {
  if (!job.result || job.versionId !== props.product.versionId) return
  selectedId.value = job.id
  decisions.value = []
  const c = job.result.classification
  for (const group of ['work_types', 'surface_locations', 'substrates'] as const) {
    for (const l of c[group])
      decisions.value.push({
        group,
        label: l.label,
        proposed: l.label,
        action: '',
        value: l.label,
        reason: '',
        evidence: l.evidence,
      })
  }
  for (const [label, j] of Object.entries(c.properties))
    decisions.value.push({
      group: 'properties',
      label,
      proposed: j.state,
      action: '',
      value: j.state,
      reason: '',
      evidence: j.evidence,
    })
}
async function review() {
  await run(async () => {
    if (!profileId.value || decisions.value.some((d) => !d.action || !d.reason.trim()))
      throw new Error('Выберите профиль и решение с основанием для каждого предложения.')
    await admin.review(
      props.product.id,
      props.etag,
      selectedId.value,
      decisions.value.map(({ group, label, action, value, reason }) => ({
        group,
        label,
        action,
        value,
        reason,
      })),
      profileId.value,
    )
    reviews.value = await admin.reviews(props.product.id)
    readiness.value = await admin.readiness(props.product.id)
    selectedId.value = ''
    notice.value = 'Решения администратора сохранены отдельно от ответа модели.'
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
    <h2>Классификация → проверка → публикация</h2>
    <p>
      Работаем с сохранённой версией {{ product.version }}. Нормы и цены подтверждаются в форме
      карточки.
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
      <article v-for="job in jobs" :key="job.id">
        <p>
          {{ labels[job.status] }} · попыток {{ job.attempts }} · {{ job.createdAt }}
          <strong v-if="job.versionId !== product.versionId"
            >Устаревшая версия — использовать нельзя</strong
          >
        </p>
        <details>
          <summary>Сохранённый вход и ответ модели</summary>
          <pre>{{
            JSON.stringify({ input: job.input, result: job.result, error: job.errorCode }, null, 2)
          }}</pre>
        </details>
        <button
          v-if="job.status === 'succeeded' && job.versionId === product.versionId"
          :disabled="busy || product.status === 'published'"
          @click="choose(job)"
        >
          Проверить предложения
        </button>
      </article>
      <form v-if="selected?.result && options" @submit.prevent="review">
        <p>
          Область: {{ selected.result.classification.scope }}.
          {{ selected.result.classification.review_reasons.join('; ') }}
        </p>
        <p>
          Подтвердите, отклоните или исправьте каждую метку. Неподдерживаемые Java-метки сохраняются
          в проверке. Сопоставление {{ options.mappingVersion }} требует контекста поверхности и
          вида товара.
        </p>
        <fieldset v-for="d in decisions" :key="`${d.group}:${d.label}`">
          <legend>{{ d.group }} · {{ d.label }}: {{ d.proposed }}</legend>
          <blockquote v-for="(e, i) in d.evidence" :key="i">
            {{ e.field }}: «{{ e.quote }}»
          </blockquote>
          <p v-if="!d.evidence.length">Нет цитат: модель указала неизвестность.</p>
          <label
            >Решение
            <select v-model="d.action" required>
              <option value="" disabled>Выберите</option>
              <option value="accept">Подтвердить</option>
              <option value="reject">Отклонить</option>
              <option value="correct">Исправить</option>
            </select></label
          >
          <label v-if="d.action === 'correct'"
            >Значение
            <select v-model="d.value">
              <template v-if="d.group === 'properties'"
                ><option v-for="state in ['yes', 'no', 'unknown', 'conflict']" :key="state">
                  {{ state }}
                </option></template
              >
              <template v-else
                ><option v-for="entry in options[d.group]" :key="entry.id" :value="entry.id">
                  {{ entry.name }} ({{ entry.id }})
                </option></template
              >
            </select></label
          >
          <label>Основание решения <input v-model="d.reason" required maxlength="4000" /></label>
        </fieldset>
        <label
          >Проверенный профиль расчёта
          <select v-model="profileId" required>
            <option disabled value="">Выберите</option>
            <option v-for="profile in options.profiles" :key="profile.id" :value="profile.id">
              {{ profile.id }} — {{ profile.conditions }}
            </option>
          </select></label
        >
        <p>
          Указывайте источник нормы расхода. Публикация проверит единицы, фасовку, геометрию и
          подтверждённую норму товара.
        </p>
        <button :disabled="busy">Сохранить проверку</button>
      </form>
      <details v-if="reviews.length">
        <summary>Решения администратора ({{ reviews.length }})</summary>
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
