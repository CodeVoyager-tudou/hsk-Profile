// 演示用 Mock 数据（后端不可用时自动启用，便于预览 UI）
export const MOCK = {
  medicines: [
    { id: 1, name: '苯磺酸氨氯地平片', category: '慢病用药', indication: '高血压、慢性稳定性心绞痛的治疗', price: 28.5, pointsPrice: 850, stock: 120 },
    { id: 2, name: '盐酸二甲双胍缓释片', category: '慢病用药', indication: '2型糖尿病，特别是肥胖的患者', price: 19.8, pointsPrice: 600, stock: 200 },
    { id: 3, name: '阿托伐他汀钙片', category: '慢病用药', indication: '高胆固醇血症、冠心病', price: 42.0, pointsPrice: 1200, stock: 88 },
    { id: 4, name: '厄贝沙坦片', category: '慢病用药', indication: '原发性高血压症', price: 35.6, pointsPrice: 0, stock: 60 },
    { id: 5, name: '布洛芬缓释胶囊', category: '解热镇痛', indication: '缓解轻至中度疼痛及感冒发热', price: 16.9, pointsPrice: 320, stock: 150 },
    { id: 6, name: '对乙酰氨基酚片', category: '解热镇痛', indication: '普通感冒或流行性感冒引起的发热', price: 9.5, pointsPrice: 0, stock: 300 },
    { id: 7, name: '阿莫西林胶囊', category: '抗感染', indication: '敏感菌所致呼吸道、泌尿道感染等', price: 12.8, pointsPrice: 240, stock: 180 },
    { id: 8, name: '头孢克肟分散片', category: '抗感染', indication: '敏感菌引起的支气管炎、肺炎等', price: 24.0, pointsPrice: 480, stock: 95 },
    { id: 9, name: '硝苯地平控释片', category: '慢病用药', indication: '高血压、心绞痛', price: 31.2, pointsPrice: 720, stock: 110 },
    { id: 10, name: '格列美脲片', category: '慢病用药', indication: '2型糖尿病', price: 38.0, pointsPrice: 0, stock: 70 },
    { id: 11, name: '双氯芬酸钠肠溶片', category: '解热镇痛', indication: '关节炎、软组织疼痛', price: 14.5, pointsPrice: 200, stock: 140 },
    { id: 12, name: '阿奇霉素片', category: '抗感染', indication: '敏感菌引起的呼吸道、皮肤感染', price: 22.0, pointsPrice: 440, stock: 65 },
  ],
  activities: [
    { id: 1, name: '高血压专享券', thresholdAmount: 50, discountAmount: 12, totalCount: 1000, issuedCount: 320, limitPerUser: 1, endTime: '2026-12-31' },
    { id: 2, name: '糖尿病满减券', thresholdAmount: 80, discountAmount: 20, totalCount: 500, issuedCount: 410, limitPerUser: 2, endTime: '2026-10-31' },
    { id: 3, name: '抗感染新客券', thresholdAmount: 30, discountAmount: 8, totalCount: 2000, issuedCount: 1998, limitPerUser: 1, endTime: '2026-09-30' },
  ],
  myCoupons: [
    { id: 1, couponName: '高血压专享券', thresholdAmount: 50, discountAmount: 12, expireTime: '2026-12-31', status: 'UNUSED' },
    { id: 2, couponName: '满30减5', thresholdAmount: 30, discountAmount: 5, expireTime: '2026-09-15', status: 'USED' },
    { id: 3, couponName: '新客立减', thresholdAmount: 0, discountAmount: 3, expireTime: '2026-08-30', status: 'EXPIRED' },
  ],
  orders: [
    { id: -1, orderNo: 'CD20260902001', medicineName: '苯磺酸氨氯地平片', quantity: 2, totalAmount: 57.0, discountAmount: 12, pointsUsed: 0, payType: 'CASH', status: 'PAID', createTime: '2026-09-01T10:23:00', category: '慢病用药' },
    { id: -2, orderNo: 'CD20260902002', medicineName: '盐酸二甲双胍缓释片', quantity: 1, totalAmount: 0, discountAmount: 0, pointsUsed: 600, payType: 'POINTS', status: 'PAID', createTime: '2026-08-29T15:40:00', category: '慢病用药' },
    { id: -3, orderNo: 'CD20260902003', medicineName: '阿莫西林胶囊', quantity: 3, totalAmount: 38.4, discountAmount: 0, pointsUsed: 0, payType: 'CASH', status: 'PENDING', createTime: '2026-09-02T09:10:00', category: '抗感染' },
    { id: -4, orderNo: 'CD20260902004', medicineName: '布洛芬缓释胶囊', quantity: 1, totalAmount: 16.9, discountAmount: 0, pointsUsed: 0, payType: 'CASH', status: 'CANCELLED', createTime: '2026-08-25T18:00:00', category: '解热镇痛' },
  ],
  week: {
    signedDays: 4, todaySigned: false, todayPoints: 15, fullAttendanceBonus: 100, fullAttendance: false,
    days: [
      { weekDay: '周一', date: '2026-08-31', points: 10, signed: true },
      { weekDay: '周二', date: '2026-09-01', points: 12, signed: true },
      { weekDay: '周三', date: '2026-09-02', points: 15, signed: false },
      { weekDay: '周四', date: '2026-09-03', points: 12, signed: false },
      { weekDay: '周五', date: '2026-09-04', points: 12, signed: false },
      { weekDay: '周六', date: '2026-09-05', points: 15, signed: false },
      { weekDay: '周日', date: '2026-09-06', points: 20, signed: false },
    ],
  },
  records: [
    { id: 1, createTime: '2026-09-01T08:00:00', points: 12, type: 'SIGN_IN', remark: '每日签到' },
    { id: 2, createTime: '2026-08-31T08:00:00', points: 10, type: 'SIGN_IN', remark: '每日签到' },
    { id: 3, createTime: '2026-08-29T15:40:00', points: -600, type: 'EXCHANGE', remark: '兑换 盐酸二甲双胍缓释片' },
    { id: 4, createTime: '2026-08-25T10:00:00', points: 50, type: 'TASK', remark: '完善健康档案' },
    { id: 5, createTime: '2026-08-20T08:00:00', points: 15, type: 'SIGN_IN', remark: '每日签到' },
  ],
  points: { total: 1280, used: 600 },
}
