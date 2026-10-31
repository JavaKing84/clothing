<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, downloadExport, BizError, session } from '../api'
import { toast } from '../toast'

const props = defineProps({ warehouseId: { type: Number, default: 1 } })

const overview = ref(null)
const profit = ref(null)
const profitDenied = ref(false)
const ranking = ref([])
const dim = ref('qty')
const busy = ref(false)

const isOwner = computed(() => session.can('report:profit:read'))

async function load() {
  busy.value = true
  try {
    overview.value = await api.overview(props.warehouseId)
    ranking.value = await api.ranking(props.warehouseId, dim.value)
    if (isOwner.value) {
      try {
        profit.value = await api.profit(props.warehouseId)
        profitDenied.value = false
      } catch (e) {
        // 403 不是错误，是权限边界。界面要说明白，而不是弹一个红条吓人
        if (e instanceof BizError && e.code === 403) profitDenied.value = true
        else throw e
      }
    } else {
      profitDenied.value = true
    }
  } catch (e) {
    toast.err(e.message || '报表加载失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch([() => props.warehouseId, dim], load)

async function exportXlsx(kind) {
  try {
    await downloadExport(kind, props.warehouseId)
    toast.ok('已生成 Excel，注意浏览器下载栏')
  } catch (e) {
    toast.err(e.message || '导出失败')
  }
}

const money = (n) => Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
</script>

<template>
  <div>
    <div class="cards">
      <div class="card stat">
        <div class="label">今日营业额</div>
        <div class="value num">¥{{ money(overview?.todayAmount) }}</div>
        <div class="foot muted">{{ overview?.todayQty || 0 }} 件 · {{ overview?.todayOrders || 0 }} 单</div>
      </div>
      <div class="card stat">
        <div class="label">本周 / 本月</div>
        <div class="value num">¥{{ money(overview?.weekAmount) }}</div>
        <div class="foot muted">本月 ¥{{ money(overview?.monthAmount) }}</div>
      </div>
      <div class="card stat">
        <div class="label">毛利（仅店主可见）</div>
        <div class="value num" v-if="profit">
          ¥{{ money(profit.gross) }}
          <span class="pill pill-ok" style="vertical-align: middle">毛利率 {{ profit.margin }}%</span>
        </div>
        <div class="value" v-else style="font-size: 15px; color: var(--muted)">无权查看（403）</div>
        <div class="foot muted" v-if="profit">实收 ¥{{ money(profit.revenue) }} − 成本 ¥{{ money(profit.cost) }}</div>
      </div>
      <div class="card stat">
        <div class="label">待补货 / 待查挂账</div>
        <div class="value num">{{ overview?.alertCount || 0 }} 款</div>
        <div class="foot muted">挂账 {{ overview?.pendingLoss || 0 }} 笔待核</div>
      </div>
    </div>

    <div class="card table-card">
      <div class="spread head">
        <div>
          <h3>销量排行</h3>
          <p class="muted">口径：营业额按实收统计，退货与价格调整一并计入</p>
        </div>
        <div class="row">
          <button class="btn" :class="{ 'btn-primary': dim === 'qty' }" @click="dim = 'qty'">按件数</button>
          <button class="btn" :class="{ 'btn-primary': dim === 'amount' }" @click="dim = 'amount'">按金额</button>
          <button class="btn" @click="exportXlsx('sales')">导出 Excel</button>
        </div>
      </div>

      <table>
        <thead>
          <tr>
            <th style="width: 56px">#</th>
            <th>款号</th>
            <th>名称</th>
            <th style="text-align: right">件数</th>
            <th style="text-align: right">金额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in ranking" :key="r.spuNo">
            <td class="muted">{{ i + 1 }}</td>
            <td class="mono">{{ r.spuNo }}</td>
            <td>{{ r.spuName }}</td>
            <td class="num" style="text-align: right">{{ r.qty }}</td>
            <td class="num" style="text-align: right">{{ money(r.amount) }}</td>
          </tr>
          <tr v-if="!ranking.length">
            <td colspan="5" class="muted" style="text-align: center; padding: 36px">今天还没有动销</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="profitDenied" class="muted note">
      店员账号看不到毛利：后端在<strong>接口层</strong>直接返回 403，在<strong>字段层</strong>把进价留空，
      在<strong>查询层</strong>根本不 SELECT 成本列——三层都堵死，不存在"前端藏起来"这种假权限。
    </p>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.stat {
  padding: 16px 18px;
}
.label {
  font-size: 12.5px;
  color: var(--muted);
}
.value {
  font-size: 25px;
  font-weight: 600;
  margin: 8px 0 6px;
  font-family: "Songti SC", Georgia, serif;
}
.foot {
  font-size: 12px;
}
.table-card {
  overflow: hidden;
}
.head {
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}
.head h3 {
  font-size: 16px;
}
.head p {
  margin: 6px 0 0;
  font-size: 12.5px;
}
.note {
  margin-top: 14px;
  font-size: 12.5px;
  line-height: 1.9;
  max-width: 780px;
}
@media (max-width: 1100px) {
  .cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
