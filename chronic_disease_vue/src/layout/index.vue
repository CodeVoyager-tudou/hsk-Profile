<template>
  <div class="app-shell">
    <main class="app-content">
      <!-- 不用 keep-alive：页面切换即重新挂载，进页面就是最新数据，无需手动刷新浏览器。
           页面切换动画也**不用 <transition mode="out-in">**：out-in 需等离场动画播完才挂载新页面，
           连续切页打断过渡状态机后新页面永不挂载，内容区整块空白（只能手动刷新恢复）。
           改为「带 key 的普通 div + CSS 淡入」：换页触发重挂载+淡入，无过渡状态可卡死。 -->
      <router-view v-slot="{ Component, route }">
        <div class="page-fade" :key="route.path">
          <component :is="Component" />
        </div>
      </router-view>
    </main>

    <!-- 全局 Toast 提示（demo store 的 toastMsg/toastVisible 在 state 上，勿用顶层 store.x） -->
    <transition name="toast-fade">
      <div v-if="state.toastVisible" class="toast-overlay">
        <div class="toast-box">{{ state.toastMsg }}</div>
      </div>
    </transition>

    <!-- 底部导航栏 -->
    <nav class="tab-bar" v-if="showTabBar">
      <div
        v-for="tab in tabs"
        :key="tab.path"
        class="tab-item"
        :class="{ active: isActive(tab) }"
        @click="goTab(tab)"
      >
        <span class="tab-icon" v-html="tab.icon"></span>
        <span class="tab-label">{{ tab.label }}</span>
      </div>
    </nav>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStore } from '@/store/demo'

const route = useRoute()
const router = useRouter()
const { state } = useStore()

const tabs = [
  {
    label: '首页',
    path: '/index',
    match: ['/index'],
    icon: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 2L2 9.5V22h7v-7h6v7h7V9.5L12 2z"/></svg>'
  },
  {
    label: '商城',
    path: '/shop/medicine',
    match: ['/shop', '/orders', '/coupons'],
    icon: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M7 4h10l1 4H6l1-4zm-2 6h14l-1 10H6L5 10z M9 14h2v4H9z M13 14h2v4h-2z"/></svg>'
  },
  {
    label: 'AI助手',
    path: '/ai',
    match: ['/ai'],
    icon: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 2a2 2 0 00-2 2v1H8a2 2 0 00-2 2v1H4a1 1 0 00-1 1v10a1 1 0 001 1h16a1 1 0 001-1V7a1 1 0 00-1-1h-2V5a2 2 0 00-2-2h-2V2a2 2 0 00-2-0zM9 12a1.5 1.5 0 110 3 1.5 1.5 0 010-3zm6 0a1.5 1.5 0 110 3 1.5 1.5 0 010-3z M9.5 17h5a.5.5 0 010 1h-5a.5.5 0 010-1z"/></svg>'
  },
  {
    label: '我的',
    path: '/user/profile',
    match: ['/user', '/points'],
    icon: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4 0-8 2-8 6v2h16v-2c0-4-4-6-8-6z"/></svg>'
  }
]

const hiddenRoutes = ['/login', '/register', '/shop/detail', '/shop/cashier']

const showTabBar = computed(() => {
  return !hiddenRoutes.some(p => route.path.startsWith(p))
})

function isActive(tab) {
  return tab.match.some(m => route.path.startsWith(m))
}

function goTab(tab) {
  router.push(tab.path)
}
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f7fa;
  max-width: 480px;
  margin: 0 auto;
  box-shadow: 0 0 40px rgba(0,0,0,.08);
  position: relative;
  overflow: hidden;
}

/* 网页版（桌面宽屏）：放开手机壳宽度，让页面占满浏览器窗口；
   <768px（手机/窄窗）保持 480px 手机样式不变，内容排版由各页面自行适配 */
@media (min-width: 768px) {
  .app-shell {
    max-width: 1100px;
  }
}

.app-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
}

/* 底部导航 */
.tab-bar {
  display: flex;
  height: 56px;
  background: #fff;
  border-top: 1px solid #eee;
  flex-shrink: 0;
  position: relative;
  z-index: 100;
  box-shadow: 0 -1px 8px rgba(0,0,0,.04);
}

.tab-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #999;
  cursor: pointer;
  transition: color .2s;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}

.tab-item.active {
  color: #00BFA5;
}

.tab-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
}

.tab-icon :deep(svg) {
  width: 24px;
  height: 24px;
}

.tab-label {
  font-size: 10px;
  font-weight: 500;
}

/* 页面切换动画：纯 CSS 淡入（配合 :key 重挂载触发） */
.page-fade {
  animation: page-fade .2s ease;
}
@keyframes page-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* 全局 Toast */
.toast-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 9999;
  pointer-events: none;
}

.toast-box {
  background: rgba(0, 0, 0, 0.78);
  color: #fff;
  padding: 12px 20px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.5;
  text-align: center;
  max-width: 300px;
  word-break: break-word;
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: opacity .25s ease;
}
.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
}
</style>
