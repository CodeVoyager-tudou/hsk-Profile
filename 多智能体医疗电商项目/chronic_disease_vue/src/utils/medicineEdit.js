/**
 * 药品编辑表单的 diff 与校验（管理端）。
 *
 * 为什么要单独一个模块：这段逻辑决定"哪些字段会发给后端"，而审计里记录的就是这批字段 ——
 * 埋在组件里既不好测、也容易在改表单时改坏。字段清单必须与后端 `MedicineUpdateRequest` 一一对应。
 *
 * 约定：
 *   · 表单里所有值都按字符串处理（`<input>` 的原始值），提交前再转数字；
 *   · 只有与初值不同的字段才会进入 payload（未动的字段不发，后端也不会改）；
 *   · 文本按去空白比较，数字按数值比较（"12" 与 "12.00" 视为未改）。
 */

export const MEDICINE_TEXT_FIELDS = ['name', 'genericName', 'category', 'manufacturer', 'indication', 'dosage']

export const MEDICINE_NUMBER_FIELDS = ['price', 'stock', 'pointsPrice', 'pointsReward']

export const MEDICINE_FIELD_LABELS = {
  name: '药品名称',
  genericName: '通用名',
  category: '分类',
  manufacturer: '生产厂家',
  indication: '适应症',
  dosage: '用法用量',
  price: '现金价',
  stock: '库存',
  pointsPrice: '积分兑换价',
  pointsReward: '返积分',
}

export const MEDICINE_ALL_FIELDS = [...MEDICINE_TEXT_FIELDS, ...MEDICINE_NUMBER_FIELDS]

/** 接口返回的药品行 → 表单初值（统一成字符串，null/undefined 归一为空串） */
export function formOfMedicine(medicine = {}) {
  const form = {}
  for (const field of MEDICINE_ALL_FIELDS) {
    const value = medicine[field]
    form[field] = value == null ? '' : String(value)
  }
  return form
}

/** 值是否算"没改"：文本比去空白后的字符串，数字比数值 */
export function sameValue(field, a, b) {
  if (MEDICINE_TEXT_FIELDS.includes(field)) {
    return String(a ?? '').trim() === String(b ?? '').trim()
  }
  const numA = Number(a)
  const numB = Number(b)
  // 任一侧不是合法数字（含空串）时退回字符串比较，避免 Number('') === 0 这种误判
  if (String(a ?? '').trim() === '' || String(b ?? '').trim() === '' || Number.isNaN(numA) || Number.isNaN(numB)) {
    return String(a ?? '') === String(b ?? '')
  }
  return numA === numB
}

/** 与初值不同的字段列表（顺序固定，便于断言与提示） */
export function changedFields(origin, form) {
  return MEDICINE_ALL_FIELDS.filter((field) => !sameValue(field, form[field], origin[field]))
}

/**
 * 组装提交体：只放变更字段；数字字段做类型转换，留空视作错误（要清空数字字段不该用"清空"表达）。
 *
 * @returns {{ payload?: object, error?: string }}
 */
export function buildMedicinePayload(origin, form) {
  const payload = {}
  for (const field of changedFields(origin, form)) {
    const raw = form[field]
    if (MEDICINE_TEXT_FIELDS.includes(field)) {
      payload[field] = String(raw ?? '').trim()
      continue
    }
    if (String(raw ?? '').trim() === '') {
      return { error: `${MEDICINE_FIELD_LABELS[field]}不能留空（不想改请取消）` }
    }
    payload[field] = Number(raw)
  }
  return { payload }
}

// 与后端列约束对齐的上限（复核 P2-4）：price 是 DECIMAL(10,2)（整数位 8 位），
// 积分/库存是 INT 且给业务上限，避免溢出时漏成一段原始数据库错误。
export const MEDICINE_PRICE_MAX = 99999999.99
export const MEDICINE_INT_MAX = 999999

/**
 * 提交前的本地校验：明显错误当场提示，不必等后端往返。
 * 后端仍会再校验一遍（接口可被脚本直接调用），这里只是体验层。
 *
 * @returns {string} 错误文案；空串表示通过
 */
export function validateMedicinePayload(payload, form) {
  if ('name' in payload && !payload.name) {
    return '药品名称不能为空'
  }
  if ('price' in payload) {
    if (!(payload.price > 0)) {
      return '现金价必须大于 0'
    }
    if (!/^\d+(\.\d{1,2})?$/.test(String(form.price).trim())) {
      return '现金价最多两位小数'
    }
    if (payload.price > MEDICINE_PRICE_MAX) {
      return `现金价超出上限（${MEDICINE_PRICE_MAX}）`
    }
  }
  for (const field of ['stock', 'pointsPrice', 'pointsReward']) {
    if (!(field in payload)) continue
    // 整数三兄弟必须显式校验整数（复核 P2-7b）：库存填 12.7 时 number 输入框不拦手工输入，
    // 后端 Jackson 绑到 Integer 会静默取整成 12 —— "用户以为设了 12.7，系统默默存了 12"。
    if (!Number.isInteger(payload[field])) {
      return `${MEDICINE_FIELD_LABELS[field]}必须是整数`
    }
    if (payload[field] < 0) {
      return `${MEDICINE_FIELD_LABELS[field]}不能为负数`
    }
    if (payload[field] > MEDICINE_INT_MAX) {
      return `${MEDICINE_FIELD_LABELS[field]}超出上限（${MEDICINE_INT_MAX}）`
    }
  }
  return ''
}

/** 新增药品的空表单（字段与编辑一致，便于复用同一个弹窗） */
export function emptyMedicineForm() {
  const form = {}
  for (const field of MEDICINE_ALL_FIELDS) {
    form[field] = ''
  }
  return form
}

/**
 * 新增药品的提交体：**留空的字段不发**（后端按表默认值处理：库存/积分为 0、新建即上架），
 * 只有名称与现金价是必填。
 *
 * <p>与编辑的区别就在这里：编辑是"与初值 diff"，新增是"填了才发" ——
 * 新增时留空库存表达的是"用默认值"，不是"清空某个已有值"。</p>
 *
 * @returns {{ payload?: object, error?: string }}
 */
export function buildCreatePayload(form) {
  const payload = {}
  for (const field of MEDICINE_ALL_FIELDS) {
    const raw = form[field]
    const text = String(raw ?? '').trim()
    if (text === '') {
      continue
    }
    payload[field] = MEDICINE_TEXT_FIELDS.includes(field) ? text : Number(text)
  }
  if (!payload.name) {
    return { error: '药品名称不能为空' }
  }
  if (payload.price == null) {
    return { error: '现金价必填' }
  }
  return { payload }
}

/** 新增的校验：必填项 + 与编辑同一套取值规则 */
export function validateCreatePayload(payload, form) {
  if (!payload.name) {
    return '药品名称不能为空'
  }
  if (payload.price == null) {
    return '现金价必填'
  }
  return validateMedicinePayload(payload, form)
}
