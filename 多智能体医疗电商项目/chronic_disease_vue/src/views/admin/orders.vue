<template>
  <div class="admin-page">
    <AdminNav />
    <div class="page-pad">
      <div class="toolbar">
        <select v-model="statusFilter" class="in" @change="load(1)">
          <option value="">全部状态</option>
          <option value="PENDING">待支付</option>
          <option value="PAID">已支付</option>
          <option value="CANCELLED">已取消</option>
        </select>
        <button class="btn" @click="load(1)">查询</button>
      </div>

      <div class="table-wrap">
        <table class="tb">
          <thead>
            <tr><th>ID</th><th>订单号</th><th>用户</th><th>药品</th><th>应付</th><th>方式</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="o in rows" :key="o.id">
              <td>{{ o.id }}</td>
              <td class="ellipsis">{{ o.orderNo }}</td>
              <td>{{ o.userId }}</td>
              <td class="ellipsis">{{ o.medicineName }}</td>
              <td>￥{{ (Number(o.totalAmount || 0) - Number(o.discountAmount || 0)).toFixed(2) }}</td>
              <td>{{ payText(o.payType) }}</td>
              <td>{{ statusText(o.status) }}</td>
              <td class="ops">
                <button v-if="o.status === 'PAID'" class="btn warn" @click="onRefund(o)">退款</button>
              </td>
            </tr>
            <tr v-if="!rows.length"><td colspan="8" class="empty">无数据</td></tr>
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
import { statusText, payText } from '@/utils/format'
import AdminNav from './components/AdminNav.vue'

const router = useRouter()
const { state, toast, adminOrderPage, adminRefund } = useStore()

const rows = ref([])
const pageNum = ref(1)
const hasMore = ref(false)
const statusFilter = ref('')

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
  const r = await adminOrderPage(p, statusFilter.value)
  if (!r.ok) {
    toast(r.msg)
    return
  }
  rows.value = r.data.records || []
  hasMore.value = (r.data.current || p) < (r.data.pages || 1)
}

async function onRefund(o) {
  if (!confirm(`确认为订单 ${o.orderNo} 退款？（退余额/积分/库存/优惠券，幂等护栏保证不重复退）`)) return
  const r = await adminRefund(o.id)
  toast(r.ok ? '退款成功，操作已记入审计' : r.msg)
  if (r.ok) load(pageNum.value)
}

onMounted(() => {
  if (guard()) load(1)
})
</script>

<style scoped src="./admin.css"></style>
