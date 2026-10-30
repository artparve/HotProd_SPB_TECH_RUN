<script setup lang="ts">
import { useRouter } from 'vue-router'
import RoomInspirationGallery from '../components/RoomInspirationGallery.vue'
import EstimateView from './EstimateView.vue'
import { workspace } from '../composables/useWorkspace'
import { stageInspiration } from '../composables/useInspirationImport'
import type { PreparedInspiration } from '../domain/inspirations'

const router = useRouter()
async function openCalculator(value: PreparedInspiration) {
  stageInspiration(value)
  await router.push('/calculator')
}
</script>

<template>
  <RoomInspirationGallery :project="workspace.project" @prepared="openCalculator" />
  <EstimateView />
</template>
