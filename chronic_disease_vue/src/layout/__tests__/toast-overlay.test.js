// @vitest-environment happy-dom
// 回归测试：全局 toast 浮层必须真正显示 demo store 的 toast 消息。
// 历史 bug：Layout 用顶层 store.toastVisible / store.toastMsg 渲染，
// 但 demo store 这两个字段在 state 上 → 恒为 undefined → 兑换失败/成功等提示都不显示。
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import Layout from '@/layout/index.vue'
import { useStore } from '@/store/demo'

const StubPage = { name: 'StubPage', render: () => null }

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Layout, children: [{ path: '', component: StubPage }] },
    ],
  })
}

afterEach(() => {
  const { state } = useStore()
  state.toastVisible = false
  state.toastMsg = ''
})

describe('Layout 全局 toast 浮层', () => {
  it('toast() 之后浮层显示对应消息（绑定 state.toastMsg 而非顶层 store.toastMsg）', async () => {
    const { state, toast } = useStore()
    const router = makeRouter()
    router.push('/')
    await router.isReady()
    const wrapper = mount(Layout, { global: { plugins: [router] } })

    // 初始不显示
    expect(wrapper.find('.toast-box').exists()).toBe(false)

    // 触发一次 toast（模拟积分不足的兑换失败）
    toast('兑换失败：积分余额不足')
    await nextTick()

    expect(state.toastVisible).toBe(true)
    expect(wrapper.find('.toast-box').exists()).toBe(true)
    expect(wrapper.find('.toast-box').text()).toContain('积分余额不足')
  })
})
