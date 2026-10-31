<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, session, BizError, OfflineError } from '../api'
import { offline } from '../offline'
import { toast } from '../toast'

const props = defineProps({ warehouseId: { type: Number, default: 1 } })

const barcodeEl = ref(null)
const barcode = ref('')
const orderNo = ref('')
const lines = ref([]) // {skuId,spuNo,spuName,size,color,qty,remainQty,tagPrice}
const busy = ref(false)
const lastHit = ref(null)
const offlineCount = ref(offline.size())

const showPay = ref(false)
const received = ref('')
const payType = ref('WECHAT')

const tagTotal = computed(() =>
  lines.value.reduce((s, l) => s + Number(l.tagPrice || 0) * l.qty, 0).toFixed(2)
)
const totalQty = computed(() => lines.value.reduce((s, l) => s + l.qty, 0))

onMounted(() => barcodeEl.value?.focus())

async function ensureOrder() {
  if (orderNo.value) return orderNo.value
  const o = await api.openOrder(props.warehouseId)
  orderNo.value = o.orderNo
  return orderNo.value
}

/** 扫码主流程：先查货（给店员看这是什么），再加购（真扣库存）。 */
async function scan() {
  const code = barcode.value.trim()
  if (!code || busy.value) return
  busy.value = true
  try {
    const hit = await api.resolve(code, props.warehouseId)
    if (!hit.bound) {
      // 未建档的码：引导手动输入货号（Q38），不要静默失败
      toast.err('这个码还没建档，请手动输入货号')
      lastHit.value = { plain: true, barcode: code }
      return
    }
    lastHit.value = hit
    const no = await ensureOrder()
    const r = await api.addItem(no, code, 1, crypto.randomUUID())
    mergeLine(r)
    barcode.value = ''
    if (r.duplicated) toast.info('这次扫码已处理过，没有重复扣库存')
    else toast.ok(`${r.spuName} ${r.color}/${r.size} 已加入 · 剩 ${r.remainQty}`)
  } catch (e) {
    handleScanError(e, code)
  } finally {
    busy.value = false
    await nextTick()
    barcodeEl.value?.focus()
  }
}

function mergeLine(r) {
  const hit = lines.value.find((l) => l.skuId === r.skuId)
  if (hit) {
    hit.qty += 1
    hit.remainQty = r.remainQty
  } else {
    lines.value.push({
      skuId: r.skuId,
      spuNo: r.spuNo,
      spuName: r.spuName,
      size: r.size,
      color: r.color,
      qty: 1,
      remainQty: r.remainQty,
      tagPrice: lastHit.value?.sku?.tagPrice ?? lastHit.value?.sku?.specialPrice ?? 0,
    })
  }
}

function handleScanError(e, code) {
  if (e instanceof OfflineError) {
    // 断网：记进离线队列，收银继续。这是 Q23 的核心——不能因为没网就不做生意
    offlineCount.value = offline.push({ barcode: code, quantity: 1, scanType: 2 })
    barcode.value = ''
    toast.info(`网络断了，已存本地（待补传 ${offlineCount.value} 笔）`)
    return
  }
  if (e instanceof BizError && e.code === 1001) {
    const d = e.data || {}
    toast.err(`库存不足：${d.spuNo || ''} ${d.color || ''}/${d.size || ''} 只剩 ${d.remainQty ?? 0} 件`)
    return
  }
  toast.err(e.message || '扫码失败')
}

async function flushOffline() {
  try {
    const r = await offline.flush(api.syncBatch, props.warehouseId)
    offlineCount.value = offline.size()
    const bad = (r.results || []).filter((x) => x.result === 'REJECTED' || x.result === 'CONFLICTED')
    toast.ok(`补传 ${r.sent} 笔，成功 ${r.accepted} 笔`)
    if (bad.length) toast.err(`有 ${bad.length} 笔被拒（多为条码/库存问题），请核对`)
  } catch (e) {
    toast.err('补传失败：' + (e.message || '网络仍未恢复'))
  }
}

function removeLine(l) {
  lines.value = lines.value.filter((x) => x !== l)
}

function openPay() {
  if (!lines.value.length) return
  received.value = tagTotal.value
  showPay.value = true
}

async function doCheckout() {
  const amount = Number(received.value)
  if (!Number.isFinite(amount) || amount < 0) {
    toast.err('实收金额不对')
    return
  }
  busy.value = true
  try {
    const paid = await api.checkout(orderNo.value, {
      receivedAmount: amount,
      payType: payType.value,
    })
    const change = (amount - Number(paid.receivedAmount)).toFixed(2)
    toast.ok(`收款 ${amount.toFixed(2)} 元${Number(change) > 0 ? ` · 找零 ${change}` : ''}`)
    showPay.value = false
    orderNo.value = ''
    lines.value = []
    lastHit.value = null
    barcodeEl.value?.focus()
  } catch (e) {
    toast.err(e.message || '结账失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="sale">
    <section class="scan card">
      <div class="scan-head">
        <div>
          <h2>扫吊牌</h2>
          <p class="muted">扫码枪回车即录入；也可以手输条码或货号</p>
        </div>
        <div class="row">
          <span v-if="offlineCount" class="pill pill-warn">离线 {{ offlineCount }}</span>
          <button v-if="offlineCount" class="btn" @click="flushOffline">补齐传</button>
        </div>
      </div>

      <div class="scan-box">
        <input
          ref="barcodeEl"
          v-model="barcode"
          placeholder="扫码 / 输入条码后回车"
          autocomplete="off"
          @keyup.enter="scan"
        />
        <button class="btn btn-primary" :disabled="busy" @click="scan">{{ busy ? '…' : '加购' }}</button>
      </div>

      <div v-if="lastHit" class="hit card">
        <template v-if="lastHit.plain">
          <div class="spread">
            <strong>未建档条码</strong>
            <span class="mono muted">{{ lastHit.barcode }}</span>
          </div>
          <p class="muted" style="margin: 8px 0 0">请到「库存矩阵」按货号确认款式，或让店主先建档。</p>
        </template>
        <template v-else>
          <div class="spread">
            <div>
              <strong>{{ lastHit.sku.spuName }}</strong>
              <div class="muted" style="margin-top: 4px">
                {{ lastHit.sku.spuNo }} · {{ lastHit.sku.color }}/{{ lastHit.sku.size }}
              </div>
            </div>
            <div style="text-align: right">
              <div class="price num">¥{{ lastHit.sku.tagPrice ?? lastHit.sku.specialPrice ?? '—' }}</div>
              <div class="muted" style="font-size: 12px">库存 {{ lastHit.stockQty }} 件</div>
            </div>
          </div>
        </template>
      </div>
    </section>

    <section class="cart card">
      <div class="spread" style="padding: 14px 16px; border-bottom: 1px solid var(--line)">
        <div>
          <h3>本单 {{ totalQty }} 件</h3>
          <div class="muted mono" style="font-size: 12px">{{ orderNo || '扫第一件时自动开单' }}</div>
        </div>
        <button class="btn btn-accent" :disabled="!lines.length || busy" @click="openPay">结账</button>
      </div>

      <div v-if="!lines.length" class="empty muted">
        还没有商品。扫一件吊牌试试——库存会当场减一（加购即扣，ADR-11）。
      </div>

      <table v-else>
        <thead>
          <tr>
            <th>款号</th>
            <th>颜色/尺码</th>
            <th style="text-align: right">数量</th>
            <th style="text-align: right">吊牌价</th>
            <th style="text-align: right">小计</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in lines" :key="l.skuId">
            <td>
              <div>{{ l.spuName }}</div>
              <div class="muted mono" style="font-size: 12px">{{ l.spuNo }}</div>
            </td>
            <td>{{ l.color }} / {{ l.size }}</td>
            <td class="num" style="text-align: right">{{ l.qty }}</td>
            <td class="num" style="text-align: right">{{ Number(l.tagPrice).toFixed(2) }}</td>
            <td class="num" style="text-align: right">{{ (Number(l.tagPrice) * l.qty).toFixed(2) }}</td>
            <td style="text-align: right">
              <button class="btn-ghost" @click="removeLine(l)">移除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="total">
        <span class="muted">吊牌合计</span>
        <span class="num big">¥{{ tagTotal }}</span>
      </div>
    </section>

    <div v-if="showPay" class="mask" @click.self="showPay = false">
      <div class="pay card">
        <h3>结账</h3>
        <p class="muted" style="margin: 6px 0 16px">吊牌合计 ¥{{ tagTotal }}，实收可手输（允许抹零）</p>

        <label class="muted" style="font-size: 12.5px">实收金额</label>
        <input v-model="received" type="number" step="0.01" style="width: 100%; font-size: 22px" />

        <label class="muted" style="font-size: 12.5px; display: block; margin-top: 14px">支付方式</label>
        <div class="row" style="margin-top: 6px">
          <button
            v-for="p in ['WECHAT', 'ALIPAY', 'CASH', 'CARD']"
            :key="p"
            class="btn"
            :class="{ 'btn-primary': payType === p }"
            @click="payType = p"
          >
            {{ { WECHAT: '微信', ALIPAY: '支付宝', CASH: '现金', CARD: '刷卡' }[p] }}
          </button>
        </div>

        <div class="spread" style="margin-top: 20px">
          <button class="btn" @click="showPay = false">再想想</button>
          <button class="btn btn-accent" :disabled="busy" @click="doCheckout">
            {{ busy ? '结账中…' : '确认收款' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sale {
  display: grid;
  grid-template-columns: minmax(320px, 420px) 1fr;
  gap: 18px;
  align-items: start;
}
.scan {
  padding: 16px;
}
.scan-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}
.scan-head h2 {
  font-size: 17px;
}
.scan-head p {
  margin: 6px 0 0;
  font-size: 12.5px;
}
.scan-box {
  display: flex;
  gap: 8px;
}
.scan-box input {
  flex: 1;
  font-size: 16px;
  letter-spacing: 0.02em;
}
.hit {
  margin-top: 12px;
  padding: 12px 14px;
}
.price {
  font-size: 17px;
  font-weight: 600;
}

.cart {
  overflow: hidden;
}
.cart h3 {
  font-size: 16px;
}
.empty {
  padding: 48px 20px;
  text-align: center;
  font-size: 13.5px;
  line-height: 1.9;
}
.total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-top: 1px solid var(--line);
  background: #fbf7ef;
}
.big {
  font-size: 22px;
  font-weight: 600;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(28, 26, 23, 0.45);
  display: grid;
  place-items: center;
  z-index: 40;
}
.pay {
  width: 380px;
  padding: 22px;
}
</style>
