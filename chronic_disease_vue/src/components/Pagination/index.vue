<template>
  <div :class="{ hidden: hidden }" class="pagination-container">
    <el-pagination
      :background="background"
      :current-page.sync="currentPage"
      :page-size.sync="pageSize"
      :layout="layout"
      :page-sizes="pageSizes"
      :total="total"
      v-bind="$attrs"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  total: { required: true, type: Number },
  page: { type: Number, default: 1 },
  limit: { type: Number, default: 20 },
  pageSizes: { type: Array, default: () => [10, 20, 30, 50] },
  layout: { type: String, default: 'total, sizes, prev, pager, next, jumper' },
  background: { type: Boolean, default: true },
  autoScroll: { type: Boolean, default: true },
  hidden: { type: Boolean, default: false }
})

const emit = defineEmits(['update:page', 'update:limit', 'pagination'])

const currentPage = computed({
  get() { return props.page },
  set(val) { emit('update:page', val) }
})

const pageSize = computed({
  get() { return props.limit },
  set(val) { emit('update:limit', val) }
})

function handleSizeChange(val) {
  emit('pagination', { page: currentPage.value, limit: val })
}

function handleCurrentChange(val) {
  emit('pagination', { page: val, limit: pageSize.value })
}
</script>

<style scoped>
.pagination-container {
  padding: 15px 0;
}
.pagination-container.hidden {
  display: none;
}
</style>
