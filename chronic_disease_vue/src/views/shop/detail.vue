<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useStore } from '@/store/demo'
import { medImage } from '@/utils/images'

const router = useRouter()
const route = useRoute()
const { state, buy, exchange, loadMyCouponsForDetail, searchMedicines, loadAccount } = useStore()

const detailQty = ref(1)
const detailCoupon = ref('')

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

async function handleBuy() {
  if (!currentMed.value) return
  state.detailQty = detailQty.value
  state.detailCoupon = detailCoupon.value
  const r = await buy(currentMed.value.id)
  // 现金单停在 PENDING（渠道未自动确认）→ 进收银台完成「确认支付/返回/超时关单」演示
  if (r && r.pending && r.orderId) {
    router.push('/shop/cashier/' + r.orderId)
  }
}

function handleExchange() {
  if (!currentMed.value) return
  state.detailQty = detailQty.value
  state.detailCoupon = detailCoupon.value
  exchange(currentMed.value.id)
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

      <!-- 购买数量 -->
      <div class="detail-card">
        <div class="card-title">购买数量</div>
        <div class="qty-box">
          <button class="qty-btn" @click="dec" :disabled="detailQty <= 1">−</button>
          <span class="qty-num">{{ detailQty }}</span>
          <button class="qty-btn" @click="inc" :disabled="detailQty >= 99">+</button>
        </div>
      </div>

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

    <!-- 支付方式（余额支付 = 本项目自建的余额账户，同步扣款；现金 = 模拟渠道演示回调） -->
    <div class="pay-method" v-if="currentMed">
      <div class="pm-title">支付方式</div>
      <div class="pm-group">
        <div class="pm-item" :class="{ active: state.payMethod !== 'BALANCE' }" @click="state.payMethod = 'CASH'">
          现金支付<span class="pm-sub">模拟渠道</span>
        </div>
        <div class="pm-item" :class="{ active: state.payMethod === 'BALANCE' }" @click="state.payMethod = 'BALANCE'">
          余额支付<span class="pm-sub">可用 ￥{{ Number(state.balance || 0).toFixed(2) }}</span>
        </div>
      </div>
    </div>

    <!-- 底部购买栏 -->
    <div class="buy-bar" v-if="currentMed">
      <div class="bar-qty">数量 ×{{ detailQty }}</div>
      <button class="btn-primary" @click="handleBuy">{{ state.payMethod === 'BALANCE' ? '余额购买' : '立即购买' }}</button>
      <button
        v-if="currentMed.pointsPrice > 0"
        class="btn-gold"
        @click="handleExchange"
      >积分兑换</button>
    </div>
  </div>
</template>

<style scoped>
.detail-page { padding-bottom: 0; }

/* 支付方式选择 */
.pay-method {
  margin: 12px 16px;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.pm-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 10px;
}
.pm-group { display: flex; gap: 10px; }
.pm-item {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #eee;
  border-radius: 8px;
  font-size: 14px;
  color: #555;
  cursor: pointer;
  text-align: center;
}
.pm-item.active {
  border-color: #00BFA5;
  color: #009688;
  background: #E0F7F4;
  font-weight: 600;
}
.pm-sub {
  display: block;
  font-size: 11px;
  color: #999;
  margin-top: 2px;
}

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

/* 数量选择 */
.qty-box {
  display: flex;
  align-items: center;
  gap: 16px;
}
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

/* 底部购买栏 */
.buy-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid #eee;
  box-shadow: 0 -2px 10px rgba(0,0,0,.04);
}
.bar-qty {
  flex: 1;
  font-size: 14px;
  color: #666;
}
.btn-primary, .btn-gold {
  border: none;
  border-radius: 24px;
  padding: 11px 24px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
  transition: opacity .2s;
}
.btn-primary:active, .btn-gold:active { opacity: .85; }
.btn-primary {
  background: linear-gradient(135deg, #00BFA5, #009688);
}
.btn-gold {
  background: linear-gradient(135deg, #FFB74D, #FF9800);
}
</style>
