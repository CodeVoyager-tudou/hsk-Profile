<script setup>
import { onMounted } from 'vue'
import { useStore } from '@/store/demo'
import { formatTime } from '@/utils/format'

const { state, loadRecords, loadBalance, loadMyCoupons } = useStore()

onMounted(() => {
  loadRecords()
  loadBalance()
  loadMyCoupons()
})
</script>

<template>
  <div class="records-page">
    <!-- 顶部标题栏 -->
    <header class="nav-bar">
      <span class="nav-title">积分记录</span>
    </header>

    <!-- 积分流水 -->
    <section class="card">
      <div class="card-head"><h3>积分流水（最近 20 条）</h3></div>
      <ul class="record-list" v-if="state.records && state.records.length">
        <li class="record-item" v-for="(row, i) in state.records" :key="i">
          <div class="rec-main">
            <div class="rec-row">
              <span class="rec-type">{{ row.type }}</span>
              <span class="rec-pt" :class="row.points >= 0 ? 'pt-pos' : 'pt-neg'">
                {{ row.points >= 0 ? '+' : '' }}{{ row.points }}
              </span>
            </div>
            <div class="rec-remark">{{ row.remark }}</div>
            <div class="rec-time">{{ formatTime(row.createTime) }}</div>
          </div>
        </li>
      </ul>
      <div v-else class="empty">暂无记录</div>
    </section>

    <!-- 领取记录汇总 -->
    <section class="card">
      <div class="card-head"><h3>领取记录汇总</h3></div>
      <div class="summary">
        <div class="sum-item">
          <div class="sum-label">累计积分</div>
          <div class="sum-value">{{ state.points.total }}<span class="unit">分</span></div>
        </div>
        <div class="sum-item">
          <div class="sum-label">已用积分</div>
          <div class="sum-value">{{ state.points.used }}<span class="unit">分</span></div>
        </div>
        <div class="sum-item highlight">
          <div class="sum-label">可用积分</div>
          <div class="sum-value">{{ state.points.total - state.points.used }}<span class="unit">分</span></div>
        </div>
        <div class="sum-item">
          <div class="sum-label">优惠券总数</div>
          <div class="sum-value">{{ state.myCoupons.length }}<span class="unit">张</span></div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.records-page {
  min-height: 100vh;
  background: #f5f7fa;
  padding-bottom: 20px;
}

/* 顶部标题栏 */
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 48px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}
.nav-title {
  font-size: 17px;
  font-weight: 600;
  color: #1a1a1a;
}

/* 通用卡片 */
.card {
  margin: 12px 16px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.card-head {
  margin-bottom: 12px;
}
.card-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
}

/* 积分流水列表 */
.record-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.record-item {
  padding: 12px 0;
  border-bottom: 1px solid #f2f3f5;
}
.record-item:last-child { border-bottom: none; }
.rec-main { display: flex; flex-direction: column; gap: 4px; }
.rec-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rec-type {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
}
.rec-pt { font-size: 15px; font-weight: 700; }
.rec-remark { font-size: 13px; color: #666; }
.rec-time { font-size: 12px; color: #aaa; }

.pt-pos { color: #00BFA5; font-weight: 700; }
.pt-neg { color: #FF5252; font-weight: 700; }

/* 汇总 */
.summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.sum-item {
  padding: 14px;
  background: #f5f7fa;
  border-radius: 10px;
}
.sum-item.highlight {
  background: #E0F7F4;
}
.sum-label {
  font-size: 12px;
  color: #888;
}
.sum-value {
  margin-top: 6px;
  font-size: 20px;
  font-weight: 700;
  color: #1a1a1a;
}
.sum-item.highlight .sum-value { color: #00BFA5; }
.sum-value .unit {
  margin-left: 2px;
  font-size: 13px;
  font-weight: 500;
  color: #999;
}
.sum-item.highlight .sum-value .unit { color: #00BFA5; }

.empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: #bbb;
}
</style>
