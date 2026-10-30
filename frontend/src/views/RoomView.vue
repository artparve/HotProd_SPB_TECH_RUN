<script setup lang="ts">
import { ref, watch } from 'vue'
import { newId } from '../domain/id'
import AppIcon from '../components/AppIcon.vue'
import RoomEditor from '../components/RoomEditor.vue'
import ProductSwatch from '../components/ProductSwatch.vue'
import RoomPlan from '../components/RoomPlan.vue'
import { newRoom, roomSchema } from '../domain/types'
import type { Recommendations } from '../domain/types'
import { service, dataMode } from '../services/api'
import { useAsync } from '../composables/useAsync'
import { workspace, notify } from '../composables/useWorkspace'
import { money } from '../domain/format'

const room = ref({
  ...newRoom(),
  name: 'Санузел',
  type: 'bathroom' as const,
  length: 3,
  width: 2,
  humidity: 'high' as const,
})
const result = ref<Recommendations | null>(null)
const showValidation = ref(false)
const { busy, error, run } = useAsync()
watch(
  room,
  () => {
    result.value = null
  },
  { deep: true },
)
async function recommend() {
  showValidation.value = true
  if (!roomSchema.safeParse(room.value).success) return
  const fingerprint = JSON.stringify(room.value)
  const response = await run(async () => {
    const result = await service.recommendations(roomSchema.parse(room.value))
    if (fingerprint !== JSON.stringify(room.value))
      throw new Error('Параметры изменились. Повторите подбор.')
    return result
  })
  if (response) result.value = response
}
function addToProject() {
  if (workspace.project.rooms.length >= 20) {
    notify('В проекте может быть не более 20 комнат')
    return
  }
  workspace.project.rooms.push({ ...roomSchema.parse(room.value), id: newId() })
  notify('Комната добавлена в общий расчёт')
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">МАТЕРИАЛЫ ПОД ВАШИ УСЛОВИЯ</span>
      <h1>У каждой комнаты — свой характер<span class="heading-dot">.</span></h1>
      <p>Влажность, нагрузка и назначение помогают исключить неподходящее.</p>
    </div>
  </div>
  <div class="workspace-grid">
    <section class="panel main-panel">
      <div class="panel-heading">
        <div class="section-title">
          <span class="section-icon"><AppIcon name="settings" /></span>
          <div>
            <h2>Параметры комнаты</h2>
            <p>Проверьте условия, даже если выбрали готовый тип</p>
          </div>
        </div>
      </div>
      <form @submit.prevent="recommend">
        <RoomEditor :room="room" :show-validation="showValidation" />
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <div class="panel-bottom">
          <span class="muted">{{
            dataMode === 'demo' ? 'Демо-правила, не ML' : 'Рекомендации из API'
          }}</span
          ><button class="button primary" :disabled="busy">
            {{ busy ? 'Подбираем…' : 'Найти материалы' }}<AppIcon name="arrow" :size="18" />
          </button>
        </div>
      </form>
    </section>
    <aside class="project-summary">
      <section class="panel summary-card">
        <h2>Подходит — значит проверено</h2>
        <RoomPlan :length="room.length" :width="room.width" compact />
        <ul class="check-list">
          <li><AppIcon name="check" :size="17" /> Условия влажности</li>
          <li><AppIcon name="check" :size="17" /> Нагрузка на поверхность</li>
          <li><AppIcon name="check" :size="17" /> Отопление помещения</li>
        </ul>
        <p class="field-hint">
          Неизвестный признак — не разрешение. В демо такие товары исключаются.
        </p>
      </section>
    </aside>
  </div>
  <section v-if="result" class="recommendations-section">
    <div class="section-heading">
      <div>
        <span class="eyebrow">РЕЗУЛЬТАТ ПОДБОРА</span>
        <h2>Материалы для вашей комнаты</h2>
      </div>
      <button class="button secondary" @click="addToProject">
        <AppIcon name="plus" :size="17" /> Добавить комнату в проект
      </button>
    </div>
    <div v-if="!result.products.length" class="panel empty-state">
      <AppIcon name="box" :size="36" />
      <h3>Нет подтверждённых вариантов</h3>
      <p>Попробуйте другой состав работ или уточните характеристики каталога.</p>
    </div>
    <div class="recommendation-grid">
      <article
        v-for="item in result.products"
        :key="item.product.id"
        class="panel recommendation-card"
      >
        <ProductSwatch :product="item.product" large />
        <div class="recommendation-content">
          <span class="line-category">{{ item.product.category }}</span>
          <h3>{{ item.product.name }}</h3>
          <p>{{ item.reason }}</p>
          <strong
            >{{ money(item.product.price) }} <small>/ {{ item.product.saleUnit }}</small></strong
          ><RouterLink
            class="text-button"
            :to="{ path: '/product', query: { id: item.product.id } }"
            >Рассчитать количество <AppIcon name="arrow" :size="16"
          /></RouterLink>
        </div>
      </article>
    </div>
    <div class="alert info">
      <AppIcon name="info" :size="18" />
      <div>
        <p v-for="warning in result.warnings" :key="warning">{{ warning }}</p>
      </div>
    </div>
  </section>
</template>
