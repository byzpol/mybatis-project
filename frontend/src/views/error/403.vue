<!--
  403 页 —— F02 新增
  路由守卫在 meta.permission 校验不通过时重定向到这里。

  为什么单独做一页而不是弹个 ElMessage 了事：
  1. 弹消息是"短暂的"，用户点完就不知道刚才发生了什么，刷新又回来了；
  2. 页面级拒绝要给用户"下一步去哪"的出口（返回首页 / 返回上一页）；
  3. 与 404 页风格一致，答辩演示时"越权访问被挡住"是能看到的证据。

  ⚠️ 与后端 403 的分工：后端 @RequirePermission 抛的 403 由 axios 拦截器弹提示，
     这里处理的是"前端路由就没打算让你进"的情况。两者不冲突。
-->
<template>
  <div class="forbidden">
    <el-result icon="error" title="403" sub-title="抱歉，你没有访问该页面的权限">
      <template #extra>
        <el-button @click="goBack">返回上一页</el-button>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
const goHome = () => router.push('/')
// 用 back() 而非写死路径：用户可能从任意页面跳进来，回到来源页最符合直觉
const goBack = () => router.back()
</script>

<style scoped>
.forbidden {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>
