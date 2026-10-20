import { get, post } from '@/api/request'

/**
 * 认证接口（成员A 公共基础设施）
 *
 * 后端实现：`com.lab.cms.controller.AuthController`（S05 已完成，无需登录即可访问
 * —— SpringMvcConfig 里把 `/api/v1/auth/**` 整段放行了）。
 *
 * ⚠️ 三条路径的语义差异，别混用：
 *   · login    拿用户名密码换一个 Session（服务端写入 JSESSIONID）
 *   · logout   让服务端 invalidate Session，**同时**必须清前端态
 *   · me       用当前 Session 换回用户信息（刷新页面后恢复 pinia 用）
 */

/**
 * 登录
 * POST /api/v1/auth/login
 *
 * @param {{username:string, password:string}} data
 *        后端 LoginDTO 的约束：username 非空且 ≤50；password 非空且 6~64。
 *        前端表单校验必须与这里一致，否则会出现"前端放行、后端 400"的割裂体验。
 * @returns {Promise<LoginVO>} 裸数据（拦截器已解包），字段为：
 *          userId / username / nickname / avatar / roles / permissions / superAdmin / loginTime
 *          ⚠️ 是 userId 不是 id；roles/permissions 是数组（后端用 Set，JSON 序列化成数组）
 *
 * 注意：本接口成功时后端返回的 message 是「登录成功」而不是默认的「操作成功」，
 *      但我们只取 data，所以不影响。
 */
export function login(data) {
  return post('/auth/login', data)
}

/**
 * 登出
 * POST /api/v1/auth/logout
 *
 * ⚠️ 传 `silent: true`：
 * 登出时 Session 很可能已经过期（用户挂机 30 分钟后再点退出），
 * 后端会回 401。如果不静默，拦截器会弹"登录已过期，请重新登录"并抢着跳转 ——
 * 而用户只是点了「退出登录」，看到这条提示会觉得莫名其妙。
 * 静默后由调用方（Navbar）自己决定清态与跳转。
 */
export function logout() {
  return post('/auth/logout', null, { silent: true })
}

/**
 * 查询当前登录用户
 * GET /api/v1/auth/me
 *
 * 未登录时后端抛 BusinessException(UNAUTHORIZED) → HTTP 401 的**统一 JSON**
 * （不是被拦截器拦的，SpringMvcConfig 特意放行了这个路径，
 *  就是为了让前端能拿到结构化响应去判断"是没登录还是别的错"）。
 *
 * ⚠️ 默认 `silent: true`：这个接口的语义是"探活当前会话"，
 * 未登录返回 401 是**预期结果**，不是异常。
 * 不静默的话，用户第一次打开站点就会被弹一条"登录已过期"。
 * 路由守卫会接住 reject，自己决定跳不跳登录页。
 *
 * @param {object} [config] 需要覆盖默认行为时传入（如 `{ silent: false }`）
 * @returns {Promise<LoginVO>} 与 login 相同的结构
 */
export function getCurrentUser(config = { silent: true }) {
  return get('/auth/me', null, config)
}
