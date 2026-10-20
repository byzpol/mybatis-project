import { defineStore } from 'pinia'
import * as authApi from '@/api/modules/auth'

/**
 * 用户状态（pinia）
 *
 * F01 建立骨架（token / userInfo / permissions），F02 补 hasPermission / hasRole，
 * F03 补登录登出三个 action 与 superAdmin、avatarText，并接上真实后端。
 *
 * ⚠️ 依赖方向：本文件 import `@/api/modules/auth` → 那个模块 import `@/api/request`。
 *    所以 **`request.js` 里不能静态 import 本文件**，否则形成
 *    request → store → auth → request 的循环（ESM 虽能靠函数提升兜住，
 *    但这种"能跑"的循环一旦有人在模块顶层取用就立刻炸）。
 *    F03 已把 request.js 里的 store / router 依赖改成处理 401 时才动态 import。
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

    /**
     * 后端 /auth/me 与 /auth/login 返回的 LoginVO：
     * { userId, username, nickname, avatar, roles, permissions, superAdmin, loginTime }
     */
    userInfo: null,

    /** 权限标识数组，如 ['article:audit', 'article:publish'] */
    permissions: [],

    /**
     * 是否已经尝试过"用当前 Session 恢复登录态"。
     *
     * 为什么需要它：路由守卫里会调 fetchUserInfo()，
     * 而守卫每次导航都会跑。不加这个标记的话，用户每点一次菜单就多打一次 /auth/me。
     * 只尝试一次，之后要么有 userInfo（已登录），要么就是没登录（直接跳登录页）。
     */
    bootstrapped: false
  }),

  getters: {
    isLogin: (state) => !!state.userInfo,

    /** 当前用户 ID。注意后端字段是 userId，不是 id —— 别写成 userInfo.id */
    userId: (state) => state.userInfo?.userId ?? null,

    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',

    avatar: (state) => state.userInfo?.avatar || '',

    /** 角色码数组，如 ['ROLE_ADMIN']；便于页面按角色显示不同入口 */
    roles: (state) => state.userInfo?.roles || [],

    /**
     * 是否超级管理员。
     * 后端 LoginUser.hasPermission() 对超管恒返回 true（唯一的"特权短路"点），
     * 前端必须跟着认这个字段，否则会出现"后端放行、前端把菜单藏了"的不一致。
     */
    isSuperAdmin: (state) => state.userInfo?.superAdmin === true,

    /**
     * 头像占位文字：昵称首字。
     * 项目里没有头像上传功能（不在成员A 的范围），所以 el-avatar 用文字兜底，
     * 比放一张写死的默认头像图片更合适 —— 也顺带满足"不引外链资源"的要求。
     */
    avatarText: (state) => {
      const name = state.userInfo?.nickname || state.userInfo?.username || ''
      return name.slice(0, 1).toUpperCase()
    }
  },

  actions: {
    /**
     * 写入用户信息。登录成功、刷新页面恢复、/auth/me 回来时都调它。
     * @param {{userId:number, username:string, nickname?:string, avatar?:string,
     *          roles?:string[], permissions?:string[], superAdmin?:boolean}} info
     */
    setUserInfo(info) {
      this.userInfo = info || null
      this.permissions = info?.permissions || []
      // 兼容：万一某处传的是 { id } 而不是 { userId }，补一份映射，避免 userId 恒为 null
      if (info && info.userId == null && info.id != null) {
        this.userInfo = { ...info, userId: info.id }
      }
    },

    /** 登出或 401 时调用，清空所有登录态 */
    clearUserInfo() {
      this.token = ''
      this.userInfo = null
      this.permissions = []
    },

    /**
     * 登录。
     * @param {{username:string, password:string}} payload
     * @returns {Promise<object>} LoginVO（调用方一般不需要，直接看 store 即可）
     * 失败时**不在这里弹提示** —— axios 响应拦截器已经统一弹过一次了（F02 设计），
     * 这里再弹一次就是"同一个错误两条红条"。
     */
    async login(payload) {
      const vo = await authApi.login(payload)
      this.setUserInfo(vo)
      this.bootstrapped = true
      return vo
    },

    /**
     * 登出。
     * ⚠️ 用 try/finally：即使服务端登出失败（Session 已过期、网络断），
     *    前端也必须清干净本地态 —— 否则用户看到"还在登录着"却什么都做不了。
     */
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.clearUserInfo()
      }
    },

    /**
     * 用当前 Session 恢复登录态（刷新页面后调）。
     * 未登录时会 reject（后端返回 401），调用方（路由守卫）自己处理。
     */
    async fetchUserInfo() {
      const vo = await authApi.getCurrentUser()
      this.setUserInfo(vo)
      return vo
    },

    /**
     * 判断当前用户是否拥有某个权限码。
     *
     * 支持的写法（与后端 RBAC 的权限码格式 "资源:动作" 对齐）：
     *   hasPermission('article:audit')  → 精确命中
     *   hasPermission('article:audit')  → 当持有 'article:*' 时也命中（同资源全动作）
     *   hasPermission('article:audit')  → 当持有 '*' 或 superAdmin 时命中
     *
     * 为什么必须支持通配：ROLE_CHIEF 之类角色如果逐条列权限，后端加一个新动作
     * 就要回来改角色配置；写成 'article:*' 后，新增 'article:xxx' 自动覆盖。
     * 这与后端 LoginUser.hasPermission() 的规则必须保持一致，否则前后端判断结果会打架
     * （表现为"菜单能看见但点进去 403"）。
     *
     * 为什么空串返回 true：调用点常写成 `:disabled="!hasPermission(perm)"`，
     * 而 perm 可能来自后端菜单配置、本身就是空的（表示"该页只需登录"）。
     * 返回 false 会把所有无权限声明的页面全锁死。
     *
     * ⚠️ 这是「体验层」判断（决定菜单/按钮显不显示），不是安全边界。
     *    真正的拦截在后端 @RequirePermission，前端判断被绕过也拿不到数据。
     *
     * @param {string} [perm] 权限码，如 'article:audit'；为空表示不限制
     * @returns {boolean}
     */
    hasPermission(perm) {
      if (!perm) return true
      // 超管短路：与后端 LoginUser.hasPermission() 的第一行保持一致
      if (this.isSuperAdmin) return true

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
