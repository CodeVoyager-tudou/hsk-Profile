<template>
  <div :class="{ 'has-logo': showLogo }" class="sidebar-menu-container">
    <logo v-if="showLogo" :collapse="isCollapse" />
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        :background-color="variables.menuBg"
        :text-color="variables.menuText"
        :unique-opened="true"
        :active-text-color="variables.menuActiveText"
        :collapse-transition="false"
        mode="vertical"
      >
        <sidebar-item
          v-for="(route, index) in sidebarRouters"
          :key="route.path + index"
          :item="route"
          :base-path="route.path"
        />
      </el-menu>
    </el-scrollbar>
  </div>
</template>

<script setup>
import Logo from './Logo.vue'
import SidebarItem from './SidebarItem.vue'
import useAppStore from '@/store/modules/app'
import useSettingsStore from '@/store/modules/settings'

const route = useRoute()
const sidebar = computed(() => useAppStore().sidebar)
const sidebarRouters = computed(() => {
  return [
    {
      path: '/index',
      meta: { title: '首页', icon: 'dashboard', affix: true }
    },
    {
      path: '/ai',
      meta: { title: 'AI助手', icon: 'chat' }
    },
    {
      path: '/mine',
      meta: { title: '我的', icon: 'user' },
      children: [
        { path: '/user/profile', meta: { title: '个人中心', icon: 'user' } },
        { path: '/shop/medicine', meta: { title: '药品商城', icon: 'shopping' } },
        { path: '/orders', meta: { title: '我的订单', icon: 'order' } },
        { path: '/coupons', meta: { title: '优惠券', icon: 'coupon' } },
        { path: '/points/signin', meta: { title: '每日签到', icon: 'signin' } },
        { path: '/points/records', meta: { title: '积分记录', icon: 'records' } }
      ]
    }
  ]
})
const showLogo = computed(() => useSettingsStore().sidebarLogo)
const variables = computed(() => ({
  menuBg: '#fff',
  menuText: '#333',
  menuActiveText: '#1f6f43'
}))
const isCollapse = computed(() => !sidebar.value.opened)
const activeMenu = computed(() => {
  const { meta, path } = route
  if (meta.activeMenu) {
    return meta.activeMenu
  }
  return path
})
</script>
