import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

/**
 * 路由表（主干 · F03 完善）
 *
 * 设计要点：
 * 1. 用 createWebHistory 而不是 createWebHashHistory —— URL 干净（/articles/list 而不是 /#/...），
 *    代价是刷新深层路径需要后端/服务器把未知路径回退到 index.html。
 *    本项目部署在 Tomcat 的 /cms-mis 下，刷新 /cms-mis/articles/list 会 404，
 *    解决办法见 docs/F01-前端工程初始化实测记录.md「部署衔接」一节。
 * 2. base 取 import.meta.env.BASE_URL —— 它由 vite.config.js 的 base 决定：
 *    开发时是 '/'，构建时是 '/cms-mis/'，两边自动一致，不用手改。
 * 3. 所有业务页面走懒加载（() => import(...)），首屏只加载布局与当前页。
 * 4. meta 字段约定（侧边栏菜单与面包屑都从这些字段生成，不要另建一份菜单配置）：
 *    · meta.title      页面标题（浏览器标签 + 面包屑 + 侧边栏菜单项）
 *    · meta.group      所属菜单分组名，如 '文章管理'；空/不写表示不分组（顶级菜单项）
 *    · meta.icon       Element Plus 图标组件名（字符串），Sidebar 里查表取组件
 *    · meta.permission 访问该页所需的权限码（如 'article:view'），空表示只需登录
 *    · meta.hidden     true 表示"有路由但不进菜单"（如个人中心）
 *    · meta.public     免登录访问（目前只有登录页）
 *
 * ⚠️ 权限码口径说明（F03 实测发现的不一致，务必先读）
 *    下面的 permission 用的是**数据库 sys_permission 里真实存在的码**
 *    （article:view / article:update / article:manage / category:manage / user:manage /
 *      role:manage / log:view / report:view），因为菜单过滤的依据是"当前用户被授予了什么"，
 *    而授予关系就来自那 20 条 sys_permission 种子数据。
 *    而《接口契约.md》§1.3 写的是另一套（article:list / article:edit / article:offline /
 *      article:archive）—— 那套码在库里**一条都没有**。
 *    两套不一致的直接后果：若按契约写，菜单对所有角色都不显示，S09 的
 *    @RequirePermission("article:list") 也会把所有人挡在 403。
 *    详见 docs/F03-后台布局与主题实测记录.md「权限码口径不一致」一节。
 *    最终以哪套为准属于需要定的事，**在定下来之前，前端一律跟随库表**。
 */

// 尚未实现的模块统一走这个占位页（F05/F06/S09~S16 会逐个换成真实页面）
const Placeholder = () => import('@/views/placeholder/index.vue')

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
    // 默认落到文章列表（4 个角色的种子数据都含 article:view，不会出现"进去就 403"）
    redirect: '/articles/list',
    children: [
      // ---------------- 文章管理（成员A） ----------------
      {
        path: 'articles/list',
        name: 'ArticleList',
        component: Placeholder,
        meta: { title: '文章列表', group: '文章管理', icon: 'Document', permission: 'article:view' }
      },
      {
        path: 'articles/mine',
        name: 'ArticleMine',
        component: Placeholder,
        meta: { title: '我的投稿', group: '文章管理', icon: 'EditPen', permission: 'article:view' }
      },
      {
        path: 'articles/audit',
        name: 'ArticleAudit',
        component: Placeholder,
        meta: { title: '待我审核', group: '文章管理', icon: 'Stamp', permission: 'article:audit' }
      },
      // ---------------- 内容分类与标签（成员B） ----------------
      {
        path: 'categories',
        name: 'CategoryManage',
        component: Placeholder,
        meta: { title: '分类管理', group: '内容管理', icon: 'Files', permission: 'category:manage' }
      },
      {
        path: 'tags',
        name: 'TagManage',
        component: Placeholder,
        meta: { title: '标签管理', group: '内容管理', icon: 'PriceTag', permission: 'category:manage' }
      },
      // ---------------- 用户角色权限（成员C） ----------------
      {
        path: 'users',
        name: 'UserManage',
        component: Placeholder,
        meta: { title: '用户管理', group: '用户与权限', icon: 'User', permission: 'user:manage' }
      },
      {
        path: 'roles',
        name: 'RoleManage',
        component: Placeholder,
        meta: { title: '角色权限', group: '用户与权限', icon: 'Key', permission: 'role:manage' }
      },
      // ---------------- 日志与统计（成员D） ----------------
      {
        path: 'logs/oper',
        name: 'OperLog',
        component: Placeholder,
        meta: { title: '操作日志', group: '日志与统计', icon: 'Tickets', permission: 'log:view' }
      },
      {
        path: 'reports',
        name: 'ReportView',
        component: Placeholder,
        meta: { title: '数据统计', group: '日志与统计', icon: 'DataLine', permission: 'report:view' }
      },
      // ---------------- 不进菜单的页面 ----------------
      {
        path: 'profile',
        name: 'Profile',
        component: Placeholder,
        // hidden：Navbar 下拉菜单要能跳到这里，但它不该出现在左侧菜单里
        meta: { title: '个人信息', hidden: true }
      }
    ]
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
 * 四件事按顺序做：
 * ① 写浏览器标签标题（用 meta.title，没写则只用系统名）
 * ② 首次进入时尝试用当前 Session 恢复登录态（否则刷新页面就被踢回登录页）
 * ③ 未登录拦截：非 public 路由且没有用户信息 → 踢回 /login，并带上 redirect
 * ④ 权限拦截：meta.permission 存在且 hasPermission 不通过 → 跳 /403
 *
 * ⚠️ 这是「体验层」的拦截，不是安全边界。真正的鉴权在后端
 *    LoginInterceptor + PermissionAspect（主干已冻结），前端守卫被绕过也拿不到数据。
 */
router.beforeEach(async (to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME

  const userStore = useUserStore()

  /**
   * 恢复登录态（只尝试一次）。
   *
   * 为什么必须做：本项目用 Session 认证，用户按 F5 刷新页面时 pinia 会重置，
   * 但 Cookie 里的 JSESSIONID 还在、服务端会话还有效。
   * 如果只看 pinia 就判定"未登录"，刷新一次就被踢到登录页 —— 体验很差。
   *
   * /auth/me 默认是 silent 的（见 api/modules/auth.js），
   * 未登录时返回 401 但不会弹提示、不会抢着跳转，由这里接住 reject 走正常流程。
   */
  if (!userStore.bootstrapped) {
    userStore.bootstrapped = true
    if (!userStore.userInfo) {
      try {
        await userStore.fetchUserInfo()
      } catch (e) {
        // 未登录 / 会话过期：保持未登录态，交给下面的逻辑跳登录页
      }
    }
  }

  // 免登录页面（登录页）
  if (to.meta.public) {
    // 已登录还往登录页跑，直接送回首页，避免出现"登着还让你再登一次"的困惑
    if (userStore.userInfo && to.path === '/login') {
      return next({ path: '/' })
    }
    return next()
  }

  // 没登录：跳登录页，并把原目标路径记在 query 上，登录成功后可以跳回去
  if (!userStore.userInfo) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  // 权限拦截：路由 meta 声明了 permission 才校验，
  // 未声明的路由只要登录就能进 —— 避免每个页面都必须配权限码，
  // 让"只需登录"成为默认、"需要权限"成为显式声明。
  if (to.meta.permission && !userStore.hasPermission(to.meta.permission)) {
    return next({ path: '/403' })
  }

  next()
})

export default router
