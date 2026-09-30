<template>
  <div class="admin-nav">
    <router-link
      v-for="t in tabs"
      :key="t.path"
      :to="t.path"
      class="tab"
      active-class="active"
    >{{ t.label }}</router-link>
  </div>
</template>

<script setup>
// 管理端页签导航：四个管理页共用；访问控制由 /admin 路由区各页面的角色检查 + 网关闸门双层保证
const tabs = [
  { path: '/admin/medicine', label: '药品管理' },
  { path: '/admin/seckill', label: '秒杀活动' },
  { path: '/admin/orders', label: '订单管理' },
  { path: '/admin/compensation', label: '补偿台账' },
]
</script>

<style scoped>
.admin-nav {
  display: flex;
  gap: 4px;
  background: #fff;
  border-bottom: 1px solid #eee;
  position: sticky;
  top: 0;
  z-index: 10;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 12px 0;
  font-size: 13px;
  color: #666;
  text-decoration: none;
}
.tab.active {
  color: #009688;
  font-weight: 700;
  border-bottom: 2px solid #00BFA5;
}
</style>
