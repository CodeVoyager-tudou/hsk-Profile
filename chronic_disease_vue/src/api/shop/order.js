import request from '@/utils/request'

/**
 * 生成下单幂等键（J-02）。
 *
 * 为什么需要它：用户双击「立即购买」、或请求超时后前端自动重试时，
 * 会向后端发出两次完全相同的下单请求。若没有幂等键，
 * 后端会创建两张订单、扣两次库存。
 * 后端以 (user_id, request_id) 建唯一索引，同一个 key 只会成功一次。
 *
 * 注意：真正需要「重试也复用同一个 key」时，应由调用方自己生成并持有该值，
 * 然后作为第二个参数传入；不传时这里会生成一个一次性的 key。
 */
export function newRequestId() {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID()
    }
  } catch (e) {
    // 非安全上下文（http）下 crypto.randomUUID 可能不可用，走下面的兜底
  }
  return 'req-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

/**
 * 下单。
 * @param {object} query 查询参数（userId/medicineId/quantity/userCouponId）
 * @param {string} [requestId] 幂等键；同一笔「用户意图」的重试应复用同一个值
 */
export function createOrder(query, requestId) {
  return request({
    url: '/api/shop/order/create',
    method: 'post',
    params: query,
    // X-Request-Id 让后端能识别重复提交（J-02）。
    // 注意 request.js 的请求拦截器只读取 headers.isToken，追加其他头不受影响。
    headers: { 'X-Request-Id': requestId || newRequestId() }
  })
}

export function exchangeOrder(query) {
  return request({
    url: '/api/shop/order/exchange',
    method: 'post',
    params: query
  })
}

export function listOrder(query) {
  return request({
    url: '/api/shop/order/list/' + query.userId,
    method: 'get',
    params: query
  })
}

export function cancelOrder(id) {
  return request({
    url: '/api/shop/order/cancel/' + id,
    method: 'post'
  })
}
