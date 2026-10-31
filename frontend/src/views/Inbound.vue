<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { api } from '../api'
import { toast } from '../toast'

const props = defineProps({ warehouseId: { type: Number, default: 1 } })

const barcodeEl = ref(null)
const barcode = ref('')
const orderNo = ref('')
const lines = ref([])
const busy = ref(false)
const submitted = ref(null)

const totalQty = computed(() => lines.value.reduce((s, l) => s + l.qty, 0))

onMounted(() => barcodeEl.value?.focus())

async function scan() {
  const code = barcode.value.trim()
  if (!code || busy.value) return
  busy.value = true
  try {
    const hit = await api.resolve(code, props.warehouseId)
    if (!hit.bound) {
      toast.err('这个码没建档，无法入库；请先建款')
      return
    }
    if (!orderNo.value) {
      const o = await api.openInbound(props.warehouseId)
      orderNo.value = o.orderNo
      submitted.value = null
    }
    // 同单同码再扫：后端会返回 inserted=false 并累加数量（Q41 防重复计数）
    const r = await api.addInboundItem(orderNo.value, code, 1)
    const line = lines.value.find((l) => l.barcode === code)
    if (line) line.qty += 1
    else
      lines.value.push({
        barcode: code,
        spuNo: hit.sku.spuNo,
        spuName: hit.sku.spuName,
        size: hit.sku.size,
        color: hit.sku.color,
        qty: 1,
      })
    barcode.value = ''
    toast.ok(`${hit.sku.spuName} ${hit.sku.color}/${hit.sku.size} +1（单内合计 ${r.totalQty} 件）`)
  } catch (e) {
    toast.err(e.message || '入库扫码失败')
  } finally {
    busy.value = false
    await nextTick()
    barcodeEl.value?.focus()
  }
}

async function submit() {
  if (!orderNo.value) return
  busy.value = true
  try {
    const r = await api.submitInbound(orderNo.value)
    submitted.value = { orderNo: orderNo.value, flowCount: r.flowCount }
    toast.ok('入库已提交，库存和流水都已落账')
    orderNo.value = ''
    lines.value = []
  } catch (e) {
    toast.err(e.message || '提交失败')
  } finally {
    busy.value = false
    await nextTick()
    barcodeEl.value?.focus()
  }
}

function drop() {
  // 草稿单不提交就等于没发生：DRAFT 状态下库存一件没动
  orderNo.value = ''
  lines.value = []
  toast.info('已放弃本单草稿（库存未变动）')
}
</script>

<template>
  <div class="grid">
    <section class="card pad">
      <h2>扫吊牌入库</h2>
      <p class="muted hint">
        入库单是 <strong>DRAFT → 提交</strong> 两段式：扫码只改草稿，<strong>提交后</strong>才动库存。
        这样扫错一件可以直接放弃整单，不会留下一堆反向流水。
      </p>

      <div class="row" style="margin-top: 14px">
        <input
          ref="barcodeEl"
          v-model="barcode"
          placeholder="扫码 / 输入条码后回车"
          style="flex: 1; font-size: 15px"
          @keyup.enter="scan"
        />
        <button class="btn btn-primary" :disabled="busy" @click="scan">+1</button>
      </div>

      <div v-if="submitted" class="done">
        <strong>已提交 {{ submitted.orderNo }}</strong>
        <div class="muted">生成 {{ submitted.flowCount }} 条入库流水，库存已增加。</div>
      </div>
    </section>

    <section class="card">
      <div class="spread" style="padding: 14px 16px; border-bottom: 1px solid var(--line)">
        <div>
          <h3>草稿 {{ totalQty }} 件</h3>
          <div class="mono muted" style="font-size: 12px">{{ orderNo || '扫第一件时自动建单' }}</div>
        </div>
        <div class="row">
          <button class="btn" :disabled="!orderNo" @click="drop">放弃</button>
          <button class="btn btn-accent" :disabled="!orderNo || busy" @click="submit">
            {{ busy ? '提交中…' : '提交入库' }}
          </button>
        </div>
      </div>

      <div v-if="!lines.length" class="empty muted">还没有扫描记录。</div>

      <table v-else>
        <thead>
          <tr>
            <th>条码</th>
            <th>款号 / 名称</th>
            <th>颜色/尺码</th>
            <th style="text-align: right">件数</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in lines" :key="l.barcode">
            <td class="mono">{{ l.barcode }}</td>
            <td>
              <div>{{ l.spuName }}</div>
              <div class="mono muted" style="font-size: 11.5px">{{ l.spuNo }}</div>
            </td>
            <td>{{ l.color }} / {{ l.size }}</td>
            <td class="num" style="text-align: right">{{ l.qty }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 18px;
  align-items: start;
}
.pad {
  padding: 18px;
}
h2 {
  font-size: 17px;
}
.hint {
  font-size: 12.5px;
  line-height: 1.9;
  margin: 8px 0 0;
}
.done {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid #cadfd3;
  background: #e8f3ec;
  border-radius: 9px;
  font-size: 13px;
}
.empty {
  padding: 40px;
  text-align: center;
}
</style>
