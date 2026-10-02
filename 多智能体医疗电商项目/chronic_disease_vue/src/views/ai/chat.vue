<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  newSessionId,
  streamQuery,
  loadSessions,
  loadSessionMessages,
  renameSession,
  deleteSession,
} from '@/utils/chat'
import { useStore } from '@/store/demo'
import { useRouter } from 'vue-router'

const store = useStore()
const router = useRouter()

/** 「我的订单」是跳转不是提问：医疗 AI 没有业务数据，问了只会编造 */
function goOrders() {
  router.push('/orders')
}

// AI 回答里待支付订单带的轻标记（约定见 Python 侧 agents/order_agent.py 的 _order_marker）：
// 渲染成「去支付」按钮，点击直达该订单的收银台，省去用户自己去订单列表里找。
function goCashier(orderId) {
  router.push('/shop/cashier/' + orderId)
}

/** 用户点了 AI 给的「确认取消」按钮：走与订单页同一个取消链路（归属校验 + 原子取消 + 退库存/名额） */
async function confirmCancel(orderId) {
  // store 里的 cancelOrder 自带二次确认（原生 confirm），避免误点
  await store.cancelOrder(orderId)
}

/**
 * 把回答文本切成「普通文字 / 去支付按钮 / 确认取消按钮」交替的片段。
 * - 流式过程中标记可能只到一半（如 "[order:1"），尾部残片先不显示，避免闪出半截标记；
 * - 识别不了标记的旧回答照旧当纯文字显示，不受影响。
 */
function renderSegments(m) {
  const raw = m.text || (m.role === 'assistant' && sending.value ? '思考中…' : '') || ''
  const segments = []
  const pattern = /\[(order|cancel):(\d+)\]/g
  let last = 0
  for (const match of raw.matchAll(pattern)) {
    if (match.index > last) segments.push({ text: raw.slice(last, match.index) })
    segments.push(match[1] === 'order'
      ? { orderId: Number(match[2]) }
      : { cancelId: Number(match[2]) })
    last = match.index + match[0].length
  }
  const tail = raw.slice(last).replace(/\[(order|cancel):\d*$/, '')
  if (tail) segments.push({ text: tail })
  return segments.length ? segments : [{ text: '' }]
}

const showSidebar = ref(false)

let msgSeq = 0
function nextMsg(role, text) {
  return { id: ++msgSeq, role, text }
}

const messages = ref([
  nextMsg('assistant', '您好，我是慢性病健康助手 🤖\n可为您解答高血压、糖尿病、高血脂等慢性病的用药、饮食、急救、生活方式等问题。\n\n如遇紧急情况请立即拨打 120。'),
])
const input = ref('')
const sending = ref(false)
const sessionId = ref(newSessionId())
const listEl = ref(null)

const sessions = ref([])
const activeSessionId = ref(null)

let currentController = null

function abortStream() {
  if (currentController) {
    currentController.abort()
    currentController = null
  }
}

const suggestions = [
  '高血压患者饮食注意什么？',
  '二甲双胍怎么吃副作用小？',
  '突发胸痛如何处理？',
  '糖尿病足如何护理？',
]

async function refreshSessions() {
  try {
    // FE-11：区分「确实没有历史会话」与「加载失败」。
    // 原实现把失败也当成空列表，界面于是显示「暂无历史会话」——
    // 用户看到的是"我没有数据"，而真实原因是"我没登录/请求被拒绝"，
    // 结果就是没人知道该去重新登录。
    const list = await loadSessions(store.state.token, {
      onError: (e) => {
        const msg = String((e && e.message) || '')
        ElMessage.error(
          /401|403/.test(msg)
            ? '登录状态已过期，请重新登录后再查看历史会话'
            : '加载历史会话失败，请稍后重试'
        )
      },
    })
    sessions.value = list
    // 进入页面默认开新会话，不自动续上最近一条：
    // 自动续聊会把新提问追加进旧会话（垃圾测试会话也常排在最新，被当成当前会话）。
    // 历史会话由用户在侧栏手动点开续聊（openSession）。
  } catch (e) {
    sessions.value = []
  }
}

async function openSession(sid) {
  abortStream()
  showSidebar.value = false
  try {
    const data = await loadSessionMessages(sid, store.state.token)
    sessionId.value = sid
    activeSessionId.value = sid
    const msgs = (data.messages || []).filter((m) => m.role === 'user' || m.role === 'assistant')
    messages.value = msgs.length
      ? msgs.map((m) => nextMsg(m.role, m.content || ''))
      : [nextMsg('assistant', '您好，我是慢性病健康助手 🤖\n请描述您的健康问题。')]
  } catch (e) {
    ElMessage.error('加载历史会话失败')
    sessionId.value = newSessionId()
    activeSessionId.value = null
  }
}

function newConversation() {
  abortStream()
  showSidebar.value = false
  sessionId.value = newSessionId()
  activeSessionId.value = null
  messages.value = [nextMsg('assistant', '您好，我是慢性病健康助手 🤖\n请描述您的健康问题。')]
}

async function onRename(sid) {
  const cur = sessions.value.find((s) => s.session_id === sid)
  try {
    const { value } = await ElMessageBox.prompt('重命名会话：', '重命名', {
      inputValue: cur ? cur.title : '',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    if (!value || !value.trim()) return
    await renameSession(sid, value.trim(), store.state.token)
    await refreshSessions()
  } catch (e) {
    if (e !== 'cancel' && e?.action !== 'cancel') ElMessage.error('重命名失败')
  }
}

async function onDelete(sid) {
  try {
    await ElMessageBox.confirm('确认删除该会话？', '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch (e) {
    return
  }
  try {
    if (activeSessionId.value === sid) abortStream()
    await deleteSession(sid, store.state.token)
    if (activeSessionId.value === sid) newConversation()
    await refreshSessions()
  } catch (e) {
    ElMessage.error('删除会话失败')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q || sending.value) return
  input.value = ''
  activeSessionId.value = sessionId.value // 当前提问所属会话，供侧栏高亮
  messages.value.push(nextMsg('user', q))
  messages.value.push(nextMsg('assistant', ''))
  const target = messages.value[messages.value.length - 1]
  sending.value = true
  scrollToBottom()

  abortStream()
  currentController = new AbortController()
  try {
    await streamQuery({
      query: q,
      sessionId: sessionId.value,
      sourceFilter: null,
      token: store.state.token,
      signal: currentController.signal,
      onToken: (tok) => {
        target.text += tok
        scrollToBottom()
      },
      onDone: () => {
        sending.value = false
        refreshSessions()
        scrollToBottom()
      },
      onError: (msg) => {
        target.text = (target.text ? target.text + '\n' : '') + '❌ ' + msg
        sending.value = false
      },
      // FE-06：回答被截断时明确告知用户。
      // 流在没有收到结束帧的情况下中断（网络抖动、后端超时断开），
      // 已经收到的内容仍然显示，但必须让用户知道"这段回答不完整"，
      // 而不是把半截答案当成完整答案 —— 医疗场景下截断处可能正是用药剂量。
      onIncomplete: () => {
        target.text += '\n\n⚠️ 回答未生成完整（连接中断），建议点击重试以获取完整回答。'
      },
    })
  } finally {
    sending.value = false
    currentController = null
  }
}

function onEnterKey(e) {
  if (e.isComposing || e.keyCode === 229) return
  send()
}

function useSuggestion(s) {
  send(s)
}

onMounted(() => {
  scrollToBottom()
  refreshSessions()
})

onUnmounted(() => {
  abortStream()
})

watch(() => messages.value.length, scrollToBottom)
</script>

<template>
  <div class="chat-layout">
    <!-- 顶部栏 -->
    <!--
      FE-12（可访问性）修复说明：
        · 只有图标的按钮必须给 aria-label，否则屏幕阅读器只会念出"按钮"，用户不知道是干什么的；
        · 纯装饰性的 <svg> 加 aria-hidden="true"，避免被重复念一遍无意义的图形内容。
    -->
    <div class="chat-topbar">
      <button class="menu-btn" type="button" aria-label="打开历史会话列表" @click="showSidebar = true">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="#333" aria-hidden="true"><path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/></svg>
      </button>
      <span class="topbar-title">AI健康助手</span>
      <button class="new-btn" type="button" aria-label="开始新对话" @click="newConversation">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>
        <span>新对话</span>
      </button>
    </div>

    <!-- 主对话区 -->
    <div class="chat-main">
      <div class="chat-chips">
        <!--
          改用 <button> 而非 <div @click>（FE-12）：
          div 默认不在键盘 Tab 顺序里，键盘用户和屏幕阅读器都无法操作它。
          button 天然可聚焦、可回车/空格触发，并会被正确朗读为"按钮"。
        -->
        <button
          v-for="s in suggestions"
          :key="s"
          type="button"
          class="suggest-chip"
          @click="useSuggestion(s)"
        >
          {{ s }}
        </button>
        <!-- 订单不是提问，是跳转：直接去订单页，不要当作 AI 问题发出去 -->
        <button type="button" class="suggest-chip" @click="goOrders">我的订单 →</button>
      </div>

      <div class="chat-list" ref="listEl">
        <div
          v-for="m in messages"
          :key="m.id"
          class="msg"
          :class="m.role === 'user' ? 'msg-user' : 'msg-ai'"
        >
          <div class="avatar">{{ m.role === 'user' ? '🧑' : '🤖' }}</div>
          <div class="bubble">
            <div class="bubble-text">
              <template v-for="(seg, i) in renderSegments(m)" :key="i">
                <button
                  v-if="seg.orderId"
                  type="button"
                  class="pay-chip"
                  @click="goCashier(seg.orderId)"
                >去支付 →</button>
                <button
                  v-else-if="seg.cancelId"
                  type="button"
                  class="cancel-chip"
                  @click="confirmCancel(seg.cancelId)"
                >确认取消该订单</button>
                <template v-else>{{ seg.text }}</template>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input-bar">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          placeholder="描述您的症状或问题…"
          @keydown.enter.exact.prevent="onEnterKey($event)"
          class="chat-input"
          resize="none"
        />
        <button
          class="send-btn"
          type="button"
          :disabled="sending || !input.trim()"
          @click="send()"
        >
          发送
        </button>
      </div>
    </div>

    <!-- 遮罩 -->
    <div v-if="showSidebar" class="sidebar-mask" @click="showSidebar = false"></div>

    <!-- 侧边抽屉 -->
    <aside class="chat-drawer" :class="{ open: showSidebar }">
      <div class="drawer-head">
        <span class="drawer-title">历史会话</span>
        <button class="drawer-close" type="button" aria-label="关闭历史会话列表" @click="showSidebar = false">✕</button>
      </div>
      <div class="sidebar-list">
        <div
          v-for="s in sessions"
          :key="s.session_id"
          class="sidebar-item"
          :class="{ active: s.session_id === activeSessionId }"
          @click="openSession(s.session_id)"
        >
          <span class="item-icon" aria-hidden="true">💬</span>
          <span class="item-title">{{ s.title || '新会话' }}</span>
          <div class="item-actions" @click.stop>
            <!--
              FE-12：title 属性在部分屏幕阅读器下不会被朗读，必须补 aria-label；
              并把纯符号包一层 aria-hidden 的 span，避免符号本身被逐字念出来。
            -->
            <button
              class="action-btn"
              type="button"
              title="重命名"
              :aria-label="`重命名会话：${s.title || '新会话'}`"
              @click="onRename(s.session_id)"
            ><span aria-hidden="true">✎</span></button>
            <button
              class="action-btn danger"
              type="button"
              title="删除"
              :aria-label="`删除会话：${s.title || '新会话'}`"
              @click="onDelete(s.session_id)"
            ><span aria-hidden="true">🗑</span></button>
          </div>
        </div>
        <div v-if="!sessions.length" class="sidebar-empty">暂无历史会话</div>
      </div>
    </aside>
  </div>

  <p class="chat-disclaimer">⚠️ 内容仅供参考，不能替代专业医疗建议。急症请拨打 120。</p>
</template>

<style scoped>
.chat-layout {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 84px);
  background: #f5f7fa;
  position: relative;
  overflow: hidden;
}

/* 顶部栏 */
.chat-topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  flex-shrink: 0;
}
.menu-btn {
  width: 36px;
  height: 36px;
  border: none;
  background: #f5f5f7;
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.menu-btn:active { background: #e8e8ea; }
.topbar-title {
  flex: 1;
  font-size: 17px;
  font-weight: 700;
  color: #1a1a1a;
}
.new-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  padding: 8px 14px;
  border-radius: 18px;
  cursor: pointer;
}
.new-btn:active { opacity: .85; }

/* 主对话区 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* flex 子项默认 min-height:auto=内容高度：会话一旦变长，整个主区被撑高，
     输入条被顶出 chat-layout 的 overflow:hidden 之外（表现为输入框消失）。
     min-height:0 允许它收缩到剩余空间，让列表内部滚动、输入条固定在底部 */
  min-height: 0;
}
.chat-chips {
  display: flex;
  gap: 8px;
  padding: 10px 16px;
  overflow-x: auto;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}
.chat-chips::-webkit-scrollbar { display: none; }
.suggest-chip {
  flex-shrink: 0;
  padding: 6px 14px;
  border-radius: 16px;
  background: #E0F7F4;
  color: #00BFA5;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid #b2e8e0;
  /* FE-12：改成 <button> 后需要显式继承字体，否则会退回浏览器默认的按钮字体 */
  font-family: inherit;
  line-height: 1.5;
}
/* FE-12：键盘聚焦时必须可见 —— 否则键盘用户 Tab 到元素上也看不出焦点在哪 */
.suggest-chip:focus-visible,
.send-btn:focus-visible,
.menu-btn:focus-visible,
.new-btn:focus-visible,
.drawer-close:focus-visible,
.action-btn:focus-visible {
  outline: 2px solid #00BFA5;
  outline-offset: 2px;
}
.chat-list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  -webkit-overflow-scrolling: touch;
}
.msg { display: flex; gap: 10px; align-items: flex-start; }
.msg-user { flex-direction: row-reverse; }
.avatar {
  width: 34px; height: 34px; flex-shrink: 0;
  font-size: 22px; line-height: 34px; text-align: center;
  background: #f0f2f5; border-radius: 50%;
}
.bubble {
  max-width: 78%;
  border-radius: 14px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.msg-ai .bubble { background: #fff; color: #333; border-top-left-radius: 4px; box-shadow: 0 1px 4px rgba(0,0,0,.04); }
.msg-user .bubble { background: #00BFA5; color: #fff; border-top-right-radius: 4px; }
.bubble-text {
  font-family: inherit;
  white-space: pre-wrap;
  margin: 0;
}
/* AI 回答里待支付订单的「去支付」按钮（由 [order:<id>] 标记渲染而来） */
.pay-chip {
  display: inline-block;
  margin: 2px 0 2px 4px;
  padding: 3px 12px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  vertical-align: baseline;
}
.pay-chip:hover { filter: brightness(1.06); }
/* 取消提案按钮：与"去支付"区分开（写操作，用醒目的危险色 + 更高点击门槛） */
.cancel-chip {
  display: inline-block;
  margin: 4px 0 2px;
  padding: 4px 14px;
  border: 1px solid #ffcdd2;
  border-radius: 12px;
  background: #fff;
  color: #E53935;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.cancel-chip:hover { background: #fff5f5; }
.chat-input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 16px;
  background: #fff;
  border-top: 1px solid #eee;
}
.chat-input { flex: 1; }
.send-btn {
  flex-shrink: 0;
  border: none;
  background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  padding: 10px 20px;
  border-radius: 20px;
  cursor: pointer;
}
.send-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 网页版（桌面宽屏）：会话区收窄到可读行宽并居中。
   不加这条的话气泡会随外壳拉满 1100px，一行五六十个字没法读；
   <768px 时手机端不受影响。 */
@media (min-width: 768px) {
  .chat-topbar,
  .chat-chips,
  .chat-list,
  .chat-input-bar {
    width: 100%;
    max-width: 840px;
    margin-left: auto;
    margin-right: auto;
  }
}

/* 遮罩 */
.sidebar-mask {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,.4);
  z-index: 99;
  animation: fadeIn .2s ease;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

/* 侧边抽屉 */
.chat-drawer {
  position: absolute;
  top: 0;
  left: 0;
  bottom: 0;
  width: 280px;
  max-width: 80vw;
  background: #fff;
  z-index: 100;
  transform: translateX(-100%);
  transition: transform .28s cubic-bezier(.4,0,.2,1);
  display: flex;
  flex-direction: column;
  box-shadow: 2px 0 12px rgba(0,0,0,.08);
}
.chat-drawer.open {
  transform: translateX(0);
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.drawer-title {
  font-size: 16px;
  font-weight: 700;
  color: #1a1a1a;
}
.drawer-close {
  width: 32px;
  height: 32px;
  border: none;
  background: #f5f5f7;
  border-radius: 8px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
}
.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.sidebar-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  color: #444;
  transition: background .15s;
}
.sidebar-item:hover { background: #f5f7f9; }
.sidebar-item.active { background: #E0F7F4; color: #00BFA5; font-weight: 600; }
.item-icon { font-size: 16px; }
.item-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-actions {
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity .15s;
}
.sidebar-item:hover .item-actions { opacity: 1; }
.action-btn {
  border: none;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
  color: #999;
}
.action-btn.danger { color: #FF5252; }
.sidebar-empty {
  padding: 40px 12px;
  text-align: center;
  color: #bbb;
  font-size: 13px;
}

.chat-disclaimer {
  text-align: center;
  font-size: 12px;
  color: #999;
  padding: 8px 16px;
  margin: 0;
}

/* Element Plus 主题色覆盖 */
:deep(.el-button--primary) {
  background-color: #00BFA5;
  border-color: #00BFA5;
}
:deep(.el-button--primary:hover) {
  background-color: #00b3a0;
  border-color: #00b3a0;
}
:deep(.el-textarea__inner:focus) {
  border-color: #00BFA5;
}
</style>
