<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import { orderImg } from '@/utils/images'
import { formatTime, statusText, payText } from '@/utils/format'

const router = useRouter()
const { state, loadOrders, cancelOrder } = useStore()

// ===== 待支付倒计时（列表内展示） =====
// 展示窗口默认 30 分钟，与后端 chronic.pay.timeout-delay-level=16（30m）对齐；
// 权威超时在服务端延迟消息（30m + 2m 宽限 ≈ 32 分钟），这里只是展示。
// 可用 VITE_PAY_WINDOW_MINUTES 覆盖（演示时改成 1，配合后端把延迟档位调小，
// 几十秒就能看到「已超时 → 自动关单」的全过程）。
const PAY_WINDOW_MINUTES = Number(import.meta.env.VITE_PAY_WINDOW_MINUTES || 30)
const nowTs = ref(Date.now())
let tickTimer = null
function startTick() {
  clearInterval(tickTimer)
  nowTs.value = Date.now()
  tickTimer = setInterval(() => (nowTs.value = Date.now()), 1000)
}
/** 已超过展示窗口但后端还没关单（延迟消息在 2 分钟宽限后到）——列表里要显式区分，别停在 00:00 */
function isExpired(o) {
  if (!o.createTime) return false
  return nowTs.value >= new Date(o.createTime).getTime() + PAY_WINDOW_MINUTES * 60 * 1000
}
function remainText(o) {
  if (!o.createTime) return ''
  const expire = new Date(o.createTime).getTime() + PAY_WINDOW_MINUTES * 60 * 1000
  const s = Math.max(0, Math.floor((expire - nowTs.value) / 1000))
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
}
// 页面切走即卸载，定时器随 onUnmounted 清理即可
onMounted(() => {
  loadOrders()
  startTick()
})
onUnmounted(() => clearInterval(tickTimer))

// 待支付的现金单：点卡片（商品图/信息区）进收银台，与「继续支付」按钮同行为；
// 其他状态的卡片点击无动作（保持原有行为不变）
function openOrder(o) {
  if (o.status === 'PENDING' && o.payType === 'CASH') {
    router.push('/shop/cashier/' + o.id)
  }
}
</script>

<template>
  <div class="orders-page">
    <div class="nav-bar">
      <span class="back" @click="router.back()">‹</span>
      <span class="title">我的订单</span>
    </div>

    <div class="page-pad">
      <div v-if="state.orders.length === 0" class="empty">
        <div class="empty-emoji">📦</div>
        <div class="empty-text">暂无订单，去商城逛逛吧</div>
      </div>

      <div v-for="o in state.orders" :key="o.id" class="order-card">
        <div class="ohead">
          <span class="ono">订单号 {{ o.orderNo }}<span v-if="o.id < 0" class="mock-tag">演示</span></span>
          <span
            class="ostatus"
            :class="o.status === 'PAID' ? 'st-success' : o.status === 'CANCELLED' ? 'st-info' : 'st-warning'"
          >
            {{ statusText(o.status) }}
          </span>
        </div>
        <div
          class="obody"
          :class="{ clickable: o.status === 'PENDING' && o.payType === 'CASH' }"
          @click="openOrder(o)"
        >
          <div class="oimg"><img :src="orderImg(o)" alt=""></div>
          <div class="oinfo">
            <div class="oname">{{ o.medicineName }}</div>
            <div class="oqty">数量 ×{{ o.quantity }}</div>
          </div>
          <div class="oprices">
            <div class="oamt">￥{{ Number(o.totalAmount || 0).toFixed(2) }}</div>
            <div class="odiscount" v-if="o.discountAmount > 0">已优惠 ￥{{ Number(o.discountAmount || 0).toFixed(2) }}</div>
            <div class="odiscount refund" v-if="o.refundAmount > 0">已退款 ￥{{ Number(o.refundAmount || 0).toFixed(2) }}</div>
            <div class="odiscount points" v-if="o.pointsUsed > 0">耗积分 {{ o.pointsUsed }}</div>
          </div>
        </div>
        <div class="ofoot">
          <span class="meta">
            {{ payText(o.payType) }} · {{ formatTime(o.createTime) }}
            <span v-if="o.status === 'PENDING' && o.payType === 'CASH'" class="remain">
              {{ isExpired(o) ? '已超时，正在自动关单' : `剩余 ${remainText(o)}` }}
            </span>
          </span>
          <span class="ops">
            <button
              v-if="o.status === 'PENDING' && o.payType === 'CASH'"
              class="btn-pay"
              @click.stop="router.push('/shop/cashier/' + o.id)"
            >
              继续支付
            </button>
            <button
              v-if="o.status === 'PAID'"
              class="btn-cancel"
              @click="cancelOrder(o.id)"
            >
              取消订单
            </button>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.orders-page {
  min-height: 100vh;
  background: #f5f7fa;
  padding-bottom: 20px;
}
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 12px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .04);
}
.nav-bar .back {
  width: 32px;
  text-align: center;
  font-size: 28px;
  line-height: 1;
  color: #00BFA5;
  cursor: pointer;
}
.nav-bar .title {
  flex: 1;
  margin-left: 4px;
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.page-pad {
  padding: 12px 16px;
}
.empty {
  text-align: center;
  padding: 60px 0;
  color: #999;
}
.empty-emoji {
  font-size: 40px;
}
.empty-text {
  margin-top: 8px;
  font-size: 13px;
}
.order-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .04);
  padding: 14px 16px;
  margin-bottom: 12px;
}
.ohead {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
  color: #888;
}
.obody.clickable { cursor: pointer; }
.ostatus {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}
/* 演示订单标记：mock 订单用负数 id，仅存在于前端兜底数据中（数据库无此单），
   醒目标记避免用户把它当真实订单去理解/对比 */
.mock-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 11px;
  color: #FF9800;
  border: 1px solid rgba(255, 152, 0, .45);
}
.st-success {
  color: #00BFA5;
  background: rgba(0, 191, 165, .12);
}
.st-info {
  color: #999;
  background: rgba(153, 153, 153, .12);
}
.st-warning {
  color: #FF9800;
  background: rgba(255, 152, 0, .12);
}
.obody {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px 0;
}
.oimg {
  width: 64px;
  height: 64px;
  border-radius: 10px;
  overflow: hidden;
  flex-shrink: 0;
  background: #f5f7fa;
}
.oimg img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.oinfo {
  flex: 1;
}
.oname {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}
.oqty {
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}
.oprices {
  text-align: right;
}
.oamt {
  font-size: 17px;
  font-weight: bold;
  color: #FF5252;
}
.odiscount {
  margin-top: 3px;
  font-size: 11px;
  color: #999;
}
.odiscount.refund {
  color: #00BFA5;
}
.odiscount.points {
  color: #FFB74D;
}
.ofoot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
}
.meta {
  font-size: 12px;
  color: #bbb;
}
/* 待支付剩余时间：每秒跳动的倒计时，醒目提示用户尽快支付 */
.remain {
  margin-left: 6px;
  color: #FF9800;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.btn-cancel {
  border: 1px solid #FF5252;
  background: #fff;
  color: #FF5252;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 14px;
  cursor: pointer;
}
.btn-pay {
  border: 1px solid #00BFA5;
  background: #E0F7F4;
  color: #009688;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 14px;
  cursor: pointer;
}
</style>
