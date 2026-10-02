// @vitest-environment happy-dom
//
// FE-04 回归防护：「生产模式」下后端不可达时，绝不允许把失败伪装成成功。
//
// 背景：demo store 在 fetch 抛异常（后端不可达/超时/CORS）时会走 catch 分支做演示兜底。
// 原实现只有 searchMedicines 检查了 DEMO_ENABLED 开关，而 buy/receive/doSignIn/cancelOrder
// 这些**写操作**的 catch 完全没有检查 —— 于是生产环境后端一挂，
// 用户会看到「下单成功（演示）」并离开页面，实际订单根本没创建。
//
// 这里通过 mock 把 DEMO_ENABLED 固定为 false，专门验证生产分支的行为。
// 注意：演示模式（DEMO_ENABLED=true）的行为由 demo-exchange.test.js 覆盖（测试环境默认开启演示）。
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/utils/demoMode', () => ({
  DEMO_ENABLED: false,
  DEMO_DISABLED_MESSAGE: '后端服务不可用，请稍后重试',
}))

import { useStore } from '@/store/demo'

function freshState() {
  const { state } = useStore()
  Object.assign(state, {
    token: 'tk_test',
    username: 'tester',
    userId: 1,
    isLogin: true,
    detailQty: 1,
    detailCoupon: '',
    page: 'detail',
    toastMsg: '',
    toastVisible: false,
    points: { total: 1000, used: 0 },
    orders: [],
    activities: [],
    myCoupons: [],
  })
}

beforeEach(() => {
  freshState()
  vi.restoreAllMocks()
})

describe('生产模式：后端不可达时不得伪装成功（FE-04）', () => {
  it('buy()：必须如实报错，不得提示「下单成功」，也不得跳转首页', async () => {
    const { state, buy } = useStore()
    global.fetch = vi.fn().mockRejectedValue(new Error('Network Error'))

    await buy(1)

    expect(state.toastVisible).toBe(true)
    expect(state.toastMsg).toContain('后端服务不可用')
    expect(state.toastMsg).not.toContain('下单成功')
    // 停留在详情页，用户能看到错误并重试；跳走会让用户误以为已下单
    expect(state.page).toBe('detail')
  })

  it('exchange()：必须如实报错，不得扣本地积分、不得跳转', async () => {
    const { state, exchange } = useStore()
    state.medicines = [{ id: 1, name: '药', pointsPrice: 900, stock: 10 }]
    state.currentMed = { id: 1, name: '药', pointsPrice: 900, stock: 10 }
    global.fetch = vi.fn().mockRejectedValue(new Error('Network Error'))

    await exchange(1)

    expect(state.toastMsg).toContain('后端服务不可用')
    expect(state.toastMsg).not.toContain('兑换成功')
    expect(state.points.used).toBe(0)   // 绝不能凭空扣积分
    expect(state.page).toBe('detail')
  })

  it('doSignIn()：必须如实报错，不得虚增积分、不得伪造签到流水', async () => {
    const { state, doSignIn } = useStore()
    global.fetch = vi.fn().mockRejectedValue(new Error('Network Error'))

    await doSignIn()

    expect(state.toastMsg).toContain('后端服务不可用')
    expect(state.toastMsg).not.toContain('签到成功')
    expect(state.points.total).toBe(1000)  // 积分不变
    expect(state.records.length).toBe(0)   // 不产生假流水
  })
})
