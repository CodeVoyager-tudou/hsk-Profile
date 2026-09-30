import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  getToken,
  setToken,
  removeToken,
  getRefreshToken,
  setRefreshToken,
  removeRefreshToken
} from '../auth.js'

vi.mock('js-cookie', () => ({
  default: {
    get: vi.fn(),
    set: vi.fn(),
    remove: vi.fn()
  }
}))

import Cookies from 'js-cookie'

describe('auth.js', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should get token', () => {
    Cookies.get.mockReturnValue('test-token')
    expect(getToken()).toBe('test-token')
    expect(Cookies.get).toHaveBeenCalledWith('cd_token')
  })

  // FE-02 回归防护：原实现是 Cookies.set(key, value) 不带任何选项，
  // 等于没有 SameSite（增大 CSRF 面）、没有 Path（作用域不明确）。
  // 下面这几条锁定「必须带安全选项」这一行为，防止将来被改回去。
  it('写入 access token 时必须带 SameSite/Path 安全选项', () => {
    setToken('new-token')
    expect(Cookies.set).toHaveBeenCalledTimes(1)
    const [key, value, opts] = Cookies.set.mock.calls[0]
    expect(key).toBe('cd_token')
    expect(value).toBe('new-token')
    expect(opts).toBeTruthy()
    expect(opts.sameSite).toBe('lax')
    expect(opts.path).toBe('/')
    // secure 取决于当前页面协议（https 才为 true），这里只断言它是布尔值，
    // 避免测试与运行环境（happy-dom 默认 http）耦合
    expect(typeof opts.secure).toBe('boolean')
  })

  it('删除 token 时必须带相同的 path，否则浏览器不会真正删除', () => {
    removeToken()
    expect(Cookies.remove).toHaveBeenCalledWith('cd_token', { path: '/' })
  })

  it('refresh token 同样受安全选项保护（它是长效凭证，泄露危害更大）', () => {
    setRefreshToken('rt-token')
    const [key, value, opts] = Cookies.set.mock.calls[0]
    expect(key).toBe('cd_refresh_token')
    expect(value).toBe('rt-token')
    expect(opts.sameSite).toBe('lax')
    expect(opts.path).toBe('/')

    removeRefreshToken()
    expect(Cookies.remove).toHaveBeenCalledWith('cd_refresh_token', { path: '/' })
    expect(getRefreshToken).toBeTypeOf('function')
  })
})
