<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import ProductSwatch from './ProductSwatch.vue'
import type { Estimate } from '../domain/types'
import { downloadJson, money, number } from '../domain/format'
import { saveEstimate, setCart } from '../composables/useWorkspace'

defineProps<{ estimate: Estimate; readonly?: boolean }>()
</script>

<template>
  <div class="estimate-result">
    <div class="result-banner">
      <span class="result-check"><AppIcon name="check" :size="25" /></span>
      <div>
        <h2>Ваша смета готова</h2>
        <p>
          {{ estimate.lines.length }} позиций ·
          {{ new Date(estimate.createdAt).toLocaleDateString('ru-RU') }}
        </p>
      </div>
    </div>
    <div v-if="!estimate.lines.length" class="empty-state">
      <AppIcon name="box" :size="36" />
      <h3>Подходящие материалы не найдены</h3>
      <p>Уточните условия помещения или расширьте каталог.</p>
    </div>
    <div v-for="line in estimate.lines" :key="line.id" class="estimate-line">
      <div class="line-main">
        <ProductSwatch :product="line.product" />
        <div class="line-description">
          <span class="line-category">{{ line.roomName }} · {{ line.product.category }}</span>
          <h3>{{ line.product.name }}</h3>
          <span class="muted">{{ money(line.product.price) }} / {{ line.product.saleUnit }}</span>
        </div>
        <div class="line-price">
          <strong>{{ money(line.total) }}</strong
          ><span>{{ line.quantity }} {{ line.product.saleUnit }}</span>
        </div>
      </div>
      <details class="calculation-details">
        <summary>Как рассчитано <AppIcon name="down" :size="14" /></summary>
        <div>
          <p class="formula">{{ line.formula }}</p>
          <div class="calculation-numbers">
            <span
              >Потребность <b>{{ number(line.need) }} {{ line.product.unit }}</b></span
            ><span
              >К покупке <b>{{ number(line.purchased) }} {{ line.product.unit }}</b></span
            ><span
              >Остаток <b>{{ number(line.remainder) }} {{ line.product.unit }}</b></span
            >
          </div>
          <p>{{ line.reason }}</p>
          <small
            >Количество × цена: {{ line.quantity }} × {{ money(line.product.price) }} =
            {{ money(line.total) }}</small
          >
        </div>
      </details>
    </div>
    <div class="result-totals">
      <div>
        <span>Материалы</span><b>{{ money(estimate.materials) }}</b>
      </div>
      <div>
        <span>Ориентировочные работы</span><b>{{ money(estimate.labor) }}</b>
      </div>
      <div class="grand-total">
        <span>Итого</span><b>{{ money(estimate.total) }}</b>
      </div>
    </div>
    <div class="result-actions">
      <button
        v-if="!readonly"
        class="button primary"
        :disabled="!estimate.lines.length"
        @click="setCart(estimate)"
      >
        <AppIcon name="cart" :size="18" /> Подготовить корзину</button
      ><button class="button secondary" @click="saveEstimate(estimate)">
        <AppIcon name="bookmark" :size="17" /> Сохранить</button
      ><button
        class="button secondary"
        @click="downloadJson(estimate, `repaircalc-${estimate.id}.json`)"
      >
        <AppIcon name="download" :size="17" /> JSON
      </button>
    </div>
    <details class="estimate-notes">
      <summary>
        <AppIcon name="info" :size="17" /> Источники и ограничения
        <AppIcon name="down" :size="14" />
      </summary>
      <ul>
        <li v-for="warning in estimate.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <small
        >Каталог: {{ estimate.versions.catalog }} · Правила: {{ estimate.versions.rules }} · Модель:
        {{ estimate.versions.model }}</small
      >
    </details>
    <p class="field-hint">
      Корзина — локальный состав заказа для экспорта, не покупка. Цена и наличие требуют
      подтверждения.
    </p>
  </div>
</template>
