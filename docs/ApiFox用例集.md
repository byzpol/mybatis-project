# ApiFox 用例集 · 文章模块全量联调（S16 ★M4）

> 用途：把文章模块 14 个接口的**正向、参数校验、状态非法、权限、分页边界、并发**六类场景
> 整理成可直接粘贴进 ApiFox 的用例清单，共 **50 条**。
> 每条的"预期 code"都来自**真机实测**（`target/s16-live.sh`，PASS=62 / FAIL=0），
> 不是照着契约推的。执行结果与缺陷见 `docs/联调报告.md`。
>
> 环境：`http://localhost:8080/cms-mis/api/v1`，Tomcat 9.0.122 单实例，
> 账号 `author(1) / editor(2) / chief(3) / admin(4) / author2(5)`，口令统一 `123456`。
> 认证走 Session（登录种 Cookie），**不是** Bearer Token —— ApiFox 里用"Cookie 保持"而不是 Authorization 头。

## 〇、先说清楚与手册 S16 的 5 处偏差

手册给的用例要求里有 5 处与本项目实际不符。**不照抄手册**，按实际改写并登记原因：

| # | 手册要求 | 实际 | 处理 |
|---|---|---|---|
| 1 | "文章 **15 个**接口的正向调用各 1 条" | 实际 **14 个**已实现；第 15 个"分页检索 `GET /articles`"**尚未实现** | 正向用例做 15 条（14 接口 + 审核两个环节各 1 条），并在报告里把"分页检索未实现"记为遗留 |
| 2 | 参数校验含 "**auditStage 传 3**" | 入参里**没有 `auditStage`**；审核环节由文章状态反推，入参是 `auditAction` | 改测 `auditAction=3`（B04） |
| 3 | 参数校验含 "**result 传 0**" | 入参里没有 `result`（那是落库列名）；`auditAction` 合法值只有 1/2 | 改测 `auditAction=0`（B05） |
| 4 | 分页边界：`page/size/sortField` 的 6 条 | 文章模块**没有分页接口**，无法在文章域执行 | 改走 `/health/page-echo`（S06 建的分页归一化探针，**不碰数据库**），6 条全部真跑 |
| 5 | 并发："同一文章并发发布 2 次" | 可执行，但实测**未产生 11010**，而是 200 + 11002（见 F01 备注） | 保留用例，报告里如实记录差异 |

> ⚠️ 第 1 条是**真缺口**不是记错：`docs/接口分类与鉴权表.md` 第 8 行"分页检索"状态是"⏳ 未实现"，
> 而 S17 的优化对象恰恰是它。这条会带到 S17 一并处理。

## 一、A 组 · 正向调用（15 条，P0）

> 全部要求已登录、且带对应权限的账号。`{P1}` 指自建的一篇草稿，链路按表内顺序执行。

| 用例编号 | 所属模块 | 接口名 | 方法+路径 | 请求参数示例 | 预期 code | 预期 data 关键字段 | 前置依赖 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| A01 | 文章 | 保存草稿 | `POST /articles/draft` | `{"title":"【S16联调】A01","summary":"s","content":"c","categoryId":1,"isTop":0}` | 200 | `data` = 新文章 id（Long） | author 登录 | P0 |
| A02 | 文章 | 更新草稿 | `PUT /articles/{P1}/draft` | `{"title":"【S16联调】主链路-改","summary":"s2","content":"c2","categoryId":2,"isTop":0}` | 200 | 无 data（`message="草稿已保存"`） | A01 已建 P1；author 是作者 | P0 |
| A03 | 文章 | 提交初审 | `POST /articles/{P1}/submit` | 无请求体 | 200 | `message="已提交初审"`；状态变 1 | A02 后 P1 为草稿(0) | P0 |
| A04 | 文章 | 批量提交 | `POST /articles/batch-submit` | `{"ids":[1165,1166]}` | 200 | `data.total=2`、`data.successCount=2`、`data.failCount=0`、`data.failList=[]` | 两篇自己的草稿 | P0 |
| A05a | 文章 | 编辑初审 | `POST /articles/{P1}/audit` | `{"auditAction":1,"auditOpinion":"S16联调：编辑初审通过"}` | 200 | `message="审核完成"`；状态 1→2 | A03 后 P1 为待编辑初审(1)；editor 登录且非作者 | P0 |
| A05b | 文章 | 主编终审 | `POST /articles/{P1}/audit` | `{"auditAction":1,"auditOpinion":"S16联调：主编终审通过"}` | 200 | 状态 2→3 | A05a 后 P1 为待主编终审(2)；chief 登录 | P0 |
| A06 | 文章 | 文章详情 | `GET /articles/{P1}` | 无 | 200 | `data.id`、`data.status/statusDesc/statusColor`、`data.categoryName`、`data.auditLogs`、`data.authorName` | P1 存在 | P0 |
| A07 | 文章 | 审核流水 | `GET /articles/{P1}/audit-logs` | 无 | 200 | `data` 数组；元素含 `auditStage/auditorName/result/opinion/auditTime/articleVersion` | P1 存在 | P0 |
| A08 | 文章 | 立即发布 | `POST /articles/{P1}/publish` | 无请求体 | 200 | `message="文章已发布"`；状态 3→5 | A05b 后 P1 为已核准(3)；chief 登录 | P0 |
| A09 | 文章 | 排期发布 | `POST /articles/{P2}/schedule` | `{"scheduleTime":"2026-10-13 19:49:02"}` | 200 | `message="排期设置成功"`；状态 3→6 | P2 为已核准(3)；`scheduleTime` 须晚于当前时间≥1 分钟 | P0 |
| A10 | 文章 | 撤回 | `POST /articles/{P1}/withdraw` | 无请求体 | 200 | `message="文章已撤回"`；状态 5→7 | P1 为已发布(5)；作者本人 | P0 |
| A11 | 文章 | 下架 | `POST /articles/{P1}/offline` | `{"reason":"S16联调：内容涉及过期信息，运营下架"}` | 200 | `message="文章已下架"`；状态 5→8 | P1 为已发布(5)；chief 登录；`reason` 非空白 | P0 |
| A12 | 文章 | 归档 | `POST /articles/{P1}/archive` | 无请求体 | 200 | `message="文章已归档"`；状态 8→9 | P1 为已下架(8)；chief 登录 | P0 |
| A13 | 文章 | 版本列表 | `GET /articles/{P1}/versions` | 无 | 200 | `data` 数组；元素 `id/versionNo/titleSnapshot/modifierId/modifierName/modifyRemark/createTime/isCurrent`；**无正文字段** | P1 存在 | P0 |
| A14 | 文章 | 版本回滚 | `POST /articles/{P1}/rollback` | `{"versionNo":1}` | 200 | `message="已回滚到指定版本"` | P1 状态 ∈ {草稿 0, 已驳回 4, 已撤回 7}；作者本人 | P0 |

**A 组实测要点**（真机原文见 `target/s16-live-output-2.txt`）：
- 全链路 `草稿 → 提交 → 初审 → 终审 → 已发布 → 撤回 → 再发布 → 下架 → 归档` 九步一次走通，**每步状态码都对**。
- A13 版本列表在"创建 + 改一次"后返回 **2 条**（`创建草稿` + `修改草稿`），回滚后又追加 1 条。
- A07 在"刚提交、还没审"时返回 `data:[]`（空数组，不是 404）。

## 二、B 组 · 参数校验（8 条，P0）

| 用例编号 | 所属模块 | 接口名 | 方法+路径 | 请求参数示例 | 预期 code | 预期 data/关键 message | 前置依赖 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| B01 | 文章 | 保存草稿·标题空 | `POST /articles/draft` | `{"title":"","summary":"s","content":"c","categoryId":1,"isTop":0}` | **400** | message 含「文章标题不能为空」 | author 登录 | P0 |
| B02 | 文章 | 保存草稿·标题超长 | `POST /articles/draft` | `{"title":"标"×201, ...}` | **400** | 含「文章标题长度不能超过 200」 | 同上 | P0 |
| B03 | 文章 | 保存草稿·分类不存在 | `POST /articles/draft` | `{"title":"…","categoryId":99999,...}` | **200** ⚠️ | `data`=新 id（**未做分类存在性校验**，见报告缺陷 D-03） | 同上 | P1 |
| B04 | 文章 | 审核·结论非法 | `POST /articles/{P1}/audit` | `{"auditAction":3,"auditOpinion":"x"}` | **400** | 含「审核结论只能是 1（通过）或 2（驳回）」 | editor 登录 | P0 |
| B05 | 文章 | 审核·结论为 0 | `POST /articles/{P1}/audit` | `{"auditAction":0,"auditOpinion":"x"}` | **400** | 同上 | 同上 | P0 |
| B06 | 文章 | 下架·原因纯空白 | `POST /articles/{P1}/offline` | `{"reason":"   "}` | **400** | 含「下架原因不能为空」 | chief 登录 | P0 |
| B07 | 文章 | 回滚·版本号 0 | `POST /articles/{P1}/rollback` | `{"versionNo":0}` | **400** | 含「版本号必须是正整数」 | author 登录 | P0 |
| B07' | 文章 | 回滚·缺版本号 | `POST /articles/{P1}/rollback` | `{}` | **400** | 含「请指定要回滚到的版本号」 | 同上 | P0 |
| B08 | 文章 | 请求体非法 JSON | `POST /articles/draft` | `{"title":"x",,}` | **400** | 含「请求体格式不正确，请检查 JSON 格式」 | author 登录 | P1 |

> ⚠️ 注意 B04/B05 的性质：`auditAction` 上只有 `@NotNull`，**Bean Validation 挡不住"传了 3"**，
> 这两条走的是 **Service 里的第二道值域校验**（`ArticleStateMachine` 之前的 `AuditResultEnum.isValid`）。
> 也就是说 400 是在进入业务逻辑后返回的 —— 用例通过不代表参数层挡住了。

## 三、C 组 · 状态非法与业务规则（10 条，P1）

| 用例编号 | 所属模块 | 场景 | 方法+路径 | 请求参数示例 | 预期 code | 期望 message 关键片段 | 前置依赖 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| C01 | 文章 | 草稿直接发布 | `POST /articles/{id}/publish` | 无 | **11002** | 「当前状态为『草稿』，不允许执行『立即发布』」 | 一篇文章停在草稿(0) | P0 |
| C02 | 文章 | 已发布再提交 | `POST /articles/{P1}/submit` | 无 | **11002** | 「…『已发布』，不允许执行『提交审核』」 | P1 为已发布(5) | P0 |
| C03 | 文章 | 归档后编辑 | `PUT /articles/{P1}/draft` | 完整保存 DTO | **11011** | 「已归档的文章不可修改」 | P1 为已归档(9) | P0 |
| C04 | 文章 | 非审核环节审核 | `POST /articles/{P1}/audit` | `{"auditAction":1,"auditOpinion":"x"}` | **11002** | 「…『已发布』，不在审核环节，无法审核」 | P1 为已发布(5) | P0 |
| C05 | 文章 | 已下架再发布 | `POST /articles/{P1}/publish` | 无 | **11002** | 「…『已下架』，不允许执行『立即发布』」 | P1 为已下架(8)；**OFFLINE 无 PUBLISH_NOW 出边** | P0 |
| C06 | 文章 | 已归档再归档 | `POST /articles/{P1}/archive` | 无 | **11011** | 「已归档的文章不可修改」 | P1 为已归档(9) | P1 |
| C07 | 文章 | 作者撤回已归档 | `POST /articles/{P1}/withdraw` | 无 | **11011** | 「已归档的文章不可修改」 | P1 已归档；**作者本人**（穿过归属层） | P0 |
| C07' | 文章 | 非作者撤回已归档 | `POST /articles/{P1}/withdraw` | 无 | **11003** | 「只能操作自己创建的文章」 | P1 已归档；**chief**（在归属层被拦） | P0 |
| C08 | 文章 | 驳回不填意见 | `POST /articles/{PB}/audit` | `{"auditAction":2}` | **11014** | 「驳回时必须填写审核意见」 | PB 处于待编辑初审(1) | P0 |
| C09 | 文章 | 驳回填意见 | `POST /articles/{PB}/audit` | `{"auditAction":2,"auditOpinion":"内容不完整，退回修改"}` | 200 | 状态 1→4 | 同上 | P0 |
| C10 | 文章 | 已驳回态再审核 | `POST /articles/{PB}/audit` | `{"auditAction":1,"auditOpinion":"x"}` | **11002** | 「…『已驳回』，不在审核环节，无法审核」 | PB 为已驳回(4) | P1 |

> C07 / C07' 是一对**顺序断言**：同一篇已归档文章，作者本人拿到 11011、非作者拿到 11003。
> 两条一起才证明"归属校验排在归档判断之前"，只看一条证明不了。
> 这是 S14 固化的行为，已逐字沿用到 S15 的回滚入口。

## 四、D 组 · 权限（6 条，P0）

> 两类越权的码不同，别混：**垂直越权 → 403**（HTTP 也是 403），**水平越权 → 11003/11004**（HTTP 仍 200）。

| 用例编号 | 所属模块 | 场景 | 方法+路径 | 请求参数示例 | 预期 code | 期望 message 关键片段 | 前置依赖 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| D01 | 鉴权 | 无 Session 调全部接口 | 14 个接口 × 各 1 次 | 各接口正常请求体（不带 Cookie） | **HTTP 401 ×14** | 未登录或登录已过期 | **清空 Cookie** | P0 |
| D02 | 鉴权 | author 调发布 | `POST /articles/{id}/publish` | 无 | **403** | 「没有该操作权限，需要：`article:publish`」 | author 登录 | P0 |
| D03 | 鉴权 | author 调审核 | `POST /articles/{id}/audit` | `{"auditAction":1}` | **403** | 「…需要：`article:audit`」 | author 登录 | P0 |
| D03' | 鉴权 | editor 调发布 | `POST /articles/{id}/publish` | 无 | **403** | 「…需要：`article:publish`」 | editor 登录（有 audit 无 publish） | P1 |
| D04 | 鉴权 | author 改他人草稿（水平越权） | `PUT /articles/2/draft` | 完整保存 DTO | **11003** | 「只能操作自己创建的文章」 | author 登录；id=2 属 author2 | P0 |
| D05 | 鉴权 | editor 审自己创建的稿 | `POST /articles/{PD}/audit` | `{"auditAction":1,"auditOpinion":"x"}` | **11004** | 「不能审核自己创建的文章」 | PD 由 editor 自建并已提交 | P0 |
| D06 | 鉴权 | admin 改他人草稿 | `PUT /articles/2/draft` | 完整保存 DTO | **11003** | 「只能操作自己创建的文章」 | admin 登录；**验证无超管短路** | P0 |

> D04 用种子 `id=2`（author2 的草稿）而不是自建稿：这两条是**必然被拒**的请求，
> 用现成数据不产生任何写入，跑完不用清理。D06 的预期是 11003 而非 200，
> 正是"`checkOwnership` 刻意没有超管短路"这条设计的实测证据。

## 五、E 组 · 分页边界（9 条，P1）

> **执行通道（S20 起改为真实列表接口）**：`GET /api/v1/articles`。
> S16 时期这条通道还不存在（列表接口是 S17 才做的），当时的做法是打 S06 建的分页归一化探针
> `GET /health/page-echo` —— 它调 `PageQuery.normalize()` 与 `checkSortField()` 两个纯逻辑方法，
> **不碰数据库**，跑任何边界值都不改数据，响应里同时回"归一化前 / 归一化后"两组值。
> S20 按 §八 的交接把这 9 条**回填到真实列表接口重跑**（结果见下面的"回填重跑"小节）。

| 用例编号 | 场景 | 请求 | 预期 code | 预期 data 字段 | 优先级 |
|---|---|---|---|---|---|
| E01 | 页码下溢 | `?page=0&size=10` | 200 | `normalizedPage=1`（`rawPage=0`） | P1 |
| E02 | 页码超大 | `?page=999&size=10` | 200 | `normalizedPage=999`（**不截断**，空页由 SQL 返回） | P1 |
| E03 | 每页 0 条 | `?page=1&size=0` | 200 | `normalizedSize=10`（回落默认值） | P1 |
| E04 | 每页负数 | `?page=1&size=-1` | 200 | `normalizedSize=10` | P1 |
| E05 | 每页 9999 条 | `?page=1&size=9999` | 200 | `normalizedSize=100`（截到 `MAX_SIZE`） | P1 |
| E06 | 排序方向乱写 | `?page=1&size=10&sortOrder=zzz` | 200 | `normalizedSortOrder=desc` | P1 |
| E07 | 排序字段注入串 | `?sortField=create_time; DROP TABLE cms_article` | **400** | 「排序字段非法：…（只允许字母、数字、下划线…）」 | P0 |
| E08 | 排序字段表达式 | `?sortField=1=1` | **400** | 同上 | P0 |
| E09 | 合法排序字段 | `?sortField=create_time` | 200 | `acceptedSortField=create_time` | P1 |

> "预期 data 字段"那一列是**探针上的字段名**（`normalizedPage` 等）。
> 真实列表接口返回的是 `PageResult`，没有这些键，回填时必须把判据翻译过去 —— 见下表。

### 5.1 回填重跑（S20，真实 `GET /api/v1/articles`）

脚本 `target/s20_apifox_e.py`，结果 **PASS=24 / FAIL=0**（明细 `target/s20-apifox-e-result.tsv`）。

| 用例 | 探针上的判据（S16） | 真实列表接口上的等价判据（S20 实测） | 结果 |
|---|---|---|---|
| E01 | `normalizedPage=1` | `data.current == 1` | ✅ 1 |
| E02 | `normalizedPage=999` | `data.current == 999` 且 `records=[]`、`total` 仍是真实值 | ✅ 999 / 0 条 / total=54 |
| E03 | `normalizedSize=10` | `data.size == 10` | ✅ 10 |
| E04 | `normalizedSize=10` | `data.size == 10` | ✅ 10 |
| E05 | `normalizedSize=100` | `data.size == 100` 且返回条数 ≤100 | ✅ 100 / 54 条 |
| E06 | `normalizedSortOrder=desc` | 结果集按 `create_time` **非递增** | ✅ 首 22:20:14 → 末 22:19:57 |
| E07 | **400** | **400** + 提示含「排序字段非法」 | ✅ 400 |
| E08 | **400** | **400** | ✅ 400 |
| E09 | `acceptedSortField=create_time` | 200 且按 `create_time` **非递减** | ✅ 首 2025-11-19 → 末 2026-09-27 |
| 附 | — | 非白名单列（`sortField=content`）→ 400 且列出自选 | ✅ 提示「可选：create_time、update_time、publish_time、view_count、title、id」 |

> ⚠️ **回填不是"把 URL 换掉"**：探针回的是 `PageQuery` 归一化后的**自述**，
> 真实接口回的是 `PageResult`，两边**没有共同字段名**。这一层翻译必须显式做，
> 否则会出现"用例写着通过、其实断言的是 `undefined == undefined`"的假通过。
> ⚠️ `data.size` 是**回显的归一化后 size**（`PageResult` 由 `PageQuery` 造出来），
> 所以 E03/E04/E05 的判据能直接搬 —— 这一点是实测确认的，不是推测。
> ⚠️ E02 仍然**不做上界截断**：`current` 如实回 999、`records` 为空，但 `total` 是真实的，
> 前端因此能区分"没有这一页"和"没有数据"。

> E07 是这批用例里安全价值最高的一条：`sortField` 是要拼进 `ORDER BY` 的，
> 项目用"MP `OrderItem` + 正则白名单（`^[A-Za-z_][A-Za-z0-9_]{0,63}$`）"两重防线挡住它，
> **不是**用 `trim()` 后拼字符串 —— `trim()` 会吃掉 NUL，`"id\0"` 能绕过，这个坑在 `PageQuery` 注释里有专段记录。
> ⚠️ E07/E08 返回的是 **400 而不是静默降级成默认排序**：故意做成报错，因为静默降级会让前端以为排序生效了。
> ⚠️ E02 的 `page=999` **不做上界截断**是有意的：截断会让前端拿到第 1 页却以为在第 999 页。

## 六、F 组 · 并发（2 条，P1）

| 用例编号 | 场景 | 方法+路径 | 请求参数示例 | 预期 | 前置依赖 | 优先级 |
|---|---|---|---|---|---|---|
| F01 | 同一文章并发发布 2 次 | `POST /articles/{PF}/publish` ×2（并发） | 无请求体 | 恰好 **1 个 200**；另一个为 `11002` 或 `11010`；最终状态 = 已发布(5) | PF 为已核准(3)；chief 登录 | P1 |
| F02 | 同一文章并发改草稿 2 次 | `PUT /articles/{PG}/draft` ×2（并发） | 两份不同的完整 DTO | 至少 1 个 200；两个都 200 也**不算错**（见备注） | PG 为草稿(0)；author 是作者 | P1 |

> **F01 实测**：`200 / 11002`，最终状态 5 —— 第二个请求读到的已经是"已发布"，被状态机拒掉。
> ⚠️ 与 S13 记录（`成功=1 / 11010=1`）**不一致**：那一轮是乐观锁冲突，这一轮是状态非法。
> 两者都是"恰好一个成功"，结果等价；差异来自**两个请求读 version 的时点是否重叠**，
> 属时序依赖，不能作为固定预期。用例里写成"`11002` 或 `11010`"。
>
> **F02 实测**：`200 / 200`，两次都成功。
> 这不是缺陷：`updateDraft` 的 version 是**在事务内读出来的**，两个请求只要没同时读到同一个 version，
> 就都会正常成功（后一个基于前一个的结果再改一次）。要稳定触发 11010 需要客户端把 version 传进来做条件更新，
> 而本项目契约里**没有**这个字段（S13 实测是在更紧的时序下偶然撞到的）。
> 这条用例的价值在于**把"并发下不会数据错乱"这件事钉住**：无论谁赢，最终库里的版本号都是连续递增的。

## 七、怎么在 ApiFox 里跑

1. **环境变量**：`baseUrl = http://localhost:8080/cms-mis/api/v1`；`authorCookie / editorCookie / chiefCookie / adminCookie` 用"前置脚本登录后写变量"。
2. **登录**：`POST /auth/login`，body `{"username":"author","password":"123456"}`；ApiFox 会自动保存 `Set-Cookie`。
3. **用例执行顺序**：A 组**必须按序**（A01→A02→A03→A05a→A05b→A08→A10→A11→A12），
   因为后一步依赖前一步的状态；B/C/D/E/F 组**互相独立**，可任意顺序并行。
4. **变量**：把 A01 返回的 `data` 存为 `P1`，后续 A02/A03/… 全用它 —— 这样一整套用例跑完**只新建 1 篇主链路文章**。
5. **别用 `Authorization` 头**：本项目是 Session 认证，用 `Cookie`。用错了表现是**全部 401**，
   而不是 403 —— 看到 401 先查认证方式，不要先去查权限。
6. **断言**：本项目业务码在响应体 `code` 里，**不总是等于 HTTP 状态码**。
   100~599 会同步到 HTTP，`10xxx~14xxx` 保持 HTTP 200。所以断言要分两层：
   先断言 HTTP（401/403/400 这几种），再断言 `body.code`。
   **只断言 HTTP 200 会漏掉所有业务失败**（11002/11003/11011 全是 HTTP 200）。

## 八、与"分页检索未实现"的关系（S16 交接 → S17 实现 → **S20 收口**）

手册 S16 要求"文章 15 个接口各 1 条正向用例"，当时实际只有 14 个 —— 缺的是
`GET /api/v1/articles`（分页检索，契约 §1.3 第 8 行）。

它对 S16 的影响：**9 条分页边界用例被迫改道**（E01~E09 打的是 `/health/page-echo` 而不是列表接口）。
改道不算作弊 —— `page-echo` 调的正是列表接口会调的同一个 `PageQuery.normalize()`，
但它**证明不了**列表接口自己的 WHERE/ORDER BY 拼接是否正确，这个空白要由 S17 补。

**S17 已实现**该接口（`article:view`；`selectPage` + 收集外键批查 + 内存拼 VO；排序白名单
`create_time/update_time/publish_time/view_count/title/id`），但 S17 只做了**等价验证**，
没有回填这 9 条用例。

**S20 收口**：见 §5.1 —— 9 条全部回填到真实列表接口重跑，**24 项断言全绿**。
回填时暴露的一条真实经验是"**判据要翻译，不能只换 URL**"（探针回自述字段，
列表接口回 `PageResult`，两边字段名没有交集）。至此 S16 留下的这条尾巴关闭。
