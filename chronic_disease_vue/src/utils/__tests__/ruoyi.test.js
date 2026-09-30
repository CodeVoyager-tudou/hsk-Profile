import { describe, it, expect } from 'vitest'
import { parseTime, addDateRange, selectDictLabel, selectDictLabels } from '../ruoyi.js'

describe('ruoyi.js', () => {
  describe('parseTime', () => {
    it('should format Date object with default pattern', () => {
      const date = new Date(2026, 8, 5, 10, 23, 45)
      expect(parseTime(date)).toBe('2026-09-05 10:23:45')
    })

    it('should format timestamp', () => {
      const ts = new Date(2026, 0, 1, 0, 0, 0).getTime()
      expect(parseTime(ts)).toBe('2026-01-01 00:00:00')
    })

    it('should format 10-digit timestamp as seconds', () => {
      const ts = Math.floor(new Date(2026, 0, 1).getTime() / 1000)
      expect(parseTime(ts)).toBe('2026-01-01 00:00:00')
    })

    it('should format string date with dashes', () => {
      expect(parseTime('2026-09-05 10:23:45')).toBe('2026-09-05 10:23:45')
    })

    it('should return null for null/undefined', () => {
      expect(parseTime(null)).toBe(null)
      expect(parseTime(undefined)).toBe(null)
      expect(parseTime('')).toBe(null)
    })

    it('should format with custom pattern', () => {
      const date = new Date(2026, 8, 5, 10, 23, 45)
      expect(parseTime(date, '{y}/{m}/{d}')).toBe('2026/09/05')
    })

    it('should format day of week', () => {
      const date = new Date(2026, 8, 7) // Monday (Mon=1 in JS getDay())
      expect(parseTime(date, '{a}')).toBe('一')
    })
  })

  describe('addDateRange', () => {
    it('should add beginTime and endTime to params', () => {
      const params = { name: 'test' }
      const dateRange = ['2026-01-01', '2026-12-31']
      const result = addDateRange(params, dateRange)
      expect(result.params.beginTime).toBe('2026-01-01')
      expect(result.params.endTime).toBe('2026-12-31')
      expect(result.name).toBe('test')
    })

    it('should add custom property names', () => {
      const params = {}
      const dateRange = ['2026-01-01', '2026-12-31']
      const result = addDateRange(params, dateRange, 'Date')
      expect(result.params.beginDate).toBe('2026-01-01')
      expect(result.params.endDate).toBe('2026-12-31')
    })

    it('should handle empty dateRange', () => {
      const params = {}
      const result = addDateRange(params, [])
      expect(result.params.beginTime).toBeUndefined()
      expect(result.params.endTime).toBeUndefined()
    })
  })

  describe('selectDictLabel', () => {
    it('should find label by value', () => {
      const dictList = [
        { label: '男', value: '0' },
        { label: '女', value: '1' }
      ]
      expect(selectDictLabel(dictList, '0')).toBe('男')
      expect(selectDictLabel(dictList, '1')).toBe('女')
    })

    it('should return value if no match found', () => {
      const dictList = [{ label: '男', value: '0' }]
      expect(selectDictLabel(dictList, '99')).toBe('99')
    })

    it('should return empty string for undefined/null', () => {
      const dictList = []
      expect(selectDictLabel(dictList, undefined)).toBe('')
      expect(selectDictLabel(dictList, null)).toBe('')
    })
  })

  describe('selectDictLabels', () => {
    it('should join multiple labels with separator', () => {
      const dictList = [
        { label: '读写', value: '1' },
        { label: '运动', value: '2' }
      ]
      const result = selectDictLabels(dictList, '1', ',')
      expect(result).toBe('读写')
    })
  })
})
