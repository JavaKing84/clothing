/**
 * 离线队列（Q23 的客户端一半）。
 *
 * 关键设计是 `deviceSeq`：本机单调递增的序号，**不用手机时钟**。
 * 手机时间会被用户改、会被时区切换，用它排序必然乱序；
 * 而服务端要靠"序号连续"判断有没有漏传，序号必须由客户端自己保证单调。
 *
 * 队列里存的是"扫码事实"（条码 + 数量 + 类型），不是"接口请求"。
 * 这样即使后端接口改名，离线数据也不会失效——数据是资产，接口是通道。
 */

const KEY = 'yl.offline.queue'
const SEQ_KEY = 'yl.offline.seq'
const DEVICE_KEY = 'yl.device.id'

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '[]')
  } catch {
    return []
  }
}

function write(list) {
  localStorage.setItem(KEY, JSON.stringify(list))
}

export const offline = {
  /** 设备号：换手机之前一直不变，服务端用它做去重与追溯 */
  deviceId() {
    let d = localStorage.getItem(DEVICE_KEY)
    if (!d) {
      d = 'WEB-' + Math.random().toString(36).slice(2, 10).toUpperCase()
      localStorage.setItem(DEVICE_KEY, d)
    }
    return d
  },

  size() {
    return read().length
  },

  list() {
    return read()
  },

  /** 入队。返回当前队列长度，供界面显示"离线 3 笔"。 */
  push(item) {
    const seq = Number(localStorage.getItem(SEQ_KEY) || '0') + 1
    localStorage.setItem(SEQ_KEY, String(seq))
    const q = read()
    q.push({
      clientToken: 'c-' + Math.random().toString(36).slice(2, 12),
      deviceSeq: seq,
      barcode: item.barcode,
      quantity: item.quantity,
      scanType: item.scanType ?? 1, // 1 入库 / 2 出库，与后端约定一致
      at: new Date().toISOString(),
    })
    write(q)
    return q.length
  },

  clear() {
    write([])
  },

  /**
   * 补传：整批提交，服务端**逐条裁决**。
   * 这里必须把每条的结果回显给店员看——"3 条成功、1 条条码错"比
   * "同步失败"有用得多，因为店员能据此去翻那件衣服的吊牌。
   */
  async flush(syncBatch, warehouseId) {
    const items = read()
    if (!items.length) return { sent: 0, accepted: 0, results: [] }

    const batchNo = 'B-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 6)
    const data = await syncBatch({ batchNo, deviceId: this.deviceId(), warehouseId, items })
    const results = data.items || []
    const accepted = results.filter((r) => r.result === 'ACCEPTED' || r.result === 'DUPLICATED').length

    // 只清掉被服务端明确裁决过的条目；其余留着下次再传，绝不"发过就当成功"
    const settled = new Set(results.map((r) => r.clientToken))
    write(items.filter((i) => !settled.has(i.clientToken)))
    return { sent: items.length, accepted, results }
  },
}
