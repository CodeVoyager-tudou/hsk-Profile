<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import { formatDate, couponStatusText } from '@/utils/format'

const router = useRouter()
const { state, loadActivities, loadMyCoupons, receive } = useStore()

const activeTab = ref('act')
// 领券防连点：请求期间对应卡片按钮转圈并禁用
const receivingId = ref(null)

async function onReceive(couponId) {
  if (receivingId.value !== null) return
  receivingId.value = couponId
  try {
    await receive(couponId)
  } finally {
    receivingId.value = null
  }
}

function switchTab(tab) {
  activeTab.value = tab
  if (tab === 'act') loadActivities()
  else loadMyCoupons()
}

onMounted(() => {
  loadActivities()
  loadMyCoupons()
})
</script>

<template>
  <div class="coupons-page">
    <div class="nav-bar">
      <span class="back" @click="router.back()">‹</span>
      <span class="title">优惠券</span>
    </div>

    <div class="tabs">
      <span
        class="tab"
        :class="{ active: activeTab === 'act' }"
        @click="switchTab('act')"
      >领券中心</span>
      <span
        class="tab"
        :class="{ active: activeTab === 'mine' }"
        @click="switchTab('mine')"
      >我的优惠券</span>
    </div>

    <div class="page-pad">
      <!-- 领券中心 -->
      <template v-if="activeTab === 'act'">
        <div v-if="state.activities.length === 0" class="empty">
          <div class="empty-emoji">🎟️</div>
          <div class="empty-text">暂无进行中的活动</div>
        </div>
        <div v-for="c in state.activities" :key="c.id" class="coupon-card">
          <div class="coupon-inner">
            <div class="c-left">
              <div class="c-name">{{ c.name }}</div>
              <div class="c-cond">满 {{ c.thresholdAmount }} 减 {{ c.discountAmount }}</div>
              <div class="c-limit">
                剩余 {{ c.totalCount - c.issuedCount }}/{{ c.totalCount }} 张 ·
                每人限领 {{ c.limitPerUser }} 张 ·
                {{ formatDate(c.endTime) }} 截止
              </div>
            </div>
            <button
              class="btn-receive"
              :disabled="c.totalCount - c.issuedCount <= 0 || receivingId !== null"
              @click="onReceive(c.id)"
            >
              {{ receivingId === c.id ? '领取中' : (c.totalCount - c.issuedCount <= 0 ? '已领完' : '立即领取') }}
            </button>
          </div>
        </div>
      </template>

      <!-- 我的优惠券 -->
      <template v-else>
        <div v-if="state.myCoupons.length === 0" class="empty">
          <div class="empty-emoji">🎟️</div>
          <div class="empty-text">还没有优惠券，去领券中心看看</div>
        </div>
        <div
          v-for="c in state.myCoupons"
          :key="c.id"
          class="coupon-card"
          :class="{ 'is-used': c.status !== 'UNUSED' }"
        >
          <div class="coupon-inner">
            <div class="c-left">
              <div class="c-name">{{ c.couponName }}</div>
              <div class="c-cond">满 {{ c.thresholdAmount }} 减 {{ c.discountAmount }}</div>
              <div class="c-limit">有效期至 {{ formatDate(c.expireTime) }}</div>
            </div>
            <span
              class="c-tag"
              :class="c.status === 'UNUSED' ? 'tag-unused' : 'tag-used'"
            >
              {{ couponStatusText(c.status) }}
            </span>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.coupons-page {
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
.tabs {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .04);
}
.tab {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  font-size: 14px;
  color: #666;
  background: #f5f7fa;
  border-radius: 8px;
  cursor: pointer;
}
.tab.active {
  color: #fff;
  background: #00BFA5;
  font-weight: 500;
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
.coupon-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .04);
  padding: 16px;
  margin-bottom: 12px;
}
.coupon-card.is-used {
  opacity: .6;
}
.coupon-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.c-left {
  flex: 1;
}
.c-name {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}
.c-cond {
  margin-top: 6px;
  font-size: 18px;
  font-weight: bold;
  color: #FF5252;
}
.c-limit {
  margin-top: 6px;
  font-size: 11px;
  color: #999;
}
.btn-receive {
  flex-shrink: 0;
  border: none;
  background: #00BFA5;
  color: #fff;
  font-size: 13px;
  padding: 8px 16px;
  border-radius: 16px;
  cursor: pointer;
}
.btn-receive:disabled {
  background: #c8c8c8;
  cursor: not-allowed;
}
.c-tag {
  flex-shrink: 0;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 10px;
  font-weight: 500;
}
.tag-unused {
  color: #00BFA5;
  background: rgba(0, 191, 165, .12);
}
.tag-used {
  color: #999;
  background: rgba(153, 153, 153, .12);
}
</style>
