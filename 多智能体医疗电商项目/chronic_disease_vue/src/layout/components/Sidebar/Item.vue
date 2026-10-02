<script setup>
defineProps({
  title: { type: String, default: '' },
  icon: { type: String, default: '' }
})
</script>

<template>
  <i v-if="icon" class="el-icon">
    <svg-icon :icon-class="icon" />
  </i>
  <template v-if="title">
    <span>{{ title }}</span>
  </template>
</template>
