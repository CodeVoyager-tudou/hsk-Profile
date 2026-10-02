<template>
  <div class="admin-page">
    <AdminNav />
    <div class="page-pad">
      <div class="form-grid">
        <input v-model="form.medicineId" class="in" placeholder="药品ID" type="number">
        <input v-model="form.title" class="in" placeholder="活动标题">
        <input v-model="form.seckillPrice" class="in" placeholder="秒杀价(元)" type="number" step="0.01">
        <input v-model="form.totalStock" class="in" placeholder="总名额" type="number">
        <input v-model="form.startTime" class="in" type="datetime-local">
        <input v-model="form.endTime" class="in" type="datetime-local">
      </div>
      <button class="btn primary" @click="onCreate">创建活动</button>
      <div class="hint">创建后预热任务（30 秒一轮）自动把名额写入 Redis；时间格式 yyyy-MM-ddTHH:mm</div>

      <div class="table-wrap">
        <table class="tb">
          <thead>
            <tr><th>ID</th><th>标题</th><th>秒杀价</th><th>已抢/总量</th><th>窗口</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in rows" :key="a.id">
              <td>{{ a.id }}</td>
              <td class="ellipsis">{{ a.title }}</td>
              <td>￥{{ Number(a.seckillPrice || 0).toFixed(2) }}</td>
              <td>{{ a.soldCount }}/{{ a.totalStock }}</td>
              <td class="ellipsis">{{ shortTime(a.startTime) }} ~ {{ shortTime(a.endTime) }}</td>
              <td :class="a.status === 1 ? 'ok' : 'off'">{{ a.status === 1 ? '上架' : '下架' }}</td>
              <td class="ops">
                <button class="btn" @click="onToggle(a)">{{ a.status === 1 ? '下架' : '上架' }}</button>
                <button class="btn" @click="onTime(a)">改窗口</button>
              </td>
            </tr>
            <tr v-if="!rows.length"><td colspan="7" class="empty">无数据</td></tr>
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
const { state, toast, adminSeckillPage, adminSeckillCreate, adminSeckillStatus, adminSeckillTime } = useStore()

const rows = ref([])
const pageNum = ref(1)
const hasMore = ref(false)
const form = ref({ medicineId: '', title: '', seckillPrice: '', totalStock: '', startTime: '', endTime: '' })

function guard() {
  if (state.role !== 'ADMIN') {
    toast('需要管理员账号（admin）登录')
    router.push('/index')
    return false
  }
  return true
}

function shortTime(t) {
  return (t || '').replace('T', ' ').slice(5, 16)
}

async function load(p) {
  pageNum.value = p
  const r = await adminSeckillPage(p)
  if (!r.ok) {
    toast(r.msg)
    return
  }
  rows.value = r.data.records || []
  hasMore.value = (r.data.current || p) < (r.data.pages || 1)
}

async function onCreate() {
  const f = form.value
  if (!f.medicineId || !f.title || !f.seckillPrice || !f.totalStock || !f.startTime || !f.endTime) {
    toast('请完整填写活动信息')
    return
  }
  const r = await adminSeckillCreate(f)
  toast(r.ok ? '活动已创建，操作已记入审计' : r.msg)
  if (r.ok) load(1)
}

async function onToggle(a) {
  const target = a.status === 1 ? 0 : 1
  const r = await adminSeckillStatus(a.id, target)
  toast(r.ok ? '已更新' : r.msg)
  if (r.ok) load(pageNum.value)
}

async function onTime(a) {
  const start = prompt('开始时间（yyyy-MM-ddTHH:mm）', (a.startTime || '').slice(0, 16))
  if (!start) return
  const end = prompt('结束时间（yyyy-MM-ddTHH:mm）', (a.endTime || '').slice(0, 16))
  if (!end) return
  const r = await adminSeckillTime(a.id, start, end)
  toast(r.ok ? '窗口已调整，预热任务会自动跟进' : r.msg)
  if (r.ok) load(pageNum.value)
}

onMounted(() => {
  if (guard()) load(1)
})
</script>

<style scoped src="./admin.css"></style>
