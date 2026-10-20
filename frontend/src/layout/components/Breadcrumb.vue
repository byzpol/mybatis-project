<!--
  面包屑（F03）
  根据当前路由自动生成，数据全部来自路由 meta，不手工维护：
    首页 / [meta.group] / [meta.title]
  · 分组名（如"文章管理"）不是真实页面，所以渲染成纯文字而不是链接
  · 最后一项是当前页，也不做链接（点了没意义，还会多一次无用的路由跳转）
  · 只有一个层级时（如 /profile）仍然显示"首页 / 个人信息"，保持一致
-->
<template>
  <el-breadcrumb separator="/" class="breadcrumb">
    <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
    <el-breadcrumb-item v-if="group">{{ group }}</el-breadcrumb-item>
    <el-breadcrumb-item v-if="title">{{ title }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 当前页标题（来自 meta.title）。登录页/403 等不带布局的页面不会渲染本组件 */
const title = computed(() => route.meta?.title || '')

/** 所属分组名（来自 meta.group），没有则为空 */
const group = computed(() => route.meta?.group || '')
</script>

<style scoped>
.breadcrumb {
  font-size: 13px;
  line-height: var(--app-navbar-height);
}

/* 最后一项（当前页）用主文字色加粗一点，形成"我到哪了"的视觉落点 */
.breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  font-weight: 600;
  color: var(--app-text-1);
}

.breadcrumb :deep(.el-breadcrumb__inner) {
  color: var(--app-text-3);
}

/* 非链接项（分组名、当前页）不该有"可点"的暗示。
   Element Plus 给带 to 的项加 .is-link，所以这里用 :not(.is-link) 精确区分，
   不要用 :not(:last-child) —— 那会连"首页"这个真链接一起干掉。 */
.breadcrumb :deep(.el-breadcrumb__inner:not(.is-link)) {
  cursor: default;
}

.breadcrumb :deep(.el-breadcrumb__inner.is-link:hover) {
  color: var(--app-primary);
}
</style>
