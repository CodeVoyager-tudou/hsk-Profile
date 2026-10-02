import { describe, it, expect, vi } from 'vitest'
import { formatTime, formatDate, statusText, payText, accountTypeText, couponStatusText, isToday } from '../format.js'

describe('format.js', () => {
  describe('formatTime', () => {
    it('should format ISO time string', () => {
      expect(formatTime('2026-09-05T10:23:00')).toBe('2026-09-05 10:23:00')
    })

    it('should return empty string for null/undefined', () => {
      expect(formatTime(null)).toBe('')
      expect(formatTime(undefined)).toBe('')
      expect(formatTime('')).toBe('')
    })

    it('should handle already formatted strings', () => {
      expect(formatTime('2026-09-05 10:23:00')).toBe('2026-09-05 10:23:00')
    })
  })

  describe('formatDate', () => {
    it('should format ISO time to date only', () => {
      expect(formatDate('2026-09-05T10:23:00')).toBe('2026-09-05')
    })

    it('should return empty string for null/undefined', () => {
      expect(formatDate(null)).toBe('')
      expect(formatDate(undefined)).toBe('')
    })
  })

  describe('statusText', () => {
    it('should map PAID', () => {
      expect(statusText('PAID')).toBe('已支付')
    })

    it('should map CANCELLED', () => {
      expect(statusText('CANCELLED')).toBe('已取消')
    })

    it('should map PENDING', () => {
      expect(statusText('PENDING')).toBe('待支付')
    })

    it('should return original value for unknown status', () => {
      expect(statusText('UNKNOWN')).toBe('UNKNOWN')
    })
  })

  describe('payText', () => {
    it('should map CASH', () => {
      expect(payText('CASH')).toBe('现金支付')
    })

    it('should map POINTS', () => {
      expect(payText('POINTS')).toBe('积分支付')
    })

    it('should map BALANCE（余额支付）', () => {
      expect(payText('BALANCE')).toBe('余额支付')
    })

    it('should return original value for unknown pay type', () => {
      expect(payText('WECHAT')).toBe('WECHAT')
    })
  })

  describe('accountTypeText', () => {
    it('should map balance record types', () => {
      expect(accountTypeText('RECHARGE')).toBe('充值')
      expect(accountTypeText('BALANCE_PAY')).toBe('余额支付')
      expect(accountTypeText('BALANCE_REFUND')).toBe('退款')
      expect(accountTypeText('OTHER')).toBe('OTHER')
    })
  })

  describe('couponStatusText', () => {
    it('should map UNUSED', () => {
      expect(couponStatusText('UNUSED')).toBe('未使用')
    })

    it('should map USED', () => {
      expect(couponStatusText('USED')).toBe('已使用')
    })

    it('should map EXPIRED', () => {
      expect(couponStatusText('EXPIRED')).toBe('已过期')
    })

    it('should return original value for unknown status', () => {
      expect(couponStatusText('REVOKED')).toBe('REVOKED')
    })
  })

  describe('isToday', () => {
    it('should return true for today', () => {
      const now = new Date()
      const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
      expect(isToday(today)).toBe(true)
    })

    it('should return false for yesterday', () => {
      expect(isToday('2020-01-01')).toBe(false)
    })
  })
})
