import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './api'

import Login from './views/Login.vue'
import Sale from './views/Sale.vue'
import Stock from './views/Stock.vue'
import Inbound from './views/Inbound.vue'
import Report from './views/Report.vue'
import Ledger from './views/Ledger.vue'

const routes = [
  { path: '/login', component: Login, meta: { public: true, title: '登录' } },
  { path: '/', redirect: '/sale' },
  { path: '/sale', component: Sale, meta: { title: '扫码开单', icon: '⌗' } },
  { path: '/stock', component: Stock, meta: { title: '库存矩阵', icon: '▦' } },
  { path: '/inbound', component: Inbound, meta: { title: '扫码入库', icon: '↧' } },
  { path: '/report', component: Report, meta: { title: '经营报表', icon: '◲' } },
  { path: '/ledger', component: Ledger, meta: { title: '出入库流水', icon: '≡' } },
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '衣恋女装'
  if (!to.meta.public && !session.token) return '/login'
  if (to.path === '/login' && session.token) return '/sale'
  return true
})

export default router
