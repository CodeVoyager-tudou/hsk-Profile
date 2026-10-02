<template>
  <div class="drawer-container">
    <div>
      <h3 class="drawer-title">系统布局配置</h3>
      <div class="drawer-item">
        <span>显示标签页</span>
        <el-switch v-model="tagsView" class="drawer-switch" />
      </div>
      <div class="drawer-item">
        <span>显示系统Logo</span>
        <el-switch v-model="sidebarLogo" class="drawer-switch" />
      </div>
    </div>
  </div>
</template>

<script setup>
import useSettingsStore from '@/store/modules/settings'

const settingsStore = useSettingsStore()

const tagsView = computed({
  get() { return settingsStore.tagsView },
  set(val) { settingsStore.changeSetting({ key: 'tagsView', value: val }) }
})

const sidebarLogo = computed({
  get() { return settingsStore.sidebarLogo },
  set(val) { settingsStore.changeSetting({ key: 'sidebarLogo', value: val }) }
})
</script>

<style lang="scss" scoped>
.drawer-container {
  padding: 24px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
  .drawer-title {
    margin-bottom: 12px;
    color: #000;
    font-size: 14px;
    font-weight: bold;
  }
  .drawer-item {
    color: rgba(0, 0, 0, 0.65);
    padding: 12px 0;
    font-size: 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .drawer-switch {
    float: right;
  }
}
</style>
