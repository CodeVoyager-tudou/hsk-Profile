<template>
  <div class="login-container">
    <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="login-form">
      <h3 class="title">慢性病健康商城 - 注册</h3>
      <el-form-item prop="username">
        <el-input v-model="registerForm.username" type="text" size="large" auto-complete="off" placeholder="用户名">
          <template #prefix>
            <svg-icon icon-class="user" />
          </template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password">
        <el-input v-model="registerForm.password" type="password" size="large" auto-complete="off" placeholder="密码">
          <template #prefix>
            <svg-icon icon-class="password" />
          </template>
        </el-input>
      </el-form-item>
      <el-form-item style="width: 100%;">
        <el-button :loading="loading" size="large" type="primary" style="width: 100%;" @click.prevent="handleRegister">
          <span v-if="!loading">注 册</span>
          <span v-else>注 册 中...</span>
        </el-button>
      </el-form-item>
      <div style="text-align: center;">
        <router-link to="/login" style="color: #1f6f43;">已有账号？去登录</router-link>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { register } from '@/api/login'

const router = useRouter()
const registerRef = ref(null)
const loading = ref(false)

const registerForm = ref({
  username: '',
  password: ''
})

const registerRules = {
  username: [{ required: true, trigger: 'blur', message: '请输入用户名' }],
  password: [{ required: true, trigger: 'blur', message: '请输入密码' }]
}

function handleRegister() {
  registerRef.value.validate(valid => {
    if (valid) {
      loading.value = true
      register(registerForm.value).then(() => {
        router.push('/login')
      }).catch(() => {
        loading.value = false
      })
    }
  })
}
</script>

<style lang="scss" scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background-image: url('../assets/images/login-background.jpg');
  background-size: cover;
}
.login-form {
  width: 400px;
  padding: 35px 35px 15px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}
.title {
  margin: 0 auto 30px;
  text-align: center;
  color: #1f6f43;
  font-size: 24px;
}
</style>
