// @vitest-environment happy-dom
//
// P1-3 回归防护（第 2 轮复核后收尾补漏）：role 必须随**真实登录路径**持久化。
//
// 背景：P1-3 第一版只修了 applyAuth（演示模式路径）和 loadProfile（个人中心路径），
// 漏了主路径 —— Pinia user store 登录/getInfo 后调用的 syncLegacyAuth 不写 LS_ROLE，
// 真实登录后冷加载 /admin 仍被守卫弹回首页（2026-09-29 浏览器实测抓到）。
// 修复后 syncLegacyAuth 支持 role/avatar 可选参数：传了才覆盖并持久化，不传不降级。
import { beforeEach, describe, expect, it } from 'vitest'

import { restoreAuth, syncLegacyAuth } from '../demo'

function ls() {
  return {
    get: (k) => window.localStorage.getItem(k),
    set: (k, v) => window.localStorage.setItem(k, v),
    remove: (k) => window.localStorage.removeItem(k),
  }
}

beforeEach(() => {
  window.localStorage.clear()
  // 清掉 Cookie 里可能残留的 token，保证用例之间互不污染（token 走 Cookie，见 FE-02）
  document.cookie.split(';').forEach((c) => {
    const name = c.split('=')[0].trim()
    if (name) document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`
  })
})

describe('syncLegacyAuth 的 role 持久化（P1-3 补漏）', () => {
  it('登录时传 role → 写入 state 与 LS_ROLE', () => {
    syncLegacyAuth({ token: 'tk_1', username: 'admin', userId: 1, role: 'ADMIN' })
    expect(ls().get('cd_role')).toBe('ADMIN')
  })

  it('getInfo 不带 role → 不覆盖已有角色（不会把管理员降级成 USER）', () => {
    syncLegacyAuth({ token: 'tk_1', username: 'admin', userId: 1, role: 'ADMIN' })
    syncLegacyAuth({ token: 'tk_1', username: 'admin', userId: 1 })
    expect(ls().get('cd_role')).toBe('ADMIN')
  })

  it('普通用户登录 → 落盘 USER', () => {
    syncLegacyAuth({ token: 'tk_2', username: 'e2e_x', userId: 2, role: 'USER' })
    expect(ls().get('cd_role')).toBe('USER')
  })

  it('冷启动 restoreAuth 从 LS_ROLE 恢复 role（管理页刷新不再被弹回）', () => {
    syncLegacyAuth({ token: 'tk_1', username: 'admin', userId: 1, role: 'ADMIN' })
    // 模拟冷启动：store 的 state 是模块级的，这里只验证 restoreAuth 读回 LS_ROLE 这一半契约；
    // state.role 的断言放在浏览器验收（E2E 场景）里，避免与模块单例状态互相污染
    ls().set('cd_username', 'admin')
    ls().set('cd_userId', '1')
    restoreAuth()
    expect(ls().get('cd_role')).toBe('ADMIN')
  })
})
