import Cookies from 'js-cookie'
import { syncLegacyAuth } from '@/store/demo'

const TokenKey = 'cd_token'
const RefreshTokenKey = 'cd_refresh_token'

// Cookie 安全选项（FE-02 修复）。
//
// 原实现是 `Cookies.set(key, token)` —— 不传任何选项，等于：
//   · 没有 Secure    -> http 明文连接也会带上凭证
//   · 没有 SameSite  -> 跨站请求会自动携带，增大 CSRF 面
//   · 没有 Path      -> 作用域不明确
//
// 关于 HttpOnly：**前端 JS 无法设置 HttpOnly**，它只能由服务端在 Set-Cookie 响应头里下发。
// 因此本文件能做的加固就是下面这几项。若要彻底避免 XSS 读到 token，
// 需要后端改为下发 HttpOnly Cookie、前端不再直接读 token（涉及网关与 user-service 改造，
// 已作为未修项记录在审计报告中）。
const COOKIE_OPTS = {
  // 同站请求才自动携带，显著降低 CSRF 风险
  sameSite: 'lax',
  // 仅 HTTPS 下传输。本地 http 开发时必须为 false，否则浏览器会直接丢弃该 Cookie。
  secure: typeof location !== 'undefined' && location.protocol === 'https:',
  // 明确作用域，避免 Cookie 被带到同域的其他路径上
  path: '/',
}

// 删除 Cookie 时必须使用与写入时相同的 path，否则删不掉（js-cookie 的常见坑）
const REMOVE_OPTS = { path: COOKIE_OPTS.path }

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  return Cookies.set(TokenKey, token, COOKIE_OPTS)
}

export function removeToken() {
  return Cookies.remove(TokenKey, REMOVE_OPTS)
}

export function getRefreshToken() {
  return Cookies.get(RefreshTokenKey)
}

export function setRefreshToken(token) {
  return Cookies.set(RefreshTokenKey, token, COOKIE_OPTS)
}

export function removeRefreshToken() {
  return Cookies.remove(RefreshTokenKey, REMOVE_OPTS)
}

// 静默续期：用长效 refresh token 换新的短效 access token（双令牌机制）。
// 并发去重：同一时刻多个 401 只发一次续期请求，全部等待同一个结果。
let refreshingPromise = null

export function refreshAccessToken() {
  const rt = getRefreshToken()
  if (!rt) return Promise.resolve(null)
  if (!refreshingPromise) {
    refreshingPromise = fetch('/api/user/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    })
      .then((res) => res.json().catch(() => null))
      .then((body) => {
        if (body && body.code === 200 && body.data && body.data.token) {
          const data = body.data
          setToken(data.token)
          // J-17：服务端现在会**轮换** refresh token（旧的用完即失效），
          // 必须把新签发的那枚存下来，否则下一次续期会失败。
          if (data.refreshToken) {
            setRefreshToken(data.refreshToken)
          }
          // 同步业务视图层（demo store）的 token，后续请求立即使用新凭证
          syncLegacyAuth({ token: data.token, username: data.username || '', userId: data.userId })
          return data.token
        }
        // refresh token 无效/过期/账号被禁/已被登出拉黑：清掉，交给上层引导重新登录
        removeRefreshToken()
        return null
      })
      .catch(() => null)
      .finally(() => {
        refreshingPromise = null
      })
  }
  return refreshingPromise
}
