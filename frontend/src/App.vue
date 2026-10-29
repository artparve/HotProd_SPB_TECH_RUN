<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from './components/AppIcon.vue'
import { dataMode } from './services/api'
import { workspace } from './composables/useWorkspace'

const route = useRoute()
const menuOpen = ref(false)
const helpOpen = ref(false)
const helpDialog = ref<HTMLDialogElement | null>(null)
watch(helpOpen, async (open) => {
  await nextTick()
  if (open && !helpDialog.value?.open) helpDialog.value?.showModal()
  else if (!open) helpDialog.value?.close()
})
watch(
  () => route.path,
  () => {
    menuOpen.value = false
  },
)
const links = [
  { to: '/', icon: 'calculator', title: 'Общий расчёт' },
  { to: '/room', icon: 'settings', title: 'Подбор для комнаты' },
  { to: '/product', icon: 'box', title: 'Расчёт товара' },
  { to: '/saved', icon: 'bookmark', title: 'Мои сметы' },
] as const
</script>

<template>
  <a class="skip-link" href="#main">Перейти к содержимому</a>
  <div class="app-layout">
    <button
      v-if="menuOpen"
      class="sidebar-backdrop"
      aria-label="Закрыть навигацию"
      @click="menuOpen = false"
    />
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <RouterLink class="brand" to="/" aria-label="RepairCalc — главная">
        <span class="brand-mark"><AppIcon name="home" :size="25" /></span>
        <span
          >Repair<span class="brand-light">Calc</span
          ><small>РЕМОНТ НАЧИНАЕТСЯ С РАСЧЁТА</small></span
        >
      </RouterLink>
      <div class="sidebar-section-label">ВАШ ПРОЕКТ</div>
      <nav aria-label="Основная навигация">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="nav-link"
          :class="{ active: route.path === link.to }"
        >
          <AppIcon :name="link.icon" /><span>{{ link.title }}</span>
          <span v-if="link.to === '/saved' && workspace.saved.length" class="nav-count">{{
            workspace.saved.length
          }}</span>
        </RouterLink>
      </nav>
      <RouterLink class="nav-link" to="/admin" :class="{ active: route.path.startsWith('/admin') }"
        ><AppIcon name="box" /><span>Администрирование</span></RouterLink
      >
      <div class="sidebar-tip">
        <span class="tip-icon"><AppIcon name="bulb" :size="22" /></span>
        <h3>Меньше лишнего.<br />Больше уверенности.</h3>
        <p>Рассчитайте материалы с запасом, а не на глаз.</p>
        <button class="text-button" @click="helpOpen = true">
          Как это работает <AppIcon name="arrow" :size="16" />
        </button>
      </div>
      <div class="sidebar-bottom">
        <span class="partner-label">ПРОЕКТ ДЛЯ КЕЙСА</span
        ><strong class="maxidom">МАКСИДОМ<span>всё для дома</span></strong
        ><small>SPB TECH RUN · 2026</small>
      </div>
    </aside>

    <div class="main-layout">
      <header class="topbar">
        <div class="topbar-left">
          <button
            class="icon-button mobile-menu"
            aria-label="Открыть навигацию"
            :aria-expanded="menuOpen"
            @click="menuOpen = !menuOpen"
          >
            <AppIcon name="menu" /></button
          ><span class="breadcrumb"
            >Мой проект <AppIcon name="chevron" :size="14" />
            <strong>{{ route.meta.title }}</strong></span
          >
        </div>
        <div class="topbar-actions">
          <span class="mode-pill"
            ><i />{{
              route.path.startsWith('/admin')
                ? 'Административный API'
                : dataMode === 'demo'
                  ? 'Демо-режим'
                  : 'REST API'
            }}</span
          ><button class="icon-button help-button" aria-label="Помощь" @click="helpOpen = true">
            <AppIcon name="help" /></button
          ><RouterLink class="cart-button" to="/cart"
            ><AppIcon name="cart" /><span>Корзина</span
            ><b>{{ workspace.cart?.lines.length || 0 }}</b></RouterLink
          >
        </div>
      </header>
      <main id="main" tabindex="-1">
        <div v-if="workspace.storageMessage" class="alert warning" role="status">
          {{ workspace.storageMessage }}
        </div>
        <RouterView />
      </main>
      <footer class="page-footer">
        <span>RepairCalc · Умный подход к ремонту</span
        ><span
          ><AppIcon name="shield" :size="15" />
          {{
            dataMode === 'demo'
              ? 'Демо работает без внешних сервисов'
              : 'Расчёты через подключённый API'
          }}</span
        >
      </footer>
    </div>
  </div>
  <div v-if="workspace.notice" class="toast" role="status">
    <AppIcon name="check" />{{ workspace.notice }}
  </div>
  <dialog
    ref="helpDialog"
    class="help-dialog"
    aria-labelledby="help-title"
    @click.self="helpOpen = false"
    @close="helpOpen = false"
  >
    <section class="modal">
      <button
        class="icon-button modal-close"
        aria-label="Закрыть помощь"
        autofocus
        @click="helpOpen = false"
      >
        <AppIcon name="close" />
      </button>
      <span class="eyebrow">ПОНЯТНО НА КАЖДОМ ШАГЕ</span>
      <h2 id="help-title">От размеров до списка покупок</h2>
      <ol class="help-list">
        <li>Укажите объект, помещения и работы.</li>
        <li>Проверьте материалы, условия и запас.</li>
        <li>Получите расчёт упаковок, остатка и стоимости.</li>
        <li>Сохраните смету или выгрузите состав корзины в JSON.</li>
      </ol>
      <p class="muted">
        Деморежим использует учебный каталог и правила, а не ML. Цены не актуальные. Сметы хранятся
        только в этом браузере. Интеграция с реальной корзиной пока не подключена.
      </p>
      <button class="button primary" @click="helpOpen = false">
        Всё понятно <AppIcon name="check" :size="17" />
      </button>
    </section>
  </dialog>
</template>
