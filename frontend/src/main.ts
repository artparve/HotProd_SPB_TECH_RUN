import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import EstimateView from './views/EstimateView.vue'
import HomeView from './views/HomeView.vue'
import RoomView from './views/RoomView.vue'
import ProductView from './views/ProductView.vue'
import SavedView from './views/SavedView.vue'
import CartView from './views/CartView.vue'
import './styles/main.scss'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView, meta: { title: 'Общий расчёт' } },
    { path: '/calculator', component: EstimateView, meta: { title: 'Расчёт по интерьеру' } },
    { path: '/room', component: RoomView, meta: { title: 'Подбор для комнаты' } },
    { path: '/product', component: ProductView, meta: { title: 'Расчёт товара' } },
    { path: '/saved', component: SavedView, meta: { title: 'Мои сметы' } },
    { path: '/cart', component: CartView, meta: { title: 'Состав корзины' } },
    {
      path: '/admin',
      component: () => import('./views/AdminView.vue'),
      meta: { title: 'Административный каталог' },
    },
    {
      path: '/admin/products/new',
      component: () => import('./views/AdminView.vue'),
      meta: { title: 'Новая карточка' },
    },
    {
      path: '/admin/products/:id',
      component: () => import('./views/AdminView.vue'),
      meta: { title: 'Карточка каталога' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.afterEach((to) => {
  document.title = `${to.meta.title} · RepairCalc`
})
createApp(App).use(router).mount('#app')
