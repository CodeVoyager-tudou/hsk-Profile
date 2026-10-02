import { describe, it, expect, beforeEach } from 'vitest'
import tab from '../tab.js'

describe('plugins/tab.js', () => {
  beforeEach(() => {
    tab.editableTabs = []
    tab.editableTabsValue = ''
    tab.tabIndex = 0
  })

  describe('addTab', () => {
    it('should add a new tab', () => {
      const view = { path: '/home', name: 'Home', meta: { title: '首页' } }
      tab.addTab(view)
      expect(tab.editableTabs.length).toBe(1)
      expect(tab.editableTabs[0].path).toBe('/home')
      expect(tab.editableTabs[0].title).toBe('首页')
      expect(tab.editableTabsValue).toBe('/home')
    })

    it('should not add duplicate tab', () => {
      const view = { path: '/home', name: 'Home', meta: { title: '首页' } }
      tab.addTab(view)
      tab.addTab(view)
      expect(tab.editableTabs.length).toBe(1)
    })

    it('should default title to "no-name" if meta.title is missing', () => {
      const view = { path: '/test', name: 'Test', meta: {} }
      tab.addTab(view)
      expect(tab.editableTabs[0].title).toBe('no-name')
    })
  })

  describe('deleteTab', () => {
    it('should remove tab by name', () => {
      tab.addTab({ path: '/home', name: 'Home', meta: { title: '首页' } })
      tab.addTab({ path: '/about', name: 'About', meta: { title: '关于' } })
      tab.deleteTab({ path: '/home', name: 'Home' })
      expect(tab.editableTabs.length).toBe(1)
      expect(tab.editableTabs[0].name).toBe('About')
    })

    it('should switch to next tab when active tab is deleted', () => {
      tab.addTab({ path: '/a', name: 'A', meta: { title: 'A' } })
      tab.addTab({ path: '/b', name: 'B', meta: { title: 'B' } })
      tab.editableTabsValue = '/a'
      tab.deleteTab({ path: '/a', name: 'A' })
      expect(tab.editableTabsValue).toBe('/b')
    })
  })

  describe('updateTab', () => {
    it('should update tab properties', () => {
      tab.addTab({ path: '/home', name: 'Home', meta: { title: '旧标题' } })
      tab.updateTab({ path: '/home', meta: { title: '新标题' } })
      expect(tab.editableTabs[0].title).toBe('新标题')
    })
  })
})
