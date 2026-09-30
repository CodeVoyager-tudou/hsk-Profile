<template>
  <router-view />
</template>

<script setup>
import useSettingsStore from '@/store/modules/settings'

onMounted(() => {
  nextTick(() => {
    document.title = useSettingsStore().title || import.meta.env.VITE_APP_TITLE
  })
})
</script>
