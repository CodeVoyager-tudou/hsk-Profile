<template>
  <div />
</template>

<script setup>
const route = useRoute()
const router = useRouter()
const { params, query } = route
const { fullPath } = params
router.replace({ path: '/' + fullPath, query })
</script>
