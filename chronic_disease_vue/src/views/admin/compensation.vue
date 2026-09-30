<template>
  <div class="admin-page">
    <AdminNav />
    <div class="page-pad">
      <div class="hint">跨服务补偿台账：自动重试 5 分钟一轮；PENDING 会一直退避重试，FAILED（超过 5 次）需人工介入 —— 手动重试与自动重试共用同一执行器，幂等由流水唯一键保证。</div>
      <div class="toolbar">
        <select v-model="statusFilter" class="in" @change="load(1)">
          <option value="">全部状态</option>
          <option value="PENDING">重试中</option>
          <option value="FAILED">已转人工</option>
          <option value="DONE">已完成</option>
        </select>
        <button class="btn" @click="load(1)">查询</button>
      </div>

      <div class="table-wrap">
        <table class="tb">
          <thead>
            <tr><th>ID</th><th>类型</th><th>业务ID</th><th>用户</th><th>参数</th><th>状态</th><th>已重试</th><th>最后错误</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in rows" :key="t.id">
              <td>{{ t.id }}</td>
              <td>{{ t.bizType }}</td>
              <td>{{ t.bizId }}</td>
              <td>{{ t.userId }}</td>
              <td class="ellipsis">{{ t.payload }}</td>
              <td :class="t.status === 'FAILED' ? 'warn' : t.status === 'DONE' ? 'ok' : ''">{{ t.status }}</td>
              <td>{{ t.retryCount }}</td>
              <td class="ellipsis">{{ t.lastError || '—' }}</td>
              <td class="ops">
                <button v-if="t.status !== 'DONE'" class="btn" @click="onRetry(t)">重试</button>
              </td>
            </tr>
            <tr v-if="!rows.length"><td colspan="9" class="empty">无数据</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="pageNum <= 1" @click="load(pageNum - 1)">上一页</button>
        <span>第 {{ pageNum }} 页</span>
        <button class="btn" :disabled="!hasMore" @click="load(pageNum + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import AdminNav from './components/AdminNav.vue'

const router = useRouter()
const { state, toast, adminCompensationPage, adminCompensationRetry } = useStore()

const rows = ref([])
const pageNum = ref(1)
const hasMore = ref(false)
const statusFilter = ref('FAILED')

function guard() {
  if (state.role !== 'ADMIN') {
    toast('需要管理员账号（admin）登录')
    router.push('/index')
    return false
  }
  return true
}

async function load(p) {
  pageNum.value = p
  const r = await adminCompensationPage(p, statusFilter.value)
  if (!r.ok) {
    toast(r.msg)
    return
  }
  rows.value = r.data.records || []
  hasMore.value = (r.data.current || p) < (r.data.pages || 1)
}

async function onRetry(t) {
  const r = await adminCompensationRetry(t.id)
  toast(r.ok ? (r.data ? '重试成功' : '重试仍未成功，可稍后再试或人工核对') : r.msg)
  load(pageNum.value)
}

onMounted(() => {
  if (guard()) load(1)
})
</script>

<style scoped src="./admin.css"></style>
