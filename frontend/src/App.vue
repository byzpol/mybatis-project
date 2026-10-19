<!--
  根组件：只负责给路由一个渲染出口。
  真正的骨架（侧边栏 / 顶栏 / 面包屑）在 src/layout/AppLayout.vue，由路由 '/' 承载；
  登录页不走布局，所以两层结构要分开。
-->
<template>
  <router-view />
</template>

<script setup>
// 根组件不需要任何逻辑，保持空脚本块仅为声明「这是 <script setup> 写法」的示范
</script>
