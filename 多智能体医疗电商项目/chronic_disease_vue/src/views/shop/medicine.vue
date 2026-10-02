<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import { medImage, bannerImg } from '@/utils/images'
import { computed } from 'vue'

const router = useRouter()
const { state, searchMedicines, loadCart } = useStore()

// 购物车里总件数（角标数字）；进商城页时静默刷新一次
const cartCount = computed(() =>
  state.cartItems.reduce((sum, i) => sum + i.quantity, 0))

function goDetail(m) {
  state.currentMed = m
  state.detailQty = 1
  state.detailCoupon = ''
  router.push('/shop/detail/' + m.id)
}

function goCart() {
  router.push('/shop/cart')
}

onMounted(() => {
  searchMedicines(1)
  loadCart()
})
</script>

<template>
  <div class="medicine-page">
    <!-- 秒杀入口 -->
    <div class="seckill-entry" @click="router.push('/seckill')">
      <span class="flash">⚡</span>
      <span class="entry-title">限时秒杀</span>
      <span class="entry-sub">低至 3 折 · 先抢先得</span>
      <span class="go">进入会场 ›</span>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <div class="search-input-wrap">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="#999"><path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 10-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1114 9.5 4.5 4.5 0 019.5 14z"/></svg>
        <input
          v-model="state.keyword"
          placeholder="搜索药品名 / 适应症"
          class="search-field"
          @keyup.enter="searchMedicines(1)"
        />
      </div>
      <span class="search-btn" @click="searchMedicines(1)">搜索</span>
      <!-- 购物车入口（多商品合并结算才能凑满减门槛，见 /shop/cart） -->
      <div class="cart-entry" @click="goCart">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="#009688"><path d="M7 18a2 2 0 100 4 2 2 0 000-4zm10 0a2 2 0 100 4 2 2 0 000-4zM7.2 14.5l-.1.5h11.3l1.6-8H6.7L6 3H2v2h2.3l2 11h.9zm.9-2L7 8h11.1l-1.2 6H8.4z"/></svg>
        <span class="cart-badge" v-if="cartCount > 0">{{ cartCount > 99 ? '99+' : cartCount }}</span>
      </div>
    </div>

    <!-- 分类 chips -->
    <div class="chips">
      <div
        class="chip"
        :class="{ active: state.category === '' }"
        @click="state.category = ''; searchMedicines(1)"
      >全部</div>
      <div
        v-for="c in state.categories"
        :key="c"
        class="chip"
        :class="{ active: state.category === c }"
        @click="state.category = c; searchMedicines(1)"
      >{{ c }}</div>
    </div>

    <!-- Banner -->
    <div class="banner">
      <div class="banner-bg"></div>
      <div class="banner-text">
        <h3>慢病用药 · 正品保障</h3>
        <p>签到领积分 · 兑换好药</p>
      </div>
    </div>

    <!-- 商品列表 -->
    <div class="goods-grid">
      <div
        v-for="m in state.medicines"
        :key="m.id"
        class="goods-card"
        @click="goDetail(m)"
      >
        <div class="goods-img">
          <img :src="medImage(m)" :alt="m.name" loading="lazy">
          <span class="cat-tag">{{ m.category || '药品' }}</span>
        </div>
        <div class="goods-body">
          <div class="goods-name">{{ m.name }}</div>
          <div class="goods-indication">{{ m.indication || '-' }}</div>
          <div class="goods-price">
            <span class="cur">￥</span><span class="num">{{ m.price }}</span>
          </div>
          <div class="goods-points" v-if="m.pointsPrice > 0">积分兑 {{ m.pointsPrice }}分/件</div>
          <div class="goods-stock">库存 {{ m.stock }}</div>
        </div>
      </div>
    </div>

    <div class="empty" v-if="state.medicines.length === 0 && !state.loading">
      没有符合条件的药品
    </div>
    <div class="loading" v-if="state.loading">加载中...</div>

    <div class="pager" v-if="state.medicines.length">
      <el-pagination
        :current-page="state.medPage"
        :page-size="10"
        :total="state.totalPages * 10"
        layout="prev, pager, next"
        @current-change="(p) => searchMedicines(p)"
      />
    </div>
  </div>
</template>

<style scoped>
.medicine-page { padding-bottom: 20px; }

/* 秒杀入口 */
.seckill-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 16px 0;
  padding: 11px 14px;
  border-radius: 12px;
  background: linear-gradient(90deg, #FFF3E0, #FFE0B2);
  cursor: pointer;
}
.seckill-entry .flash { font-size: 17px; }
.seckill-entry .entry-title { font-size: 14px; font-weight: 700; color: #E65100; }
.seckill-entry .entry-sub { flex: 1; font-size: 11px; color: #BF6A1E; }
.seckill-entry .go { font-size: 12px; color: #E65100; }

/* 搜索栏 */
.search-bar {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
}
.search-input-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f5f5f7;
  border-radius: 20px;
  padding: 0 14px;
  height: 40px;
}
.search-field {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 14px;
  outline: none;
  color: #333;
}
.search-field::placeholder { color: #999; }
.search-btn {
  color: var(--primary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  padding: 0 4px;
}
.cart-entry {
  position: relative;
  display: flex;
  align-items: center;
  padding: 0 2px 0 6px;
  cursor: pointer;
}
.cart-badge {
  position: absolute;
  top: -6px;
  right: -8px;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 10px;
  color: #fff;
  background: #FF5722;
  border-radius: 8px;
  padding: 0 4px;
  box-sizing: border-box;
}

/* 分类 chips */
.chips {
  display: flex;
  gap: 8px;
  padding: 10px 16px;
  overflow-x: auto;
  background: #fff;
  -webkit-overflow-scrolling: touch;
}
.chips::-webkit-scrollbar { display: none; }
.chip {
  flex-shrink: 0;
  padding: 6px 16px;
  border-radius: 20px;
  background: #f0f2f5;
  color: #666;
  font-size: 13px;
  cursor: pointer;
  transition: all .2s;
}
.chip.active {
  background: var(--primary);
  color: #fff;
  font-weight: 500;
}

/* Banner */
.banner {
  margin: 12px 16px;
  border-radius: 16px;
  overflow: hidden;
  position: relative;
  height: 120px;
}
.banner-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #00BFA5, #4DD0E1);
}
.banner-text {
  position: relative;
  z-index: 1;
  padding: 24px;
  color: #fff;
}
.banner-text h3 { font-size: 18px; font-weight: 700; }
.banner-text p { font-size: 12px; opacity: .9; margin-top: 6px; }

/* 商品网格 */
.goods-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 12px 16px;
}

/* 网页版（桌面宽屏）：固定 2 列会把商品卡拉成半屏大方图，改为按宽度自适应列数；
   <768px 手机端仍是 2 列 */
@media (min-width: 768px) {
  .goods-grid {
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  }
}
.goods-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
  cursor: pointer;
  transition: transform .15s;
}
.goods-card:active { transform: scale(.97); }
.goods-img {
  width: 100%;
  aspect-ratio: 1;
  background: #f8f8f8;
  position: relative;
  overflow: hidden;
}
.goods-img img { width: 100%; height: 100%; object-fit: cover; }
.cat-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  background: rgba(0,191,165,.9);
  color: #fff;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 6px;
}
.goods-body { padding: 10px; }
.goods-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
  color: #1a1a1a;
}
.goods-indication {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.goods-price {
  color: var(--danger);
  font-weight: bold;
  margin-top: 6px;
  display: flex;
  align-items: baseline;
  gap: 2px;
}
.goods-price .cur { font-size: 12px; }
.goods-price .num { font-size: 18px; }
.goods-points { font-size: 11px; color: var(--warning); margin-top: 2px; }
.goods-stock { font-size: 11px; color: #ccc; margin-top: 2px; }

.empty, .loading {
  text-align: center;
  color: #999;
  padding: 40px 0;
  font-size: 14px;
}
.pager { display: flex; justify-content: center; padding: 16px; }
</style>
