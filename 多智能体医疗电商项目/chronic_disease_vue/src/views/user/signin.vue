<script setup>
import { ref, onMounted } from 'vue'
import { useStore } from '@/store/demo'
import { formatTime, isToday } from '@/utils/format'

const { state, doSignIn, loadWeek, loadRecords } = useStore()

// 签到防连点：请求期间按钮转圈
const signing = ref(false)

async function onSignIn() {
  if (signing.value) return
  signing.value = true
  try {
    await doSignIn()
  } finally {
    signing.value = false
  }
}

onMounted(() => {
  loadWeek()
  loadRecords()
})
</script>

<template>
  <div class="signin-page">
    <!-- 顶部标题栏 -->
    <header class="nav-bar">
      <span class="nav-title">每日签到</span>
    </header>

    <!-- 签到概览卡片 -->
    <section class="card summary-card">
      <div class="ss-content">
        <div class="ss-text">
          本周已签 <b class="num">{{ state.weekData ? state.weekData.signedDays : 0 }}</b
          ><span class="unit">/7 天</span>
          <div class="ss-sub">
            今日可领 {{ state.weekData ? state.weekData.todayPoints : 0 }} 积分 ·
            全勤额外 +{{ state.weekData ? state.weekData.fullAttendanceBonus : 0 }}
          </div>
        </div>
        <button
          class="sign-btn"
          :class="{ 'is-done': state.weekData && state.weekData.todaySigned }"
          :disabled="(state.weekData && state.weekData.todaySigned) || signing"
          @click="onSignIn"
        >
          <span v-if="signing" class="spinner"></span>
          <span>{{ state.weekData && state.weekData.todaySigned ? '今日已领' : '今日签到' }}</span>
        </button>
      </div>
    </section>

    <!-- 本周签到日历 -->
    <section class="card">
      <div class="card-head"><h3>本周签到日历</h3></div>
      <div class="week" v-if="state.weekData">
        <div
          class="day"
          v-for="(d, i) in state.weekData.days"
          :key="i"
          :class="{ signed: d.signed, today: isToday(d.date) }"
        >
          <div class="wd">{{ d.weekDay }}</div>
          <div class="pt">+{{ d.points }}</div>
          <div class="ok">{{ d.signed ? '✓' : '' }}</div>
        </div>
      </div>
      <div v-else class="empty">加载中...</div>
    </section>

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
  </div>
</template>

<style scoped>
.signin-page {
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

/* 签到概览 */
.summary-card {
  background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff;
}
.ss-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.ss-text {
  font-size: 14px;
  line-height: 1.5;
}
.ss-text .num {
  font-size: 22px;
  font-weight: 700;
  margin: 0 2px;
}
.ss-text .unit {
  font-size: 13px;
  opacity: 0.9;
}
.ss-sub {
  margin-top: 6px;
  font-size: 12px;
  opacity: 0.92;
}
.sign-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border: none;
  border-radius: 999px;
  background: #fff;
  color: #00BFA5;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}
.sign-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.16);
}
.sign-btn:disabled {
  cursor: not-allowed;
  opacity: 0.85;
}
.sign-btn.is-done {
  color: #9ad4cd;
  background: rgba(255, 255, 255, 0.85);
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* 签到日历 */
.week {
  display: flex;
  gap: 6px;
}
.day {
  flex: 1;
  text-align: center;
  padding: 10px 2px;
  border-radius: 10px;
  background: #f5f7fa;
  color: #888;
  transition: all 0.2s;
}
.day.signed {
  background: #E0F7F4;
  color: #00BFA5;
}
.day.today {
  border: 2px solid #00BFA5;
  font-weight: bold;
}
.wd { font-size: 12px; color: #999; }
.day.signed .wd { color: #00BFA5; }
.pt { font-size: 14px; font-weight: bold; margin-top: 4px; }
.ok { font-size: 13px; margin-top: 2px; min-height: 16px; }

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

.empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: #bbb;
}
</style>
