<!--
  后台布局骨架（F03）
  ┌──────────┬────────────────────────────┐
  │          │  Navbar (56px, 白底)        │
  │ Sidebar  ├────────────────────────────┤
  │ 220/64px │  <router-view> (过渡动画)   │
  └──────────┴────────────────────────────┘

  折叠状态由本组件统一持有，以 prop 下发、以事件上报 —— 不用全局状态：
  · Sidebar 需要它决定 el-menu 的 collapse
  · Navbar 需要它决定按钮图标是 Fold 还是 Expand
  只有这两个消费者、层级也只差一层，用 provide/inject 或 pinia 都是杀鸡用牛刀。
-->
<template>
  <el-container class="app-layout">
    <el-aside :width="asideWidth" class="app-layout__aside">
      <Sidebar :collapse="collapsed" />
    </el-aside>

    <el-container class="app-layout__body">
      <el-header height="56px" class="app-layout__header">
        <Navbar :collapsed="collapsed" @toggle="toggleCollapse" />
      </el-header>

      <el-main class="app-layout__main">
        <router-view v-slot="{ Component, route }">
          <transition name="fade-slide" mode="out-in">
            <!-- 用 route.fullPath 做 key：同一组件不同参数（如详情页 id 不同）也会重建，
                 避免"路由变了但页面没换"的经典问题 -->
            <component :is="Component" :key="route.fullPath" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import Sidebar from './components/Sidebar.vue'
import Navbar from './components/Navbar.vue'

/** 窄屏断点：与 Navbar 里隐藏昵称的媒体查询保持同一套约定 */
const NARROW_BREAKPOINT = 992

/** 用户手动折叠（点 Navbar 的按钮） */
const userCollapsed = ref(false)

/** 当前是否处于窄屏 */
const narrow = ref(false)

/** 实际折叠状态 = 用户折叠过 或 窗口太窄 */
const collapsed = computed(() => userCollapsed.value || narrow.value)

const asideWidth = computed(() =>
  collapsed.value ? 'var(--app-sidebar-collapsed-width)' : 'var(--app-sidebar-width)'
)

function toggleCollapse() {
  // 窄屏下 collapsed 恒为 true，点按钮的语义是"我想展开"，所以取反即可
  userCollapsed.value = !collapsed.value
}

/**
 * 响应式：窗口宽度 < 992px 时自动折叠，**恢复宽度后自动展开**。
 *
 * ⚠️ 只在"跨过断点"的瞬间动一次，而不是每次 resize 都强制设置。
 *    后者会导致用户手动展开后，随便拖一下窗口（哪怕仍是窄屏）就被折叠回去 ——
 *    手动操作被凭空吞掉。
 *
 * 为什么恢复宽度时要自动展开：如果不做，用户从窄屏拖回宽屏后侧边栏仍是折叠态，
 *    看起来像"坏了"（明明有空间却不显示菜单名）。窄屏折叠是系统行为，
 *    离开窄屏就该把这个系统行为撤掉。
 */
let wasNarrow = false

function handleResize() {
  const isNarrow = window.innerWidth < NARROW_BREAKPOINT
  narrow.value = isNarrow

  if (isNarrow && !wasNarrow) {
    userCollapsed.value = true
  } else if (!isNarrow && wasNarrow) {
    userCollapsed.value = false
  }

  wasNarrow = isNarrow
}

onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  // 必须解绑：SPA 里布局组件可能被重建，不解绑就会累积监听器
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.app-layout {
  height: 100vh;
  overflow: hidden;
}

.app-layout__aside {
  overflow: hidden;
  background-color: var(--app-sidebar-bg);
  /* 宽度由 :width 绑定，这里加过渡让折叠/展开是滑动的而不是瞬间跳变 */
  transition: width var(--app-transition);
}

.app-layout__body {
  min-width: 0; /* 不加的话表格一宽就把整个布局撑出横向滚动条 */
}

.app-layout__header {
  display: flex;
  align-items: center;
  padding: 0 16px;
  background-color: var(--app-bg-card);
  border-bottom: 1px solid var(--app-border);
}

.app-layout__main {
  padding: 20px;
  overflow-y: auto;
  background-color: var(--app-bg-page);
}
</style>
