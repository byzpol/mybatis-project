import { defineStore } from 'pinia'

/**
 * 用户状态（pinia）
 *
 * F01 建立骨架（token / userInfo / permissions + setUserInfo / clearUserInfo），
 * F02 补齐 hasPermission（含 'article:*' 通配）与 hasRole。
 *
 * 还没有的东西（后续步骤落）：
 *   · login() / logout() / fetchUserInfo() 这三个要发请求的 action —— 归认证模块（S12 附近），
 *     它们会调 api/modules/auth.js，届时本文件 import 那个模块即可，不会与 request.js 循环依赖
 *     （request.js → router → store/user，store/user 目前不 import request.js）。
 *
 * 关于 token：本项目鉴权走 Session（JSESSIONID Cookie，后端 30 分钟超时），
 * 前端不需要、也拿不到 token。这里留一个 token 字段只是为了：
 * ① 路由守卫有个"看起来像登录态"的标记位，语义更直观；
 * ② 将来若换成 JWT，改动集中在本文件。
 * 它不参与任何请求头的拼装 —— 千万别在 axios 拦截器里读它做 Authorization。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: '',
    /** 后端 /auth/me 返回的用户信息：{ id, username, nickname, roles, permissions } */
    userInfo: null,
    /** 权限标识数组，如 ['article:audit', 'article:publish'] */
    permissions: []
  }),

  getters: {
    isLogin: (state) => !!state.userInfo,
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',
    /** 角色码数组，如 ['ROLE_ADMIN']；便于页面按角色显示不同入口 */
    roles: (state) => state.userInfo?.roles || []
  },

  actions: {
    /**
     * 登录成功 / 刷新页面后拉取用户信息时调用。
     * @param {{ id:number, username:string, nickname?:string, roles?:string[], permissions?:string[] }} info
     */
    setUserInfo(info) {
      this.userInfo = info || null
      this.permissions = info?.permissions || []
    },

    /** 登出或 401 时调用，清空所有登录态 */
    clearUserInfo() {
      this.token = ''
      this.userInfo = null
      this.permissions = []
    },

    /**
     * 判断当前用户是否拥有某个权限码（F02 补齐）。
     *
     * 支持的写法（与后端 RBAC 的权限码格式 "资源:动作" 对齐）：
     *   hasPermission('article:audit')  → 精确命中
     *   hasPermission('article:audit')  → 当持有 'article:*' 时也命中（同资源全动作）
     *   hasPermission('article:audit')  → 当持有 '*' 时命中（超级权限）
     *
     * 为什么必须支持通配：ROLE_CHIEF 之类角色如果逐条列权限，后端加一个新动作
     * 就要回来改角色配置；写成 'article:*' 后，新增 'article:xxx' 自动覆盖。
     * 这与后端 PermissionAspect 的通配规则必须保持一致，否则前后端判断结果会打架
     * （表现为"菜单能看见但点进去 403"）。
     *
     * 为什么空串返回 true：调用点常写成 `:disabled="!hasPermission(perm)"`，
     * 而 perm 可能来自后端菜单配置、本身就是空的。空权限码视为"该页只需登录"，
     * 返回 false 会把所有无权限声明的页面全锁死。
     *
     * ⚠️ 这是「体验层」判断（决定按钮显不显示），不是安全边界。
     *    真正的拦截在后端 @RequirePermission，前端判断被绕过也拿不到数据。
     *
     * @param {string} [perm] 权限码，如 'article:audit'；为空表示不限制
     * @returns {boolean}
     */
    hasPermission(perm) {
      if (!perm) return true

      // 防御：permissions 可能因为 setUserInfo 传了 null 而意外非数组
      const perms = Array.isArray(this.permissions) ? this.permissions : []

      // 超级权限：拥有 '*' 就全部通过
      if (perms.includes('*')) return true

      // 精确命中
      if (perms.includes(perm)) return true

      // 资源级通配：持有 'article:*' 时，'article:audit' / 'article:publish' 都通过
      const sepIndex = perm.indexOf(':')
      if (sepIndex > 0) {
        const resource = perm.slice(0, sepIndex)
        if (perms.includes(`${resource}:*`)) return true
      }

      return false
    },

    /**
     * 是否拥有给定角色码之一（如 'ROLE_CHIEF'）。
     * 与 hasPermission 分工：权限码管"能不能做某个动作"，角色码管"显示哪套入口/看板"。
     * 两者不要互相替代 —— 角色是权限的集合，直接用角色判断功能会让页面逻辑与后端配置耦合。
     * @param {string|string[]} role
     * @returns {boolean}
     */
    hasRole(role) {
      const roles = this.roles
      if (!role) return false
      const wanted = Array.isArray(role) ? role : [role]
      return wanted.some((r) => roles.includes(r))
    }
  }
})
