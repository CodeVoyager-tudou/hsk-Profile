<template>
  <div class="home-page">
    <!-- 顶部用户区 -->
    <div class="home-header">
      <div class="header-top">
        <div class="user-info" @click="$router.push('/user/profile')">
          <div class="user-avatar">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="rgba(255,255,255,.9)"><path d="M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4 0-8 2-8 6v2h16v-2c0-4-4-6-8-6z"/></svg>
          </div>
          <div>
            <div class="greeting">{{ greetingText }}</div>
            <div class="username">{{ state.username || '健康用户' }}</div>
          </div>
        </div>
        <div class="header-icon" @click="$router.push('/coupons')">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="rgba(255,255,255,.85)"><path d="M21.4 11.6l-9-9a2 2 0 00-2.8 0l-9 9a2 2 0 000 2.8l9 9a2 2 0 002.8 0l9-9a2 2 0 000-2.8zM12 15a3 3 0 110-6 3 3 0 010 6z"/></svg>
        </div>
      </div>
    </div>

    <!-- 健康概览卡片 -->
    <div class="health-card">
      <div class="health-row">
        <div class="health-item">
          <div class="health-num">{{ state.points.total - state.points.used }}</div>
          <div class="health-label">可用积分</div>
        </div>
        <div class="health-divider"></div>
        <div class="health-item">
          <div class="health-num">{{ (state.myCoupons || []).filter(c => c.status === 'UNUSED').length }}</div>
          <div class="health-label">可用券</div>
        </div>
        <div class="health-divider"></div>
        <div class="health-item">
          <div class="health-num">{{ state.orders.length }}</div>
          <div class="health-label">总订单</div>
        </div>
      </div>
    </div>

    <!-- 快捷功能 -->
    <div class="quick-grid">
      <div class="quick-item" @click="$router.push('/shop/medicine')">
        <div class="quick-icon" style="background:#E0F7F4;color:#00BFA5">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M7 4h10l1 4H6l1-4zm-2 6h14l-1 10H6L5 10z"/></svg>
        </div>
        <span>药品商城</span>
      </div>
      <div class="quick-item" @click="$router.push('/points/signin')">
        <div class="quick-icon" style="background:#FFF3E0;color:#FFB74D">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 2l2.39 4.84L20 7.27l-4 3.9.95 5.49L12 14.27l-4.95 2.6L8 11.17l-4-3.9 5.61-.43L12 2z"/></svg>
        </div>
        <span>每日签到</span>
      </div>
      <div class="quick-item" @click="$router.push('/ai')">
        <div class="quick-icon" style="background:#E3F2FD;color:#2196F3">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 2a2 2 0 00-2 2v1H8a2 2 0 00-2 2v1H4a1 1 0 00-1 1v10a1 1 0 001 1h16a1 1 0 001-1V7a1 1 0 00-1-1h-2V5a2 2 0 00-2-2h-2V2a2 2 0 00-2-0zM9 12a1.5 1.5 0 110 3 1.5 1.5 0 010-3zm6 0a1.5 1.5 0 110 3 1.5 1.5 0 010-3z"/></svg>
        </div>
        <span>AI问诊</span>
      </div>
      <div class="quick-item" @click="$router.push('/orders')">
        <div class="quick-icon" style="background:#FCE4EC;color:#E91E63">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>
        </div>
        <span>我的订单</span>
      </div>
    </div>

    <!-- Banner -->
    <div class="home-banner">
      <div class="banner-bg"></div>
      <div class="banner-content">
        <h3>慢病用药 · 正品保障</h3>
        <p>专业药师审核 · 全程冷链配送</p>
        <button class="banner-btn" @click="$router.push('/shop/medicine')">去逛逛</button>
      </div>
    </div>

    <!-- 推荐药品 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">为你推荐</span>
        <span class="section-more" @click="$router.push('/shop/medicine')">查看全部 ›</span>
      </div>
      <div class="recommend-list" v-if="state.medicines.length">
        <div
          v-for="m in state.medicines.slice(0, 4)"
          :key="m.id"
          class="recommend-card"
          @click="goDetail(m)"
        >
          <div class="recommend-img">
            <img :src="medImage(m)" :alt="m.name" loading="lazy">
          </div>
          <div class="recommend-name">{{ m.name }}</div>
          <div class="recommend-price">
            <span class="cur">￥</span><span class="num">{{ m.price }}</span>
          </div>
        </div>
      </div>
      <div v-else class="loading-text">加载中...</div>
    </div>

    <!-- 健康资讯 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">健康服务</span>
      </div>
      <div class="service-list">
        <div class="service-card" @click="$router.push('/ai')">
          <div class="service-icon">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="#00BFA5"><path d="M12 2a2 2 0 00-2 2v1H8a2 2 0 00-2 2v1H4a1 1 0 00-1 1v10a1 1 0 001 1h16a1 1 0 001-1V7a1 1 0 00-1-1h-2V5a2 2 0 00-2-2h-2V2a2 2 0 00-2-0z"/></svg>
          </div>
          <div class="service-info">
            <div class="service-name">AI健康助手</div>
            <div class="service-desc">智能问诊 · 用药指导 · 健康建议</div>
          </div>
          <span class="service-arrow">›</span>
        </div>
        <div class="service-card" @click="$router.push('/points/signin')">
          <div class="service-icon">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="#FFB74D"><path d="M12 2l2.39 4.84L20 7.27l-4 3.9.95 5.49L12 14.27l-4.95 2.6L8 11.17l-4-3.9 5.61-.43L12 2z"/></svg>
          </div>
          <div class="service-info">
            <div class="service-name">积分签到</div>
            <div class="service-desc">每日签到领积分 · 兑换好药</div>
          </div>
          <span class="service-arrow">›</span>
        </div>
        <div class="service-card" @click="$router.push('/coupons')">
          <div class="service-icon">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="#E91E63"><path d="M21.4 11.6l-9-9a2 2 0 00-2.8 0l-9 9a2 2 0 000 2.8l9 9a2 2 0 002.8 0l9-9a2 2 0 000-2.8zM12 15a3 3 0 110-6 3 3 0 010 6z"/></svg>
          </div>
          <div class="service-info">
            <div class="service-name">优惠券中心</div>
            <div class="service-desc">领取专属优惠券 · 购药更优惠</div>
          </div>
          <span class="service-arrow">›</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useStore } from '@/store/demo'
import { medImage } from '@/utils/images'
import { useRouter } from 'vue-router'

const router = useRouter()
const { state, searchMedicines } = useStore()

const hour = new Date().getHours()
const greetingText = hour < 6 ? '凌晨好' : hour < 12 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好'

function goDetail(m) {
  state.currentMed = m
  state.detailQty = 1
  state.detailCoupon = ''
  router.push('/shop/detail/' + m.id)
}

onMounted(() => {
  searchMedicines(1)
})
</script>

<style scoped>
.home-page { padding-bottom: 20px; }

/* 顶部用户区 */
.home-header {
  background: linear-gradient(135deg, #00BFA5, #009688);
  padding: 16px 16px 50px;
  color: #fff;
}
.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(255,255,255,.2);
  display: flex;
  align-items: center;
  justify-content: center;
}
.greeting { font-size: 12px; opacity: .85; }
.username { font-size: 16px; font-weight: 600; }
.header-icon { cursor: pointer; padding: 6px; }

/* 健康概览卡片 */
.health-card {
  margin: -36px 16px 0;
  background: #fff;
  border-radius: 16px;
  padding: 20px 0;
  box-shadow: 0 4px 20px rgba(0,0,0,.06);
  position: relative;
  z-index: 2;
}
.health-row {
  display: flex;
  align-items: center;
  justify-content: space-around;
}
.health-item { text-align: center; flex: 1; }
.health-num {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a1a;
}
.health-label {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
.health-divider {
  width: 1px;
  height: 28px;
  background: #eee;
}

/* 快捷功能 */
.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  padding: 16px 8px 8px;
}
.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 8px 4px;
  transition: background .15s;
  border-radius: 12px;
}
.quick-item:active { background: #f0f2f5; }
.quick-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.quick-item span {
  font-size: 12px;
  color: #666;
  font-weight: 500;
}

/* Banner */
.home-banner {
  margin: 12px 16px;
  border-radius: 16px;
  overflow: hidden;
  position: relative;
  height: 120px;
}
.banner-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #00BFA5, #4DD0E1);
}
.banner-content {
  position: relative;
  z-index: 1;
  padding: 24px;
  color: #fff;
}
.banner-content h3 {
  font-size: 18px;
  font-weight: 700;
}
.banner-content p {
  font-size: 12px;
  opacity: .9;
  margin-top: 6px;
}
.banner-btn {
  margin-top: 12px;
  background: rgba(255,255,255,.25);
  border: 1px solid rgba(255,255,255,.4);
  color: #fff;
  font-size: 13px;
  padding: 5px 16px;
  border-radius: 20px;
  cursor: pointer;
  backdrop-filter: blur(4px);
}

/* 通用 section */
.section { padding: 0 16px; margin-top: 20px; }
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.section-title {
  font-size: 17px;
  font-weight: 700;
  color: #1a1a1a;
}
.section-more {
  font-size: 13px;
  color: #999;
  cursor: pointer;
}

/* 推荐药品 */
.recommend-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

/* 网页版（桌面宽屏）：固定 2 列会把卡片拉成半屏大方图，改为按宽度自适应列数
   （1100px 壳下约 4 列）；<768px 手机端仍是 2 列 */
@media (min-width: 768px) {
  .recommend-list {
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  }
}
.recommend-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
  cursor: pointer;
  transition: transform .15s;
}
.recommend-card:active { transform: scale(.97); }
.recommend-img {
  width: 100%;
  aspect-ratio: 1;
  background: #f8f8f8;
}
.recommend-img img { width: 100%; height: 100%; object-fit: cover; }
.recommend-name {
  font-size: 13px;
  font-weight: 500;
  color: #1a1a1a;
  padding: 8px 10px 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recommend-price {
  padding: 0 10px 10px;
  color: var(--danger);
  font-weight: bold;
}
.recommend-price .cur { font-size: 12px; }
.recommend-price .num { font-size: 17px; }

.loading-text {
  text-align: center;
  color: #999;
  padding: 30px;
  font-size: 14px;
}

/* 健康服务 */
.service-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.service-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
  cursor: pointer;
  transition: transform .15s;
}
.service-card:active { transform: scale(.98); }
.service-icon {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.service-info { flex: 1; }
.service-name {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
}
.service-desc {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
.service-arrow {
  font-size: 20px;
  color: #ccc;
}
</style>
