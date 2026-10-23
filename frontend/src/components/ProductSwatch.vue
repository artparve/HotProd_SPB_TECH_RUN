<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import type { Product } from '../domain/types'
defineProps<{ product: Product; large?: boolean }>()
</script>

<template>
  <div
    class="product-swatch"
    :class="[
      product.kind,
      { large, tile: product.id === 'demo-tile', vinyl: product.id === 'demo-vinyl' },
    ]"
    aria-hidden="true"
  >
    <div v-if="product.kind === 'covering'" class="material-boards"><i /><i /><i /></div>
    <div v-else-if="['paint', 'primer'].includes(product.kind)" class="paint-can">
      <div class="can-handle" />
      <div class="can-label">
        <span>REPAIR</span><AppIcon name="leaf" :size="large ? 28 : 15" /><b
          >{{ product.packageSize }} л</b
        >
      </div>
    </div>
    <div v-else-if="product.kind === 'wallpaper'" class="wallpaper-roll"><i /><i /></div>
    <AppIcon
      v-else
      :name="product.kind === 'socket' ? 'square' : product.kind === 'cable' ? 'cable' : 'layers'"
      :size="large ? 72 : 28"
    />
  </div>
</template>
