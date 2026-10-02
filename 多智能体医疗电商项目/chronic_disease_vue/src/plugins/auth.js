import useUserStore from '@/store/modules/user'

// 权限校验：读 Pinia user store（登录后内存中的 roles/permissions）。
//
// FE-09 修复：原先在 store 不可用时会**回退去读 sessionStorage**。
// 这是一个实打实的权限提升漏洞 —— sessionStorage 的内容由页面脚本任意可写，
// 任何人打开浏览器控制台执行一句
//     sessionStorage.setItem('roles', '["admin"]')
// 就能让下面所有权限判断全部通过。
//
// 现在改成「拿不到可信身份就不给任何权限」（fail-closed，失败时拒绝而非放行）。
//
// 另需注意：前端权限指令**只用于控制界面元素的显示**，
// 真正的授权必须由后端做（当前后端尚无细粒度权限体系，属已知缺口）。
function userState() {
  try {
    const s = useUserStore()
    return { roles: s.roles || [], permissions: s.permissions || [] }
  } catch (e) {
    // 不授予任何权限；绝不从用户可写的地方（sessionStorage/localStorage）读取权限
    return { roles: [], permissions: [] }
  }
}

const auth = {
  hasPermiOr(permissions) {
    const { roles, permissions: userPerms } = userState()
    if (roles.includes('admin')) return true
    return permissions.some(permission => {
      return userPerms.includes(permission) || userPerms.includes('*:*:*')
    })
  },
  hasPermiAnd(permissions) {
    const { roles, permissions: userPerms } = userState()
    if (roles.includes('admin')) return true
    return permissions.every(permission => {
      return userPerms.includes(permission) || userPerms.includes('*:*:*')
    })
  },
  hasRoleOr(roles) {
    const super_admin = 'admin'
    const { roles: userRoles } = userState()
    if (userRoles.includes(super_admin)) return true
    return roles.some(role => {
      return userRoles.includes(role)
    })
  },
  hasRoleAnd(roles) {
    const super_admin = 'admin'
    const { roles: userRoles } = userState()
    if (userRoles.includes(super_admin)) return true
    return roles.every(role => {
      return userRoles.includes(role)
    })
  }
}

export default auth
