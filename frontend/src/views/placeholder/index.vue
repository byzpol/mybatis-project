<!--
  占位页（F03）
  给"路由已经注册、但页面还没实现"的模块用。

  为什么要有它：F03 的菜单是从路由表生成的，如果只注册文章管理的路由，
  侧边栏就只有一个分组，看不出"正经企业后台"的样子，也无法演示
  "不同角色看到不同菜单"。所以 F03 把所有域的路由都登记好，
  未实现的统一落到本页 —— 页面里直接写清楚"归谁做、哪个步骤做"，
  后面 F05/F06/S09~S16 谁做到哪个模块，把路由的 component 换掉即可。
-->
<template>
  <div class="placeholder">
    <div class="page-header">
      <div class="page-header__left">
        <h1 class="page-header__title">{{ title }}</h1>
        <p class="page-header__desc">{{ desc }}</p>
      </div>
    </div>

    <div class="app-card">
      <el-empty description="该页面尚未实现" :image-size="120">
        <template #description>
          <p class="placeholder__hint">该页面尚未实现</p>
        </template>
        <div class="placeholder__meta">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="当前路由">{{ route.path }}</el-descriptions-item>
            <el-descriptions-item label="菜单分组">{{ group || '无（顶级菜单）' }}</el-descriptions-item>
            <el-descriptions-item label="所需权限">
              <el-tag v-if="permission" type="warning" effect="light" round size="small">
                {{ permission }}
              </el-tag>
              <span v-else class="placeholder__muted">仅需登录</span>
            </el-descriptions-item>
            <el-descriptions-item label="登录身份">
              <span>{{ userStore.nickname }}（{{ roleText }}）</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'

const route = useRoute()
const userStore = useUserStore()

const title = computed(() => route.meta?.title || '页面')
const group = computed(() => route.meta?.group || '')
const permission = computed(() => route.meta?.permission || '')

const desc = computed(() =>
  group.value ? `${group.value} · 页面骨架已就绪，等待对应开发步骤填充` : '页面骨架已就绪'
)

const roleText = computed(() => {
  const roles = userStore.roles
  return roles.length ? roles.join(' / ') : '无角色'
})
</script>

<style scoped>
.placeholder__hint {
  margin: 0;
  font-size: 14px;
  color: var(--app-text-2);
}

.placeholder__meta {
  width: 420px;
  max-width: 100%;
  margin: 0 auto;
  text-align: left;
}

.placeholder__muted {
  color: var(--app-text-3);
}
</style>
