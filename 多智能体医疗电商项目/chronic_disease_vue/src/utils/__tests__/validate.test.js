import { describe, it, expect } from 'vitest'
import { isExternal } from '../validate.js'

describe('validate.js', () => {
  describe('isExternal', () => {
    it('should return true for http URLs', () => {
      expect(isExternal('http://example.com')).toBe(true)
    })

    it('should return true for https URLs', () => {
      expect(isExternal('https://example.com')).toBe(true)
    })

    it('should return true for mailto links', () => {
      expect(isExternal('mailto:test@example.com')).toBe(true)
    })

    it('should return true for tel links', () => {
      expect(isExternal('tel:13800138000')).toBe(true)
    })

    it('should return false for relative paths', () => {
      expect(isExternal('/login')).toBe(false)
      expect(isExternal('/shop/medicine')).toBe(false)
      expect(isExternal('')).toBe(false)
    })

    it('should return false for hash routes', () => {
      expect(isExternal('#/home')).toBe(false)
    })
  })
})
