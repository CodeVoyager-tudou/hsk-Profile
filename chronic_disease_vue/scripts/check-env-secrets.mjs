#!/usr/bin/env node
/**
 * 检查 .env* 文件里是否有人把「密钥」写进了 VITE_ 开头的变量（FE-08）。
 *
 * 【为什么需要这个脚本】
 *   Vite 在打包时会把**所有** VITE_* 变量的值直接内联进前端 JS 产物。
 *   也就是说，任何 VITE_ 开头的值，用户在浏览器里都能搜到 —— 它本质上是公开的。
 *   把密钥写进 VITE_*，等于把密钥公开发布。
 *
 *   这类错误凭人工很容易漏（尤其是赶时间的时候），所以做成可以自动跑的检查：
 *       node scripts/check-env-secrets.mjs
 *   发现可疑项会以退出码 1 结束，可以直接挂到 CI 上。
 *
 * 【判断依据】
 *   只看变量的**名字**（等号左边），不扫注释和值 ——
 *   否则说明文字里出现 "SECRET" 一词就会被误报。
 *
 * 【误报怎么办】
 *   确实无害但名字命中的，可以加注释 `# allow-env-secret-check` 豁免该行。
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

// 名字里出现这些词，就认为它在试图放一个"秘密"
const SECRET_WORDS = [
  'SECRET', 'PASSWORD', 'PASSWD', 'PWD',
  'TOKEN', 'PRIVATEKEY', 'PRIVATE_KEY',
  'CREDENTIAL', 'ACCESSKEY', 'ACCESS_KEY', 'APPSECRET', 'APP_SECRET',
  'APIKEY', 'API_KEY',
]

function isEnvFile(name) {
  return name === '.env' || name.startsWith('.env.')
}

const offenders = []
const scanned = []

for (const name of readdirSync(root)) {
  if (!isEnvFile(name)) continue
  scanned.push(name)

  const lines = readFileSync(join(root, name), 'utf8').split(/\r?\n/)
  lines.forEach((line, i) => {
    const trimmed = line.trim()
    // 跳过空行、注释、以及显式豁免的行
    if (!trimmed || trimmed.startsWith('#') || trimmed.includes('allow-env-secret-check')) return
    // 只解析 NAME = VALUE 形式的左侧名字
    const m = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=/)
    if (!m) return
    const varName = m[1]
    if (!varName.startsWith('VITE_')) return

    const upper = varName.toUpperCase()
    const hit = SECRET_WORDS.find((w) => upper.includes(w))
    if (hit) {
      offenders.push({ file: name, line: i + 1, varName, hit })
    }
  })
}

console.log(`检查了 ${scanned.length} 个 .env 文件：${scanned.join(', ')}`)

if (offenders.length === 0) {
  console.log('✅ 未发现把密钥写进 VITE_* 变量的情况')
  process.exit(0)
}

console.error('')
console.error('❌ 发现疑似把密钥写进 VITE_* 变量：')
console.error('')
for (const o of offenders) {
  console.error(`   ${o.file}:${o.line}  ${o.varName}   （命中关键词：${o.hit}）`)
}
console.error('')
console.error('为什么这是问题：')
console.error('  Vite 会把所有 VITE_* 变量的值内联进前端 JS 产物，')
console.error('  等于把密钥直接公开发布给所有访问者。')
console.error('')
console.error('怎么修：')
console.error('  1. 把密钥从 .env 中删除，改由后端持有并代理调用；')
console.error('  2. 若该值确实不是密钥、可以公开，请在该行加注释 # allow-env-secret-check 豁免。')
process.exit(1)
