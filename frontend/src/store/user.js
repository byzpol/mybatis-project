import { defineStore } from 'pinia'

/**
 * 用户状态（pinia）—— F01 占位版
 *
 * ⚠️ 本文件在 F01 只提供「够路由守卫用」的最小集合，
 *    permissions 通配判断（hasPermission）、登录/登出的请求调用在 **F02** 补齐。
 *    这样分步是为了让 F01 的核查项（访问 / 被踢到 /login）能独立成立。
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
    }

    // TODO(F02)：hasPermission(perm) —— 需支持通配：
    //   拥有 'article:*' 时 'article:audit' 也算通过；拥有 '*' 时全部通过。
  }
})
