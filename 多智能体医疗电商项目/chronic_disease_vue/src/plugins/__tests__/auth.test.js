// plugins/auth.js 的权限判断测试。
//
// 【FE-09 修复说明】
//   原实现有一个权限提升漏洞：当 Pinia store 不可用时会**回退去读 sessionStorage**。
//   sessionStorage 由页面脚本任意可写，因此任何人打开控制台执行
//       sessionStorage.setItem('roles', '["admin"]')
//   就能让自己通过所有前端权限判断。
//
//   修复后：权限**只**来自 Pinia store；拿不到就视为无权限（fail-closed）。
//   因此本测试改为通过 Pinia 设置角色/权限，并新增一组用例锁定
//   「sessionStorage 不再能提权」这一行为。
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import auth from '../auth.js'
import useUserStore from '@/store/modules/user'

/** 每个用例前重置 Pinia，保证用例之间互不影响 */
function resetStore() {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.roles = []
  store.permissions = []
  return store
}

describe('plugins/auth.js', () => {
  beforeEach(() => {
    sessionStorage.clear()
    resetStore()
  })

  describe('hasPermiOr', () => {
    it('should return true for admin role', () => {
      useUserStore().roles = ['admin']
      expect(auth.hasPermiOr(['system:user:list'])).toBe(true)
    })

    it('should return true if user has one of the permissions', () => {
      useUserStore().permissions = ['system:user:list', 'system:role:list']
      expect(auth.hasPermiOr(['system:user:list', 'system:dept:list'])).toBe(true)
    })

    it('should return true for wildcard permission', () => {
      useUserStore().permissions = ['*:*:*']
      expect(auth.hasPermiOr(['system:user:list'])).toBe(true)
    })

    it('should return false if user has none of the permissions', () => {
      useUserStore().permissions = ['system:dept:list']
      expect(auth.hasPermiOr(['system:user:list', 'system:role:list'])).toBe(false)
    })

    it('should return false for empty permissions', () => {
      useUserStore().permissions = []
      expect(auth.hasPermiOr(['system:user:list'])).toBe(false)
    })
  })

  describe('hasPermiAnd', () => {
    it('should return true for admin role', () => {
      useUserStore().roles = ['admin']
      expect(auth.hasPermiAnd(['system:user:list'])).toBe(true)
    })

    it('should return true if user has all permissions', () => {
      useUserStore().permissions = ['a', 'b', 'c']
      expect(auth.hasPermiAnd(['a', 'b'])).toBe(true)
    })

    it('should return true for wildcard permission', () => {
      useUserStore().permissions = ['*:*:*']
      expect(auth.hasPermiAnd(['a', 'b'])).toBe(true)
    })

    it('should return false if user lacks any permission', () => {
      useUserStore().permissions = ['a']
      expect(auth.hasPermiAnd(['a', 'b'])).toBe(false)
    })
  })

  describe('hasRoleOr', () => {
    it('should return true for admin', () => {
      useUserStore().roles = ['admin']
      expect(auth.hasRoleOr(['editor'])).toBe(true)
    })

    it('should return true if user has one of the roles', () => {
      useUserStore().roles = ['editor']
      expect(auth.hasRoleOr(['admin', 'editor'])).toBe(true)
    })

    it('should return false if user has none of the roles', () => {
      useUserStore().roles = ['viewer']
      expect(auth.hasRoleOr(['admin', 'editor'])).toBe(false)
    })
  })

  describe('hasRoleAnd', () => {
    it('should return true for admin', () => {
      useUserStore().roles = ['admin']
      expect(auth.hasRoleAnd(['editor'])).toBe(true)
    })

    it('should return false if user lacks any role', () => {
      useUserStore().roles = ['editor']
      expect(auth.hasRoleAnd(['admin', 'editor'])).toBe(false)
    })
  })

  // ===== FE-09 回归防护：sessionStorage 不得影响权限判断 =====
  describe('安全回归：sessionStorage 不能用来提权（FE-09）', () => {
    it('写入 sessionStorage 的 admin 角色不再生效', () => {
      // 模拟攻击者（或 XSS）在控制台伪造权限
      sessionStorage.setItem('roles', JSON.stringify(['admin']))
      sessionStorage.setItem('permissions', JSON.stringify(['*:*:*']))

      // 真实的 Pinia store 里什么都没有 -> 必须一律拒绝
      expect(auth.hasRoleOr(['editor'])).toBe(false)
      expect(auth.hasRoleAnd(['editor'])).toBe(false)
      expect(auth.hasPermiOr(['system:user:list'])).toBe(false)
      expect(auth.hasPermiAnd(['system:user:list'])).toBe(false)
    })

    it('store 中无权限时，即便 sessionStorage 有权限也拒绝', () => {
      useUserStore().roles = ['ROLE_DEFAULT']
      useUserStore().permissions = []
      sessionStorage.setItem('permissions', JSON.stringify(['*:*:*']))

      expect(auth.hasPermiOr(['system:user:list'])).toBe(false)
    })

    it('store 中确有权限时正常放行（未过度修复）', () => {
      useUserStore().permissions = ['system:user:list']
      expect(auth.hasPermiOr(['system:user:list'])).toBe(true)
    })
  })
})
