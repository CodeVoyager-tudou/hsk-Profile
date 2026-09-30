<script setup>
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import { DEMO_ENABLED } from '@/utils/demoMode'
import { medImage } from '@/utils/images'
import { formatTime } from '@/utils/format'

const router = useRouter()
const { state, toast, loadSeckillActivities, doSeckill, pollSeckillResult, loadAccount,
        fetchSeckillCaptcha, verifySeckillCaptcha } = useStore()

const pollingTimers = ref({})

const activities = computed(() => state.seckillActivities || [])

// ===== 滑块验证码弹窗状态 =====
const modal = ref({ show: false, activity: null, captcha: null })
const dragX = ref(0)
const verifying = ref(false)
/** 拖拽轨迹采样：[时间戳, 位置] 序列，随滑块移动持续记录——后端据此做"有过程"的行为校验 */
const samples = ref([])
/** 拖拽上限 = 底图宽 280 - 碎块 44 - 边距 20（与后端 GAP_X_MAX 一致） */
const DRAG_MAX = 216

function onDragInput() {
  samples.value.push([Date.now(), dragX.value])
}

function stateOf(a) {
  const now = Date.now()
  const start = new Date(a.startTime).getTime()
  const end = new Date(a.endTime).getTime()
  if (now < start) return 'PENDING'
  if (now > end) return 'ENDED'
  return 'LIVE'
}

function stateText(a) {
  return { PENDING: '未开始', LIVE: '抢购中', ENDED: '已结束' }[stateOf(a)]
}

function progressOf(a) {
  const total = a.totalStock || 1
  return Math.min(100, Math.round(((a.soldCount || 0) / total) * 100))
}

function originalPrice(a) {
  const med = (state.medicines || []).find((m) => String(m.id) === String(a.medicineId))
  return med && med.price ? Number(med.price).toFixed(2) : null
}

// 抢购入口：先拿滑块验证码（前置人机拦截）；演示模式下后端不可达则跳过直接演示
async function onSeckill(a) {
  if (stateOf(a) !== 'LIVE') {
    toast(stateOf(a) === 'PENDING' ? '活动还没开始' : '活动已结束')
    return
  }
  const c = await fetchSeckillCaptcha()
  if (c.ok) {
    dragX.value = 0
    samples.value = []
    modal.value = { show: true, activity: a, captcha: c.data }
    return
  }
  if (!DEMO_ENABLED) {
    toast(c.msg)
    return
  }
  await doGrab(a)
}

async function doGrab(a, ticket) {
  const r = await doSeckill(a.id, ticket)
  // 网关限流(5 QPS)触发的 429：票据未被消费、仍然有效，稍候自动重试一次
  if (r && !r.ok && r.msg && r.msg.includes('请求过于频繁')) {
    await new Promise((resolve) => setTimeout(resolve, 900))
    const retry = await doSeckill(a.id, ticket)
    if (retry && retry.ok) startPolling(a)
    return
  }
  if (r && r.ok) startPolling(a)
}

async function onVerify() {
  verifying.value = true
  try {
    // 拖拽轨迹采样 → 后端行为校验（点数/时长/终点一致性）：真人拖动必有过程，脚本秒回无轨迹
    const xs = samples.value.map((s) => s[1])
    const durationMs = samples.value.length > 1
      ? samples.value[samples.value.length - 1][0] - samples.value[0][0]
      : 0
    const r = await verifySeckillCaptcha(
      modal.value.captcha.captchaId, dragX.value, xs.join(','), durationMs)
    if (r.ok) {
      modal.value.show = false
      toast('验证通过')
      await doGrab(modal.value.activity, r.ticket)
      return
    }
    // 失败即作废：自动换一张新图，用户重拖
    modal.value.error = r.msg + '（已自动换一张，请重新拖动）'
    dragX.value = 0
    samples.value = []
    const fresh = await fetchSeckillCaptcha()
    if (fresh.ok) modal.value.captcha = fresh.data
  } finally {
    verifying.value = false
  }
}

function startPolling(a) {
  let left = 5
  clearInterval(pollingTimers.value[a.id])
  pollingTimers.value[a.id] = setInterval(async () => {
    left--
    const p = await pollSeckillResult(a.id)
    if (p && p.state !== 'PROCESSING') {
      clearInterval(pollingTimers.value[a.id])
      if (p.state === 'SUCCESS') {
        // 秒杀单是「待支付现金单」：抢到只是占住名额，付款在收银台完成
        // （30 分钟窗口，超时由后端 RocketMQ 延迟消息自动关单并释放名额）
        if (p.orderId) {
          toast('秒杀成功，请在 30 分钟内完成支付')
          router.push('/shop/cashier/' + p.orderId)
        } else {
          // 拿不到 orderId（老后端/老数据）：退化为提示，去「我的订单」继续支付
          toast('秒杀成功！订单号 ' + p.orderNo + '，请到「我的订单」完成支付')
        }
        loadAccount()
      } else {
        toast('未抢到：' + (p.reason || '请稍后再试'))
      }
    } else if (left <= 0) {
      clearInterval(pollingTimers.value[a.id])
      toast('排队处理中，可稍后到「我的订单」查看结果')
    }
  }, 2000)
}

onMounted(() => {
  loadSeckillActivities()
})

onUnmounted(() => {
  Object.values(pollingTimers.value).forEach((t) => clearInterval(t))
})
</script>

<template>
  <div class="seckill-page">
    <div class="nav-bar">
      <span class="back" @click="router.back()">‹</span>
      <span class="title">限时秒杀</span>
    </div>
    <div class="page-pad">

      <div class="activity" v-for="a in activities" :key="a.id">
        <img class="aimg" :src="medImage({ name: a.medicineName || a.title, imageUrl: a.medicineImageUrl })" :alt="a.title" loading="lazy">
        <div class="ainfo">
          <div class="atitle">{{ a.title }}</div>
          <div class="aprice">
            <span class="now">￥{{ Number(a.seckillPrice || 0).toFixed(2) }}</span>
            <span class="orig" v-if="originalPrice(a)">￥{{ originalPrice(a) }}</span>
          </div>
          <div class="aprogress">
            <div class="bar"><div class="fill" :style="{ width: progressOf(a) + '%' }"></div></div>
            <span class="pnum">已抢 {{ a.soldCount || 0 }}/{{ a.totalStock }}</span>
          </div>
          <div class="ameta">{{ formatTime(a.startTime) }} ~ {{ formatTime(a.endTime) }}</div>
        </div>
        <button
          class="abtn"
          :class="{ live: stateOf(a) === 'LIVE' }"
          :disabled="stateOf(a) !== 'LIVE'"
          @click="onSeckill(a)"
        >{{ stateText(a) }}</button>
      </div>

      <div class="empty" v-if="!activities.length">暂无秒杀活动</div>
    </div>

    <!-- 滑块验证码弹窗（前置人机拦截：票据一次性，失败自动换图） -->
    <div class="cap-mask" v-if="modal.show" @click.self="modal.show = false">
      <div class="cap-box">
        <div class="cap-head">
          <span>安全验证</span>
          <span class="cap-close" @click="modal.show = false">×</span>
        </div>
        <div class="cap-img" v-if="modal.captcha">
          <img class="cap-bg" :src="modal.captcha.bgDataUrl" alt="滑块背景">
          <img
            class="cap-piece"
            :src="modal.captcha.pieceDataUrl"
            :style="{ top: modal.captcha.pieceY + 'px', left: dragX + 'px' }"
            alt="滑块"
          >
        </div>
        <div class="cap-track">
          <span class="cap-track-label">{{ dragX > 0 ? '已拖动 ' + dragX + 'px' : '拖动滑块对齐缺口' }}</span>
          <input class="cap-range" type="range" min="0" :max="DRAG_MAX" v-model.number="dragX" @input="onDragInput">
        </div>
        <button class="cap-btn" :disabled="verifying" @click="onVerify">
          {{ verifying ? '验证中…' : '验证并抢购' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.seckill-page { padding-bottom: 20px; }
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 12px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .04);
}
.nav-bar .back {
  width: 32px;
  text-align: center;
  font-size: 28px;
  line-height: 1;
  color: #00BFA5;
  cursor: pointer;
}
.nav-bar .title { flex: 1; margin-left: 4px; font-size: 16px; font-weight: 600; color: #333; }
.page-pad { padding: 12px 16px; }
.activity {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-radius: 12px;
  padding: 12px;
  margin-bottom: 12px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.aimg {
  width: 76px;
  height: 76px;
  border-radius: 10px;
  object-fit: cover;
  background: #f6f6f6;
}
.ainfo { flex: 1; min-width: 0; }
.atitle { font-size: 15px; font-weight: 600; color: #1a1a1a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aprice { margin: 5px 0; }
.aprice .now { color: #FF5252; font-size: 19px; font-weight: 700; }
.aprice .orig { color: #bbb; font-size: 12px; text-decoration: line-through; margin-left: 6px; }
.aprogress { display: flex; align-items: center; gap: 8px; }
.aprogress .bar { flex: 1; height: 7px; border-radius: 4px; background: #f0f0f0; overflow: hidden; }
.aprogress .fill { height: 100%; background: linear-gradient(90deg, #FF8A65, #FF5252); border-radius: 4px; }
.aprogress .pnum { font-size: 11px; color: #999; white-space: nowrap; }
.ameta { font-size: 11px; color: #bbb; margin-top: 5px; }
.abtn {
  border: none;
  border-radius: 16px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  background: #ddd;
  color: #fff;
  cursor: not-allowed;
}
.abtn.live { background: linear-gradient(90deg, #FF5252, #FF8A65); cursor: pointer; }
.empty { text-align: center; color: #bbb; font-size: 13px; padding: 26px 0; }

/* 滑块验证码弹窗 */
.cap-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, .55);
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cap-box {
  width: 320px;
  background: #fff;
  border-radius: 14px;
  padding: 14px;
}
.cap-head {
  display: flex;
  justify-content: space-between;
  font-size: 15px;
  font-weight: 700;
  color: #333;
  margin-bottom: 10px;
}
.cap-close { cursor: pointer; color: #999; font-size: 18px; line-height: 1; }
.cap-img {
  position: relative;
  width: 280px;
  height: 140px;
  margin: 0 auto 10px;
  border-radius: 8px;
  overflow: hidden;
  background: #f2f2f2;
}
.cap-bg, .cap-piece { position: absolute; left: 0; top: 0; width: 280px; height: 140px; }
.cap-piece { width: 44px; height: 44px; }
.cap-track { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.cap-track-label { font-size: 11px; color: #999; white-space: nowrap; }
.cap-range { flex: 1; accent-color: #00BFA5; }
.cap-btn {
  width: 100%;
  padding: 10px 0;
  border: none;
  border-radius: 10px;
  background: linear-gradient(90deg, #FF5252, #FF8A65);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}
.cap-btn:disabled { opacity: .6; }
</style>
