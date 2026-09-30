<template>
  <div class="err-page-container">
    <div class="err-code">401</div>
    <div class="err-desc">抱歉，您没有权限访问此页面。</div>
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
    color: #e04b3a;
    line-height: 1;
  }
  .err-desc {
    font-size: 18px;
    color: #666;
    margin: 10px 0;
  }
}
</style>
