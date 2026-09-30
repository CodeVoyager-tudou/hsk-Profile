import { describe, it, expect } from 'vitest'
import {
  MEDICINE_INT_MAX,
  MEDICINE_PRICE_MAX,
  buildCreatePayload,
  buildMedicinePayload,
  changedFields,
  emptyMedicineForm,
  formOfMedicine,
  sameValue,
  validateCreatePayload,
  validateMedicinePayload,
} from '../medicineEdit.js'

const MEDICINE = {
  id: 12,
  name: '阿司匹林肠溶片',
  genericName: '阿司匹林',
  category: '慢病用药',
  manufacturer: '拜耳医药',
  indication: '用于降低心肌梗死等血栓事件风险',
  dosage: '口服,一次100mg,一日1次',
  price: 12.0,
  stock: 999,
  pointsPrice: 900,
  pointsReward: 12,
  status: 1,
}

describe('medicineEdit.js', () => {
  describe('formOfMedicine', () => {
    it('把行转成表单字符串，null/undefined 归一为空串', () => {
      const form = formOfMedicine({ name: 'A', price: 12.5, stock: null, category: undefined })
      expect(form.name).toBe('A')
      expect(form.price).toBe('12.5')
      expect(form.stock).toBe('')
      expect(form.category).toBe('')
    })

    it('只取白名单字段：id/status/imageUrl 不进入表单（不该被客户端改）', () => {
      const form = formOfMedicine(MEDICINE)
      expect(Object.keys(form)).not.toContain('id')
      expect(Object.keys(form)).not.toContain('status')
    })
  })

  describe('sameValue', () => {
    it('数字按数值比较："12" 与 "12.00" 视为未改', () => {
      expect(sameValue('price', '12', '12.00')).toBe(true)
      expect(sameValue('price', '12.01', '12.00')).toBe(false)
    })

    it('文本按去空白比较', () => {
      expect(sameValue('name', '  阿司匹林 ', '阿司匹林')).toBe(true)
      expect(sameValue('indication', '', '  ')).toBe(true)
    })

    it('空串不会被当成 0（Number("") === 0 的坑）', () => {
      expect(sameValue('stock', '', '0')).toBe(false)
      expect(sameValue('stock', '', '')).toBe(true)
    })
  })

  describe('changedFields / buildMedicinePayload', () => {
    it('只提交变更字段（未动的字段不进 payload，后端也就不会改）', () => {
      const origin = formOfMedicine(MEDICINE)
      const form = { ...origin, price: '13.50', stock: '1200' }

      expect(changedFields(origin, form)).toEqual(['price', 'stock'])
      const { payload } = buildMedicinePayload(origin, form)
      expect(payload).toEqual({ price: 13.5, stock: 1200 })
      expect(Object.keys(payload)).not.toContain('name')
      expect(Object.keys(payload)).not.toContain('indication')
    })

    it('文本字段 trim 后提交，空串是"显式清空"', () => {
      const origin = formOfMedicine(MEDICINE)
      const form = { ...origin, manufacturer: '  石药集团  ', indication: '' }

      const { payload } = buildMedicinePayload(origin, form)
      expect(payload.manufacturer).toBe('石药集团')
      expect(payload.indication).toBe('')
    })

    it('数字字段被清空时给出明确错误，而不是提交 NaN/0', () => {
      const origin = formOfMedicine(MEDICINE)
      const form = { ...origin, price: '' }

      const { payload, error } = buildMedicinePayload(origin, form)
      expect(payload).toBeUndefined()
      expect(error).toContain('现金价不能留空')
    })

    it('没有任何改动时 payload 为空对象（按钮此刻也是禁用的）', () => {
      const origin = formOfMedicine(MEDICINE)
      const { payload } = buildMedicinePayload(origin, { ...origin })
      expect(payload).toEqual({})
    })
  })

  describe('validateMedicinePayload', () => {
    const origin = formOfMedicine(MEDICINE)

    it('名称不能为空', () => {
      expect(validateMedicinePayload({ name: '' }, { ...origin, name: '  ' })).toBe('药品名称不能为空')
    })

    it('现金价必须大于 0', () => {
      expect(validateMedicinePayload({ price: 0 }, { ...origin, price: '0' })).toBe('现金价必须大于 0')
    })

    it('现金价最多两位小数（列是 DECIMAL(10,2)，多了会被 MySQL 静默四舍五入）', () => {
      expect(validateMedicinePayload({ price: 12.345 }, { ...origin, price: '12.345' }))
        .toBe('现金价最多两位小数')
      expect(validateMedicinePayload({ price: 12.34 }, { ...origin, price: '12.34' })).toBe('')
    })

    it('库存/积分不能为负', () => {
      expect(validateMedicinePayload({ stock: -1 }, { ...origin, stock: '-1' })).toBe('库存不能为负数')
      expect(validateMedicinePayload({ pointsPrice: -5 }, { ...origin, pointsPrice: '-5' }))
        .toBe('积分兑换价不能为负数')
    })

    it('合法 payload 返回空串（无错误）', () => {
      expect(validateMedicinePayload({ price: 13.5, stock: 1200 }, { ...origin, price: '13.50', stock: '1200' }))
        .toBe('')
    })

    // 以下四条覆盖复核 P2-4 / P2-7b 补的规则（上限与整数校验）——
    // 这两条规则是"静默出错"型的：价格溢出会漏成原始 DB 异常，
    // 库存 12.7 会被 Jackson 静默取整成 12（用户以为设了 12.7）。
    it('现金价超出 DECIMAL(10,2) 上限要拦住', () => {
      expect(validateMedicinePayload({ price: 100000000 }, { ...origin, price: '100000000' }))
        .toBe(`现金价超出上限（${MEDICINE_PRICE_MAX}）`)
      expect(validateMedicinePayload({ price: MEDICINE_PRICE_MAX }, { ...origin, price: String(MEDICINE_PRICE_MAX) }))
        .toBe('')
    })

    it('库存/积分必须是整数（12.7 不能被静默取整成 12）', () => {
      expect(validateMedicinePayload({ stock: 12.7 }, { ...origin, stock: '12.7' })).toBe('库存必须是整数')
      expect(validateMedicinePayload({ pointsPrice: 1.5 }, { ...origin, pointsPrice: '1.5' }))
        .toBe('积分兑换价必须是整数')
      expect(validateMedicinePayload({ stock: 12 }, { ...origin, stock: '12' })).toBe('')
    })

    it('库存/积分超出业务上限要拦住', () => {
      expect(validateMedicinePayload({ stock: MEDICINE_INT_MAX + 1 }, { ...origin, stock: String(MEDICINE_INT_MAX + 1) }))
        .toBe(`库存超出上限（${MEDICINE_INT_MAX}）`)
      expect(validateMedicinePayload({ pointsPrice: MEDICINE_INT_MAX }, { ...origin, pointsPrice: String(MEDICINE_INT_MAX) }))
        .toBe('')
    })

    it('空值不参与上限/整数校验（未提交的字段不该被当成 0 或非法）', () => {
      expect(validateMedicinePayload({}, origin)).toBe('')
    })
  })

  describe('新增模式（buildCreatePayload / validateCreatePayload）', () => {
    it('空表单：提示名称必填', () => {
      const { payload, error } = buildCreatePayload(emptyMedicineForm())
      expect(payload).toBeUndefined()
      expect(error).toBe('药品名称不能为空')
    })

    it('只填名称：提示现金价必填', () => {
      const form = { ...emptyMedicineForm(), name: '测试药' }
      expect(buildCreatePayload(form).error).toBe('现金价必填')
    })

    it('留空的字段不发（后端按默认值处理），填了的做类型转换', () => {
      const form = {
        ...emptyMedicineForm(),
        name: '  测试药  ',
        price: '9.9',
        stock: '',            // 留空 = 用默认值 0，而不是报错
        pointsPrice: '100',
      }
      const { payload } = buildCreatePayload(form)
      expect(payload).toEqual({ name: '测试药', price: 9.9, pointsPrice: 100 })
      expect('stock' in payload).toBe(false)
    })

    it('新增的取值规则与编辑一致（价格必须>0、最多两位小数、积分非负）', () => {
      const base = { ...emptyMedicineForm(), name: '测试药' }
      expect(validateCreatePayload({ name: '测试药', price: 0 }, { ...base, price: '0' }))
        .toBe('现金价必须大于 0')
      expect(validateCreatePayload({ name: '测试药', price: 1.234 }, { ...base, price: '1.234' }))
        .toBe('现金价最多两位小数')
      expect(validateCreatePayload({ name: '测试药', price: 1, stock: -1 }, { ...base, price: '1', stock: '-1' }))
        .toBe('库存不能为负数')
      expect(validateCreatePayload({ name: '测试药', price: 1 }, { ...base, price: '1' })).toBe('')
    })
  })
})
