import { createWebHistory, createRouter } from 'vue-router'
import Layout from '@/layout'

export const constantRoutes = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/login/register.vue'),
    hidden: true
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/error/404.vue'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401.vue'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: '/index',
    children: [
      {
        path: '/index',
        component: () => import('@/views/dashboard/index.vue'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/shop',
    component: Layout,
    redirect: '/shop/medicine',
    name: 'Shop',
    meta: { title: '药品商城', icon: 'shopping' },
    children: [
      {
        path: 'medicine',
        component: () => import('@/views/shop/medicine.vue'),
        name: 'Medicine',
        meta: { title: '药品列表', icon: 'medicine' }
      },
      {
        path: 'detail/:id',
        component: () => import('@/views/shop/detail.vue'),
        name: 'MedicineDetail',
        meta: { title: '药品详情', activeMenu: '/shop/medicine' }
      },
      {
        // 收银台：PENDING 现金单的确认支付页（确认支付/返回；超时由后端延迟消息自动关单）
        path: 'cashier/:orderId',
        component: () => import('@/views/shop/cashier.vue'),
        name: 'Cashier',
        meta: { title: '收银台', activeMenu: '/shop/medicine' }
      }
    ]
  },
  {
    path: '/orders',
    component: Layout,
    children: [
      {
        path: '',
        component: () => import('@/views/shop/orders.vue'),
        name: 'Orders',
        meta: { title: '我的订单', icon: 'order' }
      }
    ]
  },
  {
    path: '/wallet',
    component: Layout,
    children: [
      {
        path: '',
        component: () => import('@/views/user/wallet.vue'),
        name: 'Wallet',
        meta: { title: '我的钱包', icon: 'money' }
      }
    ]
  },
  {
    path: '/seckill',
    component: Layout,
    children: [
      {
        path: '',
        component: () => import('@/views/shop/seckill.vue'),
        name: 'Seckill',
        meta: { title: '限时秒杀', icon: 'flash' }
      }
    ]
  },
  {
    // 管理端路由区：页面本身有角色检查（非 ADMIN 跳回首页），
    // 真正的权限闸门在网关 AuthFilter（JWT role=ADMIN 才放行 /api/admin/**）
    path: '/admin',
    component: Layout,
    redirect: '/admin/medicine',
    children: [
      {
        path: 'medicine',
        component: () => import('@/views/admin/medicine.vue'),
        name: 'AdminMedicine',
        meta: { title: '药品管理' }
      },
      {
        path: 'seckill',
        component: () => import('@/views/admin/seckill.vue'),
        name: 'AdminSeckill',
        meta: { title: '秒杀活动' }
      },
      {
        path: 'orders',
        component: () => import('@/views/admin/orders.vue'),
        name: 'AdminOrders',
        meta: { title: '订单管理' }
      },
      {
        path: 'compensation',
        component: () => import('@/views/admin/compensation.vue'),
        name: 'AdminCompensation',
        meta: { title: '补偿台账' }
      }
    ]
  },
  {
    path: '/coupons',
    component: Layout,
    children: [
      {
        path: '',
        component: () => import('@/views/shop/coupons.vue'),
        name: 'Coupons',
        meta: { title: '优惠券', icon: 'coupon' }
      }
    ]
  },
  {
    path: '/points',
    component: Layout,
    redirect: '/points/signin',
    name: 'Points',
    meta: { title: '积分中心', icon: 'points' },
    children: [
      {
        path: 'signin',
        component: () => import('@/views/user/signin.vue'),
        name: 'SignIn',
        meta: { title: '每日签到', icon: 'signin' }
      },
      {
        path: 'records',
        component: () => import('@/views/user/records.vue'),
        name: 'Records',
        meta: { title: '积分记录', icon: 'records' }
      }
    ]
  },
  {
    path: '/ai',
    component: Layout,
    children: [
      {
        path: '',
        component: () => import('@/views/ai/chat.vue'),
        name: 'AIChat',
        meta: { title: 'AI助手', icon: 'chat' }
      }
    ]
  },
  {
    path: '/mine',
    component: Layout,
    redirect: '/user/profile',
    hidden: true,
    children: [
      {
        path: '',
        component: () => import('@/views/user/profile.vue'),
      }
    ]
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    children: [
      {
        path: 'profile',
        component: () => import('@/views/user/profile.vue'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    } else {
      return { top: 0 }
    }
  }
})

export default router
