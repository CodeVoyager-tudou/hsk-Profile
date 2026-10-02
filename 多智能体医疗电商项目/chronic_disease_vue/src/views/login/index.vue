<template>
  <div class="login-page">
    <!-- 顶部品牌区 -->
    <div class="login-hero">
      <div class="hero-logo">
        <svg viewBox="0 0 48 48" width="56" height="56" fill="rgba(255,255,255,.95)">
          <path d="M24 4a20 20 0 100 40 20 20 0 000-40zm-2 28l-7-7 3-3 4 4 9-9 3 3-12 12z"/>
        </svg>
      </div>
      <h1 class="hero-title">慢性病健康商城</h1>
      <p class="hero-sub">健康管理 · 正品用药 · 智能问诊</p>
    </div>

    <!-- 登录卡片 -->
    <div class="login-card">
      <div class="input-group">
        <span class="input-icon">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="#999"><path d="M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4 0-8 2-8 6v2h16v-2c0-4-4-6-8-6z"/></svg>
        </span>
        <input
          v-model="loginForm.username"
          type="text"
          placeholder="请输入账号"
          class="input-field"
          autocomplete="off"
        />
      </div>

      <div class="input-group">
        <span class="input-icon">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="#999"><path d="M18 8h-1V6A5 5 0 007 6v2H6a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V10a2 2 0 00-2-2zm-6 9a2 2 0 110-4 2 2 0 010 4zm3-9H9V6a3 3 0 016 0v2z"/></svg>
        </span>
        <input
          v-model="loginForm.password"
          :type="showPwd ? 'text' : 'password'"
          placeholder="请输入密码"
          class="input-field"
          autocomplete="new-password"
          @keyup.enter="handleLogin"
        />
        <span class="input-action" @click="showPwd = !showPwd">
          <svg v-if="!showPwd" viewBox="0 0 24 24" width="20" height="20" fill="#999"><path d="M12 4.5C7 4.5 2.7 7.6 1 12c1.7 4.4 6 7.5 11 7.5s9.3-3.1 11-7.5c-1.7-4.4-6-7.5-11-7.5zm0 12.5a5 5 0 110-10 5 5 0 010 10zm0-8a3 3 0 100 6 3 3 0 000-6z"/></svg>
          <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="#999"><path d="M12 6a6 6 0 016 6 6 6 0 01-.4 2.1l3.6 3.6c1.7-1.5 3-3.2 3.8-5.2-1.7-4.4-6-7.5-11-7.5-1.2 0-2.3.2-3.4.5l2.5 2.5C13.7 6.2 12.8 6 12 6zM2.7 4.3L5 6.6C3.2 8 1.7 9.8 1 12c1.7 4.4 6 7.5 11 7.5 1.5 0 2.9-.3 4.2-.8l2.5 2.5 1.4-1.4L4.1 2.9z"/></svg>
        </span>
      </div>

      <div class="login-options">
        <label class="remember-me">
          <input type="checkbox" v-model="loginForm.rememberMe" class="checkbox" />
          <span>记住密码</span>
        </label>
        <span class="forgot-link">忘记密码？</span>
      </div>

      <button class="login-btn" :class="{ loading: loading }" @click="handleLogin" :disabled="loading">
        <span v-if="!loading">登 录</span>
        <span v-else class="loading-dots">
          <span></span><span></span><span></span>
        </span>
      </button>

      <div class="login-footer">
        <span>还没有账号？</span>
        <router-link to="/register" class="register-link">立即注册</router-link>
      </div>
    </div>

    <!-- 底部协议 -->
    <p class="agreement">
      登录即代表您同意《用户协议》和《隐私政策》
    </p>
  </div>
</template>

<script setup>
import useUserStore from '@/store/modules/user'

const userStore = useUserStore()
const router = useRouter()
const route = useRoute()
const loginRef = ref(null)
const loading = ref(false)
const redirect = ref(undefined)
const showPwd = ref(false)

const loginForm = ref({
  username: 'admin',
  password: '123456',
  rememberMe: false
})

function handleLogin() {
  if (!loginForm.value.username || !loginForm.value.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  userStore.login(loginForm.value).then(() => {
    const redirectUrl = redirect.value || '/'
    router.push({ path: redirectUrl })
  }).catch(() => {
    loading.value = false
  })
}

watch(route, (newRoute) => {
  redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background: linear-gradient(180deg, #00BFA5 0%, #009688 40%, #f5f7fa 40%, #f5f7fa 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* 品牌区 */
.login-hero {
  padding: 60px 0 30px;
  text-align: center;
  color: #fff;
}
.hero-logo {
  width: 80px;
  height: 80px;
  margin: 0 auto 12px;
  background: rgba(255,255,255,.15);
  border-radius: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.hero-title {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}
.hero-sub {
  font-size: 13px;
  opacity: .85;
  margin-top: 6px;
  letter-spacing: 1px;
}

/* 登录卡片 */
.login-card {
  width: calc(100% - 48px);
  max-width: 360px;
  background: #fff;
  border-radius: 20px;
  padding: 28px 24px;
  box-shadow: 0 8px 40px rgba(0,0,0,.08);
  margin-top: 10px;
}

.input-group {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 2px solid #eee;
  border-radius: 12px;
  padding: 0 14px;
  margin-bottom: 16px;
  transition: border-color .2s;
  background: #fafafa;
}
.input-group:focus-within {
  border-color: var(--primary);
  background: #fff;
}
.input-icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.input-field {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 15px;
  padding: 12px 0;
  outline: none;
  color: #333;
}
.input-field::placeholder { color: #bbb; }
.input-action {
  display: flex;
  align-items: center;
  cursor: pointer;
  flex-shrink: 0;
  padding: 4px;
}

.login-options {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  font-size: 13px;
}
.remember-me {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #666;
  cursor: pointer;
}
.checkbox {
  width: 16px;
  height: 16px;
  accent-color: var(--primary);
}
.forgot-link {
  color: var(--primary);
  cursor: pointer;
}

/* 登录按钮 */
.login-btn {
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #00BFA5, #009688);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity .2s, transform .1s;
}
.login-btn:active { transform: scale(.98); }
.login-btn.loading { opacity: .7; }
.login-btn:disabled { cursor: not-allowed; }

.loading-dots {
  display: inline-flex;
  gap: 4px;
}
.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
  animation: bounce .6s infinite alternate;
}
.loading-dots span:nth-child(2) { animation-delay: .2s; }
.loading-dots span:nth-child(3) { animation-delay: .4s; }
@keyframes bounce {
  to { opacity: .3; transform: translateY(-4px); }
}

.login-footer {
  text-align: center;
  margin-top: 20px;
  font-size: 14px;
  color: #999;
}
.register-link {
  color: var(--primary);
  font-weight: 500;
  margin-left: 4px;
}

/* 底部协议 */
.agreement {
  margin-top: auto;
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: #aaa;
}
</style>
