<!--
  根组件（F03 调整）

  F01 时它只放一个 <router-view />。F03 加了一层 ElConfigProvider：

  为什么需要它：F03 把 Element Plus 从「全量引入」改成了「按需引入」，
  原来在 main.js 里用 `app.use(ElementPlus, { locale: zhCn })` 注册中文语言包的写法
  用不了了（按需模式下没有 app.use(ElementPlus) 这一步），
  改用 ElConfigProvider 组件在同一处收口语言配置 —— 这是官方推荐的按需引入配套写法。

  不配它的后果：分页器显示 "Go to"、表格空数据显示 "No Data"、日期选择器是英文，
  中文界面里混着英文，一眼就能看出没做完。

  真正的骨架（侧边栏 / 顶栏 / 面包屑）在 src/layout/AppLayout.vue，由路由 '/' 承载；
  登录页不走布局，所以两层结构要分开。
-->
<template>
  <el-config-provider :locale="zhCn" :z-index="2000">
    <router-view />
  </el-config-provider>
</template>

<script setup>
// Element Plus 官方中文语言包。实查 node_modules 确认该路径存在（element-plus/es/locale/lang/zh-cn.mjs）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
