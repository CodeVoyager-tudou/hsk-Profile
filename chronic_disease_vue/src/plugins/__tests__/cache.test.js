import { describe, it, expect, beforeEach } from 'vitest'
import cache from '../cache.js'

describe('cache.js', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
  })

  describe('session storage', () => {
    it('should set and get JSON', () => {
      cache.session.setJSON('test', { a: 1, b: 'hello' })
      const result = cache.session.getJSON('test')
      expect(result).toEqual({ a: 1, b: 'hello' })
    })

    it('should return null for non-existent key', () => {
      expect(cache.session.getJSON('nonexistent')).toBeNull()
    })

    it('should remove key', () => {
      cache.session.setJSON('test', { a: 1 })
      cache.session.remove('test')
      expect(cache.session.getJSON('test')).toBeNull()
    })

    it('should clear all session storage', () => {
      cache.session.setJSON('a', 1)
      cache.session.setJSON('b', 2)
      cache.session.clear()
      expect(cache.session.getJSON('a')).toBeNull()
      expect(cache.session.getJSON('b')).toBeNull()
    })
  })

  describe('local storage', () => {
    it('should set and get JSON', () => {
      cache.local.setJSON('test', { x: 10 })
      const result = cache.local.getJSON('test')
      expect(result).toEqual({ x: 10 })
    })

    it('should return null for non-existent key', () => {
      expect(cache.local.getJSON('nonexistent')).toBeNull()
    })

    it('should remove key', () => {
      cache.local.setJSON('test', { x: 10 })
      cache.local.remove('test')
      expect(cache.local.getJSON('test')).toBeNull()
    })

    it('should clear all local storage', () => {
      cache.local.setJSON('a', 1)
      cache.local.clear()
      expect(cache.local.getJSON('a')).toBeNull()
    })
  })
})
