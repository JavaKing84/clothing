import { reactive } from 'vue'

/** 极简全局提示。柜台场景下提示必须"看得见、自己消失"，不打断扫码节奏。 */
export const toasts = reactive([])

let seq = 0

function push(type, text, ttl = 3200) {
  const id = ++seq
  toasts.push({ id, type, text })
  setTimeout(() => {
    const i = toasts.findIndex((t) => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, ttl)
}

export const toast = {
  ok: (t) => push('ok', t),
  err: (t) => push('err', t, 5200),
  info: (t) => push('info', t),
}
