import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import useSettingsStore from '../settings.js'

describe('store/modules/settings.js', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('should have default state', () => {
    const store = useSettingsStore()
    expect(store.theme).toBe('#00b8a0')
    expect(store.sideTheme).toBe('theme-light')
    expect(store.showSettings).toBe(false)
    expect(store.tagsView).toBe(true)
    expect(store.fixedHeader).toBe(false)
    expect(store.sidebarLogo).toBe(true)
    expect(store.dynamicTitle).toBe(false)
  })

  it('should change setting', () => {
    const store = useSettingsStore()
    store.changeSetting({ key: 'tagsView', value: false })
    expect(store.tagsView).toBe(false)
  })

  it('should not change non-existent key', () => {
    const store = useSettingsStore()
    store.changeSetting({ key: 'nonExistent', value: 'test' })
    expect(store.nonExistent).toBeUndefined()
  })

  it('should set title and document.title', () => {
    const store = useSettingsStore()
    store.setTitle('测试标题')
    expect(store.title).toBe('测试标题')
    expect(document.title).toBe('测试标题')
  })
})
