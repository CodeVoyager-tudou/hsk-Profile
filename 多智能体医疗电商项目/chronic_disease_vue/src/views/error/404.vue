<template>
  <div class="err-page-container">
    <div class="err-code">404</div>
    <div class="err-desc">哦，页面不见了。</div>
    <div class="err-desc">请检查您输入的URL是否正确，或者点击下方按钮返回首页。</div>
    <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style lang="scss" scoped>
.err-page-container {
  text-align: center;
  padding: 60px 0;
  .err-code {
    font-size: 120px;
    font-weight: bold;
    color: #1f6f43;
    line-height: 1;
  }
  .err-desc {
    font-size: 18px;
    color: #666;
    margin: 10px 0;
  }
}
</style>
