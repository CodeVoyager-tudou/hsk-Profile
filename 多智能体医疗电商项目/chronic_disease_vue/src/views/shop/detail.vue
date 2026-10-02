<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useStore } from '@/store/demo'
import { medImage } from '@/utils/images'

const router = useRouter()
const route = useRoute()
const { state, buy, exchange, addToCart, loadMyCouponsForDetail, searchMedicines, loadAccount, loadBalance } = useStore()

const detailQty = ref(1)
const detailCoupon = ref('')
/** 支付方式弹层：点「立即购买」后才展开（京东式——详情页不铺支付方式，选完再下单） */
const showPaySheet = ref(false)
const submitting = ref(false)

const currentMed = computed(() => {
  if (state.currentMed && String(state.currentMed.id) === String(route.params.id)) {
    return state.currentMed
  }
  const found = state.medicines.find(m => String(m.id) === String(route.params.id))
  return found || null
})

const detailCoupons = computed(() => state.detailCoupons)

function dec() {
  detailQty.value = Math.max(1, detailQty.value - 1)
}
function inc() {
  detailQty.value = Math.min(99, detailQty.value + 1)
}

// ===== 资产校验（点「立即购买」时按数量与券实时计算，不足的支付方式不可选） =====

/** 商品合计（未减券） */
const totalAmount = computed(() =>
  currentMed.value ? Number(currentMed.value.price) * detailQty.value : 0)

/** 选中的券（需满足门槛才参与抵扣，与后端同一判据） */
const activeCoupon = computed(() => {
  const c = state.detailCoupons.find(x => String(x.id) === String(detailCoupon.value))
  if (!c) return null
  return totalAmount.value >= Number(c.thresholdAmount) ? c : null
})

/** 应付金额（余额支付口径）= 合计 - 券抵扣 */
const payable = computed(() =>
  Math.max(0, totalAmount.value - (activeCoupon.value ? Number(activeCoupon.value.discountAmount) : 0)))

/** 本单需消耗的积分 */
const pointsNeed = computed(() => {
  const m = currentMed.value
  return m && m.pointsPrice > 0 ? Number(m.pointsPrice) * detailQty.value : 0
})

const balanceAvail = computed(() => Number(state.balance || 0))
const pointsAvail = computed(() =>
  Math.max(0, Number(state.points.total || 0) - Number(state.points.used || 0)))

/** 余额是否够付（1e-6 容差避免浮点尾差误判） */
const balanceEnough = computed(() => balanceAvail.value + 1e-6 >= payable.value)
/** 积分是否够付：商品需支持积分兑换且可用积分足够 */
const pointsPayable = computed(() => pointsNeed.value > 0)
const pointsEnough = computed(() => pointsPayable.value && pointsAvail.value >= pointsNeed.value)

/** 打开支付方式弹层前刷新余额/积分，保证校验基于最新资产（不是页面打开时的旧快照） */
async function openPaySheet() {
  if (!currentMed.value) return
  state.detailQty = detailQty.value
  state.detailCoupon = detailCoupon.value
  await Promise.all([loadAccount(), loadBalance()])
  // 默认选中可用的方式：优先上次选择，其次余额
  if (state.payMethod === 'POINTS' && !pointsEnough.value) state.payMethod = 'BALANCE'
  if (state.payMethod !== 'POINTS' && !balanceEnough.value && pointsEnough.value) state.payMethod = 'POINTS'
  showPaySheet.value = true
}

/** 弹层里确认：按选定支付方式建 PENDING 单 → 统一进收银台确认支付 */
async function submitOrder() {
  if (submitting.value || !currentMed.value) return
  if (state.payMethod === 'POINTS' && !pointsEnough.value) return
  if (state.payMethod !== 'POINTS' && !balanceEnough.value) return
  submitting.value = true
  try {
    const r = state.payMethod === 'POINTS'
      ? await exchange(currentMed.value.id)
      : await buy(currentMed.value.id)
    if (r && r.pending && r.orderId) {
      showPaySheet.value = false
      router.push('/shop/cashier/' + r.orderId)
    } else if (r && r.ok) {
      showPaySheet.value = false
    }
  } finally {
    submitting.value = false
  }
}

function handleAddCart() {
  if (!currentMed.value) return
  addToCart(currentMed.value.id, detailQty.value)
}

onMounted(() => {
  if (!currentMed.value) {
    searchMedicines(1)
  }
  loadMyCouponsForDetail()
  loadAccount()
})

watch(() => route.params.id, () => {
  detailQty.value = 1
  detailCoupon.value = ''
  loadMyCouponsForDetail()
})
</script>

<template>
  <div class="detail-page">
    <!-- 返回栏 -->
    <div class="nav-bar">
      <span class="back" @click="router.back()">‹</span>
      <span class="title">药品详情</span>
    </div>

    <div class="detail-wrap" v-if="currentMed">
      <!-- 商品图片 -->
      <div class="detail-img">
        <img :src="medImage(currentMed)" :alt="currentMed.name">
      </div>

      <!-- 价格信息 -->
      <div class="detail-info">
        <div class="detail-price">
          <span class="cur">￥</span><span class="num">{{ currentMed.price }}</span>
        </div>
        <div v-if="currentMed.pointsPrice > 0" class="detail-points">
          积分兑换 {{ currentMed.pointsPrice }} 分/件
        </div>
        <div class="detail-name">{{ currentMed.name }}</div>
        <div class="detail-tags">
          <span class="tag">{{ currentMed.category || '药品' }}</span>
          <span class="tag-gray">库存 {{ currentMed.stock }}</span>
        </div>
      </div>

      <!-- 适应症 -->
      <div class="detail-card">
        <div class="card-title">适应症</div>
        <p class="card-text">{{ currentMed.indication || '暂无说明' }}</p>
      </div>

      <!-- 购买信息 -->
      <div class="detail-card">
        <div class="card-title">购买信息</div>
        <div class="info-row">
          <span class="info-label">单价</span>
          <span class="info-value">￥{{ currentMed.price }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">积分兑换价</span>
          <span class="info-value">{{ currentMed.pointsPrice > 0 ? currentMed.pointsPrice + ' 分/件' : '不支持' }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">库存</span>
          <span class="info-value">{{ currentMed.stock }}</span>
        </div>
      </div>

      <!-- 购买数量（数量步进常驻在底部购买栏，这里不再重复放一张卡片） -->

      <!-- 选择优惠券 -->
      <div class="detail-card" v-if="detailCoupons.length">
        <div class="card-title">选择优惠券</div>
        <select v-model="detailCoupon" class="coupon-select">
          <option value="">不使用优惠券</option>
          <option
            v-for="c in detailCoupons"
            :key="c.id"
            :value="c.id"
          >{{ c.couponName }}（满{{ c.thresholdAmount }}减{{ c.discountAmount }}）</option>
        </select>
      </div>
    </div>

    <div class="empty" v-else>未选择商品</div>

    <!-- 底部购买栏（京东式：sticky 固定在页面底部，占文档流位置所以不遮挡内容；
         支付方式不铺在详情里，点「立即购买」后在弹层里选） -->
    <div class="buy-bar" v-if="currentMed">
      <div class="bar-qty">
        <button class="qty-btn" @click="dec" :disabled="detailQty <= 1">−</button>
        <span class="qty-num">{{ detailQty }}</span>
        <button class="qty-btn" @click="inc" :disabled="detailQty >= 99 || detailQty >= currentMed.stock">+</button>
      </div>
      <button class="btn-cart" @click="handleAddCart">加入购物车</button>
      <button class="btn-primary" @click="openPaySheet">立即购买</button>
    </div>

    <!-- 支付方式弹层：选余额/积分 + 资产校验（不足的置灰并说明差多少） -->
    <div class="dlg-mask" v-if="showPaySheet" @click.self="showPaySheet = false">
      <div class="dlg">
        <div class="dlg-title">选择支付方式</div>
        <div class="dlg-row">
          <span class="dlg-name">{{ currentMed.name }} × {{ detailQty }}</span>
          <span>￥{{ totalAmount.toFixed(2) }}</span>
        </div>
        <div class="dlg-row" v-if="activeCoupon">
          <span>优惠券抵扣</span>
          <span class="dlg-discount">-￥{{ Number(activeCoupon.discountAmount).toFixed(2) }}</span>
        </div>

        <div class="pm-group">
          <div
            class="pm-item"
            :class="{ active: state.payMethod !== 'POINTS', disabled: !balanceEnough }"
            @click="balanceEnough && (state.payMethod = 'BALANCE')"
          >
            余额支付
            <span class="pm-sub">可用 ￥{{ balanceAvail.toFixed(2) }}</span>
            <span class="pm-warn" v-if="!balanceEnough">余额不足（差 ￥{{ (payable - balanceAvail).toFixed(2) }}）</span>
          </div>
          <div
            class="pm-item"
            :class="{ active: state.payMethod === 'POINTS', disabled: !pointsEnough }"
            @click="pointsEnough && (state.payMethod = 'POINTS')"
          >
            积分支付
            <span class="pm-sub">可用 {{ pointsAvail }} 分</span>
            <span class="pm-warn" v-if="!pointsPayable">该商品不支持积分支付</span>
            <span class="pm-warn" v-else-if="!pointsEnough">积分不足（差 {{ pointsNeed - pointsAvail }} 分）</span>
          </div>
        </div>

        <div class="dlg-row dlg-pay">
          <span>应付</span>
          <span class="pay-num">
            {{ state.payMethod === 'POINTS' ? pointsNeed + ' 积分' : '￥' + payable.toFixed(2) }}
          </span>
        </div>
        <div class="dlg-btns">
          <button class="btn-ghost" @click="showPaySheet = false">再想想</button>
          <button
            class="btn-primary dlg-ok"
            :disabled="submitting || (state.payMethod === 'POINTS' ? !pointsEnough : !balanceEnough)"
            @click="submitOrder"
          >{{ submitting ? '提交中...' : '提交订单' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 底部留白：购买栏 sticky 停靠时与最后一张卡片保持间距，不贴脸也不遮挡 */
.detail-page { padding-bottom: 10px; }

/* ===== 支付方式弹层（点「立即购买」后展开） ===== */
.dlg-mask {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,.45);
  z-index: 200;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.dlg {
  width: 100%;
  max-width: 480px;
  background: #fff;
  border-radius: 16px 16px 0 0;
  padding: 18px 18px 22px;
  box-sizing: border-box;
}
@media (min-width: 768px) {
  .dlg { max-width: 480px; }
}
.dlg-title { font-size: 16px; font-weight: 700; color: #1a1a1a; margin-bottom: 12px; }
.dlg-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #555;
  padding: 5px 0;
}
.dlg-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-right: 12px; }
.dlg-discount { color: #FF9800; }
.dlg-pay { font-weight: 700; color: #1a1a1a; font-size: 15px; border-top: 1px dashed #eee; margin-top: 6px; padding-top: 10px; }
.pay-num { color: var(--danger); font-size: 19px; }
.pm-group { display: flex; gap: 10px; margin: 12px 0 4px; }
.pm-item {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #eee;
  border-radius: 8px;
  font-size: 14px;
  color: #555;
  cursor: pointer;
  text-align: center;
  /* 两个选项内容行数不同（有无"不足"提示）：固定高度让它们一样高 */
  min-height: 76px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.pm-item.active {
  border-color: #00BFA5;
  color: #009688;
  background: #E0F7F4;
  font-weight: 600;
}
.pm-item.disabled {
  color: #bbb;
  background: #fafafa;
  cursor: not-allowed;
}
.pm-sub {
  display: block;
  font-size: 11px;
  color: #999;
  margin-top: 2px;
}
.pm-item.disabled .pm-sub { color: #ccc; }
.pm-warn {
  display: block;
  font-size: 10px;
  color: #E53935;
  margin-top: 2px;
}
.dlg-btns { display: flex; gap: 10px; margin-top: 14px; }
.btn-ghost {
  flex: 1;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 22px;
  padding: 11px 0;
  font-size: 14px;
  color: #666;
  cursor: pointer;
}
.dlg-ok { flex: 2; border: none; border-radius: 22px; padding: 11px 0; font-size: 14px; font-weight: 600; color: #fff; cursor: pointer; background: linear-gradient(135deg, #00BFA5, #009688); }
.dlg-ok:disabled { background: #ccc; cursor: not-allowed; }

/* 返回栏 */
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  height: 48px;
  background: #fff;
  padding: 0 16px;
  border-bottom: 1px solid #f0f0f0;
}
.back {
  font-size: 24px;
  color: #333;
  cursor: pointer;
  padding: 4px 8px 4px 0;
}
.title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
}

/* 商品图片 */
.detail-img {
  width: 100%;
  height: 300px;
  background: #f8f8f8;
}
.detail-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 价格信息 */
.detail-info {
  background: #fff;
  padding: 16px;
  margin-bottom: 10px;
}
.detail-price {
  color: var(--danger);
  font-weight: bold;
  display: flex;
  align-items: baseline;
  gap: 2px;
}
.detail-price .cur { font-size: 14px; }
.detail-price .num { font-size: 28px; }
.detail-points {
  display: inline-block;
  margin-top: 8px;
  font-size: 12px;
  color: #FFB74D;
  background: #FFF3E0;
  padding: 3px 10px;
  border-radius: 6px;
}
.detail-name {
  font-size: 18px;
  font-weight: 700;
  color: #1a1a1a;
  margin-top: 10px;
}
.detail-tags {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.tag {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 6px;
  background: #E0F7F4;
  color: #00BFA5;
}
.tag-gray {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 6px;
  background: #f5f5f5;
  color: #999;
}

/* 卡片 */
.detail-card {
  background: #fff;
  margin: 10px 16px;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 10px;
}
.card-text {
  font-size: 14px;
  color: #666;
  line-height: 1.7;
  margin: 0;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
}
.info-label { color: #999; }
.info-value { color: #333; font-weight: 500; }

/* 数量步进（底栏内使用，见 .bar-qty 的尺寸覆盖） */
.qty-btn {
  width: 32px;
  height: 32px;
  border: 1px solid #ddd;
  background: #fafafa;
  border-radius: 8px;
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #333;
  transition: all .15s;
}
.qty-btn:active { background: #e8e8e8; }
.qty-btn:disabled { color: #ccc; cursor: not-allowed; }
.qty-num {
  font-size: 16px;
  font-weight: 600;
  min-width: 30px;
  text-align: center;
}

.coupon-select {
  width: 100%;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
  background: #fafafa;
  color: #333;
  outline: none;
}

.empty {
  text-align: center;
  color: #999;
  padding: 60px 0;
  font-size: 14px;
}

/* 底部购买栏：京东式固定在滚动容器底部。
   页面做成 flex 列 + 购买栏 margin-top:auto —— 内容不足一屏时它也被推到屏幕底部；
   position: sticky 保证内容超过一屏滚动时它钉在底部，且仍占文档流位置，
   滚到底自然"落在"内容之后，不会压住最后一张卡片。 */
.detail-page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding-bottom: 0;
}
.detail-wrap { flex: 1; }

.buy-bar {
  position: sticky;
  bottom: 0;
  z-index: 20;
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: #fff;
  border-top: 1px solid #eee;
  box-shadow: 0 -2px 10px rgba(0,0,0,.06);
}
/* 数量步进（京东也在底栏）；flex:1 把右侧两个按钮顶到右边 */
.bar-qty {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 2px;
}
.bar-qty .qty-btn {
  width: 28px;
  height: 28px;
  font-size: 16px;
  border-radius: 6px;
}
.bar-qty .qty-num {
  min-width: 34px;
  font-size: 15px;
}
.btn-primary, .btn-cart {
  border: none;
  border-radius: 24px;
  padding: 11px 22px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
  transition: opacity .2s;
  white-space: nowrap;
}
.btn-primary:active, .btn-cart:active { opacity: .85; }
.btn-primary {
  background: linear-gradient(135deg, #00BFA5, #009688);
}
.btn-cart {
  border: 1.5px solid #00BFA5;
  background: #fff;
  color: #009688;
  font-size: 14px;
  padding: 10px 18px;
}
.pm-item.disabled {
  color: #bbb;
  background: #fafafa;
  cursor: not-allowed;
}
.pm-item.disabled .pm-sub { color: #ccc; }
</style>
