import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import useAppStore from '../app.js'

vi.mock('js-cookie', () => ({
  default: {
    get: vi.fn((key) => {
      if (key === 'sidebarStatus') return '1'
      if (key === 'size') return 'default'
      return null
    }),
    set: vi.fn(),
    remove: vi.fn()
  }
}))

describe('store/modules/app.js', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should have default state', () => {
    const store = useAppStore()
    expect(store.sidebar.opened).toBe(true)
    expect(store.device).toBe('desktop')
    expect(store.size).toBe('default')
  })

  it('should toggle sidebar', () => {
    const store = useAppStore()
    expect(store.sidebar.opened).toBe(true)
    store.toggleSideBar()
    expect(store.sidebar.opened).toBe(false)
    store.toggleSideBar()
    expect(store.sidebar.opened).toBe(true)
  })

  it('should close sidebar', () => {
    const store = useAppStore()
    store.closeSideBar({ withoutAnimation: false })
    expect(store.sidebar.opened).toBe(false)
    expect(store.sidebar.withoutAnimation).toBe(false)
  })

  it('should toggle device', () => {
    const store = useAppStore()
    store.toggleDevice('mobile')
    expect(store.device).toBe('mobile')
    store.toggleDevice('desktop')
    expect(store.device).toBe('desktop')
  })

  it('should set size', () => {
    const store = useAppStore()
    store.setSize('small')
    expect(store.size).toBe('small')
  })

  it('should toggle sidebar hide', () => {
    const store = useAppStore()
    store.toggleSideBarHide(true)
    expect(store.sidebar.hide).toBe(true)
    store.toggleSideBarHide(false)
    expect(store.sidebar.hide).toBe(false)
  })
})
