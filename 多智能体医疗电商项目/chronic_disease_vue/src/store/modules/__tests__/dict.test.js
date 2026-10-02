import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import useDictStore from '../dict.js'

describe('store/modules/dict.js', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should have empty dict array', () => {
    const store = useDictStore()
    expect(store.dict).toEqual([])
  })

  it('should set dict', () => {
    const store = useDictStore()
    store.setDict('gender', [{ label: '男', value: '0' }])
    expect(store.dict.length).toBe(1)
    expect(store.dict[0].key).toBe('gender')
  })

  it('should get dict by key', () => {
    const store = useDictStore()
    store.setDict('status', [{ label: '启用', value: '1' }])
    const result = store.getDict('status')
    expect(result).toEqual([{ label: '启用', value: '1' }])
  })

  it('should return undefined for non-existent key', () => {
    const store = useDictStore()
    expect(store.getDict('nonexistent')).toBeUndefined()
  })

  it('should return undefined for null/empty key', () => {
    const store = useDictStore()
    expect(store.getDict(null)).toBeUndefined()
    expect(store.getDict('')).toBeUndefined()
  })

  it('should remove dict by key', () => {
    const store = useDictStore()
    store.setDict('a', [1])
    store.setDict('b', [2])
    const result = store.removeDict('a')
    expect(result).toBe(true)
    expect(store.dict.length).toBe(1)
    expect(store.dict[0].key).toBe('b')
  })

  it('should return false when removing non-existent key', () => {
    const store = useDictStore()
    expect(store.removeDict('nonexistent')).toBe(false)
  })

  it('should clean all dicts', () => {
    const store = useDictStore()
    store.setDict('a', [1])
    store.setDict('b', [2])
    store.cleanDict()
    expect(store.dict).toEqual([])
  })
})
