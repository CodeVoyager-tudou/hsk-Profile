<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import { medImage } from '@/utils/images'

const router = useRouter()
const { state, loadCart, updateCartItem, removeCartItem, clearCartAll, checkoutCart, loadMyCouponsForDetail } = useStore()

// 勾选的是 cart_item.id（结算后端会删除这些行），失效条目不可勾选
const checked = ref(new Set())
const showCheckout = ref(false)
const couponId = ref('')
const submitting = ref(false)

const validItems = computed(() => state.cartItems.filter(i => !i.invalid))
const allChecked = computed(() =>
  validItems.value.length > 0 && validItems.value.every(i => checked.value.has(i.id)))

const checkedItems = computed(() => state.cartItems.filter(i => checked.value.has(i.id)))
const totalAmount = computed(() =>
  checkedItems.value.reduce((sum, i) => sum + Number(i.price) * i.quantity, 0))

// 勾选条目合计是否够得着某张券的门槛：不够的在结算弹窗里标注"差 X 元"，避免点了才报错
function couponReachable(c) {
  return totalAmount.value >= Number(c.thresholdAmount)
}

function toggle(id) {
  const next = new Set(checked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checked.value = next
}

function toggleAll() {
  if (allChecked.value) {
    checked.value = new Set()
  } else {
    checked.value = new Set(validItems.value.map(i => i.id))
  }
}

function dec(i) {
  if (i.quantity <= 1) return
  updateCartItem(i.id, i.quantity - 1)
}
function inc(i) {
  if (i.quantity >= 99 || i.quantity >= i.stock) return
  updateCartItem(i.id, i.quantity + 1)
}

function openCheckout() {
  if (checkedItems.value.length === 0) return
  loadMyCouponsForDetail()
  couponId.value = ''
  showCheckout.value = true
}

async function submitCheckout() {
  if (submitting.value) return
  submitting.value = true
  try {
    const r = await checkoutCart([...checked.value], couponId.value || null, 'BALANCE')
    showCheckout.value = false
    checked.value = new Set()
    // 结算单建后是 PENDING：跳收银台完成「确认支付（此刻才扣余额）/ 返回 / 超时自动关单」
    if (r && r.pending && r.orderId) {
      router.push('/shop/cashier/' + r.orderId)
    }
  } finally {
    submitting.value = false
  }
}

const payables = computed(() => {
  const c = state.detailCoupons.find(x => String(x.id) === String(couponId.value))
  const discount = c ? Number(c.discountAmount) : 0
  return Math.max(0, totalAmount.value - discount)
})

onMounted(() => {
  loadCart()
})
</script>

<template>
  <div class="cart-page">
    <div class="nav-bar">
      <span class="back" @click="router.back()">‹</span>
      <span class="title">购物车</span>
      <span
        class="clear"
        v-if="state.cartItems.length"
        @click="clearCartAll"
      >清空</span>
    </div>

    <!-- 空购物车 -->
    <div class="empty" v-if="!state.cartLoading && state.cartItems.length === 0">
      <div class="empty-icon">🛒</div>
      <p>购物车还是空的</p>
      <button class="btn-primary empty-btn" @click="router.push('/shop/medicine')">去逛逛</button>
    </div>

    <!-- 条目列表 -->
    <div class="cart-list" v-else>
      <div
        v-for="i in state.cartItems"
        :key="i.id"
        class="cart-item"
        :class="{ invalid: i.invalid }"
      >
        <div
          class="checkbox"
          :class="{ checked: checked.has(i.id), disabled: i.invalid }"
          @click="!i.invalid && toggle(i.id)"
        ></div>
        <img class="item-img" :src="medImage({ id: i.medicineId, imageUrl: i.imageUrl, name: i.medicineName })" :alt="i.medicineName">
        <div class="item-body">
          <div class="item-name">{{ i.medicineName }}</div>
          <div class="item-price">￥{{ i.price }}</div>
          <div class="invalid-tip" v-if="i.invalid">
            {{ i.onSale ? '库存不足' : '已下架' }}
          </div>
          <div class="qty-box" v-else>
            <button class="qty-btn" @click="dec(i)" :disabled="i.quantity <= 1">−</button>
            <span class="qty-num">{{ i.quantity }}</span>
            <button class="qty-btn" @click="inc(i)" :disabled="i.quantity >= 99 || i.quantity >= i.stock">+</button>
          </div>
        </div>
        <div class="item-right">
          <div class="item-subtotal">￥{{ i.subtotal }}</div>
          <span class="item-del" @click="removeCartItem(i.id)">删除</span>
        </div>
      </div>
    </div>

    <!-- 底部结算栏 -->
    <div class="settle-bar" v-if="state.cartItems.length">
      <div class="check-all" @click="toggleAll">
        <div class="checkbox" :class="{ checked: allChecked }"></div>
        <span>全选</span>
      </div>
      <div class="settle-total">
        合计：<span class="amount">￥{{ totalAmount.toFixed(2) }}</span>
      </div>
      <button
        class="btn-settle"
        :disabled="checkedItems.length === 0"
        @click="openCheckout"
      >去结算({{ checkedItems.length }})</button>
    </div>

    <!-- 结算确认弹窗 -->
    <div class="dlg-mask" v-if="showCheckout" @click.self="showCheckout = false">
      <div class="dlg">
        <div class="dlg-title">确认订单</div>
        <div class="dlg-goods">
          <div class="dlg-row" v-for="i in checkedItems" :key="i.id">
            <span class="dlg-name">{{ i.medicineName }} × {{ i.quantity }}</span>
            <span>￥{{ i.subtotal }}</span>
          </div>
        </div>
        <div class="dlg-row dlg-sum">
          <span>商品合计</span>
          <span>￥{{ totalAmount.toFixed(2) }}</span>
        </div>
        <select v-model="couponId" class="coupon-select" v-if="state.detailCoupons.length">
          <option value="">不使用优惠券</option>
          <option
            v-for="c in state.detailCoupons"
            :key="c.id"
            :value="c.id"
            :disabled="!couponReachable(c)"
          >{{ c.couponName }}（满{{ c.thresholdAmount }}减{{ c.discountAmount }}）{{ couponReachable(c) ? '' : '— 差 ￥' + (Number(c.thresholdAmount) - totalAmount).toFixed(2) }}</option>
        </select>
        <!-- 购物车结算仅支持余额支付（后端同步扣款，失败整单回滚）；积分兑换是单商品链路 -->
        <div class="pay-line">
          <span>支付方式</span>
          <span class="pay-method-name">余额支付（可用 ￥{{ Number(state.balance || 0).toFixed(2) }}）</span>
        </div>
        <div class="dlg-row dlg-pay">
          <span>应付</span>
          <span class="pay-num">￥{{ payables.toFixed(2) }}</span>
        </div>
        <div class="dlg-btns">
          <button class="btn-ghost" @click="showCheckout = false">再想想</button>
          <button class="btn-primary dlg-ok" :disabled="submitting" @click="submitCheckout">
            {{ submitting ? '下单中...' : '提交订单' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cart-page { padding-bottom: 80px; }

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
.back { font-size: 24px; color: #333; cursor: pointer; padding: 4px 8px 4px 0; }
.title { flex: 1; font-size: 16px; font-weight: 600; color: #1a1a1a; }
.clear { font-size: 13px; color: #999; cursor: pointer; }

.empty { text-align: center; color: #999; padding: 80px 0; font-size: 14px; }
.empty-icon { font-size: 44px; margin-bottom: 12px; }
.empty-btn { margin-top: 20px; border: none; border-radius: 20px; padding: 10px 32px; color: #fff; font-size: 14px; cursor: pointer; }

.cart-list { padding: 10px 16px; }
.cart-item {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border-radius: 12px;
  padding: 12px;
  margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.cart-item.invalid { opacity: .55; }

.checkbox {
  width: 20px;
  height: 20px;
  border: 2px solid #ccc;
  border-radius: 50%;
  flex-shrink: 0;
  cursor: pointer;
  transition: all .15s;
  box-sizing: border-box;
}
.checkbox.checked {
  background: var(--primary);
  border-color: var(--primary);
  position: relative;
}
.checkbox.checked::after {
  content: '';
  position: absolute;
  left: 5px;
  top: 2px;
  width: 5px;
  height: 9px;
  border: solid #fff;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}
.checkbox.disabled { cursor: not-allowed; background: #f0f0f0; }

.item-img {
  width: 72px;
  height: 72px;
  border-radius: 10px;
  object-fit: cover;
  background: #f8f8f8;
  flex-shrink: 0;
}
.item-body { flex: 1; min-width: 0; }
.item-name {
  font-size: 14px;
  font-weight: 500;
  color: #1a1a1a;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.item-price { font-size: 13px; color: var(--danger); margin-top: 4px; }
.invalid-tip { font-size: 12px; color: #E65100; margin-top: 8px; }
.qty-box { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.qty-btn {
  width: 26px;
  height: 26px;
  border: 1px solid #ddd;
  background: #fafafa;
  border-radius: 7px;
  font-size: 15px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #333;
}
.qty-btn:disabled { color: #ccc; cursor: not-allowed; }
.qty-num { font-size: 14px; font-weight: 600; min-width: 22px; text-align: center; }
.item-right { text-align: right; flex-shrink: 0; }
.item-subtotal { font-size: 15px; font-weight: 700; color: #1a1a1a; }
.item-del { font-size: 12px; color: #999; cursor: pointer; margin-top: 10px; display: inline-block; }

.settle-bar {
  position: fixed;
  bottom: 56px;
  left: 50%;
  transform: translateX(-50%);
  width: 100%;
  max-width: 480px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: #fff;
  border-top: 1px solid #eee;
  box-shadow: 0 -2px 10px rgba(0,0,0,.04);
  box-sizing: border-box;
  z-index: 50;
}
@media (min-width: 768px) {
  .settle-bar { max-width: 1100px; }
}
.check-all { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #666; cursor: pointer; }
.settle-total { flex: 1; font-size: 13px; color: #666; }
.settle-total .amount { font-size: 18px; font-weight: 700; color: var(--danger); }
.btn-settle {
  border: none;
  border-radius: 22px;
  padding: 10px 22px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, #00BFA5, #009688);
}
.btn-settle:disabled { background: #ccc; cursor: not-allowed; }

/* 结算弹窗（沿用商城 .dlg-mask/.dlg 风格） */
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
.dlg-title { font-size: 16px; font-weight: 700; color: #1a1a1a; margin-bottom: 12px; }
.dlg-goods { max-height: 160px; overflow-y: auto; }
.dlg-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #555;
  padding: 5px 0;
}
.dlg-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-right: 12px; }
.dlg-sum {
  border-top: 1px dashed #eee;
  margin-top: 6px;
  padding-top: 10px;
  font-weight: 600;
  color: #333;
}
.coupon-select {
  width: 100%;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  background: #fafafa;
  color: #333;
  outline: none;
  margin: 10px 0;
}
.pay-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #555;
  padding: 6px 0;
}
.pay-method-name { color: #009688; font-weight: 600; }
.dlg-pay { font-weight: 700; color: #1a1a1a; font-size: 15px; }
.pay-num { color: var(--danger); font-size: 19px; }
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
.dlg-ok:disabled { opacity: .6; }
</style>
