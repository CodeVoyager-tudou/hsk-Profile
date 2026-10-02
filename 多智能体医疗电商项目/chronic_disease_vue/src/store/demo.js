import { reactive, computed } from 'vue'
import { MOCK } from '../utils/mock'
import { DEMO_ENABLED, DEMO_DISABLED_MESSAGE } from '../utils/demoMode'
import { refreshAccessToken, getToken, setToken, removeToken } from '../utils/auth'

// ============================================================================
// 【小白先看：这个文件是什么】
//
// 它是前端各页面共用的「数据中枢」。
//
// 1) 什么叫「响应式状态」？
//    下面的 state 对象里存着全站数据：药品列表、我的订单、积分、当前登录用户……
//    因为 Vue 的 reactive() 把它变成了"响应式"的，
//    所以代码里改一下 state.points.total，所有显示积分的地方会**自动刷新**，
//    不需要手动去更新界面。这就是前端框架最大的便利。
//
// 2) 什么叫「双轨」？（本项目一个特殊设计，也是历史包袱）
//    登录逻辑其实有两个地方在管：
//      · Pinia 的 user store  —— 官方推荐的写法，存 token/角色/权限
//      · 本文件（demo store）—— 商城/AI 等业务页面读的是这里的 state.token
//    两者靠 syncLegacyAuth() 单向同步。之所以有两套，是因为这个项目
//    早期用 fetch 手写了一套离线演示逻辑，后来接真实后端时保留了它。
//    好处：后端没启动时也能演示（自动降级到假数据）。
//    坏处：一旦两者不同步，就会出现"明明登录了却提示请登录"这类怪问题
//          （这就是审计里的 FE-03，已在本次修复）。
//
// 3) 那个「演示模式」开关是干嘛的？
//    见 utils/demoMode.js。简单说：VITE_ENABLE_DEMO=true 时后端不可达会走假数据，
//    生产构建为 false，此时必须如实报错 —— 绝不能把失败伪装成"下单成功"，
//    否则用户以为买到了、实际什么都没发生（这就是 FE-04）。
// ============================================================================
//
// 业务视图共享的轻量数据层（单例响应式状态）。
// 注意：本文件原为 src/store.js，因遮蔽 Pinia 的 store/index.js（Vite 解析文件优先于目录）
// 导致 main.js 默认导入失败，已迁移到 store/demo.js；视图统一从 '@/store/demo' 导入。
// 鉴权真源在 Pinia user store（登录/登出），本模块通过 syncLegacyAuth/clearLegacyAuth 镜像，
// 视图层（chat.vue/shop/user 页面）读取这里的 token/userId 即与登录态一致。

// 开发环境走 Vite 代理 /dev-api → http://localhost:8080
const API_BASE = import.meta.env.VITE_APP_BASE_API || '/dev-api'

// 演示模式下「签到」发放的积分值。
// 抽成常量是因为原先硬编码 15（散落在 toast 文案、余额累加、流水记录三处），
// 一旦要调整就得改三个地方，很容易改漏导致显示不一致。
const DEMO_SIGN_IN_POINTS = 15

// 后端是否可用（首次失败后切换 mock，避免每次请求都卡顿）
let MOCK_MODE = false
// ===== 本地鉴权存储键 =====
// 注意（FE-02）：token 的**唯一存储位置是 Cookie**（见 utils/auth.js），
// 这里不再把 token 同时镜像进 localStorage —— 双重存储会让 XSS 一次拿到两份凭证。
// localStorage 只保留用户名/用户ID 这类非敏感信息，用于刷新页面后回显。
const LS_USERS = 'cd_users'       // 演示模式的本地用户表（口令不落明文，见 digestPassword）
const LS_USERNAME = 'cd_username'
const LS_USERID = 'cd_userId'
const LS_ROLE = 'cd_role'         // 前端导航用的角色标记（仅控制入口可见性，授权一律在后端）
const LS_AVATAR = 'cd_avatar'
const LS_REMEMBER = 'cd_remember'
// 历史遗留：早期版本把 token 也写进 localStorage 的 'cd_token'。
// 现在仅用于「读取并迁移」一次，之后删除，不再写入。
const LEGACY_LS_TOKEN = 'cd_token'

// 默认演示账号。
// 口令以摘要形式存放，**不再是明文**：原实现把 '123456'/'admin' 明文写进 localStorage，
// 任何 XSS 或打开开发者工具的人都能直接读到。这里存 FNV-1a 摘要（见 digestPassword）。
// 说明：前端摘要是「不落明文」的降级措施，不是密码学安全的哈希（无密钥、可离线爆破）；
// 真正的防线是这套本地账号体系**只在演示模式生效**，生产环境走真实后端鉴权。
const DEFAULT_USERS = [
  { username: 'demo', password: digestPassword('123456', 'demo'), userId: 1 },
  { username: 'admin', password: digestPassword('admin', 'admin'), userId: 2 },
]

/**
 * 口令摘要（仅用于避免明文落盘）。
 * FNV-1a 32 位变体 + 每个账号独立的 salt，输出带 'h' 前缀以便与历史明文数据区分。
 */
function digestPassword(password, salt) {
  let h = 0x811c9dc5
  const input = String(salt) + '\u0000' + String(password)
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return 'h' + h.toString(16).padStart(8, '0')
}

/**
 * 校验口令，兼容历史明文数据。
 * 返回值：'match' | 'mismatch' | 'needUpgrade'（旧明文匹配成功，应升级为摘要）
 */
function verifyPassword(stored, input, salt) {
  const s = String(stored == null ? '' : stored)
  // 旧数据是明文：直接比较，并提示调用方升级
  if (!s.startsWith('h')) {
    return s === String(input) ? 'needUpgrade' : 'mismatch'
  }
  return s === digestPassword(input, salt) ? 'match' : 'mismatch'
}

function loadUsers() {
  try {
    const raw = localStorage.getItem(LS_USERS)
    if (raw) return JSON.parse(raw)
  } catch (_) {}
  // 注意：**不再**在读取时就把默认账号写进 localStorage。
  // 原实现只要读过一次就把明文口令持久化下来，属于"隐式落盘"。
  // 现在仅返回内存副本；只有真正发生演示注册时才会写入（且写摘要）。
  return DEFAULT_USERS.map((u) => ({ ...u }))
}
function saveUsers(users) {
  localStorage.setItem(LS_USERS, JSON.stringify(users))
}

// ===== 单例响应式状态 =====
const state = reactive({
  page: 'home',            // 当前页面：home/detail/mine/orders/coupons/records/signin/login
  userId: 1,
  username: '',
  role: 'USER',            // 角色：USER-普通用户 / ADMIN-管理员（登录时由后端签发进 JWT）
  avatar: '',              // 头像 URL（阿里云 OSS）；空 = 用占位图标
  token: '',
  isLogin: false,
  loading: false,

  // 商城
  medicines: [],
  medPage: 1,
  totalPages: 1,
  keyword: '',
  category: '',
  categories: ['慢病用药', '解热镇痛', '抗感染'],

  // 详情
  currentMed: null,
  detailQty: 1,
  detailCoupons: [],
  detailCoupon: '',

  // 优惠券
  activities: [],
  myCoupons: [],
  couponTab: 'act',

  // 订单
  orders: [],

  // 签到 / 记录
  weekData: null,
  records: [],
  points: { total: 0, used: 0 },

  // 余额账户（阿里云 OSS 之外的另一项资产能力：余额支付）
  balance: 0,             // 账户余额（元）
  // 是否处于演示兜底态（后端不可达后切换 mock）。置位后订单/余额等展示的是演示数据，
  // 页面据此显示「演示」标记，避免用户把 mock 卡片当成真实订单去操作
  isMock: false,
  accountRecords: [],     // 余额流水
  payMethod: 'BALANCE',   // 详情页支付方式：BALANCE-余额支付 / POINTS-积分支付（现金通道仅秒杀单保留）

  // 购物车（后端持久化：cart_item 表，一用户一药品一行）
  cartItems: [],          // 后端返回的 CartItemVO 列表（含现价/库存/invalid 标记）
  cartLoading: false,

  // 秒杀（Redis+Lua 预扣 + MQ 排队落库）
  seckillActivities: [],

  // toast
  toastMsg: '',
  toastVisible: false,
})

// Restore login state from localStorage (called on app mount)
export function restoreAuth() {
  try {
    // FE-03 修复：token 以 **Cookie** 为准，localStorage 仅作历史数据兜底。
    //
    // 原实现只读 localStorage：一旦 localStorage 被清空（用户手动清理 / 隐私模式 /
    // 另一个标签页执行了登出），而 Cookie 里的 token 还在，业务视图的 state.token
    // 就会是空字符串 —— 后续请求不带 Authorization 头，变成匿名请求。
    // 表现就是「明明还登录着，却被提示请先登录」。
    const username = localStorage.getItem(LS_USERNAME) || ''
    const userId = localStorage.getItem(LS_USERID) || ''
    const token = getToken() || localStorage.getItem(LEGACY_LS_TOKEN) || ''
    if (token) {
      state.token = token
      state.username = username
      state.userId = Number(userId) || 1
      state.isLogin = true
      // role/avatar 与 username/userId 同等对待（复核 P1-3）：不持久化的话，
      // 冷启动进 /admin/* 时 role 还是默认 'USER'，守卫会把已登录的管理员弹回首页。
      // 注意：这里恢复的 role 纯粹是「导航可用性」开关 —— 授权只认网关 JWT 里的
      // role claims（伪造的 X-User-Role 会被网关剥离），别把它当成权限控制。
      state.role = localStorage.getItem(LS_ROLE) || 'USER'
      state.avatar = localStorage.getItem(LS_AVATAR) || ''
      // 一次性迁移：把历史遗留在 localStorage 的 token 移到 Cookie，然后删掉那份副本。
      // 这样存储位置从「Cookie + localStorage 两份」收敛为「只有 Cookie 一份」（FE-02）。
      if (!getToken()) setToken(token)
      localStorage.removeItem(LEGACY_LS_TOKEN)
    }
  } catch (_) {}
}

// 由 Pinia user store 在登录/getInfo 成功后调用：把真实登录态镜像给业务视图层。
//
// role/avatar 是可选参数：**传了才覆盖并持久化**（复核 P1-3 补漏——最初只镜像
// token/username/userId，真实登录路径上 LS_ROLE 永远不落盘，冷加载 /admin 仍被弹回首页；
// applyAuth 只覆盖演示模式路径、loadProfile 只覆盖个人中心路径，唯独这条主路径漏了）。
// 不传时不覆盖：即使后端某天不再返回 role，也不会把已恢复的管理员降级成 USER。
export function syncLegacyAuth({ token, username, userId, role, avatar }) {
  if (!token) return
  state.token = token
  state.username = username || ''
  state.userId = Number(userId) || 0
  state.isLogin = true
  // token 只写 Cookie（不再同时写 localStorage，避免 XSS 一次拿到两份凭证）
  setToken(token)
  localStorage.setItem(LS_USERNAME, username || '')
  localStorage.setItem(LS_USERID, String(state.userId))
  if (role) {
    state.role = role
    localStorage.setItem(LS_ROLE, role)
  }
  if (avatar) {
    state.avatar = avatar
    localStorage.setItem(LS_AVATAR, avatar)
  }
}

// 由 Pinia user store 在登出时调用
export function clearLegacyAuth() {
  state.token = ''
  state.username = ''
  state.isLogin = false
  state.role = 'USER'
  state.avatar = ''
  removeToken()
  localStorage.removeItem(LEGACY_LS_TOKEN)
  localStorage.removeItem(LS_USERNAME)
  localStorage.removeItem(LS_USERID)
  localStorage.removeItem(LS_ROLE)
  localStorage.removeItem(LS_AVATAR)
}

let toastTimer = null
function toast(msg) {
  state.toastMsg = msg
  state.toastVisible = true
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (state.toastVisible = false), 2500)
}

const balanceText = computed(() => {
  if (state.points.total === 0 && state.points.used === 0) return '-'
  return String(state.points.total - state.points.used)
})

// ===== 鉴权动作 =====
// 真实后端优先：登录/注册走 Java user-service /api/user/*，拿到 JWT 后 AI 问诊、
// 会话管理、商城等经网关鉴权才能正常工作。后端不可达时降级为本地演示登录（不联网）。
async function authRequest(path, body) {
  const sep = path.includes('?') ? '&' : '?'
  const res = await fetch(API_BASE + path + sep + '_t=' + Date.now(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  // 网关/代理 5xx 说明后端不可达：返回 null，交由上层降级（不误判为业务失败）
  if (res.status >= 500) return null
  return res.json().catch(() => null)
}
function applyAuth(token, username, userId, role) {
  state.token = token
  state.username = username
  state.userId = Number(userId) || 0
  state.role = role || 'USER'
  state.isLogin = !!token
  // token 只写 Cookie（单一存储位置，见 FE-02）
  setToken(token)
  localStorage.setItem(LS_USERNAME, username)
  localStorage.setItem(LS_USERID, String(state.userId))
  // role 随登录持久化（复核 P1-3）：冷启动 restoreAuth 读回，管理员刷新/重开不再被弹回首页
  localStorage.setItem(LS_ROLE, state.role)
}
function genMockToken() {
  try {
    if (crypto && crypto.getRandomValues) {
      return Array.from(crypto.getRandomValues(new Uint8Array(16)),
        (b) => b.toString(16).padStart(2, '0')).join('')
    }
  } catch (_) {}
  return Date.now().toString(36)
}

async function login(username, password, remember) {
  if (!username || !password) return { ok: false, msg: '请输入用户名和密码' }
  // 1) 尝试真实后端登录（拿到 JWT）
  try {
    const body = await authRequest('/api/user/login', { username, password })
    // 后端业务响应（code 存在）：成功或业务失败，都如实反馈，不降级演示
    if (body && body.code !== undefined) {
      if (body.code === 200 && body.data && body.data.token) {
        applyAuth(body.data.token, body.data.username || username, body.data.userId, body.data.role)
        if (remember) localStorage.setItem(LS_REMEMBER, '1')
        else localStorage.removeItem(LS_REMEMBER)
        return { ok: true }
      }
      return { ok: false, msg: body.message || '用户名或密码错误' }
    }
    throw new Error('backend unreachable')
  } catch (e) {
    // 2) 后端不可达。
    // 生产环境**绝不**静默降级为「本地演示登录」：那会用一个前端自己伪造的 token
    // 让界面显示"已登录"，而所有真实请求都会被网关拒绝 —— 用户被假象误导（FE-02/FE-03）。
    if (!DEMO_ENABLED) {
      return { ok: false, msg: DEMO_DISABLED_MESSAGE }
    }
    // 演示模式：用本地账号表校验（口令存的是摘要，不是明文）
    const users = loadUsers()
    const u = users.find((x) => x.username === username)
    if (!u) return { ok: false, msg: '用户名或密码错误' }
    const verdict = verifyPassword(u.password, password, u.username)
    if (verdict === 'mismatch') return { ok: false, msg: '用户名或密码错误' }
    if (verdict === 'needUpgrade') {
      // 历史遗留的明文口令：本次登录成功后升级为摘要，不再继续以明文留存
      u.password = digestPassword(password, u.username)
      saveUsers(users)
    }
    // 演示登录的角色与真实后端一致：admin 账号即管理员（本地账号表无角色列，按用户名约定）
    applyAuth('tk_' + genMockToken(), u.username, u.userId, u.username === 'admin' ? 'ADMIN' : 'USER')
    if (remember) localStorage.setItem(LS_REMEMBER, '1')
    else localStorage.removeItem(LS_REMEMBER)
    toast('后端未连接，已切换演示登录')
    return { ok: true, mock: true }
  }
}

async function register(username, password) {
  if (!username || username.length < 2) return { ok: false, msg: '用户名至少 2 个字符' }
  if (!password || password.length < 4) return { ok: false, msg: '密码至少 4 个字符' }
  // 1) 尝试真实后端注册 + 自动登录拿 JWT
  try {
    const body = await authRequest('/api/user/register', { username, password, nickname: username })
    if (body && body.code !== undefined) {
      if (body.code === 200) {
        const loginBody = await authRequest('/api/user/login', { username, password })
        if (loginBody && loginBody.code === 200 && loginBody.data && loginBody.data.token) {
          applyAuth(loginBody.data.token, loginBody.data.username || username, loginBody.data.userId)
        }
        return { ok: true }
      }
      return { ok: false, msg: body.message || '注册失败' }
    }
    throw new Error('backend unreachable')
  } catch (e) {
    // 2) 后端不可达。生产环境不提供「本地注册」——本地账号只在演示时有效，
    // 真实系统里这样的账号根本无法通过网关鉴权（FE-02/FE-03）。
    if (!DEMO_ENABLED) {
      return { ok: false, msg: DEMO_DISABLED_MESSAGE }
    }
    // 演示模式：本地注册（口令存摘要，不落明文）
    const users = loadUsers()
    if (users.some((u) => u.username === username)) return { ok: false, msg: '用户名已被占用' }
    const newUserId = users.reduce((m, u) => Math.max(m, u.userId), 0) + 1
    const newUser = { username, password: digestPassword(password, username), userId: newUserId }
    users.push(newUser)
    saveUsers(users)
    applyAuth('tk_' + genMockToken(), newUser.username, newUser.userId)
    toast('后端未连接，已切换演示注册')
    return { ok: true, mock: true }
  }
}

function logout() {
  state.token = ''
  state.username = ''
  state.isLogin = false
  state.role = 'USER'
  state.avatar = ''
  removeToken()
  localStorage.removeItem(LEGACY_LS_TOKEN)
  localStorage.removeItem(LS_USERNAME)
  localStorage.removeItem(LS_USERID)
  localStorage.removeItem(LS_ROLE)
  localStorage.removeItem(LS_AVATAR)
  // 不清除 LS_REMEMBER，下次登录页可自动回显记住状态
}

// ===== API =====
async function api(path, options = {}, _retried = false) {
  if (MOCK_MODE) throw new Error('MOCK')
  const sep = path.includes('?') ? '&' : '?'
  // 允许调用方通过 options.headers 附加自定义请求头（例如下单幂等键 X-Request-Id）。
  // 注意：这里必须先展开 options.headers，否则下面的 headers 会把它整个覆盖掉，
  // 导致调用方传的头静默丢失（这正是此前幂等键从未真正发出的原因）。
  // FormData（头像等图片上传）不能手动指定 Content-Type：
  // 必须让浏览器自动生成带 boundary 的 multipart/form-data 头，否则后端解析不了文件。
  const isFormData = options.body instanceof FormData
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(options.headers || {}),
  }
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token
  const res = await fetch(API_BASE + path + sep + '_t=' + Date.now(), { ...options, headers })
  let body
  try {
    body = await res.json()
  } catch {
    body = { code: res.status, message: 'HTTP ' + res.status }
  }
  // Spring Boot 默认错误体 -> 统一格式
  // {timestamp, status, error, message?, path}
  if (body && typeof body.status === 'number' && typeof body.code !== 'number') {
    body = {
      code: body.status,
      message: body.message || body.error || ('HTTP ' + body.status),
      data: null
    }
  }
  // 双令牌：access 过期时静默续期并重试一次
  if (body && body.code === 401 && !_retried) {
    const newToken = await refreshAccessToken()
    if (newToken) return api(path, options, true)
  }
  return body
}
function ok(body) {
  return body && body.code === 200
}

// ===== 演示兜底闸门（FE-04 修复）=====
// 后端不可达时「假装成功」只允许发生在演示模式（VITE_ENABLE_DEMO=true）。
// 生产构建下必须如实报错：否则用户以为下单/签到/领券成功了，实际后端什么都没发生，
// 而用户会离开页面、再也无法核对——这是最容易被忽略却危害最大的那类 bug。
// 返回值：true = 处于演示模式，调用方可以执行本地假数据逻辑。
function demoFallbackAllowed() {
  if (!DEMO_ENABLED) {
    toast(DEMO_DISABLED_MESSAGE)
    return false
  }
  return true
}

// 生成下单幂等键（J-02 前端部分）。
// 作用：把「同一用户 + 同一 requestId」视作同一笔下单，后端唯一键会挡住重复插入。
// 用户双击「立即购买」、网络超时后自动重试时，都不会重复扣库存或重复生成订单。
function newRequestId() {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID()
    }
  } catch (e) {
    // 非安全上下文（例如 http 访问）下 crypto.randomUUID 可能不可用，走下面的兜底
  }
  return 'req-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

// ===== 加载函数 =====
async function searchMedicines(p) {
  if (p < 1) return
  state.medPage = p
  state.loading = true
  try {
    const body = await api(`/api/shop/medicine/page?pageNum=${state.medPage}&pageSize=10&keyword=${encodeURIComponent(state.keyword.trim())}&category=${encodeURIComponent(state.category)}`)
    if (ok(body)) {
      state.medicines = body.data.records
      state.totalPages = body.data.pages
    } else {
      throw new Error(body.message)
    }
  } catch (e) {
    if (!DEMO_ENABLED) {
      // 生产：不伪装成演示数据，如实报错（避免真实故障被 mock 掩盖）
      toast(DEMO_DISABLED_MESSAGE)
      throw e
    }
    MOCK_MODE = true
    state.isMock = true
    mockMedicines()
    toast('后端未连接，已切换演示数据（刷新页面可恢复）')
  } finally {
    state.loading = false
  }
}

function mockMedicines() {
  let list = MOCK.medicines.slice()
  const kw = state.keyword.trim()
  const cat = state.category
  if (kw) list = list.filter((m) => (m.name + m.indication).includes(kw))
  if (cat) list = list.filter((m) => m.category === cat)
  const pageSize = 10
  state.totalPages = Math.max(1, Math.ceil(list.length / pageSize))
  const start = (state.medPage - 1) * pageSize
  state.medicines = list.slice(start, start + pageSize)
}

async function loadMyCouponsForDetail() {
  try {
    const body = await api(`/api/shop/coupon/my/${state.userId}?status=UNUSED`)
    state.detailCoupons = ok(body) ? body.data.filter((c) => c.status === 'UNUSED') : []
  } catch (e) {
    state.detailCoupons = MOCK.myCoupons.filter((c) => c.status === 'UNUSED')
  }
}

async function loadBalance() {
  try {
    const body = await api(`/api/points/${state.userId}`)
    if (ok(body)) {
      state.points.total = body.data.totalPoints
      state.points.used = body.data.usedPoints
    } else {
      throw new Error()
    }
  } catch (e) {
    // 仅在首次加载（积分未初始化）时回退到 MOCK，避免重新进入个人中心时
    // 把兑换/下单已扣减的 usedPoints 重置为 MOCK 初始值导致积分"未扣减"
    if (state.points.total === 0 && state.points.used === 0) {
      state.points.total = MOCK.points.total
      state.points.used = MOCK.points.used
    }
  }
}

async function loadActivities() {
  try {
    const body = await api('/api/shop/coupon/activity')
    if (ok(body)) state.activities = body.data
    else throw new Error()
  } catch (e) {
    state.activities = MOCK.activities.slice()
  }
}

async function loadMyCoupons() {
  try {
    const body = await api(`/api/shop/coupon/my/${state.userId}`)
    if (ok(body)) state.myCoupons = body.data
    else throw new Error()
  } catch (e) {
    state.myCoupons = MOCK.myCoupons.slice()
  }
}

async function loadOrders() {
  try {
    const body = await api(`/api/shop/order/list/${state.userId}?pageNum=1&pageSize=20`)
    if (ok(body)) state.orders = body.data.records || []
    else throw new Error()
  } catch (e) {
    // 仅在首次加载（无订单）时回退到 MOCK，避免重新进入订单页时
    // 把已取消订单的 status=CANCELLED 重置为 MOCK 初始值 PAID
    if (!state.orders.length) state.orders = MOCK.orders.slice()
  }
}

async function loadWeek() {
  try {
    const body = await api(`/api/points/sign-in/week?userId=${state.userId}`)
    if (ok(body)) state.weekData = body.data
    else throw new Error()
  } catch (e) {
    // 仅在首次加载（无数据）时回退到 MOCK，避免重新进入签到页时
    // 把本地已签到的 todaySigned=true 重置为 MOCK 初始值导致可重复签到
    if (!state.weekData) state.weekData = Object.assign({}, MOCK.week)
  }
}

async function loadRecords() {
  try {
    const body = await api(`/api/points/record/${state.userId}`)
    if (ok(body)) state.records = body.data.slice(0, 20)
    else throw new Error()
  } catch (e) {
    state.records = MOCK.records.slice()
  }
}

// ===== 动作 =====
function goDetail(m) {
  state.currentMed = m
  state.detailQty = 1
  state.detailCoupon = ''
  state.page = 'detail'
  loadMyCouponsForDetail()
  window.scrollTo(0, 0)
}

function goBack(target) {
  state.page = target || 'home'
  window.scrollTo(0, 0)
}

function goMine() {
  state.page = 'mine'
  loadBalance()
  loadOrders()
  loadMyCoupons()
  window.scrollTo(0, 0)
}

function switchUser() {
  toast('已切换到用户 ' + state.userId)
  refreshAll()
}

// 购买请求是否在途：在途时忽略后续点击。
// 为什么还需要它（幂等键不够吗？）：
//   · X-Request-Id 解决的是「请求已经发出、但响应丢失后自动重试」——同一次调用复用同一个 id；
//   · 而用户双击会触发**两次独立的 buy() 调用**，各自生成不同的 requestId，
//     后端的唯一键无法把它们判定为同一笔，仍会产生两张订单。
// 所以前端必须自己挡住重复点击，两者配合才完整。
let buying = false

async function buy(medId) {
  if (buying) return
  buying = true
  try {
    const qty = state.detailQty
    const cp = state.detailCoupon
    // 普通购买只有余额一条支付链路（现金通道仅秒杀单保留；积分支付走 exchange 独立链路）
    const payType = 'BALANCE'
    const url = `/api/shop/order/create?userId=${state.userId}&medicineId=${medId}&quantity=${qty}&payType=${payType}` + (cp ? `&userCouponId=${cp}` : '')
    // 幂等键（J-02）：一次「点击购买」生成一个 requestId 并随请求发出。
    // 后端以 (user_id, request_id) 建唯一索引，因此同一用户重复提交只会产生一张订单，
    // 不会重复扣库存、重复用券。401 续期后 api() 会用同一份 options 重试，requestId 保持不变。
    const requestId = newRequestId()
    try {
      const body = await api(url, { method: 'POST', headers: { 'X-Request-Id': requestId } })
      if (ok(body)) {
        // 统一收银台模型：余额单建单后 PENDING（此刻不扣钱），
        // 交由详情页跳收银台「确认支付 / 超时自动关单」
        if (body.data.status === 'PENDING') {
          toast(`订单已创建：${body.data.orderNo}，请在 30 分钟内完成支付，超时将自动取消`)
          return { ok: true, pending: true, orderId: body.data.id, orderNo: body.data.orderNo }
        }
        toast(`下单成功！订单号 ${body.data.orderNo}，实付 ￥${(body.data.totalAmount - (body.data.discountAmount || 0)).toFixed(2)}（余额支付）`)
        loadBalance()
        loadAccount()
        goBack('home')
        searchMedicines(state.medPage)
        return { ok: true }
      } else {
        // 业务失败（库存不足、余额不足、优惠券已用等）：停留在详情页，让用户能改数量后重试
        const msg = body.message || '未知错误'
        toast(/余额不足/.test(msg) ? `${msg}，可到「钱包」充值后重试` : '下单失败：' + msg)
      }
    } catch (e) {
      // 走到这里说明网络层就失败了（后端不可达 / 超时 / CORS），**并不知道订单是否创建成功**。
      // 生产环境绝不能显示「下单成功」—— 用户会以为买到了并离开页面，
      // 而订单列表随后会退化成 mock 数据，用户再也无法核对真实结果（FE-04）。
      if (!demoFallbackAllowed()) return
      // 演示模式余额支付：本地校验余额，不再"假成功"把余额扣成负数（与积分兑换同口径）
      if (payType === 'BALANCE') {
        const price = (state.currentMed && state.currentMed.price) || 0
        const payable = price * qty
        if (state.balance < payable) {
          toast(`余额不足：当前 ${state.balance}，需 ${payable.toFixed(2)}`)
          return
        }
        state.balance -= payable
      }
      toast(`下单成功（演示）×${qty} 件，订单号 MOCK${Date.now().toString().slice(-6)}`)
      goBack('home')
    }
  } finally {
    buying = false
  }
}

async function exchange(medId) {
  const qty = state.detailQty
  // 不在前端预检积分是否充足：本地积分可能是陈旧快照，预检反而可能拦下本可成功的兑换。
  // 积分不足由后端在确认支付时如实返回（"积分余额不足"），前端原样透出即可。
  const med = state.currentMed && String(state.currentMed.id) === String(medId)
    ? state.currentMed
    : state.medicines.find((m) => String(m.id) === String(medId))
  // 仅演示兜底（后端不可达）用它做本地校验；真实链路不预检，积分是否够由后端确认支付时判定
  const cost = med && med.pointsPrice > 0 ? med.pointsPrice * qty : 0
  try {
    const body = await api(`/api/shop/order/exchange?userId=${state.userId}&medicineId=${medId}&quantity=${qty}`, { method: 'POST' })
    if (ok(body)) {
      // 统一收银台模型：积分单 PENDING，交由详情页跳收银台确认支付（此刻才真正扣分）
      if (body.data.status === 'PENDING') {
        toast(`订单已创建：${body.data.orderNo}，需 ${body.data.pointsUsed} 积分，请在 30 分钟内确认支付`)
        return { ok: true, pending: true, orderId: body.data.id, orderNo: body.data.orderNo }
      }
      toast(`积分兑换成功！消耗 ${body.data.pointsUsed} 积分`)
      loadBalance()
      goBack('home')
    } else {
      // 后端业务失败（积分不足/药品不支持兑换等）：如实提醒，不要误报成功
      toast('兑换失败：' + (body.message || '未知错误'))
    }
  } catch (e) {
    // 后端不可达。生产环境直接如实报错（FE-04），不做任何本地"假成功"。
    if (!demoFallbackAllowed()) return
    // 演示模式：先做本地余额校验，避免积分不足仍提示成功、把本地积分扣成负数；
    // 积分未初始化(0/0)时无法判断，不拦截
    const inited = state.points.total !== 0 || state.points.used !== 0
    const avail = state.points.total - state.points.used
    if (cost <= 0) {
      toast('该药品不支持积分兑换')
    } else if (inited && avail < cost) {
      toast(`积分不足：当前可用 ${avail}，需 ${cost} 积分`)
    } else {
      state.points.used += cost
      toast(`积分兑换成功（演示）消耗 ${cost} 积分`)
      goBack('home')
    }
  }
}

// ===== 购物车 =====
// 与 buy() 同一套纪律：在途防双击（后端幂等键挡"重试"，这里挡"连点"）。
let cartBusy = false
let checkingOut = false

async function addToCart(medId, qty) {
  if (cartBusy) return
  cartBusy = true
  try {
    const body = await api(`/api/shop/cart/items?medicineId=${medId}&quantity=${qty}`, { method: 'POST' })
    if (ok(body)) {
      toast('已加入购物车')
      loadCart()
    } else {
      toast('加购失败：' + (body.message || '未知错误'))
    }
  } catch (e) {
    if (!demoFallbackAllowed()) return
    toast('已加入购物车（演示）')
  } finally {
    cartBusy = false
  }
}

async function loadCart() {
  // 静默加载：失败不打断页面（购物车是附属功能，别用弹窗轰炸），列表保持空态即可
  state.cartLoading = true
  try {
    const body = await api('/api/shop/cart')
    state.cartItems = ok(body) ? body.data : []
  } catch (e) {
    state.cartItems = []
  } finally {
    state.cartLoading = false
  }
}

async function updateCartItem(itemId, qty) {
  if (cartBusy) return
  cartBusy = true
  try {
    const body = await api(`/api/shop/cart/items/${itemId}?quantity=${qty}`, { method: 'PUT' })
    if (ok(body)) loadCart()
    else toast('修改失败：' + (body.message || '未知错误'))
  } catch (e) {
    toast('网络异常，修改失败')
  } finally {
    cartBusy = false
  }
}

async function removeCartItem(itemId) {
  if (cartBusy) return
  cartBusy = true
  try {
    const body = await api(`/api/shop/cart/items/${itemId}`, { method: 'DELETE' })
    if (ok(body)) loadCart()
    else toast('删除失败：' + (body.message || '未知错误'))
  } catch (e) {
    toast('网络异常，删除失败')
  } finally {
    cartBusy = false
  }
}

async function clearCartAll() {
  if (cartBusy) return
  cartBusy = true
  try {
    const body = await api('/api/shop/cart', { method: 'DELETE' })
    if (ok(body)) loadCart()
  } finally {
    cartBusy = false
  }
}

/**
 * 购物车合并结算：勾选条目 + 可选优惠券 + 支付方式 → 一笔 CART 订单。
 *
 * 满减券的门槛后端按「多件合计」判定——这正是购物车存在的意义：
 * 单买一件够不着的"满100减10"，凑几件就够了。
 * 结算仅支持余额支付：建 PENDING 单后由页面跳收银台确认支付（此刻才扣款）。
 */
async function checkoutCart(itemIds, userCouponId, payType) {
  if (checkingOut) return null
  checkingOut = true
  try {
    const requestId = newRequestId()
    const body = await api('/api/shop/order/cart/checkout', {
      method: 'POST',
      headers: { 'X-Request-Id': requestId },
      body: JSON.stringify({
        itemIds,
        userCouponId: userCouponId || null,
        payType: payType || 'BALANCE',
      }),
    })
    if (ok(body)) {
      loadCart() // 已结算条目后端已删除，刷新本地列表
      // 统一收银台模型：余额单也是 PENDING（确认支付才扣款），交由页面跳收银台
      if (body.data.status === 'PENDING') {
        toast(`订单已创建：${body.data.orderNo}，请在 30 分钟内完成支付`)
        return { ok: true, pending: true, orderId: body.data.id, orderNo: body.data.orderNo }
      }
      const payNote = payType === 'BALANCE' ? '（余额支付）' : ''
      const payable = (body.data.totalAmount || 0) - (body.data.discountAmount || 0)
      toast(`下单成功！订单号 ${body.data.orderNo}，实付 ￥${payable.toFixed(2)}${payNote}`)
      loadBalance()
      loadAccount()
      return { ok: true }
    }
    toast('结算失败：' + (body.message || '未知错误'))
    return null
  } catch (e) {
    // 网络层失败：不知道后端是否已建单，不能假报成功（FE-04 同款纪律）
    if (!demoFallbackAllowed()) return null
    toast('网络异常，请稍后到订单列表核对')
    return null
  } finally {
    checkingOut = false
  }
}

async function receive(couponId) {
  try {
    const body = await api(`/api/shop/coupon/receive?userId=${state.userId}&couponId=${couponId}`, { method: 'POST' })
    if (ok(body)) {
      toast('领取成功！')
      loadActivities()
      loadMyCoupons()
    } else {
      toast('领取失败：' + (body.message || '未知错误'))
    }
  } catch (e) {
    // 生产环境：领取失败就如实报错，绝不提示"领取成功"（FE-04）
    if (!demoFallbackAllowed()) return
    toast('领取成功（演示）')
    const act = state.activities.find((a) => a.id === couponId)
    if (act) act.issuedCount++
    state.myCoupons.unshift({
      id: Date.now(),
      couponName: act ? act.name : '新券',
      thresholdAmount: act ? act.thresholdAmount : 0,
      discountAmount: act ? act.discountAmount : 0,
      expireTime: act ? act.endTime : '2026-12-31',
      status: 'UNUSED',
    })
  }
}

async function doSignIn() {
  try {
    const body = await api(`/api/points/sign-in?userId=${state.userId}`, { method: 'POST' })
    if (ok(body)) {
      toast(`签到成功，获得 ${body.data.points} 积分！`)
      loadWeek()
      loadRecords()
      loadBalance()
    } else {
      toast(body.message || '签到失败')
    }
  } catch (e) {
    // 生产环境：签到失败必须如实报错，否则用户以为已签到、次日发现积分没变（FE-04）
    if (!demoFallbackAllowed()) return
    toast(`签到成功（演示）+${DEMO_SIGN_IN_POINTS} 积分`)
    if (state.weekData) {
      // 只按"今天"精确匹配，找不到就不标记任何一天。
      // 原实现在找不到时会退化为 days[2]（周三），等于把错误的一天标成已签到，
      // 用户看到按钮变灰、却不知道自己到底签的是哪一天。
      const today = state.weekData.days.find((d) => isTodayLocal(d.date))
      if (today) {
        today.signed = true
        state.weekData.signedDays++
        state.weekData.todaySigned = true
      } else {
        // mock 周数据是固定日期，真实日期不在其中时无法映射到具体某一天。
        // 此时仅记录积分与流水，不改动周视图（避免标记错误日期）。
        state.weekData.todaySigned = true
      }
    }
    state.points.total += DEMO_SIGN_IN_POINTS
    state.records.unshift({
      id: Date.now(),
      createTime: new Date().toISOString(),
      points: DEMO_SIGN_IN_POINTS,
      type: 'SIGN_IN',
      remark: '每日签到（演示）',
    })
  }
}

// opts.skipConfirm：调用方已用页内弹窗确认过（收银台），跳过这里的原生 confirm，避免二次弹窗
async function cancelOrder(id, opts = {}) {
  if (!opts.skipConfirm
      && !confirm('确认取消该订单？现金订单会退回优惠券，秒杀订单会释放秒杀名额。')) return
  // 演示订单（mock 数据用负数 id）：绝不调真实接口——
  // 此前 mock 用正数 id 1~4，与真实订单自增 id 撞车，取消演示卡片
  // 会误伤数据库里同 id 的真实订单（已发生过：真实单被报「订单已取消」）。
  if (id < 0) {
    const o = state.orders.find((x) => x.id === id)
    if (o) o.status = 'CANCELLED'
    toast('订单已取消（演示）')
    return
  }
  try {
    const body = await api(`/api/shop/order/cancel/${id}`, { method: 'POST' })
    if (ok(body)) {
      toast('订单已取消')
      loadOrders()
      loadBalance()
    } else {
      toast('取消失败：' + (body.message || '未知错误'))
    }
  } catch (e) {
    // 生产环境：取消失败要如实报错，否则用户以为订单已取消、实际仍占用库存与积分（FE-04）
    if (!demoFallbackAllowed()) return
    toast('订单已取消（演示）')
    const o = state.orders.find((x) => x.id === id)
    if (o) o.status = 'CANCELLED'
  }
}

// ===== 秒杀（滑块验证码 → Redis+Lua 预扣 → RocketMQ 排队 → 结果轮询）=====

// 获取滑块验证码（底图/碎块 PNG base64 + 碎块纵坐标；缺口 x 由服务端保密）
async function fetchSeckillCaptcha() {
  try {
    const body = await api('/api/shop/seckill/captcha')
    if (ok(body)) return { ok: true, data: body.data }
    return { ok: false, msg: body.message || '获取验证码失败' }
  } catch (e) {
    return { ok: false, msg: '后端未连接' }
  }
}

// 校验滑块（一次性票据）；携带拖拽轨迹采样与总时长，供后端做行为校验；失败即作废，需刷新重拖
async function verifySeckillCaptcha(captchaId, x, trajectory, durationMs) {
  try {
    const body = await api(`/api/shop/seckill/captcha/verify?captchaId=${captchaId}&x=${x}` +
      `&trajectory=${encodeURIComponent(trajectory)}&durationMs=${durationMs}`, { method: 'POST' })
    if (ok(body)) return { ok: true, ticket: body.data }
    return { ok: false, msg: body.message || '验证未通过' }
  } catch (e) {
    return { ok: false, msg: '后端未连接' }
  }
}

function demoSeckillList() {
  return [{
    id: 1, medicineId: 1, title: '布洛芬缓释胶囊 限时秒杀（演示）', seckillPrice: 9.9,
    totalStock: 50, soldCount: 23, status: 1,
    startTime: new Date(Date.now() - 3600e3).toISOString(),
    endTime: new Date(Date.now() + 86400e3).toISOString(),
  }]
}

async function loadSeckillActivities() {
  if (MOCK_MODE) {
    state.seckillActivities = demoSeckillList()
    return
  }
  try {
    const body = await api('/api/shop/seckill/list')
    state.seckillActivities = ok(body) ? (body.data || []) : []
  } catch (e) {
    if (!demoFallbackAllowed()) {
      state.seckillActivities = []
      return
    }
    state.seckillActivities = demoSeckillList()
  }
}

// 抢购：需携带滑块验证签发的一次性票据；这里只触发，结果由 pollSeckillResult 轮询
async function doSeckill(activityId, ticket) {
  try {
    const url = `/api/shop/seckill/${activityId}` + (ticket ? `?ticket=${ticket}` : '')
    const body = await api(url, { method: 'POST' })
    if (ok(body)) {
      toast(body.data || '已进入排队')
      return { ok: true }
    }
    toast(body.message || '抢购失败')
    return { ok: false, msg: body.message || '抢购失败' }
  } catch (e) {
    if (!demoFallbackAllowed()) return { ok: false, msg: '后端未连接' }
    return { ok: true, mock: true }
  }
}

// 轮询抢购结果：SUCCESS:{orderNo}:{orderId} / FAILED:{原因} / 处理中
async function pollSeckillResult(activityId) {
  try {
    const body = await api(`/api/shop/seckill/result/${activityId}`)
    const data = ok(body) ? body.data : null
    if (!data) return { state: 'PROCESSING' }
    if (data.startsWith('SUCCESS:')) {
      // 第三段 orderId 供"抢到后直接跳收银台"；旧数据只有两段，容忍缺失
      const [orderNo, orderId] = data.slice(8).split(':')
      return { state: 'SUCCESS', orderNo, orderId: orderId ? Number(orderId) : null }
    }
    // 失败原因可能自带冒号，整段保留（不能用 split 截断）
    if (data.startsWith('FAILED:')) return { state: 'FAILED', reason: data.slice(7) }
    return { state: 'PROCESSING' }
  } catch (e) {
    return { state: 'SUCCESS', orderNo: 'MOCK' + Date.now().toString().slice(-6), mock: true }
  }
}

function refreshAll() {
  // 演示态恢复出口：后端恢复后走全量刷新即回到真实数据（否则 MOCK_MODE 一旦置位
  // 只能靠刷新整页摆脱，余额/订单会一直停留在 mock 值上）
  MOCK_MODE = false
  state.isMock = false
  loadBalance()
  searchMedicines(1)
  loadWeek()
  loadRecords()
  loadActivities()
  loadMyCoupons()
  loadOrders()
}

// ===== 头像（阿里云 OSS）=====

// 拉取当前用户资料，回填头像 URL。失败静默：头像只是增强信息，UI 有占位图标兜底。
async function loadProfile() {
  if (MOCK_MODE || !state.token) return
  try {
    const body = await api('/api/user/info')
    if (ok(body) && body.data) {
      if (!state.username) state.username = body.data.username || state.username
      state.avatar = body.data.avatar || ''
      state.role = body.data.role || 'USER'
      // 随拉取持久化（复核 P1-3）：冷启动 restoreAuth 读回，避免刷新后头像空、管理员被弹回
      localStorage.setItem(LS_ROLE, state.role)
      localStorage.setItem(LS_AVATAR, state.avatar)
    }
  } catch (_) { /* 静默 */ }
}

// 上传头像：multipart 交给后端存 OSS（仅本人，身份来自网关注入的 X-User-Id）。
// 客户端预检与后端校验双保险：类型必须是图片、≤5MB。
async function uploadAvatar(file) {
  if (!file) return { ok: false, msg: '请选择图片' }
  if (!/^image\//.test(file.type || '')) return { ok: false, msg: '仅支持图片文件' }
  if (file.size > 5 * 1024 * 1024) return { ok: false, msg: '图片不能超过 5MB' }
  try {
    const fd = new FormData()
    fd.append('file', file)
    const body = await api('/api/user/avatar', { method: 'POST', body: fd })
    if (ok(body)) {
      state.avatar = body.data
      localStorage.setItem(LS_AVATAR, state.avatar)
      return { ok: true, url: body.data }
    }
    return { ok: false, msg: body.message || '头像上传失败' }
  } catch (e) {
    // 后端不可达：仅演示模式下用本地 data URL 预览（不伪装成"已上传到服务器"）
    if (!demoFallbackAllowed()) return { ok: false, msg: '头像上传失败' }
    const dataUrl = await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = reject
      reader.readAsDataURL(file)
    })
    state.avatar = dataUrl
    return { ok: true, url: dataUrl, mock: true }
  }
}

// ===== 余额账户（余额支付）=====

// 拉取余额账户。失败/演示模式：余额按 0 处理并由 UI 兜底，不阻塞页面。
async function loadAccount() {
  if (MOCK_MODE || !state.token) return
  try {
    const body = await api(`/api/account/${state.userId}`)
    if (ok(body) && body.data) {
      state.balance = Number(body.data.balance || 0)
    }
  } catch (_) { /* 静默：余额展示属增强信息 */ }
}

// 充值（模拟支付渠道入账）。客户端预检：>0 且 ≤5000（与后端一致的双保险）。
async function recharge(amount) {
  const amt = Number(amount)
  if (!amt || amt <= 0) return { ok: false, msg: '充值金额不合法' }
  if (amt > 5000) return { ok: false, msg: '单笔充值不能超过 5000 元' }
  try {
    const body = await api(`/api/account/recharge?amount=${amt}`, { method: 'POST' })
    if (ok(body)) {
      state.balance = Number(body.data || 0)
      loadAccountRecords()
      return { ok: true }
    }
    return { ok: false, msg: body.message || '充值失败' }
  } catch (e) {
    if (!demoFallbackAllowed()) return { ok: false, msg: '充值失败' }
    state.balance += amt
    return { ok: true, mock: true }
  }
}

// 拉取余额流水（最近 200 条）
async function loadAccountRecords() {
  if (MOCK_MODE || !state.token) return
  try {
    const body = await api(`/api/account/record/${state.userId}`)
    if (ok(body)) state.accountRecords = body.data || []
  } catch (_) { /* 静默 */ }
}

// 继续支付待支付订单（PENDING 现金单）。余额单为同步支付，不会出现 PENDING。
async function payPendingOrder(orderId) {
  // 演示订单（负数 id）走本地演示支付，理由同 cancelOrder：避免误伤同 id 真实订单
  if (orderId < 0) {
    const o = state.orders.find((x) => x.id === orderId)
    if (o) o.status = 'PAID'
    toast('支付成功（演示）')
    return { ok: true, mock: true }
  }
  try {
    const body = await api(`/api/shop/order/pay/${orderId}`, { method: 'POST' })
    if (ok(body)) {
      // 只有真的推进到 PAID 才算支付成功：最后一秒点支付可能与「超时自动关单」竞争，
      // 后端会把输掉竞争的情况返回业务错误；这里再按状态兜底，不把 CANCELLED 当成功
      if (body.data && body.data.status === 'PAID') {
        toast('支付成功')
        loadOrders()
        loadBalance()
        return { ok: true }
      }
      const msg = body.data && body.data.status
        ? '订单状态已变为 ' + body.data.status + '（可能已超时关闭）'
        : (body.message || '支付失败')
      toast('支付失败：' + msg)
      return { ok: false, msg }
    }
    toast('支付失败：' + (body.message || '未知错误'))
    return { ok: false, msg: body.message || '支付失败' }
  } catch (e) {
    if (!demoFallbackAllowed()) return { ok: false, msg: '支付失败' }
    const o = state.orders.find((x) => x.id === orderId)
    if (o) o.status = 'PAID'
    toast('支付成功（演示）')
    return { ok: true, mock: true }
  }
}

function isTodayLocal(date) {
  const d = new Date()
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return date === today
}

// 拉取单个订单（收银台用）：真实接口；演示态下返回 null，由页面自行提示
async function fetchOrder(orderId) {
  if (MOCK_MODE) return null
  try {
    const body = await api(`/api/shop/order/${orderId}`)
    if (ok(body) && body.data) return body.data
    return null
  } catch (e) {
    if (!demoFallbackAllowed()) return null
    return null
  }
}

// ===== 管理端（/api/admin/**，网关 ADMIN 角色闸门 + 服务端二次校验 + 审计）=====
// 设计约定：管理操作**不走演示兜底** —— 后端不可达就如实失败，
// 绝不本地伪造"管理成功"（管理动作影响的是真实数据，假成功比失败更危险）。

async function adminCall(path, options = {}) {
  try {
    const body = await api(path, options)
    if (ok(body)) return { ok: true, data: body.data }
    return { ok: false, msg: body.message || '操作失败' }
  } catch (e) {
    return { ok: false, msg: '后端未连接，管理操作不可用' }
  }
}

const adminMedicinePage = (pageNum = 1, status = '', keyword = '') =>
  adminCall(`/api/admin/medicine/page?pageNum=${pageNum}&pageSize=10` +
    (status !== '' ? `&status=${status}` : '') +
    (keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''))

/**
 * 编辑药品：payload 里**只放要改的字段**（后端按"null = 这一列不改"处理）。
 * 调用方（编辑弹窗）负责与初值做 diff —— 这样审计里记录的就是真实变更，
 * 而不是"提交了哪些字段"。
 */
const adminMedicineUpdate = (id, payload) =>
  adminCall(`/api/admin/medicine/${id}/update`, { method: 'POST', body: JSON.stringify(payload) })

/** 新增药品：payload 里是"填了的字段"，留空由后端按默认值处理（名称与现金价必填） */
const adminMedicineCreate = (payload) =>
  adminCall('/api/admin/medicine', { method: 'POST', body: JSON.stringify(payload) })

/** 删除药品：被订单/秒杀活动引用时后端会拒绝并给出原因，前端原样提示 */
const adminMedicineDelete = (id) =>
  adminCall(`/api/admin/medicine/${id}`, { method: 'DELETE' })

const adminMedicineStatus = (id, status) =>
  adminCall(`/api/admin/medicine/${id}/status?status=${status}`, { method: 'POST' })

const adminMedicineImage = (id, file) => {
  if (!file) return Promise.resolve({ ok: false, msg: '请选择图片' })
  const fd = new FormData()
  fd.append('file', file)
  return adminCall(`/api/admin/medicine/${id}/image`, { method: 'POST', body: fd })
}

const adminSeckillPage = (pageNum = 1) =>
  adminCall(`/api/admin/seckill/page?pageNum=${pageNum}&pageSize=10`)

const adminSeckillCreate = (p) =>
  adminCall(`/api/admin/seckill/create?medicineId=${p.medicineId}&title=${encodeURIComponent(p.title)}` +
    `&seckillPrice=${p.seckillPrice}&totalStock=${p.totalStock}` +
    `&startTime=${encodeURIComponent(p.startTime)}&endTime=${encodeURIComponent(p.endTime)}`, { method: 'POST' })

const adminSeckillStatus = (id, status) =>
  adminCall(`/api/admin/seckill/${id}/status?status=${status}`, { method: 'POST' })

const adminSeckillTime = (id, startTime, endTime) =>
  adminCall(`/api/admin/seckill/${id}/time?startTime=${encodeURIComponent(startTime)}` +
    `&endTime=${encodeURIComponent(endTime)}`, { method: 'POST' })

const adminOrderPage = (pageNum = 1, status = '') =>
  adminCall(`/api/admin/order/page?pageNum=${pageNum}&pageSize=10` +
    (status !== '' ? `&status=${status}` : ''))

const adminRefund = (id) =>
  adminCall(`/api/admin/order/${id}/refund`, { method: 'POST' })

const adminCompensationPage = (pageNum = 1, status = '') =>
  adminCall(`/api/admin/compensation/page?pageNum=${pageNum}&pageSize=10` +
    (status !== '' ? `&status=${status}` : ''))

const adminCompensationRetry = (id) =>
  adminCall(`/api/admin/compensation/${id}/retry`, { method: 'POST' })

// 导出单例 composable
export function useStore() {
  return {
    state,
    balanceText,
    toast,
    restoreAuth,
    login,
    register,
    logout,
    searchMedicines,
    loadMyCouponsForDetail,
    loadBalance,
    loadActivities,
    loadMyCoupons,
    loadOrders,
    loadWeek,
    loadRecords,
    goDetail,
    goBack,
    goMine,
    switchUser,
    buy,
    exchange,
    addToCart,
    loadCart,
    updateCartItem,
    removeCartItem,
    clearCartAll,
    checkoutCart,
    receive,
    doSignIn,
    cancelOrder,
    fetchOrder,
    loadProfile,
    uploadAvatar,
    loadAccount,
    recharge,
    loadAccountRecords,
    payPendingOrder,
    loadSeckillActivities,
    doSeckill,
    pollSeckillResult,
    fetchSeckillCaptcha,
    verifySeckillCaptcha,
    adminMedicinePage,
    adminMedicineUpdate,
    adminMedicineCreate,
    adminMedicineDelete,
    adminMedicineStatus,
    adminMedicineImage,
    adminSeckillPage,
    adminSeckillCreate,
    adminSeckillStatus,
    adminSeckillTime,
    adminOrderPage,
    adminRefund,
    adminCompensationPage,
    adminCompensationRetry,
    refreshAll,
  }
}
