<!--
  后台布局骨架 —— F01 占位版
  ⚠️ F03 会用真正的骨架替换本文件（Sidebar + Navbar + Breadcrumb + theme.css）。
     这里先给出「一个能渲染、且预留好插槽位置」的最小实现，
     目的是让 F01 的路由表（'/' → AppLayout，children 为空）能跑起来、不报错。
-->
<template>
  <el-container class="app-layout">
    <el-aside width="200px" class="app-layout__aside">
      <!-- F03 在这里挂 <Sidebar /> -->
      <div class="app-layout__logo">CMS 内容管理系统</div>
      <el-menu default-active="/" router>
        <el-menu-item index="/">
          <span>首页（F03 换成动态菜单）</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-layout__header">
        <!-- F03 在这里挂 <Navbar /> -->
        <span>顶栏占位</span>
      </el-header>

      <el-main>
        <!-- 业务页面出口：F03 起由路由 children 填充 -->
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
// F01 只做布局占位，不引入任何状态与请求
</script>

<style scoped>
.app-layout {
  height: 100vh;
}
.app-layout__aside {
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}
.app-layout__logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #e4e7ed;
}
.app-layout__header {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
</style>
