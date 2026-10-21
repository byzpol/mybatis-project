<!--
  「我的投稿」（F06）
  ===========================================================================
  页面本体就是 ArticleList，只是固定只看当前登录用户的文章。

  ⚠️ 为什么单独建一个文件、而不是让 `articles/list` 和 `articles/mine`
     两个路由指向同一个组件？
  Vue Router 在**同一个组件用于两个路由**时会复用组件实例 ——
  从「文章列表」切到「我的投稿」，`onMounted` 不会重新执行，
  表格里还是原来那批数据，看起来就像"点了没反应"。
  可以靠 watch(route) 或给 router-view 加 :key 绕开，
  但那两招都会影响其它页面（:key 会让所有页面切路由都重建）。
  这里用一层 5 行的薄封装解决：成本最低，也不打扰任何现有代码。
-->
<template>
  <ArticleList preset-author />
</template>

<script setup>
import ArticleList from './ArticleList.vue'

defineOptions({ name: 'ArticleMine' })
</script>
