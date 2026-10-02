import { describe, expect, it } from 'vitest'
import { medImage, orderImg, medPlaceholder } from '../images'

// 图片取值优先级：后端存的 OSS URL（medicine.image_url / 订单快照 medicineImage）
// > 本地生成的 SVG 占位图（按药品名稳定生成，同名同图）。
//
// 历史背景：兜底图原先指向外部 AI 生图接口（trae-api-cn），该域名后来失效
// （实测返回 301 + 2 字节 JSON，不是图片），全站商品图/订单图都变成"加载失败"占位图，
// 因此改为在浏览器端现生成 SVG —— 这也让下面这些断言不再依赖外部服务。
describe('medImage 优先使用 OSS URL', () => {
  it('image_url 有值时直接返回，不再走占位图', () => {
    const m = { name: '布洛芬缓释胶囊', category: '解热镇痛', imageUrl: 'https://bucket.oss.aliyuncs.com/medicine/202609/x.png' }
    expect(medImage(m)).toBe('https://bucket.oss.aliyuncs.com/medicine/202609/x.png')
  })

  it('image_url 为空/字段缺失时退回本地生成的 SVG 占位图', () => {
    const generated = medImage({ name: '布洛芬缓释胶囊', category: '解热镇痛', imageUrl: '' })
    expect(generated.startsWith('data:image/svg+xml;utf8,')).toBe(true)

    const fallback = medImage({ name: '阿莫西林', category: '抗感染' })
    expect(fallback.startsWith('data:image/svg+xml;utf8,')).toBe(true)
  })

  it('占位图按名称稳定生成：同名同图、不同名不同图', () => {
    expect(medPlaceholder('布洛芬缓释胶囊')).toBe(medPlaceholder('布洛芬缓释胶囊'))
    expect(medPlaceholder('布洛芬缓释胶囊')).not.toBe(medPlaceholder('阿莫西林胶囊'))
  })

  it('占位图是合法 SVG（解码后以 <svg 开头，且名称已做 XML 转义）', () => {
    // 占位图只取名称前两字做标签，所以特殊字符要落在前两位才走得到转义分支
    const svg = decodeURIComponent(medPlaceholder('&<测试').replace('data:image/svg+xml;utf8,', ''))
    expect(svg.startsWith('<svg')).toBe(true)
    expect(svg).toContain('&amp;&lt;')
  })

  it('orderImg 支持订单快照的 medicineImage 字段', () => {
    expect(orderImg({ medicineName: 'a', category: 'b', medicineImage: 'https://oss/x.jpg' })).toBe('https://oss/x.jpg')
    expect(orderImg({ medicineName: 'a', category: 'b' }).startsWith('data:image/svg+xml;utf8,')).toBe(true)
  })
})
