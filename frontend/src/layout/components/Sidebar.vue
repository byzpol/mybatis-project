<!--
  左侧导航（F03）
  · 宽度 220px，折叠后 64px，深色底
  · 菜单项**从路由表动态生成**（读 router.options.routes 里 '/' 的子路由），
    不另建一份菜单配置 —— 两套配置必然有一天对不上
  · 只显示当前用户有权限的项（userStore.hasPermission）
  · 选中项左侧 3px 主色高亮条
-->
<template>
  <div class="sidebar">
    <!-- 品牌区：logo 用 CSS 画，不引图片（满足"无外链资源"要求） -->
    <div class="sidebar__brand">
      <div class="sidebar__logo" aria-hidden="true">C</div>
      <transition name="sidebar-fade">
        <span v-show="!collapse" class="sidebar__title">CMS 内容管理系统</span>
      </transition>
    </div>

    <el-scrollbar class="sidebar__scroll">
      <el-menu
        :default-active="activeMenu"
        :collapse="collapse"
        :collapse-transition="false"
        router
        unique-opened
      >
        <template v-for="group in menuGroups" :key="group.name || '__ungrouped__'">
          <!-- 有分组名 → 可展开的子菜单 -->
          <el-sub-menu v-if="group.name" :index="group.name">
            <template #title>
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.name }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-sub-menu>

          <!-- 无分组名 → 直接平铺的顶级菜单项 -->
          <template v-else>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </template>
      </el-menu>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
// 图标显式 import：按需引入模式下不会自动注入（@element-plus/icons-vue 是独立的包）。
// 这里用"名字 → 组件"的映射表，路由 meta.icon 里只存字符串，
// 好处是路由表保持纯数据（将来若菜单改由后端下发，不用改这段逻辑）。
import {
  Document,
  EditPen,
  Stamp,
  Files,
  PriceTag,
  User,
  Key,
  Tickets,
  DataLine
} from '@element-plus/icons-vue'

defineProps({
  /** 是否折叠（由 AppLayout 统一控制：窗口变窄时自动折叠、点按钮手动折叠） */
  collapse: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const ICONS = { Document, EditPen, Stamp, Files, PriceTag, User, Key, Tickets, DataLine }

/** 默认图标：路由 meta.icon 写了但映射表里没有时兜底，避免菜单项缺图标导致文字错位 */
const FALLBACK_ICON = Document

/**
 * 当前高亮项。
 * 直接用 route.path：本项目的菜单项与路由路径一一对应（index="/articles/list"），
 * 所以不需要再维护一份"路由 name → 菜单 index"的映射。
 */
const activeMenu = computed(() => route.path)

/**
 * 菜单数据：从路由表推导。
 *
 * 过滤规则（三层）：
 *   ① 没 meta.title 的不要 —— 没有标题的菜单项没法显示
 *   ② meta.hidden 的不要 —— 如"个人信息"，有路由但不进菜单
 *   ③ meta.permission 不通过的不要 —— 菜单层面的权限控制
 */
const menuGroups = computed(() => {
  const root = router.options.routes.find((r) => r.path === '/')
  const children = root?.children || []

  const visible = children.filter(
    (c) =>
      c.meta &&
      c.meta.title &&
      !c.meta.hidden &&
      userStore.hasPermission(c.meta.permission)
  )

  // 按 meta.group 归组，保持路由表里的声明顺序（不排序：顺序由路由表决定，改顺序只改一处）
  const groups = []
  for (const child of visible) {
    const name = child.meta.group || ''
    let bucket = groups.find((g) => g.name === name)
    if (!bucket) {
      bucket = { name, icon: FALLBACK_ICON, items: [] }
      groups.push(bucket)
    }
    // 子路由的 path 是相对路径（'articles/list'），拼成绝对路径给 el-menu 的 index
    bucket.items.push({
      path: `/${child.path}`,
      title: child.meta.title,
      icon: ICONS[child.meta.icon] || FALLBACK_ICON
    })
  }

  // 分组的图标取组内第一项的图标，省得再为分组配一份映射
  groups.forEach((g) => {
    if (g.items.length > 0) {
      g.icon = g.items[0].icon
    }
  })

  return groups
})
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background-color: var(--app-sidebar-bg);
}

/* ---------------- 品牌区 ---------------- */
.sidebar__brand {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  align-items: center;
  height: var(--app-navbar-height);
  padding: 0 16px;
  overflow: hidden;
  border-bottom: 1px solid var(--app-sidebar-border);
}

.sidebar__logo {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--app-primary) 0%, #4f8df7 100%);
  border-radius: var(--app-radius-sm);
}

.sidebar__title {
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  white-space: nowrap;
}

.sidebar-fade-enter-active,
.sidebar-fade-leave-active {
  transition: opacity var(--app-transition);
}

.sidebar-fade-enter-from,
.sidebar-fade-leave-to {
  opacity: 0;
}

/* ---------------- 菜单区 ---------------- */
.sidebar__scroll {
  flex: 1;
  min-height: 0;
}

/* el-menu 在深色底下的样式。用 :deep() 覆盖而不是给 el-menu 传
   background-color / text-color 这些 prop —— 那组 prop 只覆盖一部分状态，
   选中态、hover 态还得再补，不如一次性用 CSS 写清楚。 */
:deep(.el-menu) {
  background-color: transparent;
  border-right: none;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  height: 46px;
  line-height: 46px;
  color: var(--app-sidebar-text);
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  color: #fff;
  background-color: var(--app-sidebar-hover-bg);
}

/* 展开的分组标题也高亮，否则用户不知道自己在哪个分组里 */
:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
}

/* 选中项：3px 主色高亮条 + 主色浅底 + 白字 */
:deep(.el-menu-item.is-active) {
  color: #fff;
  background-color: rgba(47, 111, 237, 0.16);
}

:deep(.el-menu-item.is-active)::before {
  position: absolute;
  top: 0;
  left: 0;
  width: 3px;
  height: 100%;
  content: '';
  background-color: var(--app-sidebar-active);
}

/* 二级菜单（分组展开后的子项）内缩，形成层级感 */
:deep(.el-menu .el-menu-item) {
  padding-left: 40px !important;
}

/* 折叠态：图标居中，去掉内缩 */
:deep(.el-menu--collapse .el-menu-item),
:deep(.el-menu--collapse .el-sub-menu__title) {
  padding: 0 !important;
  /* justify-content 一定要显式写：el-menu-item / el-sub-menu__title 是 flex 容器，
     默认 flex-start，光靠 text-align 居中不了图标（F03 截图里发现的：折叠后图标挤在左边） */
  justify-content: center;
}

:deep(.el-menu--collapse .el-menu-item .el-icon),
:deep(.el-menu--collapse .el-sub-menu__title .el-icon) {
  width: auto;
  margin: 0;
}

:deep(.el-menu--collapse .el-menu-item.is-active)::before {
  width: 3px;
}

/* 禁止横向滚动：菜单项的内边距 + 图标宽度会让内容比 220px 略宽，
   el-scrollbar 就会在侧边栏底部画一条横向滚动条（截图里那条深色残留）。
   侧边栏内的内容永远不该横向滚动。 */
:deep(.el-scrollbar__wrap) {
  overflow-x: hidden;
}

:deep(.el-scrollbar__bar.is-horizontal) {
  display: none;
}
</style>
