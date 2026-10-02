<template>
  <div class="wallet-page">
    <div class="nav-bar">
      <span class="back" @click="$router.back()">‹</span>
      <span class="title">我的钱包</span>
    </div>
    <div class="page-pad">
      <!-- 余额卡片 + 充值 -->
      <div class="balance-card">
        <div class="balance-label">账户余额（元）</div>
        <div class="balance-num">￥{{ Number(state.balance || 0).toFixed(2) }}</div>
        <div class="recharge-row">
          <button
            v-for="amt in quickAmounts"
            :key="amt"
            class="recharge-btn"
            :class="{ active: customAmount === '' && pendingAmount === amt }"
            @click="onQuick(amt)"
          >￥{{ amt }}</button>
          <input
            v-model="customAmount"
            class="recharge-input"
            type="number"
            min="0"
            max="5000"
            placeholder="自定义"
            @input="pendingAmount = 0"
          >
        </div>
        <button class="recharge-submit" :disabled="recharging" @click="onRecharge">
          {{ recharging ? '充值中…' : '充值（模拟渠道）' }}
        </button>
        <div class="recharge-tip">充值即模拟第三方支付成功回调入账，不对接微信/支付宝</div>
      </div>

      <!-- 余额流水 -->
      <div class="section-head">
        <span class="section-title">余额流水</span>
        <span class="section-more" @click="loadAccountRecords()">刷新 ›</span>
      </div>
      <div class="records" v-if="state.accountRecords.length">
        <div class="record" v-for="r in state.accountRecords" :key="r.id">
          <div class="record-main">
            <div class="record-title">{{ accountTypeText(r.type) }}</div>
            <div class="record-meta">{{ r.remark || '—' }} · {{ formatTime(r.createTime) }}</div>
          </div>
          <div class="record-amount" :class="r.amount >= 0 ? 'in' : 'out'">
            {{ r.amount >= 0 ? '+' : '' }}{{ Number(r.amount).toFixed(2) }}
          </div>
        </div>
      </div>
      <div class="empty" v-else>暂无流水</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useStore } from '@/store/demo'
import { formatTime, accountTypeText } from '@/utils/format'

const { state, toast, loadAccount, loadAccountRecords, recharge } = useStore()

const quickAmounts = [50, 100, 500]
const pendingAmount = ref(0)
const customAmount = ref('')
const recharging = ref(false)

function onQuick(amt) {
  pendingAmount.value = amt
  customAmount.value = ''
}

async function onRecharge() {
  const amount = customAmount.value !== '' ? Number(customAmount.value) : pendingAmount.value
  if (!amount || amount <= 0) {
    toast('请先选择或输入充值金额')
    return
  }
  recharging.value = true
  try {
    const r = await recharge(amount)
    toast(r.ok ? (r.mock ? `已充值 ￥${Number(amount).toFixed(2)}（演示，未提交服务器）` : '充值成功') : (r.msg || '充值失败'))
    if (r.ok) {
      customAmount.value = ''
      pendingAmount.value = 0
    }
  } finally {
    recharging.value = false
  }
}

onMounted(() => {
  loadAccount()
  loadAccountRecords()
})
</script>

<style scoped>
.wallet-page { padding-bottom: 20px; }
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
.page-pad { padding: 12px 16px; }

.balance-card {
  background: linear-gradient(135deg, #00BFA5, #009688);
  border-radius: 16px;
  padding: 20px 16px;
  color: #fff;
}
.balance-label { font-size: 13px; opacity: .85; }
.balance-num { font-size: 30px; font-weight: 700; margin: 6px 0 14px; }
.recharge-row { display: flex; gap: 8px; align-items: center; }
.recharge-btn {
  flex: 1;
  padding: 8px 0;
  border: 1px solid rgba(255,255,255,.6);
  border-radius: 8px;
  background: rgba(255,255,255,.12);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.recharge-btn.active {
  background: #fff;
  color: #009688;
  font-weight: 700;
}
.recharge-input {
  flex: 1.2;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(255,255,255,.6);
  border-radius: 8px;
  background: rgba(255,255,255,.12);
  color: #fff;
  font-size: 14px;
}
.recharge-input::placeholder { color: rgba(255,255,255,.75); }
.recharge-submit {
  width: 100%;
  margin-top: 12px;
  padding: 10px 0;
  border: none;
  border-radius: 10px;
  background: #fff;
  color: #009688;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}
.recharge-submit:disabled { opacity: .6; cursor: default; }
.recharge-tip { margin-top: 8px; font-size: 11px; opacity: .8; }

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 20px 0 10px;
}
.section-title { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.section-more { font-size: 12px; color: #999; cursor: pointer; }

.records {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.record {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 14px;
  border-bottom: 1px solid #f5f5f5;
}
.record:last-child { border-bottom: none; }
.record-title { font-size: 14px; color: #333; font-weight: 500; }
.record-meta { font-size: 11px; color: #999; margin-top: 3px; }
.record-amount { font-size: 15px; font-weight: 700; }
.record-amount.in { color: #00BFA5; }
.record-amount.out { color: #FF5252; }
.empty { text-align: center; color: #bbb; font-size: 13px; padding: 26px 0; }
</style>
