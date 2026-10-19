# F02 · axios 统一封装 实测记录

> 对应《成员A-开发提示词手册》F02（D11 / 10-19 / 半天）
> 本篇所有结论均来自本机实际执行，原始输出见 `docs/evidence/F02-自检与联调原始输出.md`。
> 与手册不一致的地方，一律以 `docs/接口契约.md` 为准，并在第三节逐条列明。

---

## 一、本步产出

| 文件 | 行数 | 性质 | 说明 |
|---|---|---|---|
| `frontend/src/api/request.js` | 276 | 新建 | axios 实例 + 请求/响应拦截器 + 快捷方法 |
| `frontend/src/api/modules/article.js` | 226 | 新建 | 文章域 16 个接口方法（入户线模板） |
| `frontend/src/store/user.js` | 112 | 修改 | 补 `hasPermission`（含通配）、`hasRole`，更新文件头注释 |
| `frontend/src/router/index.js` | 94 | 修改 | 打开权限拦截，注册 `/403` 路由，更新注释 |
| `frontend/src/views/error/403.vue` | 40 | 新建 | 越权页（权限拦截的落地页，`router` 注释里指向的就是它） |
| `frontend/vite.config.js` | 76 | 修改 | **修 bug**：dev proxy 补 `rewrite`（见第四节 4.1） |
| `frontend/src/api/.gitkeep` | — | 删除 | api 目录已有真实文件，占位文件冗余 |

前端产物重新构建：`vite build` **1613 模块 / 2.74s / 11 个产物文件**（F01 是 1611 模块 / 10 个，新增的是 403 页及其 css）。

---

## 二、契约对照：手册 F02 与 `接口契约.md` 的 4 处差异

手册 F02 第二节列了 14 个方法，契约 §1.3 标题写「15 个」但**表体实际 16 行**。逐条核对后有 4 处出入，**全部以契约为准**，代码里已在对应方法上写中文注释说明。

| # | 手册写法 | 契约实际 | 处理 |
|---|---|---|---|
| 1 | `audit(id, data)`，`data: {stage, result, opinion}` | `ArticleAuditDTO = { auditAction:1通过/2驳回, auditOpinion }`，**不传 stage** | 按契约。审核阶段由文章当前状态决定（`status=1` 即初审、`status=2` 即终审），前端传 stage 反而会和后端真实状态打架 |
| 2 | `POST /articles/{id}/versions/{versionNo}/rollback` | `POST /api/v1/articles/{id}/rollback` | 按契约 URL，`versionNo` 改放请求体 |
| 3 | 未列 `DELETE /articles/{id}`（仅草稿/驳回态，权限 `article:delete`） | 契约第 5 行有 | 补 `deleteArticle(id)` |
| 4 | 未列 `GET /articles/{id}/audit-logs`（审核流水，权限 `article:view`） | 契约第 9 行有 | 补 `listAuditLogs(id)` |

另外两处细节以契约为准：

- **方法总数**：手册 14 个 → 实际 **16 个**（补上面两条）。
- **分页参数名**：契约 `PageQuery` 固定 `page / size / sortField / sortOrder`（`size` 上限 100、`sortOrder` 默认 `desc`），不是手册里笼统的 `params`。
- **排序字段白名单**：后端只接受 `createTime / updateTime / publishTime / viewCount / title`（契约 §2.1 后的白名单代码），传别的会被 400 拒掉 —— 这条已写进 `pageArticles` 的注释，F06 列表页要用。

> 结论：手册的接口清单是**设计阶段的草稿**，契约是**评审冻结版**。这类差异正属于"AI 幻觉高发区"，所以本步是逐行对着契约表写的，没有照抄手册。

---

## 三、关键设计逐项说明

### 3.1 `baseURL` 只写 `'/api/v1'`，绝不出现 host

`request.js` 里没有任何 `http://localhost:8080` 之类的地址。理由：一旦写死，dev 与生产两套环境必须改代码，且上线后会变成跨源请求（`withCredentials` 立刻出问题）。

链路是这样的：

```
dev：浏览器 → http://localhost:5173/api/v1/xxx     （同源）
        └─ vite proxy 转发 → http://localhost:8080/cms-mis/api/v1/xxx
生产：浏览器 → http://host:8080/cms-mis/api/v1/xxx （同源）
        └─ 同一个 Tomcat 内的 DispatcherServlet
```

静态检查已加进自检：把 `request.js` 的块注释剔掉后，代码区不含 `http://` / `localhost` / `127.0.0.1` / `:8080` / `:5173`。**PASS**。

### 3.2 `withCredentials = true` 为什么必须为 true

这是手册明确要求回答的问题。

本项目鉴权走 **Session**（后端 `LoginInterceptor` 从 `HttpSession` 取登录态），浏览器把会话标识放在 `JSESSIONID` Cookie 里。而 axios 的默认值是 `withCredentials = false` —— **跨源请求不带 Cookie**。后果是典型的"登录接口返回成功，紧接着的下一个请求就 401"，因为后端每次都认为这是新会话。

什么时候算跨源：dev 时前端在 Vite 的 `5173`、后端在 Tomcat 的 `8080`，虽然配了 proxy 让浏览器看到同源，但只要有一个人绕过 proxy 直连 8080（或以后换成前后端分离部署），立刻暴露。生产环境是同一个 Tomcat 的 `/cms-mis` 下，同源，带上也无副作用。

配套后端约束（契约 §3.4）：`allowCredentials(true)` 与 `allowedOrigins("*")` **不能同时用**，必须写具体源。实测响应头里能看到 `vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers`，说明 CORS 配置是这个模式。

### 3.3 `Content-Type` 只给带 body 的方法设

手册说"统一设置 `application/json`（GET 请求除外，避免后端 415）"。实现时按 method 判断：

```js
const method = (config.method || 'get').toLowerCase()
if (['post', 'put', 'patch', 'delete'].includes(method) && config.data !== undefined) {
  config.headers['Content-Type'] = 'application/json'
}
```

**为什么要连 `config.data !== undefined` 一起判**：`POST /articles/{id}/submit` 这类接口没有请求体。给一个"声明了 JSON body 却没有 body"的请求加 `Content-Type: application/json`，某些容器/过滤器会直接回 415。所以判据是"有 body 才加头"，而不是"是 POST 就加头"。

自检覆盖：POST 带头 **PASS**，GET 不带头 **PASS**。

### 3.4 全屏 Loading 用计数，不用布尔

一个详情页打开时会并发发多个请求（详情 + 审核流水 + 版本列表）。用布尔的话第一个请求返回就把 Loading 关了，后两个还在飞 —— 用户看到一闪。用计数，归零才关。

另外两处防御：

- `closeLoading()` 里 `if (loadingCount > 0)` 才递减 —— 否则异常路径可能把计数减成负数，Loading 永久卡住。
- 请求拦截器的**失败分支**（`error` 回调）也要 `closeLoading()` —— 请求在发出前就失败时如果不还计数，计数永远回不到 0。
- 提供 `config.showLoading === false` 逃生口：页面初始化时静默拉字典不该盖住整页。

### 3.5 业务错误与 HTTP 错误分开写，保证"只弹一次"

这是手册的核查项，也是最容易做错的地方。两条分支**互斥且各自只弹一次**：

| 场景 | 后端实际返回 | 走哪条 | 弹几次 |
|---|---|---|---|
| 成功 | HTTP 200 + `code:200` | 成功分支，`resolve(res.data)` 裸数据 | 0 |
| 业务异常（10xxx~14xxx） | HTTP **200** + `code:11001` | 成功分支里判 `code` | 1 |
| 业务异常（400/403/404 等） | HTTP **404** + `code:404` | `error` 分支，取 `error.response.data.message` | 1 |
| 未登录 | HTTP **401** | `error` 分支 → 走会话过期处理 | 1 |
| 网络断/超时 | 无 response | `error` 分支 → 兜底文案 | 1 |

关键点：**`code === 401` 与 `status === 401` 走同一个 `handleUnauthorized()`**。如果分开写两套（一套在 fulfilled 里清 state，一套在 rejected 里只弹消息），就会出现"清了登录态但没跳转"或"弹两条提示"。

实测（自检 §5~§9）：业务错误弹 1 次 **PASS**、HTTP 404 弹 1 次 **PASS**、并发 3 个 401 弹 1 次 **PASS**。

### 3.6 并发 401 只弹一次 + 只跳一次

`handleUnauthorized()` 用一个模块级 `redirecting` 标记：

```js
if (redirecting) return          // 后面的 401 直接静默返回
redirecting = true
ElMessage.error('登录已过期，请重新登录')
await router.replace({ path: '/login', query: { redirect: current.fullPath } })
// finally 里 redirecting = false
```

为什么需要：刷新页面时可能同时有 5 个请求 401，不加标记就会弹 5 条提示、push 5 次路由。

两个边界都处理了：
- 已经在 `/login` 上时不再重复跳转（只弹提示），避免"登录页跳登录页"。
- `clearUserInfo()` 放在判断 `redirecting` **之前** —— 即使这次不弹提示不跳转，登录态也必须干净，否则路由守卫会以为还登着。

### 3.7 防御分支：非统一结构原样透传

```js
if (res === null || typeof res !== 'object' || typeof res.code === 'undefined') return res
```

判据用 `typeof res.code === 'undefined'` 而不是 `res.code !== 200`。原因：将来做导出（`responseType: 'blob'`）时，`res` 是个 `Blob` 对象，用后者会被误判成"业务失败"并弹一个 "操作失败"。自检覆盖：`'plain-text'` 原样返回 **PASS**。

### 3.8 `hasPermission` 的通配规则

三种写法都要命中：

| 持有的权限 | `hasPermission('article:audit')` |
|---|---|
| `'article:audit'` | ✅ 精确命中 |
| `'article:*'` | ✅ 资源级通配 |
| `'*'` | ✅ 超级权限 |

```js
if (!perm) return true
if (perms.includes('*')) return true
if (perms.includes(perm)) return true
const i = perm.indexOf(':')
if (i > 0 && perms.includes(`${perm.slice(0, i)}:*`)) return true
return false
```

三个设计决定：

1. **为什么空参返回 `true`**：调用点常写成 `:disabled="!hasPermission(perm)"`，而 `perm` 可能来自后端菜单配置、本身就是空的。空权限码语义是"该页只需登录"，返回 `false` 会把所有未声明权限的页面全锁死。
2. **为什么通配规则必须和后端一致**：后端 `PermissionAspect` 也认 `article:*`。两边规则不一致的症状很典型 —— **菜单能看见，点进去 403**。
3. **`hasRole` 与 `hasPermission` 分工**：权限码管"能不能做某个动作"，角色码管"显示哪套入口/看板"。用角色判功能会让页面逻辑和后端角色配置紧耦合，后端加一个动作就得回来改前端。

顺带把 `router` 里 F01 预留的 TODO 打开了：

```js
if (to.meta.permission && !userStore.hasPermission(to.meta.permission)) return next({ path: '/403' })
```

默认"只需登录"，只有显式声明了 `meta.permission` 才校验。

> ⚠️ 前端这套是**体验层**判断，不是安全边界。真正的拦截在后端 `LoginInterceptor` + `PermissionAspect`（主干已冻结）。前端守卫被绕过也拿不到数据。

---

## 四、踩坑

### 4.1 【真 bug】vite dev proxy 少了 `rewrite`，dev 期所有接口 404

**现象**：起 Tomcat 9 + vite dev，浏览器请求 `/api/v1/health/ping` 返回 **HTTP 404**，且响应体是 **Tomcat 自己的 HTML 错误页**（`<!doctype html><html lang="zh"><head><title>HTTP状态 404 - 未找到</title>`），不是后端的统一 JSON。这个"HTML 404 而不是 JSON 404"非常有迷惑性 —— 会让人误以为后端没起或 war 没部署。

**根因**：F01 写的 proxy 是

```js
'/api': { target: 'http://localhost:8080', changeOrigin: true }   // ❌ 无 rewrite
```

注释还写着"后端 URL 本身就带 /api/v1 前缀，原样转发即可"。但后端的 context path 是 **`/cms-mis`**，接口真身是 `http://localhost:8080/cms-mis/api/v1/health/ping`。原样转发打到 `http://localhost:8080/api/v1/...`，Tomcat 里根本没有 `/api` 这个上下文。

**A/B 实证**：

| 请求 | 结果 |
|---|---|
| `GET http://localhost:8080/cms-mis/api/v1/health/ping`（直连，带 context） | `200` + `{"code":200,"message":"操作成功","data":"pong",...}` |
| `GET http://localhost:8080/api/v1/health/ping`（直连，不带 context） | `404` ← 证明容器里没有 `/api` 上下文 |
| `GET http://localhost:5199/api/v1/health/ping`（经代理，**未 rewrite**） | `404` + HTML |
| `GET http://localhost:5199/api/v1/health/ping`（经代理，**加 rewrite 后**） | `200` + `{"code":200,...,"data":"pong",...}` |

**修复**：

```js
'/api': {
  target: 'http://localhost:8080',
  changeOrigin: true,
  rewrite: (path) => `/cms-mis${path}`
}
```

**注意这与 `base` 是两件事**：`base: '/cms-mis/'` 管**静态资源 URL**（构建产物），proxy 的 rewrite 管**接口转发**（只影响 dev，构建产物不经过 proxy，所以生产不需要这段）。

**教训**：F01 只验了"war 里的页面和静态资源能打开"，没验"dev 期能不能连上后端"，所以漏了。**页面能打开 ≠ 能联调**。

### 4.2 未进 bundle 的文件，`vite build` 根本不校验

`request.js` / `article.js` 写完后 `npm run build` 依旧 **BUILD SUCCESS**，但模块数只从 1611 涨到 1613（那 2 个是 403 页）。原因：还没有任何页面 `import` 这两个模块，**tree-shaking 直接跳过，vite 压根没转译它们**。也就是说这次 build 对 F02 的产出毫无校验作用。

发现方式：比对构建日志的模块数（1611 → 1613），与预期不符。

**解法**：写一个临时的自检页（`frontend/f02-selftest.html` + `f02-selftest.js`，验证完即删），显式 `import` 真实模块并直接调用真实拦截器函数，在 Chrome 无头里跑断言。为了不污染仓库，临时文件只在验证期间存在，测完删除。

**这条值得记住**：新建的前端模块在"还没有调用方"的阶段，构建通过不代表文件正确。

### 4.3 直接调用拦截器函数，比对真实 HTTP 更可控

自检里验证 401 流式时用了两种手段：

- **直接喂假响应给真实拦截器**：`request.interceptors.response.handlers[0].fulfilled({ data: {code:401,...}, config: {} })`。这样能精确控制 HTTP 码与业务码的组合（业务码 401 / HTTP 401 / 两者同时），是真实请求很难构造的。
- **真实 HTTP 往返**：经 vite proxy → Tomcat，打 `/health/page-echo`（需登录）拿真 401。

两者都保留。纯模拟的好处是**可重复、不依赖环境**；真实往返的好处是能验证 proxy、序列化、CORS 这些链路。

### 4.4 `spy` 打桩 `ElMessage.error` 需要兜底

自检要数"弹了几次提示"，做法是临时替换 `ElMessage.error`。但 element-plus 的导出对象属性是否可写没有保证，所以加了兜底：打桩后校验 `ElMessage.error !== orig`，失败则改用 `document.querySelectorAll('.el-message').length` 计数。

实测打桩成功（输出里能看到 `calls=["文章不存在"]`）。

### 4.5 自检脚本改 URL 的精确定位问题（排查过程记录）

E2E 段一开始挂在 `if (location.search.includes('e2e'))` 后面，结果**一直不执行**，且没有任何异常，看起来像"代码没生效"。

真实原因很朴素：跑在前面的 401 用例执行了 `router.replace({ path: '/login', query: { redirect: '/403' } })`，**把浏览器地址改成了 `...f02-selftest.html?redirect=/403`**，原来那个 `?e2e=1` 被覆盖了，所以 `location.search` 里没有 `e2e`。

把 `location.search` 打进输出后一眼看穿（`search="?redirect=/403"`）。**顺带证明了 `redirect` 参数确实写进了 URL**。

**收获**：页面内的 bug 排查，"看不到效果"时先怀疑"我以为的条件其实不成立"，把中间态打出来比反复读代码快得多。

---

## 五、实测结果

### 5.1 自检 39 项全通过

环境：vite dev（`5199`）+ Tomcat 9.0.122（`8080`，war 已部署在 `/cms-mis`）+ Chrome 无头 `--headless=new --no-proxy-server --dump-dom --virtual-time-budget=20000`。

```
RESULT pass=39 fail=0
```

分五组：

| 组 | 项数 | 覆盖 |
|---|---|---|
| 实例配置 | 6 | baseURL 相对、timeout、withCredentials、代码区无 host、四个快捷方法、article 16 个方法 |
| 请求拦截器 | 2 | POST 带 Content-Type、GET 不带 |
| hasPermission / hasRole | 8 | 精确命中、`article:*` 通配、`*` 超级、未授予、空参、角色数组 |
| 响应拦截器（模拟） | 17 | 裸 data 解包、非统一结构透传、业务错误只弹 1 次、silent、HTTP 404 取后端 message、网络异常兜底、业务码 401 全流程、HTTP 401 全流程、并发 3 个 401 只弹 1 次 |
| 真实后端 E2E | 5 | 经 proxy 打 `GET /health/ping` 拿到 `"pong"`、真实 HTTP 401 → reject + 清登录态 + 跳 `/login` + 只弹 1 次 |

### 5.2 真实后端 401 的原始响应

```
HTTP/1.1 401 Unauthorized
vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers
content-type: application/json;charset=UTF-8

{"code":401,"message":"未登录或登录已过期","timestamp":1791633094372}
```

注意 `message` 是「未登录或登录已过期」，而前端统一提示「登录已过期，请重新登录」（前端文案固定，便于用户理解）。这是刻意的：**用户看到的提示由前端决定，后端 message 用于排查**。

### 5.3 顺带确认的两件事

- `GET /cms-mis/api/v1/articles` 返回 `{"code":404,"message":"接口不存在：GET /cms-mis/api/v1/articles",...}` —— `ArticleController` 还没实现（S09 才做），同时**证明 S08 的统一 404 在 API 路径上仍然生效**，没有回归。
- 经 proxy 的响应 `content-type: application/json;charset=UTF-8`，证明 proxy 没有破坏响应头与字符集。

### 5.4 构建

```
vite v8.3.4 building client environment for production...
✓ 1613 modules transformed.
../src/main/webapp/index.html                        0.79 kB
../src/main/webapp/assets/403-*.css                  0.09 kB
../src/main/webapp/assets/404-*.css                  0.09 kB
../src/main/webapp/assets/login-*.css                0.32 kB
../src/main/webapp/assets/AppLayout-*.css            0.39 kB
../src/main/webapp/assets/index-*.css              360.17 kB
../src/main/webapp/assets/*.js（5 个）             ~1020 kB
✓ built in 2.74s
```

---

## 六、已知限制与后续衔接

| 项 | 现状 | 后续 |
|---|---|---|
| `/api/v1/articles` 等业务接口 | 后端未实现（404） | **S09~S11** 实现后再回来做真实联调，届时 `article.js` 的方法签名不用改 |
| 登录 / 登出 / `GET /auth/me` | 前端还没有 | 归认证模块（S12 附近）；`store/user.js` 的文件头注释里已写明届时 `import` 哪个 api 模块，并说明**不会与 `request.js` 形成循环依赖**（`request.js → router → store/user`，store/user 不 import request.js） |
| 401 后跳 `/login` | 已实现，但登录页还是 F01 占位版 | **F04** 做真登录页，届时读 `route.query.redirect` 跳回原页 |
| 权限拦截 | 已打开，但路由表里还没有带 `meta.permission` 的路由（F01 的 children 是空数组） | **F03/F06** 加业务路由时补 `meta.permission`，如 `/articles/audit` 配 `'article:audit'` |
| `403.vue` | 已建立，但正常操作走不到（没有受限路由） | F03 起可验证 |
| 排期时间格式 | 前端传 ISO 8601 字符串（如 `'2026-10-25T09:00:00'`） | S09 实现时确认后端 `@DateTimeFormat`/Jackson 的解析格式；若后端要 `yyyy-MM-dd HH:mm:ss`，在 `publishSchedule` 里转换 |
| `rollback` 的请求体字段名 | 契约未给 DTO，按 `cms_article_version.version_no` 推定为 `{ versionNo }` | **S15** 实现时对齐，若后端改名只需改一处 |
| `batchSubmit` 返回值 | 前端按"逐条结果数组 `[{id, success, message}]`"实现（需求 A-F04："不因单条失败整批回滚"） | S09 实现时对齐具体结构 |

---

## 七、与《企业开发规范清单》的对应

| 条款 | 本步对应 |
|---|---|
| 5-5 / 5-6 目录与分层 | `src/api/` 集中 HTTP 调用（`request.js` + `modules/*.js`），页面不直接写 `axios.get` |
| 统一返回体 | 前端只认 `{code, message, data}`，成功 message 固定「操作成功」（自检里断言的 200 分支即来自此契约） |
| HTTP 状态码与业务码同时设置 | 响应拦截器**两条分支都实现**（`code===401` 与 `status===401`），正是为了适配契约 §3.3 的双轨制 |
| 前端 6 目录 | `api / components / layout / router / store / utils / views` 齐备，`components`、`utils` 仍是 `.gitkeep` 占位 |

---

## 八、人工核查清单

手册 F02 的四条核查项，逐条给判定依据：

- [x] **`request.js` 里没有任何硬编码的 host 或端口**
  - 判据：`request.defaults.baseURL === '/api/v1'`（自检 PASS）；源码剔掉块注释后不含 `http://` / `localhost` / `127.0.0.1` / `:8080` / `:5173`（自检 PASS）。**注意**：文件头注释里为说明问题确实提到了 `http://localhost:8080`，所以静态检查必须先剔注释 —— 这也是自检脚本这么写的原因。
- [x] **让后端故意返回 401，前端能清空登录态并跳 `/login`**
  - 判据：真实后端（Tomcat 9，未登录打 `GET /api/v1/health/page-echo`）→ `HTTP 401` + 统一 JSON → 自检 4 项 PASS：reject、pinia 清空、`router.currentRoute === '/login'`、提示只弹 1 次。另用模拟覆盖业务码 401 与并发 3 个 401。
- [x] **让后端故意抛 `BusinessException`，前端只弹一次错误提示**
  - 判据：模拟 `code:11001`（HTTP 200）→ `ElMessage.error` 恰好 1 次、文案取 `message`、`error.code` 透传（自检 4 项 PASS）。HTTP 404 分支同样只弹 1 次（自检 PASS），证明两条分支不会互相叠加。
- [x] **`hasPermission` 的通配逻辑正确**
  - 判据：8 项 PASS —— 精确命中 / `article:*` 覆盖 `article:audit` 与 `article:anything` / `*` 全通过 / 未授予 false / 空参 true / `hasRole` 单值 false 与数组任一命中。

手工可复现步骤（供答辩演示）：

1. 起 Tomcat 9（**必须先 `export CATALINA_HOME=` 与 `CATALINA_BASE=`**，否则会起成环境变量指向的 Tomcat 11，见 F01 记录）
2. `cd frontend && npm run dev`
3. 浏览器打开 `http://localhost:5173/`，F12 控制台执行：
   ```js
   // ① 探活：应打出 "pong"
   const { default: request } = await import('/src/api/request.js')
   await request.get('/health/ping')
   // ② 未登录 401：应弹「登录已过期，请重新登录」并跳到 /login
   await request.get('/health/page-echo').catch(() => {})
   ```

---

## 九、F03 预告

F03（后台布局与主题，D11~D12 / 10-19 ~ 10-20 / 1 天）产出：

- `frontend/src/layout/AppLayout.vue` —— 把 F01 的占位版换成真骨架
- `frontend/src/layout/components/` —— `Sidebar.vue` / `Navbar.vue` / `Breadcrumb.vue`
- `frontend/src/styles/theme.css` —— 主题变量

与 F02 的衔接点：

- 侧边栏菜单项按 `meta.permission` 过滤，调的就是本步的 `userStore.hasPermission()`；F03 做菜单时要顺手给路由补 `meta.permission`，否则过滤无从判定。
- Navbar 的"退出登录"按钮届时调 `router.push('/login')` + `userStore.clearUserInfo()`（`clearUserInfo` 本步已有），但**真正的 `/auth/logout` 请求要等认证模块**，F03 先只清前端态。
