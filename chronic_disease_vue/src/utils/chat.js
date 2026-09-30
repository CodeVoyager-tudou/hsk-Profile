/**
 * AI 问诊相关接口（前端侧）。
 *
 * 【小白先看：为什么前端不直接连 Python】
 *   请求链路是：
 *     前端 -> 网关(8090) -> user-service(8081) -> Python AI 服务 -> 大模型
 *   前端不直连 Python，原因是：
 *     · 鉴权统一在网关做（Python 服务不该暴露给公网）；
 *     · 网关/Java 层可以做限流、日志、熔断；
 *     · 浏览器跨域问题也统一在这里解决。
 *
 * 【为什么要"流式"（一边生成一边显示）】
 *   大模型生成一段长回答要好几秒。如果等它全部生成完再一次性返回，
 *   用户会对着空白屏幕干等，体验很差。
 *   流式就是"它每吐出几个字，前端就显示几个字"，用户马上能看到内容。
 *
 * 【为什么流式解析这么麻烦】
 *   数据不是一次性到达的，而是被网络切成很多小片（chunk）。
 *   可能一片正好切在一个 JSON 的中间，甚至切在一个汉字的两个字节中间。
 *   所以必须：
 *     · 把收到的片段先攒在缓冲区（buffer），凑齐完整的一帧才解析；
 *     · 用 TextDecoder 的 stream:true 处理"汉字被切开"的情况；
 *     · 结束时再冲刷一次解码器，否则最后一个字可能丢。
 *
 * 【三层数据格式的转换】
 *   Python 输出 NDJSON（每行一个 JSON）
 *     -> Java 转成 SSE（Server-Sent Events）格式
 *     -> 前端按 SSE 解析
 */
// AI 慢性病问诊相关接口：经 Gateway(8090) -> user-service /user/ai 代理（SSE），
// Java 再经 WebClient -> nginx:9000 -> Python 多实例。前端不直连 Python
import { refreshAccessToken } from '@/utils/auth'
import { DEMO_ENABLED, DEMO_DISABLED_MESSAGE } from '@/utils/demoMode'

// AI 接口走原生 fetch（不经 axios 实例，baseURL 不会自动拼上），必须手动拼环境前缀：
// 开发环境 = /dev-api（Vite 代理剥掉后转发网关 8090）；生产环境为空（nginx 直接代理 /api/，见 deploy/nginx/chronic.conf）
const AI_BASE = (import.meta.env.DEV ? import.meta.env.VITE_APP_BASE_API : '') + '/api/ai'

const DISCLAIMER =
  '\n\n---\n\u26a0\ufe0f 免责声明：本内容仅供参考，不能替代专业医疗建议。如有不适，请及时就医。'

// 生成会话 ID（后端接受任意 id，未传则自动生成）
export function newSessionId() {
  try {
    if (crypto && crypto.randomUUID) return crypto.randomUUID()
    if (crypto && crypto.getRandomValues) {
      const bytes = crypto.getRandomValues(new Uint8Array(16))
      return 'sess-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    }
  } catch (e) {}
  return 'sess-' + Date.now()
}

// 知识源下拉（供前端筛选）
export async function loadSources() {
  try {
    const res = await fetch(AI_BASE + '/sources?_t=' + Date.now())
    const body = await res.json()
    return (body && (body.sources || (body.data && body.data.sources))) || []
  } catch (e) {
    return ['disease', 'medication', 'lifestyle', 'lab', 'risk']
  }
}

// ===== 会话管理（历史会话 / 自定义标题），经 Java 代理，需登录 token =====

function authHeaders(token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = 'Bearer ' + token
  return headers
}

// 历史会话列表：[{session_id, title, updated_at, message_count}]
//
// FE-11 修复：区分「确实没有历史会话」与「请求失败」。
// 原实现把任何异常都吞成 []，于是当接口返回 401（登录过期）或 500 时，
// 界面会显示「暂无历史会话」—— 把「你没登录」伪装成「你没有数据」，
// 用户完全不知道需要重新登录。
//
// 之所以用 `res.ok === false`（严格等于）而不是 `!res.ok`：
// 某些测试替身/自定义 fetch 不会设置 ok 字段，宽松判断会把正常响应当成失败。
// 真实的 Response 对象 ok 一定是布尔值，因此严格判断既安全又准确。
export async function loadSessions(token, { onError } = {}) {
  try {
    const res = await fetch(AI_BASE + '/sessions', { headers: authHeaders(token) })
    if (res.ok === false) {
      // 失败时仍然返回 []（保持既有调用方不炸），但把错误交给 onError 回调，
      // 让上层可以显示「加载失败，请重新登录」而不是「暂无历史会话」。
      const err = new Error(`加载会话列表失败：HTTP ${res.status}`)
      if (onError) onError(err)
      return []
    }
    const body = await res.json()
    const list = body && (body.sessions || (body.data && body.data.sessions))
    return Array.isArray(list) ? list : []
  } catch (e) {
    if (onError) onError(e)
    return []
  }
}

// 回看某会话全量消息：{session_id, title, messages:[{role,content,ts}]}
export async function loadSessionMessages(sessionId, token) {
  const res = await fetch(AI_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/messages', {
    headers: authHeaders(token),
  })
  if (!res.ok) throw new Error('加载会话失败')
  const body = await res.json()
  return body.data || body
}

// 重命名会话（自定义标题）
export async function renameSession(sessionId, title, token) {
  const res = await fetch(AI_BASE + '/sessions/' + encodeURIComponent(sessionId), {
    method: 'PATCH',
    headers: authHeaders(token),
    body: JSON.stringify({ title }),
  })
  if (!res.ok) throw new Error('重命名失败')
  return true
}

// 删除会话
export async function deleteSession(sessionId, token) {
  const res = await fetch(AI_BASE + '/sessions/' + encodeURIComponent(sessionId), {
    method: 'DELETE',
    headers: authHeaders(token),
  })
  if (!res.ok) throw new Error('删除失败')
  return true
}

/**
 * 流式问答：优先 SSE POST /api/ai/query/stream，连接失败回退 POST /api/ai/query，
 * 网络完全不可达时给出演示回答（明确标注）。业务失败（401/500 等）直接报错，不伪装。
 * @param {Object} opts { query, sessionId, sourceFilter, token, signal, onToken, onDone, onError, onIncomplete }
 *   signal: AbortSignal，切换会话/卸载组件时终止在途流
 *   onIncomplete: 可选。当"回答被截断"（流在没有收到结束帧的情况下中断）时调用，
 *                 用于提示用户"回答未生成完整"，避免把半截答案当成完整答案（FE-06）。
 * @returns {Promise<string>} 实际使用的通道：'sse' | 'post' | 'mock' | 'error' | 'aborted'
 */
export function streamQuery({ query, sessionId, sourceFilter, token, signal, onToken, onDone, onError, onIncomplete }) {
  // 把 onIncomplete 一路传下去；它只是"附加通知"，缺失时行为与从前完全一致
  const opts = { query, sessionId, sourceFilter, token, signal, onToken, onDone, onError, onIncomplete }
  return streamSSE(opts).then(
    (ok) => {
      if (ok) return 'sse'
      if (signal && signal.aborted) return 'aborted'
      // SSE 连接建立失败（网络/网关/后端不可达），回退非流式接口
      return fallbackPost(query, sessionId, token, { signal, onToken, onDone, onError })
    }
  )
}

// ===== SSE 流式：fetch POST + ReadableStream 逐事件解析 =====
// 后端事件：start(会话开始) / token(增量文本) / end(结束) / error(异常)
async function streamSSE({ query, sessionId, sourceFilter, token, signal, onToken, onDone, onError, onIncomplete }) {
  let reader
  try {
    const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
    if (token) headers['Authorization'] = 'Bearer ' + token
    let res = await fetch(AI_BASE + '/query/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, sessionId, sourceFilter: sourceFilter || null }),
      signal,
    })
    // access token 过期：静默续期后用新凭证重试一次
    if (res.status === 401) {
      const fresh = await refreshAccessToken()
      if (fresh) {
        headers['Authorization'] = 'Bearer ' + fresh
        res = await fetch(AI_BASE + '/query/stream', {
          method: 'POST',
          headers,
          body: JSON.stringify({ query, sessionId, sourceFilter: sourceFilter || null }),
          signal,
        })
      }
    }
    // 非 200（如 401 未登录）或浏览器不支持流式读取：交给 fallbackPost 处理
    if (!res.ok || !res.body) return false
    reader = res.body.getReader()
  } catch (e) {
    return false
  }

  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let received = false // 是否已收到过 token：决定失败时能否安全重试（避免重复回答）
  let sawEnd = false   // 是否收到后端明确的 end 帧 —— 用来区分「回答完整」与「被截断」

  // 处理单个 SSE 帧。
  // 返回值：null = 继续；'end' = 正常结束（调用方应 onDone）；
  //         'stop' = 已就自身错误通知过调用方，直接结束、不要再 onDone；
  //         'retry' = 一个 token 都没收到，应回退重试
  const handleFrame = (frame) => {
    const event = parseSseFrame(frame)
    if (!event) return null
    let payload
    try { payload = JSON.parse(event.data) } catch (e) { return null }
    if (event.name === 'token') {
      received = true
      onToken(payload.token || '')
    } else if (event.name === 'end') {
      sawEnd = true
      return 'end'
    } else if (event.name === 'error') {
      // 已有部分内容：就地报错（注意此处**不**再调用 onDone，避免重复通知界面）；
      // 一个 token 都没有：安全回退 POST 重试
      if (received) {
        onError(payload.message || '系统处理出错')
        return 'stop'
      }
      return 'retry'
    }
    // start 事件无需处理
    return null
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      // FE-06 修复之一：最后一片要**冲刷解码器**。
      // TextDecoder 会把"不完整的多字节序列"暂存在内部 —— 例如一个汉字 3 个字节，
      // 网络只送到了前 2 个字节。此时它什么也不返回，等后面的字节补齐。
      // 如果流就此结束而我们没有调用一次 decode()（stream:false）去冲刷，
      // 这个字符就**永久丢失**了（表现为回答结尾少一个字）。
      buffer += decoder.decode(value, { stream: !done })
      if (done) break
      // 归一化 \r\n 分隔（部分网关/后端会输出 \r\n\r\n 分帧）
      buffer = buffer.replace(/\r\n/g, '\n')
      // SSE 以空行分帧，逐帧解析 event/data
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const r = handleFrame(frame)
        if (r === 'end') { onDone(); return true }
        if (r === 'stop') return true
        if (r === 'retry') return false
      }
    }

    // 流已结束：处理「最后一片没有以空行结尾」的残留帧，
    // 否则最后一帧（常常正是 end 帧）会被丢掉。
    buffer = buffer.replace(/\r\n/g, '\n')
    if (buffer.trim()) {
      const r = handleFrame(buffer)
      buffer = ''
      if (r === 'retry') return false
      if (r === 'stop') return true
      if (r === 'end') { onDone(); return true }
    }

    // FE-06 修复之二：流在没有 end 帧的情况下结束 = **回答被截断**。
    // 原实现直接调用 onDone()，用户看到半截回答却毫不知情。
    // 医疗场景下这很危险 —— 截断的位置可能正好是用药剂量或急救指引。
    if (received && !sawEnd) {
      if (onIncomplete) onIncomplete()
    }
    onDone()
    return true
  } catch (e) {
    // 主动取消：静默结束，不算完成也不报错
    if (signal && signal.aborted) return true
    // 中途断流：已有部分内容按完成处理，但要告知调用方"内容不完整"
    if (received) {
      if (!sawEnd && onIncomplete) onIncomplete()
      onDone()
      return true
    }
    return false
  }
}

// 解析单个 SSE 帧：取 event: 与 data: 行（data 兼容多行拼接）
export function parseSseFrame(frame) {
  let name = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (!dataLines.length) return null
  return { name, data: dataLines.join('\n') }
}

// POST 回退（非流式，一次性返回）。仅网络级失败（fetch 抛异常）才降级演示回答；
// 业务失败（未登录/服务错误/无回答）如实报错，不把演示回答伪装成真实回答
async function fallbackPost(query, sessionId, token, { signal, onToken, onDone, onError }) {
  try {
    const headers = { 'Content-Type': 'application/json' }
    if (token) {
      headers['Authorization'] = 'Bearer ' + token
    }
    let res = await fetch(AI_BASE + '/query', {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, sessionId }),
      signal,
    })
    if (signal && signal.aborted) return 'aborted'
    // access token 过期：静默续期后用新凭证重试一次
    if (res.status === 401) {
      const fresh = await refreshAccessToken()
      if (fresh) {
        headers['Authorization'] = 'Bearer ' + fresh
        res = await fetch(AI_BASE + '/query', {
          method: 'POST',
          headers,
          body: JSON.stringify({ query, sessionId }),
          signal,
        })
        if (signal && signal.aborted) return 'aborted'
      }
    }
    if (res.status === 401 || res.status === 403) {
      onError('请先登录后再使用 AI 问答')
      onDone()
      return 'error'
    }
    const body = await res.json().catch(() => null)
    if (body && body.answer) {
      onToken(body.answer)
      onDone()
      return 'post'
    }
    onError((body && body.message) || 'AI 服务暂时不可用，请稍后重试')
    onDone()
    return 'error'
  } catch (e) {
    if (signal && signal.aborted) return 'aborted'
    if (!DEMO_ENABLED) {
      // 生产：后端不可达时如实报错，不返回演示回答掩盖故障
      onError(DEMO_DISABLED_MESSAGE)
      onDone()
      return 'error'
    }
    // 仅网络不可达时降级演示回答（演示模式）
    mockAnswer(query, onToken, onDone)
    return 'mock'
  }
}

// ===== 演示用：后端网络不可达时的关键词问答（明确标注演示，避免与真实回答混淆）=====
function mockAnswer(query, onToken, onDone) {
  const ans = '【演示模式 · 后端未连接】\n\n' + generateMockAnswer(query) + DISCLAIMER
  let i = 0
  const chunk = 3

  // FE-10 修复：定时器必须在**任何情况下**都被清理。
  // 原实现只在「顺利跑到结尾」时 clearInterval —— 如果 onToken / onDone 抛异常
  //（最典型的场景：用户切换了会话或组件已卸载，回调里访问了已销毁的响应式对象），
  // 这个 setInterval 会永远跑下去，持续占用 CPU 并阻止相关对象被回收。
  let timer = null
  const stop = () => {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  timer = setInterval(() => {
    try {
      const piece = ans.slice(i, i + chunk)
      i += chunk
      if (piece) onToken(piece)
      if (i >= ans.length) {
        stop()
        onDone()
      }
    } catch (e) {
      // 回调抛错（例如组件已卸载）：立刻停表，避免定时器泄漏
      stop()
    }
  }, 22)
}

function generateMockAnswer(q) {
  const s = (q || '').toLowerCase()
  if (/(急救|急症|胸痛|胸闷|昏迷|晕倒|中风|心梗|休克)/.test(q)) {
    return (
      '\u26a0\ufe0f 急症提示：如您或身边的人出现剧烈胸痛、持续昏迷、一侧肢体无力/言语不清等表现，请立即拨打 120 急救电话，让患者平卧、避免剧烈搬动，疑似低血糖可先予含糖食物。\n\n' +
      '常见急症处理要点：\n1. 高血压急症：保持安静半卧位，避免情绪激动，监测血压，舌下含服降压药需遵医嘱。\n2. 低血糖：出现冷汗、心悸、手抖时立即进食糖果或含糖饮料，15 分钟后复测血糖。\n3. 心绞痛/心梗：立即停止活动，舌下含服硝酸甘油（无禁忌时），等待救援。'
    )
  }
  if (/(高血压|血压)/.test(s)) {
    return (
      '高血压管理建议：\n1. 规律监测血压，建议早晚各测一次并记录。\n2. 限盐（每日<5g）、控油、增加蔬果与低脂奶摄入（DASH 饮食）。\n3. 规律运动：每周 5 次、每次 30 分钟中等强度有氧运动，血压未控制达标前避免剧烈运动。\n4. 戒烟限酒、控制体重（BMI 18.5-23.9）、管理情绪与睡眠。\n5. 遵医嘱规律服药，勿自行停药或加量；常用药物如氨氯地平、厄贝沙坦等，常见副作用需关注踝部水肿、干咳等。'
    )
  }
  if (/(糖尿病|血糖|二甲双胍)/.test(s)) {
    return (
      '糖尿病管理建议：\n1. 监测血糖：空腹及餐后 2 小时，控制目标个体化（一般空腹 4.4-7.0，餐后<10）。\n2. 饮食：控制总热量，主食粗细搭配，减少精制糖与含糖饮料，定时定量。\n3. 运动：餐后 1 小时左右中等强度有氧 30 分钟，每周≥150 分钟，避免空腹运动防低血糖。\n4. 二甲双胍：餐中或餐后服用可减少胃肠反应；长期使用注意 B12 缺乏。\n5. 足部护理：每日检查双足，穿合脚鞋袜，避免破损感染。'
    )
  }
  if (/(用药|药物|副作用|氨氯地平|他汀|阿司匹林)/.test(s)) {
    return (
      '慢病用药注意事项：\n1. 遵医嘱按时按量服药，不随意停药、换药或加量。\n2. 了解常见副作用：氨氯地平可能出现踝部水肿、面部潮红；他汀类需关注肌肉酸痛与肝功能；阿司匹林注意出血与胃肠反应。\n3. 服药时间有讲究：降压药建议晨起服用，他汀类夜间服用效果更佳，二甲双胍餐中/餐后服。\n4. 多药联用需关注相互作用，就诊时请带齐用药清单。\n5. 出现明显不适及时就医，勿自行处理。'
    )
  }
  return (
    '您好，我是慢性病健康助手，可为您解答高血压、糖尿病、高血脂、用药指导、急救处理、生活方式等问题。\n\n您可以直接描述问题，例如：\n\u2022 高血压患者饮食要注意什么？\n\u2022 二甲双胍怎么吃副作用小？\n\u2022 突发胸痛怎么处理？\n\u2022 糖尿病足如何护理？'
  )
}
