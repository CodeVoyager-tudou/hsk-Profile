import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import useUserStore from '@/store/modules/user'
import useSettingsStore from '@/store/modules/settings'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login', '/register']

router.beforeEach((to, from, next) => {
  NProgress.start()
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title)
    if (to.path === '/login') {
      next({ path: '/' })
      NProgress.done()
    } else if (whiteList.indexOf(to.path) !== -1) {
      next()
    } else {
      if (useUserStore().roles.length === 0) {
        // FE-05 修复：这里**不要**再去置 isRelogin.show = true。
        //
        // 原实现在调用 getInfo() 之前就把该标记置为 true，用意是"防止重复弹窗"，
        // 但副作用是：当 getInfo() 因凭证过期而返回 401 时，
        // request.js 里的判断 `if (!isRelogin.show)` 会因为已被置位而**跳过整个提示框**，
        // 用户被静默跳转到登录页，完全不知道发生了什么。
        // 该状态应由 request.js 独占管理（它带去重与超时兜底复位）。
        useUserStore().getInfo().then(() => {
          next({ ...to, replace: true })
        }).catch(err => {
          useUserStore().logOut().then(() => {
            ElMessage.error(err?.msg || err?.message || String(err))
            next({ path: '/login' })
          })
        })
      } else {
        next()
      }
    }
  } else {
    if (whiteList.indexOf(to.path) !== -1) {
      next()
    } else {
      next(`/login?redirect=${to.fullPath}`)
      NProgress.done()
    }
  }
})

router.afterEach(() => {
  NProgress.done()
})
