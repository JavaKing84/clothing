<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { toast } from '../toast'

const props = defineProps({ warehouseId: { type: Number, default: 1 } })

const TYPES = [
  { v: '', label: '全部类型' },
  { v: 1, label: '入库' },
  { v: 2, label: '出库' },
  { v: 3, label: '撤销冲正' },
  { v: 4, label: '调拨出' },
  { v: 5, label: '调拨入' },
  { v: 6, label: '盘点调整' },
  { v: 7, label: '退货入库' },
  { v: 8, label: '价格调整' },
]

const flowType = ref('')
const spuNo = ref('')
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const busy = ref(false)

async function load() {
  busy.value = true
  try {
    const params = { warehouseId: props.warehouseId, page: page.value, size }
    if (flowType.value !== '') params.flowType = flowType.value
    if (spuNo.value.trim()) params.spuNo = spuNo.value.trim()
    const d = await api.ledger(params)
    list.value = d.list || []
    total.value = d.total || 0
  } catch (e) {
    toast.err(e.message || '流水加载失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch([flowType, () => props.warehouseId, page], load)

function search() {
  page.value = 1
  load()
}

/** 冲正流水金额为负、数量为 0 的是"价格调整"：这是对账最容易看错的一行，单独标色。 */
function rowClass(f) {
  if (f.flowType === 'PRICE_ADJUST') return 'adjust'
  if (f.flowType === 'REVERSE') return 'reverse'
  return ''
}
</script>

<template>
  <div>
    <div class="card toolbar">
      <select v-model="flowType" style="width: 140px">
        <option v-for="t in TYPES" :key="t.v" :value="t.v">{{ t.label }}</option>
      </select>
      <input v-model="spuNo" placeholder="按货号过滤，如 W26-1003" style="width: 220px" @keyup.enter="search" />
      <button class="btn" :disabled="busy" @click="search">{{ busy ? '查询中…' : '查询' }}</button>
      <span class="grow"></span>
      <span class="pill">共 {{ total }} 条</span>
      <div class="row">
        <button class="btn" :disabled="page <= 1" @click="page--">上一页</button>
        <span class="num muted">{{ page }}</span>
        <button class="btn" :disabled="page * size >= total" @click="page++">下一页</button>
      </div>
    </div>

    <div class="card table-wrap">
      <table>
        <thead>
          <tr>
            <th>流水号</th>
            <th>类型</th>
            <th>款号 / 颜色尺码</th>
            <th style="text-align: right">数量</th>
            <th style="text-align: right">结存</th>
            <th style="text-align: right">金额</th>
            <th>单据</th>
            <th>操作人</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in list" :key="f.flowNo" :class="rowClass(f)">
            <td class="mono">{{ f.flowNo }}</td>
            <td><span class="pill" :class="{ 'pill-accent': f.signedQty < 0 }">{{ f.flowTypeLabel }}</span></td>
            <td>
              <div>{{ f.spuNo }} <span class="muted">{{ f.color }}/{{ f.size }}</span></div>
              <div class="muted" style="font-size: 11.5px">{{ f.spuName }}</div>
            </td>
            <td class="num" style="text-align: right" :style="{ color: f.signedQty < 0 ? 'var(--accent)' : 'var(--ok)' }">
              {{ f.signedQty > 0 ? '+' : '' }}{{ f.signedQty }}
            </td>
            <td class="num muted" style="text-align: right">{{ f.beforeQty }} → {{ f.afterQty }}</td>
            <td class="num" style="text-align: right">{{ f.salePrice == null ? '—' : Number(f.salePrice).toFixed(2) }}</td>
            <td class="mono muted">{{ f.bizNo || '—' }}</td>
            <td>{{ f.operatorName }}</td>
            <td class="muted mono">{{ (f.createTime || '').slice(5, 16) }}</td>
          </tr>
          <tr v-if="!list.length">
            <td colspan="9" class="muted" style="text-align: center; padding: 40px">没有符合条件的流水</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="muted note">
      台账号「数量」是正数业务量，带符号的「±」才是库存增减；价格调整流水数量为 0、金额为负，
      它记录的是整单抹零/折扣——营业额 =（出库 + 退货 + 价格调整）金额之和，所以账永远平。
    </p>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.table-wrap {
  overflow: auto;
  max-height: calc(100vh - 250px);
}
tr.adjust td {
  background: #fdf6ec;
}
tr.reverse td {
  background: #f7efee;
}
.note {
  margin-top: 12px;
  font-size: 12.5px;
  line-height: 1.9;
  max-width: 860px;
}
</style>
