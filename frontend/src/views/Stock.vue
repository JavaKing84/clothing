<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { toast } from '../toast'

const props = defineProps({ warehouseId: { type: Number, default: 1 } })

const data = ref({ colors: [], rows: [], colorTotals: {}, grandTotal: 0 })
const keyword = ref('')
const onlyStocked = ref(true)
const loading = ref(false)
const elapsed = ref(0)

async function load() {
  loading.value = true
  const t0 = performance.now()
  try {
    data.value = await api.matrix(props.warehouseId)
    elapsed.value = Math.round(performance.now() - t0)
  } catch (e) {
    toast.err(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.warehouseId, load)

const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return data.value.rows.filter((r) => {
    if (onlyStocked.value && r.rowTotal === 0) return false
    if (!kw) return true
    return (r.spuNo + ' ' + r.spuName + ' ' + r.category).toLowerCase().includes(kw)
  })
})

/** 低库存判定：≤1 件视为要补，这是女装单店的经验阈值（断码比断货更常见）。 */
function cellClass(n) {
  if (!n) return 'cell-empty'
  if (n <= 1) return 'cell-low'
  if (n <= 3) return 'cell-mid'
  return ''
}
</script>

<template>
  <div>
    <div class="card toolbar">
      <input v-model="keyword" placeholder="按货号 / 品名 / 品类筛选，例如 W26-1003" style="flex: 1" />
      <label class="row muted" style="font-size: 12.5px">
        <input type="checkbox" v-model="onlyStocked" style="width: auto" />
        只看有货
      </label>
      <span class="pill">共 {{ data.grandTotal }} 件</span>
      <span class="pill" :class="elapsed < 3000 ? 'pill-ok' : 'pill-warn'">耗时 {{ elapsed }}ms</span>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <div class="card matrix-wrap">
      <table class="matrix">
        <thead>
          <tr>
            <th class="sticky-1">货号</th>
            <th class="sticky-2">尺码</th>
            <th v-for="c in data.colors" :key="c" class="col">{{ c }}</th>
            <th style="text-align: right">行合计</th>
          </tr>
          <tr class="totals">
            <td class="sticky-1 muted">颜色合计</td>
            <td class="sticky-2"></td>
            <td v-for="c in data.colors" :key="c" class="num">{{ data.colorTotals[c] }}</td>
            <td class="num" style="text-align: right">{{ data.grandTotal }}</td>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.spuNo + r.size">
            <td class="sticky-1">
              <div>{{ r.spuName }}</div>
              <div class="mono muted" style="font-size: 11.5px">{{ r.spuNo }}</div>
            </td>
            <td class="sticky-2"><strong>{{ r.size }}</strong></td>
            <td v-for="c in data.colors" :key="c" class="num" :class="cellClass(r.cells[c])">
              {{ r.cells[c] || '·' }}
            </td>
            <td class="num" style="text-align: right; font-weight: 600">{{ r.rowTotal }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="data.colors.length + 3" class="muted" style="padding: 40px; text-align: center">
              没有匹配的款。试试清空筛选，或到「扫码入库」先把货入进来。
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="legend muted">
      <span><i class="dot d-low"></i> ≤1 件（要补）</span>
      <span><i class="dot d-mid"></i> ≤3 件</span>
      <span>「·」表示该色已无货——矩阵只传非零格，避免误读成"有货但 0"</span>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.matrix-wrap {
  overflow: auto;
  max-height: calc(100vh - 220px);
}
.matrix {
  border-collapse: separate;
  border-spacing: 0;
  min-width: 720px;
}
.matrix th,
.matrix td {
  border-bottom: 1px solid var(--line);
  padding: 9px 12px;
  text-align: center;
}
.matrix th:first-child,
.matrix td:first-child,
.matrix th:nth-child(2),
.matrix td:nth-child(2) {
  text-align: left;
}
.col {
  min-width: 62px;
}
.sticky-1 {
  position: sticky;
  left: 0;
  background: var(--card);
  z-index: 2;
  min-width: 150px;
}
.sticky-2 {
  position: sticky;
  left: 150px;
  background: var(--card);
  z-index: 2;
  min-width: 64px;
}
thead .sticky-1,
thead .sticky-2 {
  background: #fbf7ef;
  z-index: 3;
}
.totals td {
  background: #fbf7ef;
  font-size: 12.5px;
  color: var(--ink-2);
}
.cell-empty {
  color: #cbc2b2;
}
.cell-low {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.cell-mid {
  background: #fbf0d8;
  color: var(--warn);
}
.legend {
  display: flex;
  gap: 18px;
  align-items: center;
  margin-top: 10px;
  font-size: 12px;
  flex-wrap: wrap;
}
.dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-right: 5px;
}
.d-low {
  background: var(--accent-soft);
  border: 1px solid #eccfc8;
}
.d-mid {
  background: #fbf0d8;
  border: 1px solid #ecdcb6;
}
</style>
