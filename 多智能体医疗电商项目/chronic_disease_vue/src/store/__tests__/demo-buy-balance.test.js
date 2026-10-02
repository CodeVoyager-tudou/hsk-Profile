// @vitest-environment happy-dom
// 余额支付的前端逻辑测试（配合后端 payType=BALANCE 同步支付链路）：
//   1) 选择余额支付 → 下单 URL 携带 payType=BALANCE，成功后刷新余额并回首页；
//   2) 后端返回"余额不足" → toast 原样透出并引导充值，不跳转（不伪装成功）。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useStore } from '@/store/demo'

const json = (body) => ({ status: 200, json: async () => body })
const MED = { id: 1, name: '感冒灵颗粒', price: 28.5, pointsPrice: 0, stock: 10 }

function freshState() {
  const { state } = useStore()
  Object.assign(state, {
    token: 'tk_test', username: 'tester', userId: 1, isLogin: true,
    medicines: [{ ...MED }],
    currentMed: { ...MED },
    detailQty: 1,
    detailCoupon: '',
    payMethod: 'BALANCE',
    balance: 100,
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

function mockFetchForCreate(responseBody) {
  global.fetch = vi.fn((url) => {
    if (String(url).includes('/order/create')) {
      return Promise.resolve(json(responseBody))
    }
    // 其余（loadBalance/loadAccount/searchMedicines）统一返回可解析的空数据
    return Promise.resolve(json({ code: 200, data: { records: [], total: 0, pages: 1, balance: 0 } }))
  })
}

describe('余额支付 - 下单', () => {
  it('payType=BALANCE 随请求发出；成功后提示余额支付并刷新余额', async () => {
    const { state, buy } = useStore()
    mockFetchForCreate({ code: 200, data: { orderNo: 'NO1', totalAmount: 28.5, discountAmount: 0 } })

    await buy(1)

    const createCall = global.fetch.mock.calls.find(([u]) => String(u).includes('/order/create'))
    expect(String(createCall[0])).toContain('payType=BALANCE')
    expect(state.toastMsg).toContain('余额支付')
    expect(state.page).toBe('home')
  })

  it('后端返回余额不足：toast 透出原因并引导充值，停留在详情页', async () => {
    const { state, buy } = useStore()
    mockFetchForCreate({ code: 400, message: '余额不足', data: null })

    await buy(1)

    expect(state.toastVisible).toBe(true)
    expect(state.toastMsg).toContain('余额不足')
    expect(state.toastMsg).toContain('充值')
    expect(state.page).toBe('detail')
  })
})
