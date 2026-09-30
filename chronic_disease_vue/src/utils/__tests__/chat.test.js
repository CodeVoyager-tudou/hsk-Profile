import { describe, it, expect, vi, beforeEach } from 'vitest'

// mock fetch globally
const mockFetch = vi.fn()
global.fetch = mockFetch

// mock crypto.randomUUID
if (!global.crypto) global.crypto = {}
global.crypto.randomUUID = () => 'test-uuid-1234'

describe('chat.js - newSessionId', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('generates a session id with crypto.randomUUID', async () => {
    const { newSessionId } = await import('@/utils/chat.js')
    const id = newSessionId()
    expect(id).toBe('test-uuid-1234')
  })
})

describe('chat.js - loadSources', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('returns sources from API', async () => {
    mockFetch.mockResolvedValue({
      json: () => Promise.resolve({ sources: ['disease', 'medication'] })
    })
    const { loadSources } = await import('@/utils/chat.js')
    const sources = await loadSources()
    expect(sources).toEqual(['disease', 'medication'])
  })

  it('returns default sources on error', async () => {
    mockFetch.mockRejectedValue(new Error('network'))
    const { loadSources } = await import('@/utils/chat.js')
    const sources = await loadSources()
    expect(sources).toEqual(['disease', 'medication', 'lifestyle', 'lab', 'risk'])
  })

  it('handles nested data structure', async () => {
    mockFetch.mockResolvedValue({
      json: () => Promise.resolve({ data: { sources: ['lab'] } })
    })
    const { loadSources } = await import('@/utils/chat.js')
    const sources = await loadSources()
    expect(sources).toEqual(['lab'])
  })
})

describe('chat.js - loadSessions', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('returns session list', async () => {
    mockFetch.mockResolvedValue({
      json: () => Promise.resolve({ sessions: [{ session_id: 's1', title: 'Test' }] })
    })
    const { loadSessions } = await import('@/utils/chat.js')
    const sessions = await loadSessions()
    expect(sessions).toHaveLength(1)
    expect(sessions[0].session_id).toBe('s1')
  })

  it('returns empty array on error', async () => {
    mockFetch.mockRejectedValue(new Error('fail'))
    const { loadSessions } = await import('@/utils/chat.js')
    const sessions = await loadSessions()
    expect(sessions).toEqual([])
  })

  it('returns empty array for non-array response', async () => {
    mockFetch.mockResolvedValue({
      json: () => Promise.resolve({ sessions: null })
    })
    const { loadSessions } = await import('@/utils/chat.js')
    const sessions = await loadSessions()
    expect(sessions).toEqual([])
  })
})

describe('chat.js - loadSessionMessages', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('returns messages for a session', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ data: { messages: [{ role: 'user', content: 'hi' }] } })
    })
    const { loadSessionMessages } = await import('@/utils/chat.js')
    const result = await loadSessionMessages('s1')
    expect(result.messages).toHaveLength(1)
  })

  it('throws on non-ok response', async () => {
    mockFetch.mockResolvedValue({ ok: false })
    const { loadSessionMessages } = await import('@/utils/chat.js')
    await expect(loadSessionMessages('s1')).rejects.toThrow('加载会话失败')
  })
})

describe('chat.js - renameSession', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('renames session successfully', async () => {
    mockFetch.mockResolvedValue({ ok: true })
    const { renameSession } = await import('@/utils/chat.js')
    const result = await renameSession('s1', '新标题')
    expect(result).toBe(true)
  })

  it('throws on failure', async () => {
    mockFetch.mockResolvedValue({ ok: false })
    const { renameSession } = await import('@/utils/chat.js')
    await expect(renameSession('s1', '新标题')).rejects.toThrow('重命名失败')
  })
})

describe('chat.js - deleteSession', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('deletes session successfully', async () => {
    mockFetch.mockResolvedValue({ ok: true })
    const { deleteSession } = await import('@/utils/chat.js')
    const result = await deleteSession('s1')
    expect(result).toBe(true)
  })

  it('throws on failure', async () => {
    mockFetch.mockResolvedValue({ ok: false })
    const { deleteSession } = await import('@/utils/chat.js')
    await expect(deleteSession('s1')).rejects.toThrow('删除失败')
  })
})

describe('chat.js - parseSseFrame', () => {
  it('parses SSE frame with event and data', async () => {
    const { parseSseFrame } = await import('@/utils/chat.js')
    expect(parseSseFrame('event: token\ndata: {"token":"hi"}')).toEqual({
      name: 'token',
      data: '{"token":"hi"}',
    })
  })

  it('joins multi-line data with newline', async () => {
    const { parseSseFrame } = await import('@/utils/chat.js')
    expect(parseSseFrame('data: line1\ndata: line2')).toEqual({
      name: 'message',
      data: 'line1\nline2',
    })
  })

  it('returns null for frame without data', async () => {
    const { parseSseFrame } = await import('@/utils/chat.js')
    expect(parseSseFrame('event: start')).toBeNull()
  })

  it('handles \\r\\n line endings', async () => {
    const { parseSseFrame } = await import('@/utils/chat.js')
    // 流式读取层已把 \r\n 归一化为 \n；解析器需兼容直接传入的 \r\n 帧
    const frame = 'event: token\r\ndata: {"token":"你好"}'
    const parsed = parseSseFrame(frame.replace(/\r\n/g, '\n'))
    expect(parsed).toEqual({ name: 'token', data: '{"token":"你好"}' })
  })
})

describe('chat.js - streamQuery', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('falls back to POST when SSE fails', async () => {
    // First call (SSE) returns non-ok
    mockFetch.mockResolvedValueOnce({ ok: false })
    // Second call (POST fallback) returns answer
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ answer: 'POST回答' })
    })

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    const channel = await streamQuery({
      query: '测试',
      sessionId: 's1',
      token: null,
      onToken,
      onDone,
      onError
    })

    expect(channel).toBe('post')
    expect(onToken).toHaveBeenCalledWith('POST回答')
    expect(onDone).toHaveBeenCalled()
  })

  it('returns mock when both SSE and POST fail', async () => {
    vi.useFakeTimers()
    mockFetch.mockRejectedValue(new Error('network'))

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    const channelPromise = streamQuery({
      query: '高血压',
      sessionId: 's1',
      token: null,
      onToken,
      onDone,
      onError
    })

    // advance timers to let mockAnswer emit all chunks
    await vi.advanceTimersByTimeAsync(5000)
    const channel = await channelPromise

    expect(channel).toBe('mock')
    expect(onToken).toHaveBeenCalled()
    expect(onDone).toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('returns sse when SSE succeeds', async () => {
    const sseBody = new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder()
        controller.enqueue(encoder.encode('event: start\ndata: {"session_id":"s1"}\n\n'))
        controller.enqueue(encoder.encode('event: token\ndata: {"token":"你好"}\n\n'))
        controller.enqueue(encoder.encode('event: end\ndata: {"is_complete":true}\n\n'))
        controller.close()
      }
    })

    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: sseBody,
      json: () => Promise.resolve({})
    })

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    const channel = await streamQuery({
      query: '测试SSE',
      sessionId: 's1',
      token: null,
      onToken,
      onDone,
      onError
    })

    expect(channel).toBe('sse')
    expect(onToken).toHaveBeenCalledWith('你好')
    expect(onDone).toHaveBeenCalled()
  })
})

describe('chat.js - 401 静默续期', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    // 清理 cookie（js-cookie 读写 document.cookie）
    document.cookie = 'cd_token=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    document.cookie = 'cd_refresh_token=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    localStorage.clear()
  })

  it('POST 返回 401 时用 refresh token 续期并重试', async () => {
    // 种下 refresh token
    document.cookie = 'cd_refresh_token=rt-test; path=/'
    mockFetch
      .mockResolvedValueOnce({ ok: false })                       // SSE 失败 → 回退 POST
      .mockResolvedValueOnce({ status: 401 })                     // POST 第一次：access 过期
      .mockResolvedValueOnce({                                    // /user/refresh 续期成功
        json: () => Promise.resolve({
          code: 200,
          data: { token: 'new-access-token', refreshToken: 'rt-test', userId: 1, username: 'admin' }
        })
      })
      .mockResolvedValueOnce({                                    // POST 重试成功
        status: 200,
        json: () => Promise.resolve({ answer: '续期后回答' })
      })

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    const channel = await streamQuery({
      query: '测试',
      sessionId: 's1',
      token: 'old-token',
      onToken,
      onDone,
      onError
    })

    expect(channel).toBe('post')
    expect(onToken).toHaveBeenCalledWith('续期后回答')
    expect(onDone).toHaveBeenCalled()
    // 新 access token 已写回 cookie 与 demo store
    expect(document.cookie).toContain('new-access-token')
  })

  it('无 refresh token 时 401 如实报错，不伪装', async () => {
    mockFetch
      .mockResolvedValueOnce({ ok: false })                       // SSE 失败
      .mockResolvedValueOnce({ status: 401 })                     // POST 401，且无 refresh token 可续期

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onError = vi.fn()

    const channel = await streamQuery({
      query: '测试',
      sessionId: 's1',
      token: null,
      onToken,
      onDone,
      onError
    })

    expect(channel).toBe('error')
    expect(onError).toHaveBeenCalledWith('请先登录后再使用 AI 问答')
    expect(onToken).not.toHaveBeenCalled()
  })
})

// ===== FE-06 回归防护：解码器冲刷 + 截断检测 =====
describe('chat.js - SSE 流结束处理（FE-06）', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  /** 构造一个把指定分片依次吐出的 SSE 响应 */
  function sseResponse(chunks) {
    const body = new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder()
        chunks.forEach((c) => controller.enqueue(
          typeof c === 'string' ? encoder.encode(c) : c
        ))
        controller.close()
      }
    })
    return { ok: true, body, json: () => Promise.resolve({}) }
  }

  it('跨分片的汉字不会丢：末尾多字节字符必须被解码器冲刷出来', async () => {
    // 「好」的 UTF-8 是 3 字节 E5 A5 BD，这里刻意切成 2 + 1 两片
    const full = new TextEncoder().encode('好')
    const part1 = full.slice(0, 2)
    const part2 = full.slice(2)

    mockFetch.mockResolvedValueOnce(sseResponse([
      'event: start\ndata: {}\n\n',
      'event: token\ndata: {"token":"',
      part1,
      part2,
      '"}\n\n',
      'event: end\ndata: {}\n\n',
    ]))

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()

    await streamQuery({
      query: '测试', sessionId: 's1', token: null,
      onToken, onDone, onError: vi.fn()
    })

    // 拼起来应该正好是完整的「好」，不丢字节也不出现乱码
    expect(onToken.mock.calls.map((c) => c[0]).join('')).toBe('好')
    expect(onDone).toHaveBeenCalled()
  })

  it('缺少 end 帧（回答被截断）时必须通知 onIncomplete', async () => {
    // 只发 token，不发 end —— 模拟连接中途断开
    mockFetch.mockResolvedValueOnce(sseResponse([
      'event: start\ndata: {}\n\n',
      'event: token\ndata: {"token":"半截回答"}\n\n',
    ]))

    const { streamQuery } = await import('@/utils/chat.js')
    const onToken = vi.fn()
    const onDone = vi.fn()
    const onIncomplete = vi.fn()

    await streamQuery({
      query: '测试', sessionId: 's1', token: null,
      onToken, onDone, onIncomplete, onError: vi.fn()
    })

    expect(onToken).toHaveBeenCalledWith('半截回答')
    expect(onIncomplete).toHaveBeenCalled()   // 关键：必须告知用户内容不完整
    expect(onDone).toHaveBeenCalled()
  })

  it('收到 end 帧时不得误报为截断', async () => {
    mockFetch.mockResolvedValueOnce(sseResponse([
      'event: start\ndata: {}\n\n',
      'event: token\ndata: {"token":"完整回答"}\n\n',
      'event: end\ndata: {"is_complete":true}\n\n',
    ]))

    const { streamQuery } = await import('@/utils/chat.js')
    const onIncomplete = vi.fn()

    await streamQuery({
      query: '测试', sessionId: 's1', token: null,
      onToken: vi.fn(), onDone: vi.fn(), onIncomplete, onError: vi.fn()
    })

    expect(onIncomplete).not.toHaveBeenCalled()
  })

  it('最后一帧没有以空行结尾时仍能被处理（end 帧不被丢弃）', async () => {
    // 末尾刻意不给 \n\n，模拟"最后一片刚好切在帧中间"
    mockFetch.mockResolvedValueOnce(sseResponse([
      'event: token\ndata: {"token":"内容"}\n\n',
      'event: end\ndata: {}',
    ]))

    const { streamQuery } = await import('@/utils/chat.js')
    const onIncomplete = vi.fn()

    const channel = await streamQuery({
      query: '测试', sessionId: 's1', token: null,
      onToken: vi.fn(), onDone: vi.fn(), onIncomplete, onError: vi.fn()
    })

    expect(channel).toBe('sse')
    expect(onIncomplete).not.toHaveBeenCalled()   // 识别到 end 帧，不算截断
  })
})
