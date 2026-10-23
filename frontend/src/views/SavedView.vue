<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EstimateResult from '../components/EstimateResult.vue'
import { workspace, notify } from '../composables/useWorkspace'
import { money } from '../domain/format'
const openId = ref<string | null>(null)
const deleteId = ref<string | null>(null)
function remove(id: string) {
  workspace.saved = workspace.saved.filter((item) => item.id !== id)
  deleteId.value = null
  notify('Смета удалена из сохранённых')
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">ВСЁ ПОД РУКОЙ</span>
      <h1>Мои сметы<span class="heading-dot">.</span></h1>
      <p>Сохранённые расчёты в этом браузере. Последние 30 смет доступны после перезагрузки.</p>
    </div>
    <RouterLink class="button primary" to="/"
      ><AppIcon name="plus" :size="18" /> Новый расчёт</RouterLink
    >
  </div>
  <section v-if="!workspace.saved.length" class="panel empty-state spacious">
    <span class="empty-icon"><AppIcon name="bookmark" :size="32" /></span>
    <h2>Здесь будет ваш план ремонта</h2>
    <p>
      Рассчитайте материалы и нажмите «Сохранить».<br />Смета останется здесь, чтобы вернуться к ней
      позже.
    </p>
    <RouterLink class="button primary" to="/"
      >Создать первую смету <AppIcon name="arrow" :size="18"
    /></RouterLink>
  </section>
  <article v-for="estimate in workspace.saved" :key="estimate.id" class="panel saved-card">
    <div class="saved-card-heading">
      <span class="saved-file-icon"><AppIcon name="file" :size="23" /></span>
      <div>
        <h2>Смета от {{ new Date(estimate.createdAt).toLocaleDateString('ru-RU') }}</h2>
        <p>
          {{ estimate.lines.length }} позиций ·
          {{ estimate.mode === 'demo' ? 'Демонстрационный расчёт' : 'Расчёт API' }}
        </p>
      </div>
      <strong>{{ money(estimate.total) }}</strong
      ><button
        class="button secondary"
        :aria-expanded="openId === estimate.id"
        @click="openId = openId === estimate.id ? null : estimate.id"
      >
        {{ openId === estimate.id ? 'Свернуть' : 'Открыть' }}</button
      ><button class="icon-button" aria-label="Удалить смету" @click="deleteId = estimate.id">
        <AppIcon name="trash" :size="18" />
      </button>
    </div>
    <div v-if="deleteId === estimate.id" class="delete-confirm" role="alert">
      <span>Удалить эту смету? Это действие нельзя отменить.</span
      ><button class="button danger" @click="remove(estimate.id)">Удалить</button
      ><button class="button secondary" @click="deleteId = null">Отмена</button>
    </div>
    <EstimateResult v-if="openId === estimate.id" :estimate="estimate" />
  </article>
</template>
