import request from '@/utils/request'
import { getRefreshToken } from '@/utils/auth'

export function login(username, password, code, uuid) {
  const data = { username, password, code, uuid }
  return request({
    url: '/api/user/login',
    headers: { isToken: false },
    method: 'post',
    data
  })
}

export function register(data) {
  return request({
    url: '/api/user/register',
    headers: { isToken: false },
    method: 'post',
    data
  })
}

export function getInfo() {
  return request({
    url: '/api/user/info',
    method: 'get'
  })
}

export function logout() {
  // J-17 修复：登出时把 refresh token 一并交给后端拉黑。
  // 原先只发空请求，后端只能拉黑请求头里的 access token，
  // 于是 refresh token 在登出后依然有效（7 天），能被用来换取新的 access token
  // —— 等于「登出」并没有真正结束会话。
  return request({
    url: '/api/user/logout',
    // 登出本身不需要携带鉴权头（FE-05）：
    // 原实现会带上 Authorization，若此时 access token 恰好已过期，
    // 网关返回 401 -> 触发一次毫无意义的「刷新令牌」请求，
    // 而刷新通常也会失败，等于每次登出都白白多打一次后端。
    headers: { isToken: false },
    method: 'post',
    data: { refreshToken: getRefreshToken() || null }
  })
}
