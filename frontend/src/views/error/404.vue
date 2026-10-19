<!--
  404 页 —— F01 占位版
  路由表最后的 '/:pathMatch(.*)*' 兜底到这里。
  注意：它与后端无关（后端 404 由 GlobalExceptionHandler 返回统一 Result），
  这里处理的是「前端路由表里没有这个路径」。
-->
<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在，请检查地址是否正确">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
const goHome = () => router.push('/')
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>
