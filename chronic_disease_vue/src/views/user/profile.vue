<template>
  <div class="profile-page">
    <!-- 顶部用户信息 -->
    <div class="profile-header">
      <div class="profile-user">
        <div class="profile-avatar" @click="onPickAvatar" :title="uploading ? '上传中…' : '点击更换头像'">
          <img v-if="state.avatar" :src="state.avatar" alt="头像" class="profile-avatar-img">
          <svg v-else viewBox="0 0 24 24" width="32" height="32" fill="rgba(255,255,255,.95)"><path d="M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4 0-8 2-8 6v2h16v-2c0-4-4-6-8-6z"/></svg>
          <span class="profile-avatar-edit">换</span>
        </div>
        <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp,image/gif" style="display:none" @change="onAvatarChange">
        <div class="profile-info">
          <div class="profile-name">{{ state.username || '健康用户' }}</div>
          <div class="profile-id">ID: {{ state.userId }}</div>
        </div>
        <div class="profile-vip">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M5 16L3 5l5.5 5L12 4l3.5 6L21 5l-2 11H5z"/></svg>
          <span>健康会员</span>
        </div>
      </div>
    </div>

    <!-- 数据概览 -->
    <div class="stats-card">
      <div class="stat-item" @click="goto('/wallet')">
        <div class="stat-num">￥{{ Number(state.balance || 0).toFixed(2) }}</div>
        <div class="stat-label">{{ state.isMock ? '余额（演示）' : '余额' }}</div>
      </div>
      <div class="stat-divider"></div>
      <div class="stat-item" @click="goto('/points/records')">
        <div class="stat-num">{{ state.points.total - state.points.used }}</div>
        <div class="stat-label">可用积分</div>
      </div>
      <div class="stat-divider"></div>
      <div class="stat-item" @click="goto('/points/records')">
        <div class="stat-num">{{ state.points.total }}</div>
        <div class="stat-label">累计积分</div>
      </div>
      <div class="stat-divider"></div>
      <div class="stat-item" @click="goto('/coupons')">
        <div class="stat-num">{{ (state.myCoupons || []).filter(c => c.status === 'UNUSED').length }}</div>
        <div class="stat-label">可用券</div>
      </div>
    </div>

    <!-- 我的订单 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">我的订单</span>
        <span class="section-more" @click="goto('/orders')">查看全部 ›</span>
      </div>
      <div class="order-status">
        <div class="status-item" @click="goto('/orders')">
          <div class="status-icon">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="#999"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6zm2 16H8v-2h8v2z"/></svg>
          </div>
          <span>全部订单</span>
        </div>
        <div class="status-item" @click="goto('/orders')">
          <div class="status-icon">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="#FFB74D"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14.5l-4-4 1.4-1.4 2.6 2.6 5.6-5.6 1.4 1.4-7 7z"/></svg>
          </div>
          <span>已付款</span>
        </div>
        <div class="status-item" @click="goto('/orders')">
          <div class="status-icon">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="#FF5252"><path d="M19 5v14H5V5h14m0-2H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V5a2 2 0 00-2-2zm-4.5 12L12 12.5 9.5 15 8 13.5 10.5 11 8 8.5 9.5 7 12 9.5 14.5 7 16 8.5 13.5 11 16 12.5z"/></svg>
          </div>
          <span>已取消</span>
        </div>
      </div>
    </div>

    <!-- 功能入口 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">常用功能</span>
      </div>
      <div class="func-grid">
        <div class="func-item" @click="goto('/coupons')">
          <div class="func-icon" style="background:#FCE4EC;color:#E91E63">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M21.4 11.6l-9-9a2 2 0 00-2.8 0l-9 9a2 2 0 000 2.8l9 9a2 2 0 002.8 0l9-9a2 2 0 000-2.8zM12 15a3 3 0 110-6 3 3 0 010 6z"/></svg>
          </div>
          <span>优惠券</span>
        </div>
        <div class="func-item" @click="goto('/points/signin')">
          <div class="func-icon" style="background:#FFF3E0;color:#FFB74D">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 2l2.39 4.84L20 7.27l-4 3.9.95 5.49L12 14.27l-4.95 2.6L8 11.17l-4-3.9 5.61-.43L12 2z"/></svg>
          </div>
          <span>每日签到</span>
        </div>
        <div class="func-item" @click="goto('/points/records')">
          <div class="func-icon" style="background:#E0F7F4;color:#00BFA5">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M19 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V5a2 2 0 00-2-2zm-7 14H7v-2h5v2zm5-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>
          </div>
          <span>积分记录</span>
        </div>
        <div class="func-item" @click="goto('/ai')">
          <div class="func-icon" style="background:#E3F2FD;color:#2196F3">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 2a2 2 0 00-2 2v1H8a2 2 0 00-2 2v1H4a1 1 0 00-1 1v10a1 1 0 001 1h16a1 1 0 001-1V7a1 1 0 00-1-1h-2V5a2 2 0 00-2-2h-2V2a2 2 0 00-2-0z"/></svg>
          </div>
          <span>AI助手</span>
        </div>
      </div>
    </div>

    <!-- 设置列表 -->
    <div class="settings-list">
      <div class="setting-item" @click="goto('/orders')">
        <span class="setting-label">我的订单</span>
        <span class="setting-arrow">›</span>
      </div>
      <div class="setting-item" @click="gotoCouponsAct">
        <span class="setting-label">领券中心</span>
        <span class="setting-arrow">›</span>
      </div>
      <div class="setting-item" @click="goto('/points/records')">
        <span class="setting-label">积分流水</span>
        <span class="setting-arrow">›</span>
      </div>
      <div class="setting-item" v-if="state.role === 'ADMIN'" @click="goto('/admin/medicine')">
        <span class="setting-label">管理中心</span>
        <span class="setting-arrow">›</span>
      </div>
      <div class="setting-item logout" @click="onLogout">
        <span class="setting-label">退出登录</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'

const router = useRouter()
const { state, logout, toast, loadOrders, loadMyCoupons, loadBalance, loadProfile, loadAccount, uploadAvatar } = useStore()

// ===== 头像上传（存阿里云 OSS，URL 由后端写回 sys_user.avatar）=====
const fileInput = ref(null)
const uploading = ref(false)

function onPickAvatar() {
  if (!uploading.value && fileInput.value) fileInput.value.click()
}

async function onAvatarChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = '' // 清空以便允许再次选择同一张图
  if (!file || uploading.value) return
  uploading.value = true
  try {
    const r = await uploadAvatar(file)
    toast(r.ok ? (r.mock ? '头像已更新（演示，未上传服务器）' : '头像已更新') : (r.msg || '头像上传失败'))
  } catch (_) {
    toast('头像上传失败')
  } finally {
    uploading.value = false
  }
}

function goto(target) {
  router.push(target)
}

function gotoCouponsAct() {
  state.couponTab = 'act'
  router.push('/coupons')
}

function onLogout() {
  if (!confirm('确认退出登录？')) return
  logout()
  toast('已退出登录')
  router.push('/login')
}

onMounted(() => {
  loadProfile()
  loadAccount()
  loadBalance()
  loadOrders()
  loadMyCoupons()
})
</script>

<style scoped>
.profile-page { padding-bottom: 20px; }

/* 顶部 */
.profile-header {
  background: linear-gradient(135deg, #00BFA5, #009688);
  padding: 24px 16px 20px;
  color: #fff;
}
.profile-user {
  display: flex;
  align-items: center;
  gap: 14px;
}
.profile-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(255,255,255,.2);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid rgba(255,255,255,.3);
  position: relative;
  cursor: pointer;
  overflow: visible;
}
.profile-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}
.profile-avatar-edit {
  position: absolute;
  right: -4px;
  bottom: -4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  color: #009688;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  font-weight: 700;
  box-shadow: 0 1px 4px rgba(0,0,0,.15);
}
.profile-info { flex: 1; }
.profile-name {
  font-size: 18px;
  font-weight: 700;
}
.profile-id {
  font-size: 12px;
  opacity: .8;
  margin-top: 2px;
}
.profile-vip {
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(255,255,255,.2);
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
}

/* 数据概览 */
.stats-card {
  display: flex;
  align-items: center;
  justify-content: space-around;
  background: #fff;
  margin: -12px 16px 0;
  border-radius: 16px;
  padding: 20px 0;
  box-shadow: 0 4px 20px rgba(0,0,0,.06);
  position: relative;
  z-index: 2;
}
.stat-item {
  text-align: center;
  flex: 1;
  cursor: pointer;
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a1a;
}
.stat-label {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
.stat-divider {
  width: 1px;
  height: 28px;
  background: #eee;
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

/* 订单状态 */
.order-status {
  display: flex;
  background: #fff;
  border-radius: 12px;
  padding: 16px 0;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.status-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.status-icon {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f8f9fb;
  border-radius: 50%;
}
.status-item span {
  font-size: 12px;
  color: #666;
}

/* 功能入口 */
.func-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  background: #fff;
  border-radius: 12px;
  padding: 16px 8px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.func-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px;
}
.func-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.func-item span {
  font-size: 12px;
  color: #666;
  font-weight: 500;
}

/* 设置列表 */
.settings-list {
  margin: 20px 16px 0;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.setting-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  cursor: pointer;
  transition: background .15s;
  border-bottom: 1px solid #f5f5f5;
}
.setting-item:last-child { border-bottom: none; }
.setting-item:active { background: #f8f9fb; }
.setting-label {
  font-size: 15px;
  color: #333;
}
.setting-arrow {
  font-size: 18px;
  color: #ccc;
}
.setting-item.logout .setting-label {
  color: var(--danger);
  text-align: center;
  width: 100%;
}
.setting-item.logout {
  justify-content: center;
}
</style>
