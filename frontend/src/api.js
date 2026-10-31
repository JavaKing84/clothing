/**
 * 与后端交互的唯一出口。
 *
 * 三条约定，全站都靠它兜住：
 * 1) 后端统一返回 {code,msg,data,traceId}，code=0 才是成功——HTTP 200 不代表业务成功；
 * 2) 401 立刻清 token 回登录页，不要让用户在"点啥都没反应"的页面上耗着；
 * 3) 网络层失败（断网/超时）抛 OfflineError，收银台据此进离线队列— 这跟"业务报错"
 *    是两件完全不同的事，绝不能混成一个 toast。
 */

const TOKEN_KEY = 'yl.token'
const USER_KEY = 'yl.user'

export class BizError extends Error {
  constructor(code, msg, data, traceId) {
    super(msg)
    this.code = code
    this.data = data
    this.traceId = traceId
  }
}

export class OfflineError extends Error {
  constructor(cause) {
    super('网络不可用')
    this.cause = cause
  }
}

export const session = {
  get token() {
    return localStorage.getItem(TOKEN_KEY) || ''
  },
  get user() {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    } catch {
      return null
    }
  },
  save(token, user) {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
  can(perm) {
    const perms = this.user?.permissions || []
    return perms.includes(perm)
  },
}

async function request(method, path, body, options = {}) {
  let resp
  try {
    resp = await fetch(path, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(session.token ? { Authorization: 'Bearer ' + session.token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: options.timeout
        ? AbortSignal.timeout(options.timeout)
        : undefined,
    })
  } catch (e) {
    throw new OfflineError(e)
  }

  if (resp.status === 401) {
    session.clear()
    if (location.hash !== '#/login') location.hash = '#/login'
    throw new BizError(401, '登录已过期，请重新登录', null, null)
  }

  let payload
  try {
    payload = await resp.json()
  } catch (e) {
    throw new BizError(resp.status, `服务异常（HTTP ${resp.status}）`, null, null)
  }
  if (payload.code !== 0) {
    throw new BizError(payload.code, payload.msg, payload.data, payload.traceId)
  }
  return payload.data
}

export const api = {
  get: (p, o) => request('GET', p, null, o),
  post: (p, b, o) => request('POST', p, b, o),

  // ---- 认证与元数据 ----
  login: (phone, password) => request('POST', '/api/v1/auth/login', { phone, password }),
  me: () => request('GET', '/api/v1/auth/me'),
  warehouses: () => request('GET', '/api/v1/warehouses'),

  // ---- 收银 ----
  matrix: (warehouseId) => request('GET', `/api/v1/stock/matrix?warehouseId=${warehouseId}`),
  // 扫码第一跳：条码 → SKU + 当前库存。三态返回（已绑定 / 未绑定 / 纯条码），
  // 前端据此决定"直接加购"还是"切手动输入货号"（Q36/Q38）。
  resolve: (barcode, warehouseId) =>
    request('GET', `/api/v1/catalog/barcode/${encodeURIComponent(barcode)}?warehouseId=${warehouseId}`),
  openOrder: (warehouseId) => request('POST', '/api/v1/sales/orders', { warehouseId }),
  addItem: (orderNo, barcode, qty, scanToken) =>
    request('POST', `/api/v1/sales/orders/${orderNo}/items`, { barcode, qty, scanToken }),
  checkout: (orderNo, body) => request('POST', `/api/v1/sales/orders/${orderNo}/checkout`, body),

  // ---- 入库 ----
  openInbound: (warehouseId) => request('POST', '/api/v1/inbound/orders', { warehouseId, supplierId: 1 }),
  addInboundItem: (orderNo, barcode, qty) =>
    request('POST', `/api/v1/inbound/orders/${orderNo}/items`, { barcode, qty }),
  submitInbound: (orderNo) => request('POST', `/api/v1/inbound/orders/${orderNo}/submit`),

  // ---- 报表与流水 ----
  overview: (warehouseId) => request('GET', `/api/v1/reports/overview?warehouseId=${warehouseId}`),
  profit: (warehouseId) => request('GET', `/api/v1/reports/profit?warehouseId=${warehouseId}`),
  ranking: (warehouseId, dim = 'qty') =>
    request('GET', `/api/v1/reports/ranking?warehouseId=${warehouseId}&dim=${dim}`),
  ledger: (params) => request('GET', '/api/v1/ledger?' + new URLSearchParams(params)),

  // ---- 离线补传 ----
  syncBatch: (batch) => request('POST', '/api/v1/sync/batch', batch),
}

/** 导出 xlsx：必须带 token，所以不能直接 window.open 一个链接。 */
export async function downloadExport(kind, warehouseId) {
  const resp = await fetch(`/api/v1/reports/export?kind=${kind}&warehouseId=${warehouseId}`, {
    headers: session.token ? { Authorization: 'Bearer ' + session.token } : {},
  })
  if (!resp.ok) throw new BizError(resp.status, '导出失败')
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${kind}-${new Date().toISOString().slice(0, 10)}.xlsx`
  a.click()
  URL.revokeObjectURL(url)
}
