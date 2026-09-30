import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// 收银台的超时展示：展示窗口（默认 30 分钟）走完后，后端延迟消息还要 2 分钟才关单，
// 这期间订单仍是 PENDING —— 页面必须显式说"已超过支付时间"，不能停在「待支付 00:00」
// 让人以为坏了。这里用组件测试把这两种状态钉住。

const fetchOrder = vi.fn()
vi.mock('@/store/demo', () => ({
  useStore: () => ({
    state: {},
    fetchOrder,
    payPendingOrder: vi.fn(),
    cancelOrder: vi.fn(),
  }),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
  useRoute: () => ({ params: { orderId: '88' } }),
}))

import Cashier from '../cashier.vue'

function pendingOrder(minutesAgo) {
  return {
    id: 88,
    orderNo: '009d3afc84294d96aa8b384b23356c20',
    status: 'PENDING',
    payType: 'CASH',
    medicineName: '苯磺酸氨氯地平片',
    quantity: 1,
    totalAmount: 23.8,
    discountAmount: 0,
    createTime: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
  }
}

async function mountWith(order) {
  fetchOrder.mockResolvedValue(order)
  const wrapper = mount(Cashier)
  await flushPromises()
  return wrapper
}

describe('收银台 · 支付窗口状态', () => {
  beforeEach(() => fetchOrder.mockReset())

  it('窗口内：显示待支付倒计时，支付按钮可用', async () => {
    const wrapper = await mountWith(pendingOrder(2))
    const text = wrapper.text()
    expect(text).toContain('待支付')
    expect(text).not.toContain('已超过支付时间')
    const pay = wrapper.findAll('button').find((b) => b.text().includes('确认支付'))
    expect(pay).toBeTruthy()
    expect(pay.attributes('disabled')).toBeUndefined()
  })

  it('窗口已过但后端还没关单：显式提示已超时，支付按钮置灰', async () => {
    const wrapper = await mountWith(pendingOrder(31))
    const text = wrapper.text()
    expect(text).toContain('已超过支付时间')
    expect(text).not.toContain('待支付 00:00')
    const pay = wrapper.findAll('button').find((b) => b.text().includes('已超时'))
    expect(pay).toBeTruthy()
    expect(pay.attributes('disabled')).toBeDefined()
    // 超时后引导用户立即关单（走同一套取消链路，会退回库存/名额）
    expect(text).toContain('立即取消订单')
  })

  it('已关单：显示已取消，不再提供支付入口', async () => {
    const cancelled = { ...pendingOrder(40), status: 'CANCELLED' }
    const wrapper = await mountWith(cancelled)
    const text = wrapper.text()
    expect(text).toContain('已取消')
    expect(text).not.toContain('确认支付')
    expect(text).not.toContain('已超时，无法支付')
  })
})
