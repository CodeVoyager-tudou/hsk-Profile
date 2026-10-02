<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useStore } from '@/store/demo'
import { orderImg } from '@/utils/images'
import { formatTime, statusText, payText } from '@/utils/format'

// 收银台：PENDING 现金单的「确认支付 / 取消订单 / 返回」页。
// 后端在下单时挂了 30 分钟的 RocketMQ 延迟消息（order-pay-timeout-topic），
// 到点仍未支付就自动关单退库存/优惠券——本页不本地判定超时，而是轮询订单状态，
// 这样无论支付、取消还是服务端超时关单，界面都会跟着真实状态走。
// 支付与取消都必须先经页内确认弹窗（不可撤销的动作不靠"手滑"完成）。
const router = useRouter()
const route = useRoute()
const { state, fetchOrder, payPendingOrder, cancelOrder } = useStore()

const orderId = Number(route.params.orderId)
const order = ref(null)
const loadError = ref('')
const paying = ref(false)
const nowTs = ref(Date.now())
/** 确认弹窗：kind=pay|cancel；取消与支付都走它，避免误触不可撤销操作 */
const confirmBox = ref({ show: false, kind: 'pay', title: '', text: '', okText: '', busy: false })
let tickTimer = null
let pollTimer = null

const PAY_WINDOW_MINUTES = Number(import.meta.env.VITE_PAY_WINDOW_MINUTES || 30) // 展示窗口，默认与后端 chronic.pay.timeout-delay-level=16（30m）对齐
// 真实关单在后端：RocketMQ 延迟消息两段 30m + 2m 宽限（约 32 分钟），另有每分钟的兜底扫表。
// 所以展示窗口走完到真正关单之间有约 2 分钟——那 2 分钟里订单仍可能是 PENDING，
// 页面必须显式说明"已超过支付时间、正在自动关闭"，而不是停在「待支付 00:00」让人以为坏了。

const expireTs = computed(() => {
  if (!order.value || !order.value.createTime) return 0
  return new Date(order.value.createTime).getTime() + PAY_WINDOW_MINUTES * 60 * 1000
})
/** 是否已超过展示窗口（此时订单可能仍 PENDING，等后端延迟消息关单） */
const expired = computed(() => expireTs.value > 0 && nowTs.value >= expireTs.value)
const remainText = computed(() => {
  if (!expireTs.value) return '--:--'
  const s = Math.max(0, Math.floor((expireTs.value - nowTs.value) / 1000))
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
})
const payable = computed(() => {
  const o = order.value || {}
  return (Number(o.totalAmount || 0) - Number(o.discountAmount || 0)).toFixed(2)
})
// 积分单：应付以「积分」为单位展示（确认支付时才真正扣分），金额仅供对照
const isPointsOrder = computed(() => order.value && order.value.payType === 'POINTS')
const pointsPayable = computed(() => Number((order.value || {}).pointsUsed || 0))

/** 应付文案：积分单 = "N 积分"，其余 = "￥xx" */
const payableText = computed(() =>
  isPointsOrder.value ? `${pointsPayable.value} 积分` : `￥${payable.value}`)

async function load() {
  const o = await fetchOrder(orderId)
  if (!o) {
    if (!order.value) loadError.value = '订单加载失败或不存在'
    return
  }
  order.value = o
}

function askPay() {
  if (!order.value || order.value.status !== 'PENDING' || paying.value || expired.value) return
  confirmBox.value = {
    show: true, kind: 'pay', busy: false,
    title: '确认支付',
    text: isPointsOrder.value
      ? `本次将扣除 ${pointsPayable.value} 积分，确认后订单完成、积分不可退回（库存不足等失败会原样报错）。`
      : `本次需支付 ￥${payable.value}，支付后订单进入已完成状态、不可撤销。`,
    okText: '确认支付',
  }
}

function askCancel() {
  if (!order.value || order.value.status !== 'PENDING') return
  confirmBox.value = {
    show: true, kind: 'cancel', busy: false,
    title: '取消订单',
    text: '取消后药品库存与优惠券会退回（秒杀单同时释放秒杀名额；'
      + '未确认支付的余额/积分单本就未扣款，取消不涉及退款），该操作不可恢复，需要重新下单。',
    okText: '确认取消',
  }
}

/** 弹窗里的「确认」：按 kind 分派到支付或取消 */
async function doConfirm() {
  const box = confirmBox.value
  if (!box.show || box.busy) return
  box.busy = true
  try {
    if (box.kind === 'pay') {
      paying.value = true
      await payPendingOrder(orderId)
      paying.value = false
    } else {
      // skipConfirm：已经在这个弹窗里确认过了，不要再弹一次原生 confirm
      await cancelOrder(orderId, { skipConfirm: true })
    }
    confirmBox.value.show = false
    await load()
  } finally {
    box.busy = false
    paying.value = false
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/orders')
}

onMounted(async () => {
  await load()
  tickTimer = setInterval(() => (nowTs.value = Date.now()), 1000)
  // PENDING 期间每 5 秒轮询一次：捕捉服务端的支付确认 / 超时自动关单
  pollTimer = setInterval(async () => {
    if (order.value && order.value.status === 'PENDING') await load()
  }, 5000)
})
onUnmounted(() => {
  clearInterval(tickTimer)
  clearInterval(pollTimer)
})
</script>

<template>
  <div class="cashier-page">
    <div class="nav-bar">
      <span class="back" @click="goBack">‹</span>
      <span class="title">收银台</span>
    </div>

    <div class="page-pad">
      <div v-if="loadError" class="empty">
        <div class="empty-emoji">😕</div>
        <div class="empty-text">{{ loadError }}</div>
        <button class="btn-ghost" @click="goBack">返回</button>
      </div>

      <template v-else-if="order">
        <!-- 状态条：待支付倒计时 / 已超时待关单 / 已支付 / 已关闭 -->
        <div class="status-card" :class="order.status === 'PENDING' ? (expired ? 'st-expired' : 'st-pending') : order.status === 'PAID' ? 'st-paid' : 'st-closed'">
          <template v-if="order.status === 'PENDING' && expired">
            <div class="st-main">⏰ 已超过支付时间</div>
            <div class="st-sub">订单正在自动关闭，通常 2 分钟内变为「已取消」；若你刚完成支付，以此刻的订单状态为准</div>
          </template>
          <template v-else-if="order.status === 'PENDING'">
            <div class="st-main">待支付 <span class="countdown">{{ remainText }}</span></div>
            <div class="st-sub">请在 {{ PAY_WINDOW_MINUTES }} 分钟内完成支付，超时订单将自动取消</div>
          </template>
          <template v-else-if="order.status === 'PAID'">
            <div class="st-main">✅ 支付成功</div>
            <div class="st-sub">订单已完成，感谢购买</div>
          </template>
          <template v-else>
            <div class="st-main">{{ statusText(order.status) }}</div>
            <div class="st-sub">订单已取消，库存与优惠券已退回{{ isPointsOrder ? '（积分未扣除）' : '' }}</div>
          </template>
        </div>

        <!-- 订单信息 -->
        <div class="order-card">
          <div class="obody">
            <div class="oimg"><img :src="orderImg(order)" alt=""></div>
            <div class="oinfo">
              <div class="oname">{{ order.medicineName }}</div>
              <div class="oqty">数量 ×{{ order.quantity }}</div>
              <div class="ometa">{{ payText(order.payType) }} · {{ formatTime(order.createTime) }}</div>
            </div>
            <div class="oprices">
              <div class="oamt" v-if="isPointsOrder">{{ pointsPayable }} 积分</div>
              <div class="oamt" v-else>￥{{ Number(order.totalAmount || 0).toFixed(2) }}</div>
              <div class="odiscount" v-if="!isPointsOrder && order.discountAmount > 0">已优惠 ￥{{ Number(order.discountAmount).toFixed(2) }}</div>
            </div>
          </div>
          <!-- 购物车合并单（CART）：逐件列出商品与金额；SINGLE 单不显示这块 -->
          <div class="oitems" v-if="order.items && order.items.length > 1">
            <div class="oitem" v-for="it in order.items" :key="it.id">
              <span class="oin-name">{{ it.medicineName }} × {{ it.quantity }}</span>
              <span class="oin-sub">￥{{ Number(it.subtotal).toFixed(2) }}</span>
            </div>
          </div>
          <div class="pay-line">
            <span>{{ isPointsOrder ? '需付积分' : '需付款' }}</span>
            <span class="pay-amount">{{ payableText }}</span>
          </div>
          <div class="order-no">订单号 {{ order.orderNo }}</div>
        </div>

        <!-- 操作区：超时后主操作变成「立即取消订单」，支付按钮置灰并说明原因 -->
        <div class="actions">
          <button
            v-if="order.status === 'PENDING'"
            class="btn-primary" :disabled="paying || expired"
            @click="askPay"
          >{{ paying ? '支付中…' : (expired ? '已超时，无法支付' : '确认支付') }}</button>
          <button
            v-if="order.status === 'PENDING'"
            class="btn-ghost btn-cancel" @click="askCancel"
          >{{ expired ? '立即取消订单（释放库存）' : '取消订单' }}</button>
          <button v-if="order.status === 'PAID'" class="btn-primary" @click="router.push('/orders')">查看我的订单</button>
          <button class="btn-ghost" @click="goBack">返 回</button>
        </div>

        <p class="hint" v-if="order.status === 'PENDING' && expired">💡 已超过 {{ PAY_WINDOW_MINUTES }} 分钟支付窗口，后端正在自动关单（通常 2 分钟内）；想立刻结束可点「立即取消订单」。</p>
        <p class="hint" v-else-if="order.status === 'PENDING'">💡 不支付也别关演示页：{{ PAY_WINDOW_MINUTES }} 分钟后（或把后端 PAY_TIMEOUT_DELAY_LEVEL 调短）可以看到「超时未支付自动关单」——由 RocketMQ 延迟消息触发；也可以点「取消订单」立即关单并退回库存/名额。</p>
      </template>

      <div v-else class="empty"><div class="empty-emoji">⏳</div><div class="empty-text">加载中…</div></div>
    </div>

    <!-- 确认弹窗：支付与取消共用，避免手滑完成不可撤销操作 -->
    <div class="dlg-mask" v-if="confirmBox.show" @click.self="confirmBox.show = false">
      <div class="dlg">
        <div class="dlg-title">{{ confirmBox.title }}</div>
        <div class="dlg-text">{{ confirmBox.text }}</div>
        <div class="dlg-actions">
          <button class="dlg-btn ghost" :disabled="confirmBox.busy" @click="confirmBox.show = false">再想想</button>
          <button
            class="dlg-btn" :class="confirmBox.kind === 'cancel' ? 'danger' : 'primary'"
            :disabled="confirmBox.busy" @click="doConfirm"
          >{{ confirmBox.busy ? '处理中…' : confirmBox.okText }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cashier-page { min-height: 100%; background: #f5f7fa; padding-bottom: 24px; }
.nav-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; background: #fff; border-bottom: 1px solid #eee;
  position: sticky; top: 0; z-index: 5;
}
.back { font-size: 22px; color: #333; cursor: pointer; line-height: 1; padding: 0 6px; }
.title { font-size: 16px; font-weight: 700; color: #1a1a1a; }

.status-card {
  margin: 14px 16px; padding: 18px 16px; border-radius: 12px;
  text-align: center; color: #fff;
}
.st-pending { background: linear-gradient(135deg, #00BFA5, #009688); }
.st-expired { background: linear-gradient(135deg, #FFA726, #EF6C00); }
.st-paid { background: linear-gradient(135deg, #4CAF50, #388E3C); }
.st-closed { background: linear-gradient(135deg, #90A4AE, #607D8B); }
.st-main { font-size: 18px; font-weight: 700; }
.countdown { font-variant-numeric: tabular-nums; letter-spacing: 1px; }
.st-sub { font-size: 12px; opacity: .92; margin-top: 6px; }

.order-card {
  margin: 0 16px; background: #fff; border-radius: 12px; padding: 14px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.obody { display: flex; gap: 12px; align-items: flex-start; }
.oimg { width: 64px; height: 64px; border-radius: 10px; overflow: hidden; flex-shrink: 0; background: #f8f8f8; }
.oimg img { width: 100%; height: 100%; object-fit: cover; }
.oinfo { flex: 1; min-width: 0; }
.oname { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.oqty { font-size: 12px; color: #999; margin-top: 4px; }
.ometa { font-size: 11px; color: #bbb; margin-top: 6px; }
.oprices { text-align: right; }
.oamt { font-size: 15px; font-weight: 700; color: #E53935; }
.odiscount { font-size: 11px; color: #FF9800; margin-top: 4px; }
/* 购物车合并单的明细块 */
.oitems {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fafbfc;
  border-radius: 8px;
}
.oitem {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: #555;
  padding: 3px 0;
}
.oin-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.oin-sub { flex-shrink: 0; color: #888; }
.pay-line {
  display: flex; justify-content: space-between; align-items: center;
  border-top: 1px dashed #eee; margin-top: 12px; padding-top: 12px;
  font-size: 13px; color: #666;
}
.pay-amount { font-size: 20px; font-weight: 800; color: #E53935; }
.order-no { font-size: 11px; color: #bbb; margin-top: 10px; word-break: break-all; }

.actions { display: flex; flex-direction: column; gap: 10px; margin: 16px; }
.btn-primary {
  border: none; background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff; font-size: 15px; font-weight: 600; padding: 12px;
  border-radius: 24px; cursor: pointer;
}
.btn-primary:disabled { background: #ccc; cursor: not-allowed; }
.btn-ghost {
  border: 1px solid #d0d0d0; background: #fff; color: #666;
  font-size: 14px; padding: 11px; border-radius: 24px; cursor: pointer;
}
.hint { font-size: 11px; color: #aaa; margin: 4px 16px; line-height: 1.6; }
.btn-cancel { color: #E53935; border-color: #ffcdd2; }

/* ===== 确认弹窗 ===== */
.dlg-mask {
  position: fixed; inset: 0; background: rgba(0, 0, 0, .45);
  display: flex; align-items: center; justify-content: center; z-index: 50;
  padding: 0 28px;
}
.dlg {
  width: 100%; max-width: 320px; background: #fff; border-radius: 16px;
  padding: 20px 18px 16px; box-shadow: 0 12px 32px rgba(0, 0, 0, .18);
}
.dlg-title { font-size: 16px; font-weight: 700; color: #1a1a1a; text-align: center; }
.dlg-text { font-size: 13px; color: #666; line-height: 1.7; margin-top: 10px; text-align: center; }
.dlg-actions { display: flex; gap: 10px; margin-top: 18px; }
.dlg-btn {
  flex: 1; border: none; font-size: 14px; font-weight: 600;
  padding: 11px 0; border-radius: 22px; cursor: pointer;
}
.dlg-btn.ghost { background: #f2f4f6; color: #666; }
.dlg-btn.primary { background: linear-gradient(135deg, #00BFA5, #009688); color: #fff; }
.dlg-btn.danger { background: linear-gradient(135deg, #EF5350, #E53935); color: #fff; }
.dlg-btn:disabled { opacity: .6; cursor: not-allowed; }
</style>
