<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, session, BizError } from '../api'
import { toast } from '../toast'

const router = useRouter()
const phone = ref('13800000000')
const password = ref('123456')
const loading = ref(false)

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    const data = await api.login(phone.value.trim(), password.value)
    session.save(data.token, data.user)
    toast.ok(`欢迎回来，${data.user.nickname || data.user.phone}`)
    router.push('/sale')
  } catch (e) {
    // 后端故意不区分"手机号不存在"和"密码错"，前端也不能自作聪明地提示是哪一种
    toast.err(e instanceof BizError ? e.message : '网络异常，请检查是否连上店里 WiFi')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <div class="panel card">
      <div class="mark">衣</div>
      <h2>衣恋女装 · 扫码出入库</h2>
      <p class="muted sub">柜台开单、库存矩阵、毛利报表，一个页面走完</p>

      <form @submit.prevent="submit">
        <label>手机号</label>
        <input v-model="phone" autocomplete="username" placeholder="登录手机号" />
        <label style="margin-top: 12px">密码</label>
        <input v-model="password" type="password" autocomplete="current-password" placeholder="密码" />

        <button class="btn btn-primary" style="width: 100%; margin-top: 18px" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <div class="hint muted">
        <div>店主 13800000000 / 123456（15 项权限，可见进价与利润）</div>
        <div>店员 13900000001 / 123456（9 项权限，看不到进价）</div>
      </div>
    </div>
    <div class="side-note">
      <h3>今天店里怎么样？</h3>
      <p>扫一下吊牌，库存当场减一；断网了也能继续扫，回店里一键补传。</p>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  height: 100%;
  display: grid;
  grid-template-columns: 420px 1fr;
  align-items: center;
  gap: 60px;
  padding: 0 8vw;
  background: radial-gradient(1200px 600px at 80% 10%, #fbf7ef 0%, var(--paper) 60%);
}
.panel {
  padding: 32px 30px;
}
.mark {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  font-family: "Songti SC", serif;
  font-size: 22px;
  margin-bottom: 16px;
}
h2 {
  font-size: 21px;
}
.sub {
  margin: 8px 0 22px;
  font-size: 13px;
}
label {
  display: block;
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 6px;
}
input {
  width: 100%;
}
.hint {
  margin-top: 18px;
  font-size: 12px;
  line-height: 1.9;
  border-top: 1px dashed var(--line);
  padding-top: 12px;
}
.side-note h3 {
  font-size: 30px;
  line-height: 1.35;
  max-width: 420px;
}
.side-note p {
  color: var(--muted);
  max-width: 380px;
  line-height: 1.9;
}
@media (max-width: 900px) {
  .wrap {
    grid-template-columns: 1fr;
  }
  .side-note {
    display: none;
  }
}
</style>
