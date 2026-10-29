<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminProductForm from '../components/AdminProductForm.vue'
import { admin, AdminApiError } from '../services/admin'
import type { AdminDraft, AdminProduct } from '../domain/admin'

const route = useRoute()
const router = useRouter()
const session = ref<Awaited<ReturnType<typeof admin.session>> | null>(null)
const busy = ref(false)
const initializing = ref(true)
const error = ref('')
const details = ref<{ field: string; code: string; message: string }[]>([])
const requestId = ref('')
const conflict = ref(false)
const notice = ref('')
const username = ref('')
const password = ref('')
const product = ref<AdminProduct | null>(null)
const draft = ref<AdminDraft | null>(null)
const etag = ref('')
const list = ref<Awaited<ReturnType<typeof admin.list>> | null>(null)
const history = ref<Awaited<ReturnType<typeof admin.history>> | null>(null)
const filters = ref({ q: '', status: '', category: '', sort: '-updated', page: 0, size: 20 })
const isNew = computed(() => route.path === '/admin/products/new')
const isCard = computed(() => isNew.value || typeof route.params.id === 'string')
const statusLabels: Record<string, string> = {
  draft: 'Черновик',
  review: 'На проверке',
  published: 'Опубликован',
  archived: 'Архив',
}

function clearError() {
  error.value = ''
  details.value = []
  requestId.value = ''
  conflict.value = false
}
function failure(value: unknown) {
  error.value = value instanceof Error ? value.message : 'Не удалось выполнить запрос.'
  if (value instanceof AdminApiError) {
    details.value = value.details
    requestId.value = value.requestId
    conflict.value = value.status === 412
    if (value.status === 401) {
      if (session.value) session.value.authenticated = false
      password.value = ''
    }
  }
}
async function run(action: () => Promise<void>) {
  busy.value = true
  clearError()
  try {
    await action()
  } catch (value) {
    failure(value)
  } finally {
    busy.value = false
  }
}
async function initialize() {
  initializing.value = true
  await run(async () => {
    session.value = await admin.session()
    if (session.value.authenticated) await loadRoute()
  })
  initializing.value = false
}
async function loadRoute() {
  const path = route.fullPath
  history.value = null
  if (isNew.value) {
    product.value = null
    etag.value = ''
    draft.value = { sourceNamespace: '', externalId: '', name: '' }
    return
  }
  if (typeof route.params.id === 'string') {
    if (product.value?.id !== route.params.id) {
      product.value = null
      draft.value = null
      etag.value = ''
    }
    const result = await admin.get(route.params.id)
    if (route.fullPath !== path) return
    product.value = result.data
    draft.value = result.data.snapshot
    etag.value = result.etag
  } else {
    product.value = null
    draft.value = null
    const result = await admin.list(filters.value)
    if (route.fullPath === path) list.value = result
  }
}
async function login() {
  await run(async () => {
    // Refresh an anonymous token after expiration; passwords stay only in this form.
    await admin.session()
    try {
      session.value = await admin.login(username.value, password.value)
    } finally {
      password.value = ''
    }
    if (!draft.value) await loadRoute()
  })
}
async function logout() {
  await run(async () => {
    await admin.logout()
    session.value = null
    draft.value = null
    product.value = null
    list.value = null
    history.value = null
    await initialize()
  })
}
async function save(input: AdminDraft) {
  notice.value = ''
  draft.value = input
  await run(async () => {
    const result = product.value
      ? await admin.update(product.value.id, input, etag.value)
      : await admin.create(input)
    product.value = result.data
    draft.value = result.data.snapshot
    etag.value = result.etag
    notice.value = `Сохранена версия ${result.data.version}.`
    history.value = null
    if (isNew.value) await router.replace(`/admin/products/${result.data.id}`)
  })
}
async function search(page = 0) {
  filters.value.page = page
  await run(async () => {
    list.value = await admin.list(filters.value)
  })
}
async function showHistory(page = 0) {
  if (product.value)
    await run(async () => {
      history.value = await admin.history(product.value!.id, page)
    })
}
async function reload() {
  await run(loadRoute)
  notice.value = 'Карточка перечитана с сервера.'
}
watch(
  () => route.fullPath,
  async () => {
    notice.value = ''
    if (session.value?.authenticated) await run(loadRoute)
  },
)
onMounted(initialize)
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-title">
    <div class="admin-heading">
      <div>
        <h1 id="admin-title">Каталог · администрирование</h1>
        <p>Рабочие карточки и история изменений.</p>
      </div>
      <button v-if="session?.authenticated" :disabled="busy" @click="logout">
        Выйти · {{ session.username }}
      </button>
    </div>
    <div v-if="error" class="admin-error" role="alert">
      <p>{{ error }}</p>
      <ul v-if="details.length">
        <li v-for="(detail, i) in details" :key="i">
          {{ detail.field }}: {{ detail.message }}
          <RouterLink
            v-if="detail.code === 'existing_product'"
            :to="`/admin/products/${encodeURIComponent(detail.message)}`"
            >Открыть существующую карточку</RouterLink
          >
        </li>
      </ul>
      <small v-if="requestId">Запрос: {{ requestId }}</small>
      <p v-if="conflict">
        Ваши поля остаются в форме. Перечитывание заменит их актуальной версией.
      </p>
      <button v-if="conflict" :disabled="busy" @click="reload">
        Перечитать и заменить поля формы
      </button>
      <button v-else-if="!session" :disabled="busy" @click="initialize">
        Повторить подключение
      </button>
      <button v-else-if="session.authenticated" :disabled="busy" @click="reload">
        Повторить запрос
      </button>
    </div>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="initializing" role="status">Проверяем сессию…</p>
    <form v-else-if="session && !session.authenticated" class="admin-login" @submit.prevent="login">
      <h2>Вход администратора</h2>
      <label
        >Имя администратора<input
          v-model="username"
          autocomplete="username"
          required
          :disabled="busy"
          maxlength="500"
      /></label>
      <label
        >Пароль<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="busy"
          maxlength="1024"
      /></label>
      <button class="admin-primary" type="submit" :disabled="busy">
        {{ busy ? 'Вход…' : 'Войти' }}
      </button>
    </form>
    <template v-else-if="session?.authenticated">
      <nav class="admin-actions" aria-label="Разделы каталога">
        <RouterLink to="/admin">Все карточки</RouterLink
        ><RouterLink to="/admin/products/new">Новая карточка</RouterLink>
      </nav>
      <p v-if="busy" role="status">Выполняем запрос…</p>
      <template v-if="!isCard">
        <form class="admin-filters" @submit.prevent="search()">
          <label>Название или внешний ID<input v-model="filters.q" maxlength="500" /></label>
          <label
            >Статус<select v-model="filters.status">
              <option value="">Все</option>
              <option v-for="(label, status) in statusLabels" :key="status" :value="status">
                {{ label }}
              </option>
            </select></label
          >
          <label>Категория<input v-model="filters.category" maxlength="4000" /></label>
          <label
            >Порядок<select v-model="filters.sort">
              <option value="-updated">Обновлены недавно</option>
              <option value="updated">Обновлены давно</option>
              <option value="-created">Созданы недавно</option>
              <option value="created">Созданы давно</option>
            </select></label
          >
          <button :disabled="busy">Найти</button>
        </form>
        <template v-if="list">
          <p>Найдено карточек: {{ list.total }}</p>
          <p v-if="!list.items.length">По заданным условиям карточек нет.</p>
          <div v-else class="admin-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Товар</th>
                  <th>Внешняя идентичность</th>
                  <th>Статус</th>
                  <th>Версия</th>
                  <th>Обновление</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in list.items" :key="item.id">
                  <td>
                    <RouterLink :to="`/admin/products/${item.id}`">{{
                      item.snapshot.name
                    }}</RouterLink>
                  </td>
                  <td>{{ item.sourceNamespace }} / {{ item.externalId }}</td>
                  <td>{{ statusLabels[item.status] }}</td>
                  <td>{{ item.version }}</td>
                  <td>
                    {{ new Date(item.updatedAt).toLocaleString('ru-RU') }} · {{ item.updatedBy }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="admin-actions">
            <button :disabled="busy || list.page === 0" @click="search(list.page - 1)">Назад</button
            ><span>Страница {{ list.page + 1 }}</span
            ><button
              :disabled="busy || (list.page + 1) * list.size >= list.total"
              @click="search(list.page + 1)"
            >
              Вперёд
            </button>
          </div>
        </template>
      </template>
      <template v-else-if="draft">
        <h2>{{ product ? `Карточка · версия ${product.version}` : 'Новый черновик' }}</h2>
        <p v-if="product">
          {{ statusLabels[product.status] }} · {{ product.id }}<br />Версия создана:
          {{ new Date(product.updatedAt).toLocaleString('ru-RU') }} · {{ product.updatedBy }}
        </p>
        <p>
          Сохранение создаёт рабочий черновик. Публикация в пользовательский каталог будет доступна
          на следующем этапе.
        </p>
        <AdminProductForm :initial="draft" :existing="!!product" :busy="busy" @save="save" />
        <section v-if="product" class="admin-history" aria-label="История карточки">
          <h2>История версий и аудита</h2>
          <button :disabled="busy" @click="showHistory()">Загрузить историю</button>
          <template v-if="history"
            ><details v-for="version in history.items" :key="version.id">
              <summary>
                Версия {{ version.version }} · {{ version.createdBy }} ·
                {{ new Date(version.createdAt).toLocaleString('ru-RU') }}
              </summary>
              <p>{{ version.action }} · запрос {{ version.requestId }}</p>
              <pre>{{ JSON.stringify(version.snapshot, null, 2) }}</pre>
            </details>
            <div class="admin-actions">
              <button :disabled="busy || history.page === 0" @click="showHistory(history.page - 1)">
                Более новые версии</button
              ><span>Страница {{ history.page + 1 }}</span
              ><button
                :disabled="busy || (history.page + 1) * history.size >= history.total"
                @click="showHistory(history.page + 1)"
              >
                Более старые версии
              </button>
            </div></template
          >
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.admin-page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 28px;
}
.admin-heading,
.admin-actions,
.admin-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 24px;
}
.admin-heading {
  justify-content: space-between;
}
.admin-error {
  padding: 16px;
  background: #fff0eb;
  border: 1px solid #b96347;
  border-radius: 8px;
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}
.admin-page :deep(label) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 12px 0;
  font-weight: 600;
}
.admin-page :deep(input),
.admin-page :deep(select),
.admin-page :deep(textarea) {
  width: 100%;
  min-height: 40px;
  padding: 9px;
  border: 1px solid #a8b5aa;
  border-radius: 5px;
  background: #fff;
  color: #27382f;
  font: inherit;
}
.admin-page :deep(button),
.admin-actions a {
  padding: 10px 15px;
  border: 1px solid #9ba99e;
  border-radius: 6px;
  background: #fff;
}
.admin-page :deep(.admin-primary) {
  background: #285c49;
  color: #fff;
  margin-top: 16px;
}
.admin-page :deep(.admin-grid) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 24px;
}
.admin-page :deep(.admin-value-field) {
  padding: 12px;
  border: 1px solid #d5ddd3;
  border-radius: 8px;
  background: #fdfefb;
}
.admin-page :deep(h2) {
  margin-top: 28px;
}
.admin-page :deep(.admin-row) {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin-bottom: 16px;
}
.admin-page :deep(.admin-row label) {
  flex: 1;
  min-width: 140px;
  margin-bottom: 0;
}
.admin-login {
  max-width: 420px;
}
.admin-table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
}
th,
td {
  padding: 12px;
  text-align: left;
  border-bottom: 1px solid #d5ddd3;
  overflow-wrap: anywhere;
}
td a {
  color: #285c49;
  text-decoration: underline;
}
th,
td:nth-child(3),
td:nth-child(4) {
  white-space: nowrap;
}
td:first-child {
  min-width: 200px;
}
td:last-child {
  min-width: 160px;
}
.admin-actions {
  margin-top: 16px;
}
.admin-history pre {
  overflow: auto;
  max-height: 450px;
  padding: 16px;
  background: #fff;
}
.admin-history details {
  margin: 16px 0;
}
.admin-filters label {
  flex: 1;
  min-width: 160px;
}
@media (max-width: 700px) {
  .admin-page {
    padding: 16px;
  }
  .admin-page :deep(.admin-grid) {
    grid-template-columns: 1fr;
  }
}
</style>
