<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session } from './api'
import { offline } from './offline'
import { toasts } from './toast'

const route = useRoute()
const router = useRouter()

const isLogin = computed(() => route.path === '/login')
const user = computed(() => session.user)
const warehouseId = ref(Number(localStorage.getItem('yl.warehouse') || 1))

const navs = [
  { path: '/sale', title: '扫码开单', icon: '⌗' },
  { path: '/stock', title: '库存矩阵', icon: '▦' },
  { path: '/inbound', title: '扫码入库', icon: '↧' },
  { path: '/report', title: '经营报表', icon: '◲' },
  { path: '/ledger', title: '出入库流水', icon: '≡' },
]

const pending = ref(offline.size())

// 路由切换时刷新离线队列角标：补传完回来立刻能看到清零
router.afterEach(() => {
  pending.value = offline.size()
})

function switchWarehouse(e) {
  warehouseId.value = Number(e.target.value)
  localStorage.setItem('yl.warehouse', String(warehouseId.value))
  // 门店切换会影响所有页面的查询，用整页刷新避免"某些页面还拿着旧仓号"的幽灵 bug
  location.reload()
}

function logout() {
  session.clear()
  router.push('/login')
}
</script>

<template>
  <div v-if="isLogin" class="login-wrap">
    <router-view />
  </div>

  <div v-else class="shell">
    <aside class="side">
      <div class="brand">
        <div class="brand-mark">衣</div>
        <div>
          <div class="brand-name">衣恋女装</div>
          <div class="brand-sub">扫码出入库 · v2</div>
        </div>
      </div>

      <nav>
        <router-link v-for="n in navs" :key="n.path" :to="n.path" class="nav" active-class="nav-on">
          <span class="nav-icon">{{ n.icon }}</span>
          <span>{{ n.title }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <div v-if="pending" class="pill pill-warn" style="margin-bottom: 8px">
          离线待补传 {{ pending }} 笔
        </div>
        <div class="who">{{ user?.nickname || user?.phone }}</div>
        <div class="muted" style="font-size: 12px">
          {{ user?.roleName || user?.role }} · 权限 {{ user?.permissions?.length || 0 }} 项
        </div>
        <button class="btn-ghost" style="margin-top: 8px; padding-left: 0" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="main">
      <header class="top">
        <h1>{{ route.meta.title }}</h1>
        <div class="row">
          <label class="muted" style="font-size: 12px">门店</label>
          <select :value="warehouseId" @change="switchWarehouse" style="padding: 6px 10px">
            <option :value="1">总店</option>
            <option :value="2">分店</option>
          </select>
        </div>
      </header>
      <router-view :warehouse-id="warehouseId" />
    </main>

    <div class="toasts">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="'toast-' + t.type">{{ t.text }}</div>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 216px 1fr;
  height: 100%;
}

.side {
  background: var(--ink);
  color: #efe7d8;
  display: flex;
  flex-direction: column;
  padding: 18px 14px;
  gap: 18px;
}

.brand {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 4px 6px 14px;
  border-bottom: 1px solid rgba(239, 231, 216, 0.12);
}
.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: var(--accent);
  display: grid;
  place-items: center;
  font-family: "Songti SC", serif;
  font-size: 18px;
}
.brand-name {
  font-family: "Songti SC", serif;
  font-size: 15px;
  letter-spacing: 0.06em;
}
.brand-sub {
  font-size: 11px;
  color: rgba(239, 231, 216, 0.55);
  margin-top: 2px;
}

nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  color: rgba(239, 231, 216, 0.78);
  text-decoration: none;
  font-size: 13.5px;
}
.nav:hover {
  background: rgba(239, 231, 216, 0.08);
  color: #fff;
}
.nav-on {
  background: rgba(239, 231, 216, 0.14);
  color: #fff;
}
.nav-icon {
  width: 16px;
  text-align: center;
  opacity: 0.8;
}

.side-foot {
  margin-top: auto;
  padding: 12px 6px 0;
  border-top: 1px solid rgba(239, 231, 216, 0.12);
}
.who {
  font-size: 13px;
}

.main {
  overflow: auto;
  padding: 22px 26px 40px;
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.top h1 {
  font-size: 20px;
}

.toasts {
  position: fixed;
  right: 20px;
  bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 50;
}
.toast {
  padding: 11px 16px;
  border-radius: 9px;
  background: var(--ink);
  color: #fbf7ef;
  box-shadow: 0 10px 30px -12px rgba(0, 0, 0, 0.5);
  max-width: 360px;
  font-size: 13px;
}
.toast-err {
  background: var(--accent);
}
.toast-ok {
  background: var(--ok);
}
</style>
