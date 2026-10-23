import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import EstimateView from './views/EstimateView.vue'
import RoomView from './views/RoomView.vue'
import ProductView from './views/ProductView.vue'
import SavedView from './views/SavedView.vue'
import CartView from './views/CartView.vue'
import './styles/main.scss'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: EstimateView, meta: { title: 'Общий расчёт' } },
    { path: '/room', component: RoomView, meta: { title: 'Подбор для комнаты' } },
    { path: '/product', component: ProductView, meta: { title: 'Расчёт товара' } },
    { path: '/saved', component: SavedView, meta: { title: 'Мои сметы' } },
    { path: '/cart', component: CartView, meta: { title: 'Состав корзины' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.afterEach((to) => {
  document.title = `${to.meta.title} · RepairCalc`
})
createApp(App).use(router).mount('#app')
