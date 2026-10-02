// @vitest-environment happy-dom
// 兑换"积分不足等类似情况是否提醒用户"的前端逻辑测试。
// 直接验证 demo store.exchange() 在四种场景下的 toast 提醒与本地状态：
//   1) 后端返回业务失败(积分不足) → 提醒，不误报成功、不扣本地积分；
//   2) 后端不可达 + 本地余额不足(演示模式) → 提醒，不再假装成功；
//   3) 后端不可达 + 余额充足(演示模式) → 演示成功并扣本地积分；
//   4) 后端兑换成功 → 成功提醒并回首页刷新余额。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useStore } from '@/store/demo'

const json = (body) => ({ status: 200, json: async () => body })
const MED = { id: 1, name: '阿司匹林肠溶片', pointsPrice: 900, stock: 10 }

function freshState() {
  const { state } = useStore()
  Object.assign(state, {
    token: 'tk_test', username: 'tester', userId: 1, isLogin: true,
    medicines: [{ ...MED }],
    currentMed: { ...MED },
    detailQty: 1,
    detailCoupon: '',
    points: { total: 0, used: 0 },
    page: 'detail',
    toastMsg: '',
    toastVisible: false,
    orders: [],
  })
}

beforeEach(() => {
  freshState()
  vi.restoreAllMocks()
})

describe('积分兑换 - 用户提醒', () => {
  it('真实后端返回 code=400/积分余额不足：toast 提醒"积分不足"，不误报成功、不扣分、不跳转', async () => {
    const { state, exchange } = useStore()
    global.fetch = vi.fn().mockResolvedValue(json({ code: 400, message: '积分余额不足', data: null }))

    await exchange(1)

    expect(state.toastVisible).toBe(true)
    expect(state.toastMsg).toContain('积分余额不足')   // 后端消息原样透出
    expect(state.toastMsg).toContain('兑换失败')
    expect(state.points.used).toBe(0)      // 失败不能扣本地积分
    expect(state.points.total).toBe(0)
    expect(state.page).toBe('detail')      // 未跳回首页（没有下单成功）
  })
  it('后端不可达 + 本地积分不足（演示模式）：提醒"积分不足"，不再假装成功', async () => {
    const { state, exchange } = useStore()
    state.points.total = 300              // 当前可用 300 < 需 900
    global.fetch = vi.fn().mockRejectedValue(new Error('Network Error'))

    await exchange(1)

    expect(state.toastVisible).toBe(true)
    expect(state.toastMsg).toContain('积分不足')
    expect(state.points.used).toBe(0)     // 不再把积分扣成负数
    expect(state.page).toBe('detail')
  })

  it('后端不可达 + 积分充足（演示模式）：演示兑换成功并扣本地积分', async () => {
    const { state, exchange } = useStore()
    state.points.total = 1280
    state.points.used = 0
    global.fetch = vi.fn().mockRejectedValue(new Error('Network Error'))

    await exchange(1)

    expect(state.toastMsg).toContain('兑换成功')
    expect(state.points.used).toBe(900)
    expect(state.page).toBe('home')
  })

  it('真实后端兑换成功(code=200)：toast 成功并回首页', async () => {
    const { state, exchange } = useStore()
    global.fetch = vi.fn((url) => {
      if (url.includes('/order/exchange')) {
        return Promise.resolve(json({ code: 200, message: 'success', data: { orderNo: 'E202609', pointsUsed: 900 } }))
      }
      if (url.includes('/points/')) {
        return Promise.resolve(json({ code: 200, data: { totalPoints: 900, usedPoints: 900 } }))
      }
      return Promise.resolve(json({ code: 200, data: { records: [] } }))
    })

    await exchange(1)

    expect(state.toastMsg).toContain('兑换成功')
    expect(state.page).toBe('home')
  })

  it('统一收银台模型：PENDING 单返回 pending 标记，交由页面跳收银台（不在此处跳首页）', async () => {
    const { state, exchange } = useStore()
    global.fetch = vi.fn().mockResolvedValue(json({
      code: 200,
      message: 'success',
      data: { id: 88, orderNo: 'E202610', status: 'PENDING', payType: 'POINTS', pointsUsed: 900 },
    }))

    const r = await exchange(1)

    expect(r).toMatchObject({ ok: true, pending: true, orderId: 88 })
    expect(state.toastMsg).toContain('订单已创建')
    expect(state.page).toBe('detail')      // 跳转由详情页负责（router.push 收银台）
    expect(state.points.used).toBe(0)      // 下单阶段不扣分
  })
})
