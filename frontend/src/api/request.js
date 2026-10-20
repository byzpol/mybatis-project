import axios from 'axios'
import { ElLoading, ElMessage } from 'element-plus'
// ⚠️ 显式引入这两个组件的**样式**。
// 原因：F03 把 Element Plus 改成了按需引入（unplugin-vue-components 只处理模板里的 <el-xxx>），
// 而 ElMessage / ElLoading 是在 .js 里以「函数」方式调用的，解析器管不到，
// 结果就是"消息弹出来了但没有样式"（白底黑字贴在左上角）。
// 谁在这里新增一个函数式调用的 EP 组件（如 ElMessageBox），就要顺手补一行 style。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/loading/style/css'

/**
 * ⚠️ 本文件刻意**不**在顶层 import `@/store/user` 与 `@/router`。
 *
 * 依赖方向必须是单向的：store → api/modules → request。
 * 如果这里反向静态 import store，就形成
 *     request → store/user → api/modules/auth → request
 * 的循环。ESM 靠"函数声明提升"能让这种循环勉强跑起来，但只要有人在模块顶层
 * 取用被循环的那个绑定，就会命中 TDZ 报 "Cannot access before initialization"，
 * 而且报错位置在启动阶段，极难定位。
 *
 * 所以这两个依赖改成"真要清登录态时才动态 import"（运行期这两句是命中模块缓存，
 * 没有额外开销）。这也是分层原则的落地：最底层不该静态依赖上层。
 */
async function resolveAppLayer() {
  const [{ useUserStore }, { default: router }] = await Promise.all([
    import('@/store/user'),
    import('@/router')
  ])
  return { useUserStore, router }
}

/**
 * axios 统一封装（F02）
 *
 * 为什么必须封一层（而不是各页面自己 axios.get）：
 * 1. 后端返回的是统一结构 {code, message, data, timestamp}（见 docs/接口契约.md §2.1），
 *    不封装的话每个调用点都要写 res.data.data，且要自己判断 code。
 * 2. 401 要清登录态 + 跳登录页，这是横切逻辑，散落各处必然漏。
 * 3. 错误提示要统一（且同一次错误只弹一次），散落各处会出现"一屏三个红条"。
 *
 * ⚠️ 本文件不允许出现任何硬编码 host / 端口。baseURL 取
 *    `import.meta.env.BASE_URL + 'api/v1'`（开发 '/api/v1'，部署 '/cms-mis/api/v1'）：
 *    · 开发时靠 vite.config.js 的 server.proxy 把 /api 转发到 localhost:8080
 *    · 生产时前端和接口同在 Tomcat 的 /cms-mis 下，天然同源
 *    一旦这里写死成绝对地址（无论 '/api/v1' 还是 'http://localhost:8080/api/v1'），
 *    部署后都会错 —— 前者少上下文路径、后者跨源，详见下面 baseURL 处的实测记录。
 */

/** 前端应用名，与 index.html / router 保持一致 */
const APP_NAME = 'CMS 内容管理系统'

/**
 * 全屏 Loading 的并发计数。
 *
 * 为什么用计数而不是布尔：一个页面打开时会并发发好几个请求（详情 + 审核流水 + 版本列表），
 * 用布尔的话第一个请求结束就把 Loading 关了，后两个还在飞 —— 用户看到闪烁。
 * 计数归零才关，才符合直觉。
 */
let loadingCount = 0
let loadingInstance = null

function openLoading() {
  loadingCount += 1
  if (loadingCount === 1 && !loadingInstance) {
    loadingInstance = ElLoading.service({
      lock: true,
      text: '加载中...',
      background: 'rgba(0, 0, 0, 0.35)'
    })
  }
}

function closeLoading() {
  // 防御：只在自己计数为正时递减，避免异常路径把计数减成负数、Loading 永久卡住
  if (loadingCount > 0) {
    loadingCount -= 1
  }
  if (loadingCount === 0 && loadingInstance) {
    loadingInstance.close()
    loadingInstance = null
  }
}

/**
 * 会话过期的统一处理（401）。
 *
 * 后端两条路径都会走到这里：
 * ① HTTP 200 + 业务 code=401（见接口契约 §3.3，401 属于 100~599，会同步到 HTTP 状态码，
 *    所以正常应该走 ②；但为稳妥两条都兜）
 * ② HTTP 401（LoginInterceptor 拦下未登录请求时）
 *
 * 为什么需要 redirecting 标记：刷新页面时可能并发 5 个请求同时 401，
 * 不加标记就会弹 5 条「登录已过期」，并且 push 5 次路由。
 *
 * @param {boolean} silent 请求方声明"401 是预期结果"（如 /auth/me 探活）。
 *        此时只清登录态，不弹提示也不跳转 —— 由调用方（路由守卫）自己决定去向。
 *        没有这个开关的话，用户第一次打开站点就会被弹一条"登录已过期"。
 */
let redirecting = false

async function handleUnauthorized(silent = false) {
  const { useUserStore, router } = await resolveAppLayer()

  // 清登录态：不管跳不跳路由，pinia 必须先干净，否则路由守卫以为还登着
  try {
    useUserStore().clearUserInfo()
  } catch (e) {
    // pinia 尚未初始化（极早期请求）时忽略，不影响主流程
  }

  if (silent) return
  if (redirecting) return
  redirecting = true
  ElMessage.error('登录已过期，请重新登录')

  const current = router.currentRoute.value
  if (current.path === '/login') {
    redirecting = false
    return
  }
  try {
    await router.replace({
      path: '/login',
      // 记下当前路径，登录成功后可跳回（登录页读 query.redirect）
      query: { redirect: current.fullPath }
    })
  } finally {
    // 等路由跳完再解锁，避免跳转期间又来一个 401 重复弹
    redirecting = false
  }
}

/** 业务码 401（登录态失效）的常量，避免魔法数字散落 */
const CODE_SUCCESS = 200
const CODE_UNAUTHORIZED = 401

const request = axios.create({
  /**
   * ⚠️ baseURL 必须是「应用基路径 + /api/v1」，不能写死成 '/api/v1'（F03 实测踩坑）
   *
   * 写死 '/api/v1' 时，浏览器把它当**根路径绝对地址**解析，
   * 于是请求打到 http://host:8080/api/v1/xxx；
   * 而 war 部署在 Tomcat 的 /cms-mis 上下文下，正确的地址是
   *     http://host:8080/cms-mis/api/v1/xxx
   * 结果就是部署后**所有接口 404**，且响应是 Tomcat 自己的 HTML 404
   * （不是后端统一 JSON），很容易被误判成"后端没起 / war 没部署"。
   *
   * 为什么开发期发现不了：dev 时 vite 的 base 是 '/'，import.meta.env.BASE_URL === '/'，
   * 拼出来仍是 '/api/v1'，一切正常。**只有部署到 war 才暴露** ——
   * 属于典型的"环境差异导致的静默失效"，必须靠真机部署验证才能抓到。
   *
   * import.meta.env.BASE_URL 由 vite.config.js 的 base 决定：
   *   开发 → '/'         → baseURL '/api/v1'
   *   构建 → '/cms-mis/' → baseURL '/cms-mis/api/v1'
   * 与 router 的 createWebHistory(import.meta.env.BASE_URL) 同源同源，改一处两边一起跟着变。
   */
  baseURL: `${import.meta.env.BASE_URL}api/v1`,
  timeout: 15000,
  /**
   * withCredentials 为什么必须为 true：
   *
   * 本项目用 Session 鉴权（后端 LoginInterceptor 从 HttpSession 取登录态），
   * 浏览器把 JSESSIONID 放在 Cookie 里。而 axios 默认 withCredentials=false ——
   * 意味着跨源请求不带 Cookie，后端每次都认为"未登录"，表现为"登录成功但下一个请求就 401"。
   *
   * 什么时候算跨源：开发时前端在 Vite 的 5173，接口在 Tomcat 的 8080，
   * 虽然配了 vite proxy 让浏览器看是同源，但一旦有人绕过 proxy 直连 8080 就立刻暴露。
   * 生产环境部署在同一个 Tomcat 的 /cms-mis 下，是同源，带上也无害。
   *
   * 配套的后端约束（见接口契约 §3.4）：CORS 的 allowCredentials(true) 与
   * allowedOrigins("*") 不能同时用，必须写具体源。
   */
  withCredentials: true
})

// ---------------------------------------------------------------------------
// 请求拦截器
// ---------------------------------------------------------------------------
request.interceptors.request.use(
  (config) => {
    /**
     * Content-Type 只对"带 body 的请求"设置。
     *
     * 不能无脑给所有请求加 application/json：GET 请求本来没有 body，
     * 加了 Content-Type: application/json 之后，某些容器/过滤器会认为
     * "声明了 JSON body 却没有 body"，直接回 415。
     * 后端对 @RequestBody 的接口才需要这个头，axios 传了对象时自己也会带，
     * 这里显式设置是为了覆盖 FormData 等场景下被误设的旧值。
     */
    const method = (config.method || 'get').toLowerCase()
    if (['post', 'put', 'patch', 'delete'].includes(method) && config.data !== undefined) {
      config.headers['Content-Type'] = 'application/json'
    }

    /**
     * 全屏 Loading：可通过 config.showLoading === false 关闭。
     * 典型场景：页面初始化时后台静默拉取字典/字典项，不该盖住整个页面。
     */
    if (config.showLoading !== false) {
      openLoading()
    }

    return config
  },
  (error) => {
    // 请求在发出前就失败（如参数序列化异常），也要把 Loading 计数还回去
    closeLoading()
    return Promise.reject(error)
  }
)

// ---------------------------------------------------------------------------
// 响应拦截器（关键）
//
// 两条分支互斥，保证同一次失败只弹一次提示：
//   A. HTTP 2xx → 看业务 code
//   B. HTTP 非 2xx → 从 error.response.data.message 取消息
// 之所以强调"只弹一次"：如果 A 分支里既 ElMessage.error 又在 catch 里再弹，
// 或者把 B 分支的消息也交给 A 处理，就会出现两个红条。
// ---------------------------------------------------------------------------
request.interceptors.response.use(
  (response) => {
    // 请求已返回，无论走哪个分支都要收 Loading
    closeLoading()

    const res = response.data
    const silent = response.config?.silent === true

    /**
     * 防御分支：不是统一结构就原样返回。
     * 为将来可能的文件下载（blob）/ 纯文本响应留口子 —— 那些走 result 结构没意义。
     * 判据用 typeof code === 'undefined'，而不是 res.code !== 200，
     * 否则 blob（Blob 对象）会被误判成"业务失败"。
     */
    if (res === null || typeof res !== 'object' || typeof res.code === 'undefined') {
      return res
    }

    // 成功：直接把裸 data 交给业务代码，调用点写 `const list = await pageArticles()` 就行
    if (res.code === CODE_SUCCESS) {
      return res.data
    }

    // 登录态失效
    if (res.code === CODE_UNAUTHORIZED) {
      handleUnauthorized(silent)
      return Promise.reject(new Error(res.message || '登录已过期，请重新登录'))
    }

    // 其他业务错误：统一提示 + reject，业务代码只处理成功路径
    if (!silent) {
      ElMessage.error(res.message || '操作失败')
    }
    const bizError = new Error(res.message || '操作失败')
    bizError.code = res.code
    bizError.data = res.data
    return Promise.reject(bizError)
  },
  (error) => {
    closeLoading()

    const response = error.response
    const silent = error.config?.silent === true

    // HTTP 401：与业务码 401 走同一套处理，避免清不清登录态两套逻辑
    if (response && response.status === CODE_UNAUTHORIZED) {
      handleUnauthorized(silent)
      return Promise.reject(error)
    }

    /**
     * 兜底文案：后端全局异常处理器（S06）返回的也是统一结构，
     * 所以 message 一般在 error.response.data.message 里。
     * 拿不到（网络断了、超时、被网关拦了返回 HTML）才用兜底文案。
     */
    let msg = response?.data?.message
    if (!msg) {
      if (error.code === 'ECONNABORTED') {
        msg = '请求超时，请稍后重试'
      } else if (error.message === 'Network Error') {
        msg = '网络异常，请检查网络连接'
      } else {
        msg = '网络异常，请稍后重试'
      }
    }

    if (!silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

// ---------------------------------------------------------------------------
// 快捷方法
//
// 为什么再包一层 get/post/...：
// ① 让业务模块的写法统一（api/modules/xxx.js 里只 import { get, post } 即可）；
// ② delete 的 axios 签名特殊（第二参数是 config 不是 data），
//    包一层把 params 摆在显眼位置，避免误用。
// ---------------------------------------------------------------------------

/** GET：params 走 query string */
export function get(url, params, config = {}) {
  return request.get(url, { params, ...config })
}

/** POST：data 走 JSON body */
export function post(url, data, config = {}) {
  return request.post(url, data, config)
}

/** PUT：data 走 JSON body */
export function put(url, data, config = {}) {
  return request.put(url, data, config)
}

/**
 * DELETE。
 * ⚠️ axios.delete 的第二个参数是 config，不是 body。
 * 真需要 body 时用 `request.delete(url, { data })`，本函数的 params 只拼 query。
 */
export function del(url, params, config = {}) {
  return request.delete(url, { params, ...config })
}

export { APP_NAME }
export default request
