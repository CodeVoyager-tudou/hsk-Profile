/**
 * HTTP 请求层 —— 全站统一的 axios 封装。
 *
 * 【小白先看：什么是"拦截器"，为什么要它】
 *   如果不管，每个页面都得自己写这几件事：
 *     1. 请求前：把登录凭证（token）塞进请求头；
 *     2. 收到响应后：判断业务是成功还是失败；
 *     3. 遇到"登录过期"：自动续期或引导重新登录；
 *     4. 出错时：弹一个统一的错误提示。
 *   重复写 100 遍既啰嗦又容易漏。axios 的「拦截器」允许我们只写一次，
 *   之后所有走这个实例的请求/响应都会自动经过这里。
 *
 * 【两条 401 路径（这是本项目一个容易踩的坑，已在 FE-05 修复）】
 *   后端表达"你没登录/凭证过期"有两种方式：
 *     · 业务码 401 —— HTTP 状态是 200，但响应体里 code=401；
 *     · 真实 HTTP 401 —— 网关直接返回的状态码。
 *   两者必须走同一套处理逻辑。原先代码只处理了第一种，
 *   于是网关返回的真实 401 会掉进通用错误分支，
 *   用户看到的是一句没意义的「系统接口401异常」，而且永远不会触发自动续期。
 *
 * 【双令牌机制（为什么要两个 token）】
 *   access token  短命（很快过期）但要频繁使用；
 *   refresh token 长命但只在续期时用一次。
 *   这样即使 access token 泄露，危害窗口也很短；
 *   用户又不用每天重新登录（快过期时自动用 refresh 换新的）。
 *   本文件在收到 401 时会自动做这件事，用户无感知，所以叫「静默续期」。
 */
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getToken, refreshAccessToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams } from '@/utils/ruoyi'
import cache from '@/plugins/cache'
import useUserStore from '@/store/modules/user'

export let isRelogin = { show: false }

// 「登录过期」弹窗的去重门闩需要一个**兜底复位**。
// 否则一旦弹窗的 Promise 因组件卸载/路由跳转而永远不 settle，
// isRelogin.show 会永久停在 true，此后所有 401 都不再提示 ——
// 用户彻底失去「会话已过期」的通知（FE-05）。
let reloginResetTimer = null
const RELOGIN_RESET_MS = 10 * 1000

function resetReloginFlag() {
  isRelogin.show = false
  if (reloginResetTimer) {
    clearTimeout(reloginResetTimer)
    reloginResetTimer = null
  }
}

/** 弹出「登录已过期」确认框；已弹出时不重复弹 */
function promptRelogin() {
  if (isRelogin.show) return
  isRelogin.show = true
  reloginResetTimer = setTimeout(resetReloginFlag, RELOGIN_RESET_MS)
  ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '登录异常提示', {
    confirmButtonText: '重新登录',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    resetReloginFlag()
    useUserStore().logOut().then(() => {
      location.href = '/index'
    })
  }).catch(() => {
    resetReloginFlag()
  })
}

/**
 * 401 的统一处理（FE-05 修复）。
 *
 * 后端有两条路径会表达「未登录/凭证过期」：
 *   1. 业务码 401 —— HTTP 状态是 200，但响应体里 code=401（业务层判断）
 *   2. 真实 HTTP 401 —— 网关注入鉴权失败时直接返回的状态码
 * 这两者必须走同一套逻辑：先尝试用 refresh token 静默续期并重放原请求，
 * 续期失败再提示用户重新登录。
 *
 * 原实现只处理了第 1 种。网关返回的真实 HTTP 401 会掉进通用错误分支，
 * 用户看到的是一句毫无意义的「系统接口401异常」，而且**永远不会触发续期**。
 */
async function handleUnauthorized(config) {
  // 已经重放过一次就不再续期，避免无限递归
  if (config && !config.__isRetry) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      return service.request({
        ...config,
        headers: { ...config.headers, Authorization: 'Bearer ' + newToken },
        __isRetry: true
      })
    }
  }
  promptRelogin()
  return Promise.reject(new Error('登录状态已过期，请重新登录'))
}

// ===== 错误提示去重（FE-07）=====
// 页面一打开常常会**并发**发起好几个请求（查余额 + 查订单 + 查优惠券…）。
// 如果后端此时不可用，每个请求都会失败，于是同一条错误提示被弹 N 次，
// 屏幕上叠满一模一样的红条，真正有用的信息反而看不见。
//
// 这里做一个很轻量的去重：同一条文案在 1.5 秒内只提示一次。
// 注意只去重"短时间内的重复"，不同错误、或间隔较久的相同错误仍会正常提示，
// 不会把真实的新错误吞掉。
const MESSAGE_DEDUP_MS = 1500
const recentMessages = new Map()

function showError(message, type = 'error') {
  const key = type + '|' + message
  const now = Date.now()
  const last = recentMessages.get(key)
  if (last && now - last < MESSAGE_DEDUP_MS) return
  recentMessages.set(key, now)
  // 顺手清理过期项，避免这个 Map 无限增长
  if (recentMessages.size > 50) {
    for (const [k, t] of recentMessages) {
      if (now - t > MESSAGE_DEDUP_MS) recentMessages.delete(k)
    }
  }
  ElMessage({ message, type, duration: 5 * 1000 })
}

// axios 1.x 的全局默认头必须挂在 common 下（顶层直接赋值不生效）
axios.defaults.headers.common['Content-Type'] = 'application/json;charset=utf-8'

const service = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 30000
})

service.interceptors.request.use(config => {
  const isToken = (config.headers || {}).isToken === false
  if (getToken() && !isToken) {
    config.headers['Authorization'] = 'Bearer ' + getToken()
  }
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  return config
}, error => {
  console.log(error)
  return Promise.reject(error)
})

service.interceptors.response.use(async res => {
  // 显式判断类型，而不是写 `res.data.code || 200`：
  // 后者会把合法的 code=0 当成 falsy 替换成 200，从而把错误响应当成成功。
  const rawCode = res.data && res.data.code
  const code = typeof rawCode === 'number' ? rawCode : 200
  // 后端 Result 的字段是 message（而非若依默认的 msg），两者都兜底
  const msg = errorCode[code] || res.data.msg || res.data.message || errorCode['default']
  // 优先用 res.config.responseType：res.request 在某些适配器/拦截器场景下可能是 undefined，
  // 直接访问 res.request.responseType 会抛 TypeError。
  const responseType = (res.config && res.config.responseType) ||
    (res.request && res.request.responseType)
  if (responseType === 'blob' || responseType === 'arraybuffer') {
    return res.data
  }
  if (code === 401) {
    // 业务码 401：走统一的续期/重登逻辑
    return handleUnauthorized(res.config)
  } else if (code === 500) {
    showError(msg, 'error')
    return Promise.reject(new Error(msg))
  } else if (code === 601) {
    showError(msg, 'warning')
    return Promise.reject(new Error(msg))
  } else if (code !== 200) {
    showError(msg, 'error')
    return Promise.reject('error')
  } else {
    return Promise.resolve(res.data)
  }
}, error => {
  // 真实 HTTP 401（网关鉴权失败）：必须与业务码 401 同等对待（FE-05）
  const status = error.response && error.response.status
  if (status === 401) {
    return handleUnauthorized(error.config)
  }
  console.log('err' + error)
  let { message } = error
  if (message === 'Network Error') {
    message = '后端接口连接异常'
  } else if (message.includes('timeout')) {
    message = '接口请求超时'
  } else if (message.includes('Request failed with status code')) {
    message = '系统接口' + message.substr(message.length - 3) + '异常'
  }
  // 用去重版提示：并发失败时不会把同一条消息弹很多遍
  showError(message, 'error')
  return Promise.reject(error)
})

export default service
