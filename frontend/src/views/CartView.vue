<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import ProductSwatch from '../components/ProductSwatch.vue'
import { workspace, notify } from '../composables/useWorkspace'
import { downloadJson, money } from '../domain/format'
const confirmClear = ref(false)
function exportCart() {
  const estimate = workspace.cart
  if (!estimate) return
  downloadJson(
    {
      schemaVersion: '1.0',
      mode: estimate.mode,
      estimateId: estimate.id,
      currency: 'RUB',
      versions: estimate.versions,
      materials: estimate.materials,
      items: estimate.lines.map((line) => ({
        productId: line.product.id,
        roomId: line.roomId,
        name: line.product.name,
        quantity: line.quantity,
        saleUnit: line.product.saleUnit,
        unitPrice: line.product.price,
        total: line.total,
      })),
    },
    `repaircalc-cart-${estimate.id}.json`,
  )
}
function clear() {
  workspace.cart = null
  confirmClear.value = false
  notify('Локальная корзина очищена')
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">СПИСОК ПОКУПОК ГОТОВ</span>
      <h1>Состав корзины<span class="heading-dot">.</span></h1>
      <p>Материалы из последней подготовленной сметы. Работы в корзину не включаются.</p>
    </div>
  </div>
  <section v-if="!workspace.cart" class="panel empty-state spacious">
    <span class="empty-icon"><AppIcon name="cart" :size="32" /></span>
    <h2>Начнём с расчёта?</h2>
    <p>
      Подберите материалы, затем нажмите «Подготовить корзину».<br />Здесь появится список с целым
      числом упаковок.
    </p>
    <RouterLink class="button primary" to="/"
      >Перейти к расчёту <AppIcon name="arrow" :size="18"
    /></RouterLink>
  </section>
  <section v-else class="panel main-panel">
    <div class="alert info">
      <AppIcon name="info" :size="19" /><span
        >Это локальный список, а не заказ в Максидоме. Интеграция с магазином не подключена.
        Экспортируйте JSON для передачи состава.</span
      >
    </div>
    <div v-for="line in workspace.cart.lines" :key="line.id" class="cart-line">
      <ProductSwatch :product="line.product" />
      <div>
        <span class="line-category">{{ line.roomName }}</span>
        <h3>{{ line.product.name }}</h3>
        <p>{{ line.quantity }} {{ line.product.saleUnit }} × {{ money(line.product.price) }}</p>
      </div>
      <strong>{{ money(line.total) }}</strong>
    </div>
    <div class="cart-total">
      <span>Материалы к покупке</span><strong>{{ money(workspace.cart.materials) }}</strong>
    </div>
    <div class="panel-bottom">
      <button class="text-button" @click="confirmClear = true">
        <AppIcon name="trash" :size="17" /> Очистить</button
      ><button class="button primary" @click="exportCart">
        <AppIcon name="download" :size="18" /> Экспорт корзины в JSON
      </button>
    </div>
    <div v-if="confirmClear" class="delete-confirm" role="alert">
      <span>Очистить локальную корзину?</span
      ><button class="button danger" @click="clear">Очистить</button
      ><button class="button secondary" @click="confirmClear = false">Отмена</button>
    </div>
  </section>
</template>
