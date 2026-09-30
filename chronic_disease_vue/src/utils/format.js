// 文本/时间格式化与状态映射
export function formatTime(t) {
  return (t || '').replace('T', ' ').slice(0, 19)
}

export function formatDate(t) {
  return (t || '').replace('T', ' ').slice(0, 10)
}

export function statusText(s) {
  return { PAID: '已支付', CANCELLED: '已取消', PENDING: '待支付' }[s] || s
}

export function payText(p) {
  return { CASH: '现金支付', POINTS: '积分兑换', BALANCE: '余额支付' }[p] || p
}

/** 资金流水类型文案（余额账户） */
export function accountTypeText(t) {
  return { RECHARGE: '充值', BALANCE_PAY: '余额支付', BALANCE_REFUND: '退款' }[t] || t
}

export function couponStatusText(s) {
  return { UNUSED: '未使用', USED: '已使用', EXPIRED: '已过期' }[s] || s
}

export function isToday(date) {
  const d = new Date()
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return date === today
}
