<!--
  登录页 —— F01 占位版
  ⚠️ 真正的登录表单（调用 POST /api/v1/auth/login、写 pinia、按 redirect 跳回）在 F02/S09 实现。
     F01 阶段这个页面只需要「能被渲染出来、并且不报错」，
     因为路由守卫会把所有未登录访问都踢到这里，这里是验证守卫是否生效的落点。
-->
<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">CMS 内容管理系统</h2>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="登录表单尚未实现"
        description="F01 阶段此处为占位页：路由守卫生效时会被重定向到这里。表单与后端对接在 F02 / S09 完成。"
      />
      <p v-if="redirect" class="login-redirect">
        登录成功后将跳转到：<code>{{ redirect }}</code>
      </p>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
// 路由守卫踢过来时带的原目标路径，登录成功后用于跳回
const redirect = computed(() => route.query.redirect || '')
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: #f0f2f5;
}
.login-card {
  width: 420px;
}
.login-title {
  margin: 0 0 16px;
  text-align: center;
  font-size: 20px;
  color: #303133;
}
.login-redirect {
  margin: 16px 0 0;
  font-size: 13px;
  color: #909399;
}
</style>
