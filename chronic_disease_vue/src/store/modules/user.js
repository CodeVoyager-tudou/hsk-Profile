import { login, logout, getInfo } from '@/api/login'
import { getToken, setToken, removeToken, setRefreshToken, removeRefreshToken } from '@/utils/auth'
import { syncLegacyAuth, clearLegacyAuth } from '@/store/demo'

// 头像缺省图（内联 SVG，assets/images 目录下没有占位图片文件）
const DEFAULT_AVATAR
  = 'data:image/svg+xml;utf8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72">'
    + '<circle cx="36" cy="36" r="36" fill="#e8f7ee"/>'
    + '<circle cx="36" cy="28" r="12" fill="#2e9e63"/>'
    + '<path d="M12 64c3-13 12-20 24-20s21 7 24 20a36 36 0 0 1-48 0z" fill="#2e9e63"/>'
    + '</svg>')

const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    id: '',
    name: '',
    avatar: '',
    roles: [],
    permissions: []
  }),
  actions: {
    login(userInfo) {
      const username = userInfo.username.trim()
      const password = userInfo.password
      const code = userInfo.code
      const uuid = userInfo.uuid
      return new Promise((resolve, reject) => {
        login(username, password, code, uuid).then(res => {
          // 后端返回 Result<LoginVO>{code, message, data:{token, refreshToken, username, userId}}
          const data = (res && res.data) || {}
          setToken(data.token)
          this.token = data.token
          // 双令牌：长效 refresh token 存独立 cookie，供 401 时静默续期
          if (data.refreshToken) setRefreshToken(data.refreshToken)
          this.id = data.userId != null ? String(data.userId) : ''
          this.name = data.username || username
          // 业务视图层（store/demo.js）镜像登录态，AI/商城页面据此携带 token。
          // role 必须在这里带上（LoginVO 已签发）：否则 LS_ROLE 不落盘，
          // 冷加载 /admin 时 restoreAuth 恢复不出 ADMIN，页面守卫把管理员弹回首页（P1-3）
          syncLegacyAuth({ token: data.token, username: this.name, userId: data.userId, role: data.role })
          resolve(res)
        }).catch(error => {
          reject(error)
        })
      })
    },
    getInfo() {
      return new Promise((resolve, reject) => {
        getInfo().then(res => {
          // GET /api/user/info 返回 Result<{id, username, nickname}>（脱敏，无密码）。
          // 注意后端字段名是 **id** 而非 userId——此前只读 user.userId，取不到值时
          // this.id 保持空串，下方 syncLegacyAuth 里 Number('')||0 会把用户 ID 写成 0：
          // 刷新页面后余额查 /api/account/0（归属校验失败显示 ¥0.00）、
          // 订单列表查 user 0 失败回退 mock 演示数据。这里做 id/userId 双兼容。
          const data = (res && res.data) || {}
          const user = data.user || data
          const rawId = user.userId != null ? user.userId : user.id
          this.id = rawId != null ? String(rawId) : this.id
          this.name = user.username || user.userName || this.name
          this.avatar = user.avatar ? import.meta.env.VITE_APP_BASE_API + user.avatar : DEFAULT_AVATAR
          // 角色来自后端（RBAC：sys_user.role 签发进 JWT），getInfo 一并镜像给业务视图层；
          // 不传时 syncLegacyAuth 不覆盖，避免把已恢复的角色降级
          this.roles = user.role === 'ADMIN' ? ['ROLE_ADMIN'] : ['ROLE_DEFAULT']
          this.permissions = ['*:*:*']
          if (this.token) {
            syncLegacyAuth({ token: this.token, username: this.name, userId: this.id, role: user.role })
          }
          resolve(res)
        }).catch(error => {
          reject(error)
        })
      })
    },
    logOut() {
      return new Promise(resolve => {
        // 后端暂无 logout 端点：无论调用是否成功，本地一律清干净
        logout().catch(() => {})
        this.token = ''
        this.id = ''
        this.name = ''
        this.roles = []
        this.permissions = []
        removeToken()
        removeRefreshToken()
        clearLegacyAuth()
        resolve()
      })
    }
  }
})

export default useUserStore
