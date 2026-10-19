import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

/**
 * 路由表（主干 · F01 占位版）
 *
 * 设计要点：
 * 1. 用 createWebHistory 而不是 createWebHashHistory —— URL 干净（/articles 而不是 /#/articles），
 *    代价是刷新深层路径需要后端/服务器把未知路径回退到 index.html。
 *    本项目部署在 Tomcat 的 /cms-mis 下，刷新 /cms-mis/articles 会 404，
 *    解决办法见 docs/F01-前端工程初始化实测记录.md「部署衔接」一节（配 Welcome 页或前端 404 兜底）。
 * 2. base 取 import.meta.env.BASE_URL —— 它由 vite.config.js 的 base 决定：
 *    开发时是 '/'，构建时是 '/cms-mis/'，两边自动一致，不用手改。
 * 3. 所有业务页面走懒加载（() => import(...)），首屏只加载布局与当前页。
 * 4. meta 里固定两个字段，为后续动态菜单/按钮级权限留结构：
 *    · meta.title      页面标题（浏览器标签 + 面包屑 + 侧边栏菜单项）
 *    · meta.permission 访问该页所需的权限标识（如 'article:audit'），空表示只需登录
 *    · meta.public     免登录访问（目前只有登录页）
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    name: 'Root',
    component: () => import('@/layout/AppLayout.vue'),
    // F03 之后由各业务负责人往这里加自己的 children（如 /articles、/categories）
    children: []
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无访问权限' }
  },
  {
    // 兜底 404：必须放最后，pathMatch 会吃掉所有未匹配路径
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  // 切换路由时回到顶部：列表页很长时不会停在上一页的滚动位置
  scrollBehavior: () => ({ top: 0 })
})

const APP_NAME = 'CMS 内容管理系统'

/**
 * 全局前置守卫。
 *
 * 三件事按顺序做：
 * ① 写浏览器标签标题（用 meta.title，没写则只用系统名）
 * ② 未登录拦截：非 public 路由且 pinia 里没有用户信息 → 踢回 /login，并带上 redirect 参数
 * ③ 权限拦截（F02 已打开）：meta.permission 存在且 hasPermission 不通过 → 跳 /403
 *
 * ⚠️ 这是「体验层」的拦截，不是安全边界。真正的鉴权在后端
 *    LoginInterceptor + PermissionAspect（主干已冻结），前端守卫被绕过也拿不到数据。
 */
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME

  // 免登录页面（登录页）直接放行
  if (to.meta.public) {
    return next()
  }

  const userStore = useUserStore()

  // 没登录：跳登录页，并把原目标路径记在 query 上，登录成功后可以跳回去
  if (!userStore.userInfo) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  // 权限拦截（F02 打开）：路由 meta 声明了 permission 才校验，
  // 未声明的路由只要登录就能进 —— 避免每个页面都必须配权限码，
  // 让"只需登录"成为默认、"需要权限"成为显式声明。
  if (to.meta.permission && !userStore.hasPermission(to.meta.permission)) {
    return next({ path: '/403' })
  }

  next()
})

export default router
