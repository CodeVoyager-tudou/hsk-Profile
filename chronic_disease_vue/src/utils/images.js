// 图片生成接口（原 guideline：所有 web 图片必须使用该接口）
//
// ⚠️ 偏离说明（2026-09-28）：该外链已不可用——实测返回 301 + 2 字节 JSON
// （content-type=application/json，不是图片），导致全站商品图/订单图渲染成
// "The image could not be loaded" 的失败占位图。
//   实测命令：curl -sI "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=test&image_size=square_hd"
//   若要恢复走远端生图，需要一个可用的图床/生图接口地址。
// 现改为在浏览器端现生成 SVG 商品图，好处：
//   1) 离线可用，不依赖外部服务；
//   2) 同一名称稳定返回同一张（不会每次刷新都换图）；
//   3) 中文用系统字体渲染，不会是乱码方块。
// 药品若已上传 OSS（medicine.image_url），仍然优先使用真实照片——本文件只做兜底。
export const IMG_API = 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image'

/** 名称 -> 色相：同名同色，不同名大概率不同色 */
function hueOf(text) {
  let h = 0
  for (const ch of String(text || '')) h = (h * 31 + ch.charCodeAt(0)) % 360
  return h
}

function escapeXml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]
  ))
}

function svgUrl(svg) {
  return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg)
}

/** 药盒 + 胶囊 + 名称前两字的商品图兜底 */
export function medPlaceholder(name) {
  const label = escapeXml((String(name || '').trim() || '药品').slice(0, 2))
  const hue = hueOf(name)
  const bg = `hsl(${hue}, 46%, 94%)`
  const fg = `hsl(${hue}, 42%, 40%)`
  const line = `hsl(${hue}, 38%, 82%)`
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600">' +
    `<rect width="600" height="600" fill="${bg}"/>` +
    `<rect x="168" y="142" width="264" height="316" rx="28" fill="#ffffff" stroke="${line}" stroke-width="6"/>` +
    `<rect x="168" y="142" width="264" height="88" rx="28" fill="${fg}" opacity="0.16"/>` +
    `<circle cx="300" cy="186" r="18" fill="${fg}" opacity="0.5"/>` +
    `<g transform="translate(232 286) rotate(-28)">` +
    `<rect width="146" height="58" rx="29" fill="${fg}" opacity="0.85"/>` +
    `<rect width="73" height="58" rx="29" fill="#ffffff" opacity="0.6"/>` +
    '</g>' +
    `<line x1="196" y1="386" x2="404" y2="386" stroke="${line}" stroke-width="6"/>` +
    `<text x="300" y="428" text-anchor="middle" font-size="44" ` +
    `font-family="'Microsoft YaHei','PingFang SC','Hiragino Sans GB',sans-serif" fill="${fg}">${label}</text>` +
    '</svg>'
  return svgUrl(svg)
}

// 按药品分类生成商品图（同名同分类返回稳定结果）
export function medImage(m) {
  // 已上传 OSS 的药品图片优先（后端 medicine.image_url）；空则退回本地生成的占位图
  if (m && m.imageUrl) return m.imageUrl
  return medPlaceholder((m && m.name) || 'medicine')
}

// 订单缩略图：复用商品分类逻辑，保持一致
export function orderImg(o) {
  // 订单快照若带 medicineImage（未来字段扩展）优先用，否则退回生成图
  if (o && o.medicineImage) return o.medicineImage
  return medImage({ name: o && o.medicineName })
}

/** 首页 banner：柔和渐变 + 胶囊圆点装饰，不写字（避免与页面上的文字打架） */
export const bannerImg = svgUrl(
  '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="480" viewBox="0 0 1200 480">' +
  '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
  '<stop offset="0" stop-color="#e8f5f0"/><stop offset="1" stop-color="#d7ece4"/>' +
  '</linearGradient></defs>' +
  '<rect width="1200" height="480" fill="url(#g)"/>' +
  '<g opacity="0.35">' +
  '<circle cx="180" cy="120" r="70" fill="#9fd6c4"/>' +
  '<circle cx="980" cy="360" r="96" fill="#b6e0d2"/>' +
  '<circle cx="620" cy="80" r="44" fill="#c9e8dd"/>' +
  '</g>' +
  '<g transform="translate(430 200) rotate(-24)">' +
  '<rect width="220" height="86" rx="43" fill="#5fa88f" opacity="0.8"/>' +
  '<rect width="110" height="86" rx="43" fill="#ffffff" opacity="0.7"/>' +
  '</g>' +
  '</svg>'
)

/** 头像兜底：姓名首字 + 稳定底色 */
export const avatarImg = medPlaceholder('健康')
