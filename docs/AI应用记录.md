# AI 应用记录（S19 ~ S22）

> 本文件记录"测试 / 回归 / 文档统稿"这几步里与 AI 协作的过程、**AI 或手册给错而被实测纠正的地方**，
> 以及需要如实登记的偏差与未做的事。
>
> **分步导航**：`§〇 ~ §八` = **S19**（单元测试与异常/边界测试，第 23~24 天，11-04~11-05）；
> `§九 ~ §十一` = **S20**（回归测试与规约自检，第 25 天，11-06）；
> `§十二 ~ §十三` = **S21**（结题报告、时序图与统稿，第 26~27 天，11-09~11-10）。
> 三部分的格式一致：每条结论都带**实测判据**（命令、输出片段、文件行号），没有实测依据的话一律不写。

---

# 第一部分 · S19（单元测试与异常/边界测试）

> 第 23~24 天（11-04 ~ 11-05）。回答手册 S19 要求"特别说明"的三件事。

---

## 〇、本步做了什么（一页摘要）

| # | 事项 | 结果 |
|---|---|---|
| 1 | 盘点既有测试资产，对照手册 S19 要求的"四类测试" | 18 个类 / 254 用例；**其中"接口层（MockMvc）"为 0** |
| 2 | 新建接口层测试基类 | `AbstractArticleApiTest`（上下文装配 + 真回滚 + 从库表装配登录态） |
| 3 | 新建 `ArticleControllerTest`（手册第 3 类） | **33 个用例**：统一返回体 / `@Valid` 400 / 业务码 / 权限三态 / 时间格式 / 审计字段 |
| 4 | 新建 `ArticleQueryTest`（手册第 4 类） | **25 个用例**：分页边界 / 排序白名单与注入 / 多条件组合 / 时间区间 |
| 5 | 全量回归 | `mvn -o test` → **312 个用例全绿**（原 254 + 新 58） |
| 6 | 证明回滚生效 | 测试前后 17 张表**逐表行数完全一致**，总计 834 → 834 |
| 7 | 证明无顺序耦合 | `runOrder=random`（固定种子）+ `runOrder=reversealphabetical` 各跑一次，均 312/312 全绿 |
| 8 | **抓到并修掉 1 个真实跨模块缺陷（D-09）** | 列表接口的时间筛选**一用就 400**，见 §五 |
| 9 | 主干改动 | **只加方法**：`SpringMvcConfig#addFormatters` + 新增 `StringToLocalDateTimeConverter`；未改任何既有签名 |

---

## 一、手册"四类测试"与本项目现状的映射

手册要求分别建四个类。本项目的实际做法是**不重复建设已经充分覆盖的部分**，只补真正的空白。
下表每一行都有实测依据。

| 手册要求 | 本项目现状 | 处置 | 依据 |
|---|---|---|---|
| **1. `ArticleStateMachineTest`**（纯单元，不加载容器） | **已有** `common/ArticleStateMachineTest.java`，21 个用例、557 行 | **不动** | 手册要求覆盖"30 种状态 × 3 动作"边界表；本类实际穷举 **10 状态 × 10 动作 = 100 种组合**，并断言"合法 14 / 非法 86"。是手册要求的**超集**（`testAllActionStatusCombinations`） |
| **2. `ArticleServiceTest`**（Spring 容器 + 事务） | **已有 8 个类**，共 145 个用例：`ArticleDraftServiceTest`(19) / `ArticleAuditServiceTest`(20) / `ArticleDetailServiceTest`(11) / `ArticleLifecycleServiceTest`(15) / `ArticleOwnershipAuthzTest`(14) / `ArticlePageQueryServiceTest`(21) / `ArticlePublishServiceTest`(29) / `ArticleVersionServiceTest`(16) | **不重建** | 手册点名要覆盖的 6 个场景（`saveDraft` 标题空、`updateDraft` 越权、`submitForAudit` 状态非法、`audit` 驳回不填意见、`publishNow` 状态非法、`rollbackVersion` 版本不存在）**全部已被这 8 个类覆盖**，且额外覆盖了乐观锁、唯一索引重试、批量容错、发布清空排期时间等 |
| **3. `ArticleControllerTest`**（MockMvc） | **不存在** —— `grep -rn "MockMvc" src/test/java` 返回 **0 行** | **新建** ✅ | 见 §二 |
| **4. `ArticleQueryTest`**（分页与多条件） | 有单元级 `PageQueryTest`(22) 与 Service 级 `ArticlePageQueryServiceTest`(21)，但都**绕过 Web 绑定** | **新建** ✅（接口层） | 见 §二 |

> **为什么不把 `ArticleServiceTest` 拆成一个类**：本项目 Service 测试按功能域切分（草稿/审核/详情/生命周期/归属/分页/发布/版本），
> 一个类只覆盖一条业务支线。合并成一个大类会带来"跑一次 300 行 setup、失败时定位困难"的代价，
> 而收益只是名字与手册对齐 —— 名字对齐不是工程目标。

---

## 二、为什么必须补"接口层"这一层（本步的技术判断）

**判据（S18 机械扫描重跑结果）**：`MockMvc` / `WebApplicationContext` 在全仓测试里的出现次数 **= 0**。

也就是说，S19 之前所有 254 个用例**都从 Service 层往下**。而下列组件**只存在于 Web 层**，
此前没有任何自动化覆盖：

- `LoginInterceptor`（认证：401）
- `PermissionAspect`（垂直越权：403）
- `@Valid` 参数校验（400）
- `GlobalExceptionHandler`（把异常翻译成统一返回体）
- `JacksonConfig` / `SpringMvcConfig` 的消息转换与**参数绑定**
- `WebLogAspect` 写 `sys_oper_log`

**这不是"覆盖率更好看"，而是"验证对象不同"**。举两个本步实测到的例子：

1. 请求体格式错时，越权角色拿到的是 **400 还是 403**？取决于"参数解析"与"权限切面"的先后。
   实测：**400 优先**（`MethodArgumentNotValidException` 在切面之前抛出）。
   这条结论只有走真实 DispatcherServlet 才测得出来。
2. query string 的时间参数格式 —— 见 §五，**这是本步抓到的真缺陷**。

---

## 三、手册要求"特别说明"的三件事

### 3.1 为什么 `@Transactional` 加在测试类上能自动回滚？什么情况下会失效？

**能回滚的机制**（三步）：

1. `SpringJUnit4ClassRunner` 注册了 `TransactionalTestExecutionListener`。
   它在**每个测试方法开始前**通过 `PlatformTransactionManager` 开一个事务，
   并把该事务的 `Connection` 绑定到**当前线程**（`TransactionSynchronizationManager`）；
   方法结束后**无条件回滚**。
2. 测试方法里的代码要拿到"同一个 `Connection`"，靠的是 `DataSourceUtils.getConnection(dataSource)`：
   它先查线程上有没有该 `DataSource` 的事务绑定，有就复用、没有才新建。
   本项目所有写库路径（MyBatis-Plus Mapper、`JdbcTemplate`）走的都是它，所以自动共享同一事务。
3. **MockMvc 是同线程的**。`mockMvc.perform(...)` 在**测试方法自己的线程**上直接调
   `DispatcherServlet`，不像真实容器那样把请求交给 Tomcat 的工作线程。
   因此整条
   `DispatcherServlet → LoginInterceptor → PermissionAspect → Controller → Service → Mapper → JDBC`
   都在测试事务里，方法结束时一起回滚。

**实测判据**（`target/s19-test-full.txt` 里的日志原文）：

```
DEBUG o.s.j.d.DataSourceTransactionManager - Initiating transaction rollback
DEBUG o.s.j.d.DataSourceTransactionManager - Rolling back JDBC transaction on Connection [...]
INFO  o.s.t.c.t.TransactionContext - Rolled back transaction for test:
      [DefaultTestContext@... testClass = ArticleQueryTest, testMethod = ...]
```

以及**行数快照**（`target/s19-before.txt` vs `target/s19-after.txt`）：17 张表逐表行数完全一致，
总计 **834 → 834**。注意其中 `sys_oper_log` 也是 560 → 560 ——
说明 `WebLogAspect` 写的操作日志**也在同一个事务里被回滚了**（这本身就是"切面在同线程同事务"的旁证）。

**会失效的四种情况**（本项目当前都**没有**踩到，但必须写清楚）：

| 失效场景 | 为什么失效 | 本项目的现状 |
|---|---|---|
| `@Transactional(propagation = REQUIRES_NEW)` | 新事务挂在测试事务之外，提交后测试回滚管不到它 | 全仓 `grep -rn "REQUIRES_NEW\|Propagation\." src/main/java` → **只出现在 3 处注释里**，没有任何代码使用 ✅ |
| 自建线程 / `@Async` / 线程池 | 事务绑定在 ThreadLocal 上，换线程就没有绑定 | 无异步写库；唯一的后台线程是 `ArticlePublishScheduler`，而它被 `SchedulingConfig` 排除在测试上下文之外 ✅ |
| 被测试代码**自己 catch 掉**异常 | 事务拦截器没看到异常 → 不会标记 `rollback-only`，于是"内部已经写进去的东西"在测试事务里仍然存在 | 有，且是**刻意的**：`ArticleServiceImpl` 的批量提交逐条 catch（见 §3.3） |
| 被测方法运行在**真实容器**里 | 容器线程 ≠ 测试线程，两者不是同一个事务 | 本类不用真容器；真机验证在 S16 用 ApiFox 做，判据是"接口返回 + 手工查库" |

> **⚠️ 一个必须记住的推论**：`@Transactional` 回滚会**掩盖"事务真的提交了吗"这件事**。
> 所以本项目**两套写法并存、分工明确**：
> - `ArticleDraftServiceTest` 等在**真库 + `@After` 物理清理**下跑 —— 它要证明的是
>   "改状态与写快照同成功同失败"、"`version` 不匹配时 MP 返回 0 而不抛异常"这类
>   **只有真提交才成立**的性质；
> - 本步新增的两个接口层类用 `@Transactional` 回滚 —— 它要证明的是**Web 层的装配与翻译**，
>   写入本身不是验证对象，回滚换来的"零污染 + 可任意造数据"更值。
>
> 两套不是重复，也不是不一致；是**验证目标不同**（类注释里各自的 Javadoc 都写了这一条）。

### 3.2 单元测试里没有 `HttpServletRequest`，`MetaObjectHandler` 会怎样？会不会让测试失败？

**先纠正手册的一个前提**：手册写的是"会返回 null"。**实测不是 null，是 `0`。**

判据（`auditMetaObjectHandler` 的实际实现 + 实测断言）：

- `AuditMetaObjectHandler.getCurrentUserId()` 走 `CurrentUserArgumentResolver.getCurrentUser()`
  → `RequestContextHolder` → Session；取不到时返回 `null`；
- 但填充时不写 `null`，而是
  `Long auditUserId = currentUserId != null ? currentUserId : SYSTEM_USER_ID;`
  其中 `SYSTEM_USER_ID = 0L`（常量，见该文件第 102 行）；
- 为什么不能填 `null`：`cms_article.create_by` 是 `NOT NULL`。而且 MP 生成的 INSERT
  **会把这一列列进去**，于是显式传 `null` 会让数据库的 `DEFAULT 0` 失效
  → `Column 'create_by' cannot be null`。

**会不会让测试失败？不会。** 这恰好证明主干那个"**可选依赖**"设计是对的：

```
同一个 saveDraft 方法：
  有 HTTP 请求  → create_by = 登录用户 id
  没有 HTTP 请求 → create_by = 0（系统账号）
  两条路径都不抛异常，也不需要调用方感知
```

**反证**：若当初实现成"拿不到登录用户就抛 NPE"，那么
`ArticlePublishScheduler`（定时任务，天然无请求上下文）和**全部 8 个 Service 测试类**都会当场炸掉；
若实现成"拿不到就跳过填充"，则会撞 `create_by NOT NULL`。

**测试该怎么写**：把两种情形**写在同一个用例里**对照断言，而不是"绕开它"。
本项目的做法是 `ArticleControllerTest#testCreateByIsLoginUserOverHttpAndZeroWithoutHttpContext`：

| 步骤 | 调用方式 | 断言 |
|---|---|---|
| ① | 直接调 `articleService.saveDraft(dto, 1L)`（无请求上下文） | `create_by == 0` |
| ② | MockMvc POST，Session 里是 `author(id=1)` | `create_by == 1` |
| ③ | MockMvc POST，Session 里是 `author2(id=5)` | `create_by == 5` |

第 ③ 步是**为了排除"它填的是某个固定值"这种可能** —— 只有换个人它也跟着变，
才能说明它读的确实是"会话里的人"。

> ⚠️ 顺序细节：必须先做 ①（无上下文）再做 ②③。
> 反过来的话要依赖 `FrameworkServlet` 在请求结束后把 `RequestContextHolder` 清干净
> —— 虽然它确实会清，但依赖这个是个不必要的假设。
>
> ⚠️ 类型细节：`create_by` 列是 `BIGINT UNSIGNED`，JDBC 驱动返回的是
> **`BigInteger` 而不是 `Long`**。第一版断言写成
> `assertEquals(Long.valueOf(0L), row.get("create_by"))` 直接失败：
> `expected: java.lang.Long<0> but was: java.math.BigInteger<0>`
> —— 值相等、类型不等。最终统一走 `((Number) v).longValue()` 归一。
> 这与"代码里 `Integer` 不能用 `==` 比"是同一类坑的变体。

### 3.3 测试数据从哪来：复用 `sql/init.sql` 的演示数据，还是每个测试自己造？

**推荐并采纳：造数据 + 事务回滚**（手册的倾向是对的，理由也正确：依赖固定数据集会造成顺序耦合）。
但本项目在这个基础上做了三条更严格的处理：

**① 造数据走 Service，断言走 HTTP，不混用通道。**
`AbstractArticleApiTest#seedArticle(ownerId, targetStatus)` 用 `articleService` 把一篇文章
推到任意目标状态；断言一律用 MockMvc 发真实 HTTP。理由：若准备数据也走 HTTP，
一旦接口退化，大量用例会以"准备阶段失败"的形式报错，把真正的问题埋掉。

**② 凡是"事实性常量"，一概从库表/接口读回来，不手写。**
- 测试里的角色与权限**不写死**，而是调**与生产登录完全相同**的两条 SQL
  （`SysUserMapper#selectRoleCodesByUserId` / `selectPermissionCodesByUserId`）装配 `LoginUser`。
  否则就是"用测试的假设验证测试的假设"：`sys_role_permission` 的种子数据一变，
  403 用例会**继续通过**，而真实权限已经不一样了。
- 期望的条数（如"已发布的文章有几篇"）先 `SELECT COUNT(*)` 查库，再与接口返回的 `total` 比。
- 期望的状态中文描述/颜色取 `ArticleStatus.of(code).getDescription()/getColor()`，不手抄字符串。
- 另有一条**守门用例** `ArticleControllerTest#testPermissionTruthGuard`，
  把"投稿人没有 `article:audit`、编辑没有 `article:publish`、管理员 20 个码"直接钉在库表真值上 ——
  这样"改种子数据"必须同步改测试，也就必须在评审里说明理由。

**③ 保留一小部分"读种子数据"的用例，但把它们写成"与库表对照"而不是"与魔数对照"。**
例如 `ArticleQueryTest#testFilterByStatus` 用 `ArticleStatus.PUBLISHED.getCode()` 去查，
再和 `SELECT COUNT(*) ... WHERE status = ?` 的结果比 —— 种子数据从 2 篇变成 5 篇，用例照样是对的。

**④ 造数据的标题一律带随机串**（`uniqueTitle()` 用 `UUID` 前 8 位）。
原因：`ArticleServiceImpl` 里有一条 `likePattern()` 转义的模糊查询，
用例之间若标题重名，会让"命中数"断言出现随机的假失败。

---

## 四、AI 在本步给错 / 我方判断错、并被实测纠正的地方

> 这一节是手册明确要求记录的。以下每一条都有**实测输出**作为判据，不是事后总结。

| # | 谁错 | 错误内容 | 实测怎么暴露的 | 最终处置 |
|---|---|---|---|---|
| 1 | **AI（初版代码）** | 审计字段断言用 `Long` 比较 JDBC 读回的值 | `AssertionError: expected: java.lang.Long<0> but was: java.math.BigInteger<0>` | 统一用 `((Number) v).longValue()`；并把原因写进 `AbstractArticleApiTest#auditColumnOf` 的注释 |
| 2 | **AI（初版方案）** | 断言响应体准备用 `MockMvcResultMatchers.jsonPath("$.code")` | 本项目只引了 `spring-test`；`com.jayway.jsonpath` 是它的**可选**依赖，不在 classpath 上 → 编译通过、**运行期 `NoClassDefFoundError`** | 改为用项目自带的 Jackson 解析 `getContentAsString(UTF_8)`，顺带还能断言"某个键不存在"（`has("content") == false`） |
| 3 | **AI（初版方案）** | 断言响应用 `getContentAsString()`（不带 charset） | Jackson 转换器写出的 `Content-Type` 不带 `charset`，默认按 ISO-8859-1 解码 → **中文 message 全变问号** | 一律 `getContentAsString(StandardCharsets.UTF_8)`；已写进 `body()` 的注释 |
| 4 | **AI（推理错误）** | 认为"MockMvc 走的是容器线程，`@Transactional` 大概率回滚不了，得改成物理清理" | 实测日志 `Rolled back transaction for test` + 17 表行数 834→834 | 保留 `@Transactional`；把"为什么同线程能回滚"写进基类 Javadoc（§3.1） |
| 5 | **手册（前提错）** | 手册 §二.2 写"没有 `HttpServletRequest` 时 `MetaObjectHandler` 会返回 null" | 实测返回的是 **`0`**（`SYSTEM_USER_ID`），且填 `null` 会撞 `create_by NOT NULL` | 按实际写进 §3.2；测试按"0 vs 登录用户"对照断言 |
| 6 | **我方判断错（一次反复）** | 发现 `beginTime` 传空格格式返回 400 后，第一反应是"用例写错了，把断言改成 400" | 查前端 `ArticleList.vue:90` 确认该 picker 配的是 `value-format="YYYY-MM-DD"`，**本来就该能通过**；先例是 S13 定过"不要在某个接口单独做格式兼容" | 判定为**真缺陷**，修后端 `addFormatters` + 前端补右边界（§五） |
| 7 | **我方判断错** | 一度打算把"`endTime` 补到 23:59:59"也做在后端转换器里 | 逻辑上不成立：**同一个转换器不知道自己是区间左端还是右端** | 转换器只负责"能解析"；右边界语义交给"知道语义的那一层"（前端 setter），并补一条用例把这个坑钉死 |

---

## 五、S19 抓到的真实缺陷：D-09（列表接口时间筛选必 400）

### 5.1 现象（实测）

```
GET /api/v1/articles?page=1&size=5&beginTime=2026-10-14&endTime=2026-10-20
→ HTTP 400
{"code":400,"message":"参数校验失败：beginTime Failed to convert property value of type
 'java.lang.String' to required type 'java.time.LocalDateTime' for property 'beginTime';
 nested exception is org.springframework.core.convert.ConversionFailedException:
 Failed to convert from type [java.lang.String] to type [java.time.LocalDateTime]
 for value [2026-10-14]; nested exception is java.lang.IllegalArgumentException:
 Parse attempt failed for value [2026-10-14]" }
```

### 5.2 根因：项目里有**两条互不相干**的"字符串 → 时间"转换链

| 入口 | 转换器 | 只认什么格式 |
|---|---|---|
| `@RequestBody` 的 JSON（排期时间等） | `JacksonConfig` 注册的 `LocalDateTimeDeserializer` | `yyyy-MM-dd HH:mm:ss`（**空格**分隔） |
| query string 绑定 DTO 属性（列表筛选时间） | Spring `DateTimeFormatterRegistrar` 的默认值 | ISO-8601（`2026-10-14T00:00:00`，**T** 分隔） |

### 5.3 影响面（已逐项确认，窄且明确）

- 后端 query string 上的时间参数**只有** `CmsArticleQueryDTO.beginTime` / `endTime` 两个
  （`grep -rn "LocalDateTime" src/main/java/com/lab/cms/dto/*.java`：另两处 `scheduleTime` 都走 `@RequestBody`）。
- 前端会踩到的**只有**一处：`frontend/src/views/article/ArticleList.vue:90`
  的创建时间范围选择器（`type="daterange" + value-format="YYYY-MM-DD"`），
  它通过 `dateRange` 的 setter 把值写进 `query.beginTime/endTime` 并随列表请求发出。
- `docs/evidence/` 下的 F06 证据脚本**未断言**这个 picker → 修复不会破坏已有验收证据。

### 5.4 为什么之前 254 个用例一个都没发现

S17 的 `ArticlePageQueryServiceTest` 直接 `new CmsArticleQueryDTO()` 然后 `setBeginTime(...)`，
**绕过了 Web 的参数绑定**；`PageQueryTest` 更是纯单元测试。
而 `MockMvc` 在全仓的出现次数是 **0**。
**这条缺陷只存在于"HTTP 绑定"这一层，恰好是当时唯一没有测试的层。**

### 5.5 修复（两层，都是最小改动）

**后端 · 只加方法，不动任何既有签名**

- 新增 `config/StringToLocalDateTimeConverter.java`，接受三种写法：
  `yyyy-MM-dd HH:mm:ss`（项目全局格式，与 `JacksonConfig.DATE_TIME_PATTERN` 同源）、
  `yyyy-MM-dd HH:mm`（容忍少写秒）、`yyyy-MM-dd`（纯日期 → 当日 `00:00:00`）；
  **刻意不接受** ISO 的 `T` 分隔（保持"项目只有一种日期书写法"，与 S13 的口径一致）。
- `config/SpringMvcConfig.java` 加 `addFormatters(FormatterRegistry)` 注册它。
  选全局注册而不是给 DTO 字段加 `@DateTimeFormat`：后者要逐字段写，
  且将来任何新增的 query 时间参数都会重踩一遍同一个坑。

**前端 · 修"右边界语义"（与上一条是两个问题）**

- `ArticleList.vue` 的 `dateRange` setter：左边界补 `00:00:00`，右边界补 `23:59:59`；
  getter 反推时 `slice(0, 10)` 还原成 `YYYY-MM-DD`，picker 显示不变。
- 为什么必须补：`endTime=2026-10-20` 解析成 `2026-10-20 00:00:00` 后，
  SQL 的 `create_time <= ?` **会漏掉 10-20 当天**。用户选"10-14 至 10-20"的
  自然语义是"含 20 日全天"。
- 为什么不能在后端补：转换器不知道自己是区间左端还是右端（这条已作为 AI 判断错误 #7 记录）。

### 5.6 验证

| 判据 | 结果 |
|---|---|
| `testThreeAcceptedTimeFormatsInQueryString`：三种写法各发一次请求 | 全部 **200** |
| `testIsoSeparatorInQueryStringIsRejected`：`2026-10-14T00:00:00` | **400**（口径只有一种） |
| `testGarbageTimeValueReturns400`：`2026/10/14`、`昨天`、`25:00:00` | 全部 **400**（不是 500） |
| `testBlankTimeValueIsTolerated`：`?beginTime=` | **200**（空串按"没传"处理） |
| `testPureDateEndTimeMissesSameDayRecords` | 纯日期右边界 → 命中 **0**；补到 `23:59:59` → 命中 **1**（右边界语义的硬证据） |
| `testReversedRangeReturns400`：`begin` 晚于 `end` | **400** |
| 全量回归 | **312 / 312 全绿**（改动未破坏任何既有用例） |

---

## 六、本步的测试规模与运行方式

| 指标 | 值 |
|---|---|
| 测试类总数 | 20（原 18 + 新 2） |
| 用例总数 | **312**（原 254 + 新 58：`ArticleControllerTest` 33 + `ArticleQueryTest` 25） |
| 新增代码行数 | 基类 ~300 行 + 两个测试类 ~1,050 行（注释占比高，全部为"为什么这么断言"） |

### 运行命令

> ⚠️ 必须用 Maven **3.9.6** 的完整路径。PATH 上的 `mvn` 是 4.0.0-rc-5，与本项目不兼容。

```bash
M="C:/Users/admin/.m2/wrapper/dists/apache-maven-3.9.6-bin/439sdfsg2nbdob9ciift5h5nse/apache-maven-3.9.6/bin/mvn.cmd"

# 跑全部测试
"$M" -o test

# 只跑一个测试类
"$M" -o test -Dtest=ArticleControllerTest

# 只跑一个测试类里的一个方法
"$M" -o test -Dtest='ArticleControllerTest#testCreateByIsLoginUserOverHttpAndZeroWithoutHttpContext'

# 跑多个类（逗号分隔，不能有空格）
"$M" -o test -Dtest='ArticleControllerTest,ArticleQueryTest'

# 打乱执行顺序复跑（验证"不依赖执行顺序"）
"$M" -o test -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=20261104
"$M" -o test -Dsurefire.runOrder=reversealphabetical
```

### 行数快照（证明回滚生效）

```bash
PY="C:/Users/admin/.workbuddy/binaries/python/versions/3.13.12/python.exe"
"$PY" target/s19_counts.py > target/s19-before.txt      # 测试前
"$M" -o test
"$PY" target/s19_counts.py > target/s19-after.txt       # 测试后
diff target/s19-before.txt target/s19-after.txt          # 无输出 = 逐表行数完全一致
```

实测输出（17 张表，总计 834）：

```
cms_article                     20      sys_permission                20
cms_article_audit_log           12      sys_report                     1
cms_article_index                3      sys_role                       4
cms_article_tag                 16      sys_role_permission           48
cms_article_version             10      sys_user                       5
cms_category                     9      sys_user_role                  5
cms_category_audit_log           0      sys_login_log                110
cms_tag                         10      sys_oper_log                 560
sys_apply_approval               1      # TOTAL                      834
```

> 脚本刻意**不用** `information_schema.TABLE_ROWS` —— InnoDB 下那是估算值，
> 会随统计信息刷新而变，用它做"前后一致"的判据会给出假失败或假通过。
> 这里对每张表真跑 `COUNT(*)`。

### 乱序复跑（证明"不依赖测试执行顺序"）

⚠️ **口径说明**：Surefire 的 `runOrder` 打乱的是**测试类之间的顺序**，
不是类内部方法的顺序（JUnit 4 的方法顺序由 `MethodSorters` 决定）。
所以下面的证据只能支撑"类间无顺序耦合"这一条 —— 不夸大。

三种顺序实测**两两不同**（每次都是 312/312 全绿）：

| 运行 | 前 6 个类 | 结果 |
|---|---|---|
| 默认（`filesystem`） | `MpPluginIntegrationTest` → `ArticleStateMachineTest` → `AuditEnumTest` → `PageQueryTest` → `PageResultTest` → `ResultTest` | 312/312 ✅ |
| `random` + `seed=20261104` | `ArticleControllerTest` → `PageResultTest` → `ArticlePageQueryServiceTest` → `ArticleDraftServiceTest` → `ArticleAuditServiceTest` → `ArticleVersionServiceTest` | 312/312 ✅ |
| `reversealphabetical` | `VersionSnapshotDuplicateKeyTest` → `ArticleVersionServiceTest` → `ArticlePublishServiceTest` → `ArticlePageQueryServiceTest` → `ArticleOwnershipAuthzTest` → `ArticleLifecycleServiceTest` | 312/312 ✅ |

> 第二行值得单独看一眼：`random` 把**整轮里最重的库测试** `ArticleControllerTest`（33 个用例、
> 每条都要起 Spring 上下文并写库）排在了**第一个**，且依然全绿 ——
> 这比"换个顺序也能过"更有说服力：它证明了本步新增的用例**不依赖任何别的测试留下的数据**。

另外，**本步新增的两个类在方法级别也是独立的**，判据是设计而非运气：
每个用例要么 `@Transactional` 回滚（接口层两类），要么自己造数据并自己清理（Service 类），
没有一处读取"上一个用例留下的状态"。

### IDEA 里的中文菜单路径（本机无 IDEA 可执行文件，此为通用路径）

- 跑整个测试类：打开测试类 → 左侧行号槽点绿色三角 → `运行 'XxxTest'`（`Ctrl+Shift+F10`）
- 跑单个方法：方法名左侧绿色三角 → `运行 '方法名()'`
- 跑全部：右键 `src/test/java` → `运行 '所有测试'`
- 看结果：底部 `运行` 工具窗口 → 左侧树形展开到方法 → 失败项双击跳到断言行

---

## 七、与手册的偏差（6 条）

| # | 手册要求 | 本项目实际 | 原因 |
|---|---|---|---|
| 1 | 建 `ArticleStateMachineTest` | **不重建**，已有且是超集（100 组合 vs 手册的 30） | 避免重复建设 |
| 2 | 建单个 `ArticleServiceTest` | 已有 8 个按业务域切分的类，共 145 例 | 一个类只覆盖一条业务支线，失败定位更快 |
| 3 | 测试类上 `@Transactional + @Rollback(true)` | 接口层两类用 `@Transactional`；Service 的 8 个类**刻意不用** | Service 测试要验证"事务真的提交了"，回滚会掩盖它 |
| 4 | 用 `MockMvcResultMatchers.jsonPath` 断言（手册未指定，但这是最常见写法） | 改用 Jackson 解析响应体 | 本项目未引 `com.jayway.jsonpath`（spring-test 的可选依赖），用了会运行期 `NoClassDefFoundError` |
| 5 | 手册 §二.2 称 `MetaObjectHandler` 无请求上下文时返回 `null` | 实测返回 **`0`** | 填 `null` 会违反 `create_by NOT NULL`；以代码与实测为准 |
| 6 | 手册未提及 query string 的时间格式 | **本步发现是真缺陷并修复**（D-09） | 手册的四类测试里没有"Web 参数绑定"这一类，而缺陷恰好在那里 |

---

## 八、本步没有做的事（如实登记）

| 事项 | 为什么不做 | 归属 |
|---|---|---|
| 阿里规约插件 `Alibaba Java Coding Guidelines` 报告 | **本机没有 IDEA 可执行文件**（只有配置目录 `C:\Users\admin\AppData\Local\JetBrains\IntelliJIdea2025.3`，无 `idea64.exe`） | 需在有 IDEA 的机器上补；替代证据见 `docs/重构记录.md` §五 |
| 引入 JaCoCo 做行覆盖率数字 | 课程未要求；且"覆盖率高"与"断言有意义"是两件事。本步的取舍是"每条断言都能说出为什么" | 留作可选 |
| 测试 `ArticlePublishScheduler` 的定时触发 | 需要在测试上下文里开 `@EnableScheduling`，而那正是 S13 踩过的坑（后台线程会真改数据 → 种子 id=14 被顺手发布、偶发失败）。定时发布改用"直接调 `publishScheduled(id)` + `findDueScheduledArticleIds(deadline)`"验证，已在 `ArticlePublishServiceTest` 覆盖 | 维持现状 |
| 给 `ArticleControllerTest` 补全部 15 个接口的正向调用 | 本步已覆盖 13 个（唯一没走 HTTP 正向的是"批量提交的 100 条上限"与"归档前的下架链路"反向组合）；**完整的 15 接口正向回归是 S20 的范围** | S20 |

---

# 第二部分 · S20（回归测试与规约自检）

> 第 25 天（11-06）。手册 S20 = **★M5 回归通过**。完整结果见 `docs/回归测试报告.md`。
> 本部分只记 **AI 协作痕迹** 与 **如实登记的偏差 / 未做的事**，不重复抄报告里的实测数据。

## 九、本步做了什么（一页摘要）

| # | 事项 | 结果 |
|---|---|---|
| 1 | 手册 12 项回归清单逐条执行 | 全部执行、逐项有记录，**合计 496 条断言 FAIL = 0** |
| 2 | 单测（回归入场券） | `mvn -o test` → **321/321**（S19 的 312 + 本步新增 9 条删除用例） |
| 3 | 真机接口 / 状态机 / 权限 / 分页 / 逻辑删除 / 版本 / 跨域 | `s20_regress.py` → **132 PASS / 0 FAIL** |
| 4 | 事务回滚（确定性失败点） | `s20_tx.py` → **9/9**（600 字意见触发 1406，验"状态回滚 + 流水不留半条"） |
| 5 | 排期定时到点自动发布 | `s20_schedule.py` → **7/7**，实测**等待 130 秒后无人干预自动发布** |
| 6 | 并发（双账号发布 / 双会话改稿） | `s20_concurrent.py` → **5/5**，**绝不双写成功**；顺带关闭 D-05 |
| 7 | 前端完整用户路径（真 Chrome + CDP） | `s20-frontend.mjs` → **32/32**，**不伪造任何接口** |
| 8 | ApiFox E 组回填重跑 | `s20_apifox_e.py` → **24 PASS / 0 FAIL**（关闭 D-01 尾巴） |
| 9 | 《企业开发规范清单》56 条自检 | 通过 **46** / 未通过 **3** / 需人工 **6** / 不适用 **1**（3 条逐条给了理由与计划） |
| 10 | **发现并当轮修复 4 项缺陷** | **D-10**（登录失败日志被事务回滚，严重）、**D-11**（`login_status` 口径，一般）、**D-12**（SPA 深链全 404，严重）、**D-13**（`DELETE` 接口缺失，严重）；另登记观察项 **D-14**（轻微） |
| 11 | 主干改动 | **只加方法**：`SpringMvcConfig#addViewControllers`（D-12）；新增 `LoginLogService` / `LoginLogServiceImpl`；未改任何既有签名 |

## 十、AI 在本步给错 / 我方判断错、并被实测纠正的地方

> 与 §四 同一口径：每条都有实测判据，不是事后总结。
> ⚠️ 本步 8 条里有 **3 条是"AI 给了一个看起来最自然的方案，但它在细节上是错的"**（#1 / #3 / #7），
> 这类错误不会编译报错，只会**静默地少写一列、多算一个数、报一个假的通过**。

| # | 谁错 | 错误内容 | 实测怎么暴露的 | 最终处置 |
|---|---|---|---|---|
| 1 | **AI（API 选型错）** | 逻辑删除直接用一个方法搞定（MP 的 `removeById` / `deleteById`）—— 看起来"一件事就该一个方法" | 查 MP 生成 SQL 的 SET 子句：**只有 `deleted = 1` 一列**，`update_by / update_time` 不会被 `MetaObjectHandler` 填充 → 删完之后**库里看不出是谁删的** | 改用**显式列列**的 `LambdaUpdateWrapper.set(deleted/updateBy/updateTime)`，并在 WHERE 里显式带上 `deleted = 0`，受影响行数≠1 即报错。这条已写进 `.set` 处的注释与 `docs/回归测试报告.md` §4.4 |
| 2 | **我方判断错（外推规则）** | 写排期断言时以为 `schedule_time` 在发布后会**保留** —— 依据是 S14 定过"撤回 / 下架刻意不清 `publish_time`" | 实测是 `NULL`。读 `ArticleServiceImpl` 确认"**发布完成后排期时间使命结束、清空**"是**设计**，不是漏清 | **改的是断言，不是代码**。教训：**S14 的"不清"是"下架后仍要能查到原发布时间"这个业务理由；发布场景没有这个理由** —— 把一条规则外推到另一个场景前，先问它当初为什么成立 |
| 3 | **AI（自检脚本正则错）** | 用正则统计"`ArticleController` 有几个端点 / 几个权限注解"，直接搜 `@GetMapping` 这类字面量 | 自检结果突然从"通过"变"未通过"：报 `18 个端点里 16 个带注解` —— 而真实端点是 **16** 个。多出来的 2 个是 **Javadoc 里的反面示例** `{@code @GetMapping("/{id}")}` | 正则**锚行首**：`(?m)^\s*@(?:Get\|Post\|Put\|Delete)Mapping`。并加通例：**凡是"从源码数数"的断言，都要先排除 Javadoc / 注释里的示例** |
| 4 | **我方漏环节** | 前端 E2E 第一版以为"编辑初审"之后就能直接点「立即发布」 | 实测 `btn-not-found`，该行只看到 `["查看","审核"]`（状态停在 2） | 补上**终审**环节后一次通过。⚠️ 这个"漏"反而是**正面证据**：它证明"按钮集合完全由当前状态推导"—— 状态没到，按钮就不存在。已写进 `docs/回归测试报告.md` §2.12 |
| 5 | **AI（断言口径写错）** | 登录失败的断言写成"`login_status = 0` 的条数 +1" | 断言不过 → 顺藤摸出**两层**问题：口径本身是错的（**D-11**），且失败日志**根本没写进库**（**D-10**） | 两层一起修：口径统一到库表 `1 成功 / 2 失败`。教训：**"条数没变"和"值不对"必须分开查** —— 混在一起会把"没写进去"误判成"写了但值不匹配" |
| 6 | **我方判断错（假失败）** | `mvn -o test` 报 `ArticlePageQueryServiceTest.testStatusFilter`「应当能筛到已发布的种子文章」失败，第一反应是"断言或代码被改坏了" | 查库：真机回归造的十几篇 `status=5` 文章按 `create_time DESC` 把**种子文章挤出了第一页**。**代码和断言都没错** | 跑 `target/s20_clean.sql` 清理后重跑 → BUILD SUCCESS。并把它固化为**标准动作顺序：真机回归 → 清理 → 单测 → 打包**。教训：**"先怀疑被测对象"之前，先怀疑测试环境里的残留数据** |
| 7 | **AI（初版证据脚本）** | 前端动作矩阵的证据脚本里写 `getRowActions(...).join(',')` | 1 条 FAIL：函数返回的是**对象数组**（含 `key`/`label`/`perm`），不是字符串数组 | 改为 `.map((a) => a.key).join(',')`。提醒：**改了函数返回结构，所有 .join / 字符串拼接的调用点都要跟着改** |
| 8 | **AI（结果转换正则）** | 把自检的 TSV 结果转 Markdown 表时用 `(.*?)\t` 之外的贪婪写法 | 生成的表**列错位**：`条目` 列里混进了"怎么查"的内容 | 改**非贪婪** `(.*?)\t`，并加"字段数不足时回退"的分支。⚠️ 这条与 #3 是同一类：**解析文本时默认要假设有噪声** |

> ⚠️ **本步最值得留下的一条判断**：D-13（`DELETE /articles/{id}` 缺失）之所以能挂一整个周期，
> 是因为**每一步的"全绿"都是真的** —— S16 用例集里没有这条、S17 分页检索不涉及、
> S18 逻辑删除只验了"库表 + MP 插件行为"（手工 `UPDATE deleted=1`）、S19 单测只测已存在的方法。
> **"没有用例"和"用例通过"是两件事。** 回归时必须先做一步**对账**：
> 拿 `docs/接口契约.md` + 手册要求的**端点集**，去比 `ArticleController` 里**实际存在的端点集**，
> 差集就是"从来没人测过、甚至没实现"的盲区。这一步在本步被补上（R1 的 16/16 正向 + 自检 5-4）。

## 十一、与手册的偏差、以及本步没有做的事（如实登记）

**与手册的偏差（4 条）**

| # | 手册要求 | 本项目实际 | 原因 |
|---|---|---|---|
| 1 | 手册写"**15 个**文章接口"（S16 起沿用） | 实际 **16 个**（S20 补齐 `DELETE` 后） | 与代码对账的结果；已在 `docs/回归测试报告.md` §一 与 `docs/ApiFox用例集.md` 登记 |
| 2 | 手册 S14 说已下架可以"**重新发布**" | 状态机里 `OFFLINE(8)` **只有 `ARCHIVE` 一条出边**（无 `PUBLISH_NOW`） | **以代码为准**（下架不可逆是有意设计）；F06 已纠正一次，S20 回归再次确认（R2/R3 都覆盖） |
| 3 | 手册 S20 只讲"回归测试"，未提"与契约对账端点集" | 本步**补了这一步**，并且正是它覆盖范围之外的地方抓到了 D-13 | D-13 挂了一整个周期，说明"只回归已有用例"会漏掉"压根没实现的东西" |
| 4 | 手册未把"事务边界 / MVC 路由兜底 / Web 参数绑定"列为回归项 | 本步的 4 项缺陷（D-10 ~ D-13）与 S19 的 D-09 **全部落在这三类上** | 它们**不是业务逻辑**，任何只覆盖业务逻辑的用例集都发现不了；已在定级口径里补"**功能全绿但实际不可用**"一条 |

**本步没有做的事**

| 事项 | 为什么不做 | 归属 |
|---|---|---|
| 阿里规约插件报告 / "格式化后 diff 为空" | **本机没有 IDEA 可执行文件**（只有配置目录，无 `idea64.exe`），与 S19 同一原因 | 6 条"需人工"如实登记在 `docs/企业开发规范清单.md` 附录 B；替代判据见 `docs/回归测试报告.md` §6.2 |
| `1-9` 两个大类的拆分（`ArticleService` 778 行 / `ArticleServiceImpl` 2239 行） | 拆成多个 `*ServiceImpl` 会破坏"一个领域一个 Service"，并让 `@Transactional` 边界变复杂；**属重构而非缺陷修复** | 成员A，结题前按"草稿/审核/发布/版本"四子域抽 `private` 协作者类（接口不变） |
| `2-7` 冻结主干里 1 处裸判空 | 该处位于 `config`/`common`，按"**主干只加不改**"的纪律不擅自动 | 组内确认后再定 |
| `4-5` 六个 `*_id` 列补索引 | 加索引要**同时改 `sql/init.sql` 与业务库**，属 DDL 变更，不宜散在回归步里做 | 成员A/B/C/D，随"索引优化"一步统一补 |
| `D-14` 别人的草稿行操作列为空 | 两个处置方向都涉及**改 F06 的 40 格动作矩阵或改手册口径**，需组内定 | 组内；处置建议见 `docs/回归测试报告.md` §4.5 |
| 引入 JaCoCo 做覆盖率数字 | 与 S19 同一取舍："覆盖率高"与"断言有意义"是两件事 | 留作可选 |

---

# 第三部分 · S21（结题报告、时序图与统稿）

> 第 26~27 天（11-09 ~ 11-10）。本步产出三份文档，**没有写业务代码**。
> 但"用 AI 写文档"恰恰是 AI 最容易把**没实现的东西写成已实现**的地方，所以这一部分的
> "人工校验"记录反而更多。

## 十二、本步做了什么（一页摘要）

| # | 事项 | 结果 |
|---|---|---|
| 1 | `docs/接口清单.md`（新） | **28 个端点**（文章 16 / 认证 3 / 健康与自检 9），逐条与 Controller 代码核对；含权限码矩阵与 7 条契约偏差 |
| 2 | `docs/结题报告.md`（新，主交付物） | 七章 + **6 张 mermaid 图**（状态机 / 架构 / 鉴权链路 / 日志链路 / 发布全流程 / 排期任务）；四域结构一致，三个域留占位 |
| 3 | `docs/部署文档.md`（新） | 环境要求 → 建库 → 配 `db.properties` → Maven → 构建打包 → 部署 → 访客验证 → **10 类启动问题排查** |
| 4 | 技术难点 | 写了 **7 条**（手册要求 ≥5），每条按"现象 / 排查 / 根因 / 解决 / **验证结果**"五段 |
| 5 | 缺陷统计口径 | 把 D-01~D-14 按"已闭环 10 / 跨模块交接 1 / 待组内定 3 / UI 观察 1"重新归口，并写明**无致命缺陷** |

## 十三、AI 产出被人工校验发现的问题（本步 5 条）

> 本步 AI 主要负责"组织成专业表述"与"写取数脚本"，问题集中在**素材与实际不符**和
> **脚本的隐含假设错了**这两类。

| # | 谁错 | 错误内容 | 怎么暴露的 | 最终处置 |
|---|---|---|---|---|
| 1 | **AI（脚本假设错）** | 用脚本扫 Controller 端点时，**只在 mapping 注解的上一行**找 `@RequirePermission` | 扫出来 **16 个端点的 `perm` 全是 `-`** —— 与"文章域 9 个权限码零豁免"的既有结论矛盾 | 实际格式是**注解在 mapping 的下方**（`@GetMapping` 换行后才是 `@RequirePermission`）。改为在 mapping 后 5 行内查找。<br/>⚠️ 教训：**"扫出来是空"要当成脚本错了去查，不能当成代码真的没注解** |
| 2 | **手册（素材与实现不符）** | 手册 S22 写"验证 **14 张**表都建出来了" | 读 `sql/init.sql`：`CREATE TABLE` 语句 **17 条** | 按实际写 **17 张**并列出表名清单，差异登记在 `docs/部署文档.md` §2.3 与 `docs/结题报告.md` §3.3 |
| 3 | **手册（素材与实现不符）** | 手册 S21 的技术难点素材第 6 条写"`MetaObjectHandler` 改为**可选依赖**" | 读 `base/AuditMetaObjectHandler.java`：实际做法是 `getCurrentUserId()` **捕获异常返回 null** + 拿不到用户时 `createBy/updateBy` 填**哨兵值 0**（不是"可选依赖"这个说法） | **按代码写**，不照抄素材。写成"排期任务无请求上下文 → 捕获异常不影响业务 + 填 `SYSTEM_USER_ID=0`"，并用实测判据（`publisher_id=0`）作验证结果 |
| 4 | **手册（文件名不符）** | 手册 S21 要求"改 `jdbc.properties`" | 读工程：实际读的是 `src/main/resources/db.properties`（`.gitignore` 里两个名字都排除了，但代码只用前者） | 文档里写成 `db.properties`，并明确标注"**不是指导书里写的 `jdbc.properties`**" |
| 5 | **我方判断错（自检项没满足）** | 第一版结题报告只**内联 4 张** mermaid 图，另 2 张（鉴权链路、日志链路）用"见 `docs/联调报告.md` §1.1/§1.2"引用替代，理由是"避免两份文档口径漂移" | 对着手册 S21 的核查项逐条过："**5 张以上 mermaid 图都能渲染**" —— 引用不算"能渲染" | 把两张链路图**内联**进 `§3.5`（并按 S16 的实测结论重写，不是复制粘贴），最终 **6 张全部内联**；另加一条渲染注意：`Result<T>` 必须写成 `Result&lt;T&gt;`，否则被当成 HTML 标签吃掉 |

**本步的取数脚本（都可复现）**

```bash
# 1) 扫全部 Controller 的端点 + 权限注解 + 完整方法签名（多行签名需先合并换行）
#    见 docs/接口清单.md §5.1 说明；关键点：权限注解在 mapping 的【下方】
# 2) 统计建表数（判据是 17，不是手册写的 14）
grep -c "^CREATE TABLE" sql/init.sql
# 3) 抽依赖版本矩阵
#    见 docs/结题报告.md §5.1；关键点：Lombok 1.18.48 + <arg>-proc:full</arg>
# 4) 校验文档结构（栅栏配对 / mermaid 图数 / 行尾）
#    要求：CRLF=0、代码栅栏数为偶数、mermaid 图 ≥5
```

> ⚠️ **顺带一条沿用自 S20 的纪律**：本步写文档时**仍然只用文本模式读、二进制模式写**
> （`open(p,'rb')` 读、`open(p,'wb')` 写），避免 Windows 下把整份文档的 `\n` 变成 `\r\n`
> 造出"整文件假 diff"。三份新文档实测 **CRLF = 0**。

**结论**：AI 在"把素材组织成专业表述"上很有效，但**素材本身可能不是真的**（手册三处与实现不符）。
凡是"AI 说本项目做了 X"，都要先回代码/库表确认 X 真的存在 —— 这与本文件 §十一 的
"**"没有用例"和"用例通过"是两件事**"是同一类纪律：**"文档里写了"和"代码里有"也是两件事**。

---

# 第四部分 · S22（部署联测与环境校验）

> 第 28 天（11-11）。这一步**没写一行业务代码**，全部工作是"把已经写完的东西真的装起来跑一遍"。
> 恰恰是这种"我以为它没问题"的步骤，最容易暴露**文档与实现不一致**和**脚本的隐含假设错了**。
> 本步抓到 4 个自己的错，其中 1 个（接口清单白名单）是 S21 留下的错误结论。

## 十四、本步做了什么（一页摘要）

| # | 事项 | 结果 |
|---|---|---|
| 1 | 建库（`sql/init.sql`） | 幂等执行，**全绿**：17 张表 / 5 用户 / 4 角色 / 20 权限 / 9 分类 / 10 标签 / 20 文章 / 12 流水 / 10 版本；10 个状态分布与设计一致 |
| 2 | 前端构建 | `npm run build` → **✓ built in 3.48s**；产物 `index.html` + `assets/` **71 个文件** |
| 3 | 后端打包 | `mvn -o -DskipTests package` → **BUILD SUCCESS**；`cms-mis.war` **25,606,903 字节** |
| 4 | 部署 Tomcat 9 并启动 | 三个启动判据全过；Spring 上下文 **2686 ms**；8080 在启动后 **5 秒**内监听 |
| 5 | 部署校验 | **44 项 PASS / 0 FAIL**（静态资源与深链 9 / API 边界 5 / 白名单与探针 9 / 登录会话中文 5 / 业务接口 8 / 登录态探针 6 / 登出 2） |
| 6 | 排期定时任务真机验证 | **12 项 PASS / 0 FAIL**：自建文章走完整链路 → 排期 → **110 秒后无人干预自动发布**，`publisher_id=0`（系统触发）、`schedule_time` 被清空 |
| 7 | **"数据库不可用"故障演练**（手册特别要求） | 容器**仍能正常启动**（懒初始化）；查库请求挂 **120 秒**；`system/health` 在库全挂时**仍报 UP**；8 分钟内日志涨 **3.49 MB / 40,270 行** |
| 8 | 文档产出 | 新增 `docs/部署记录.md`（含 **14 项"部署后必查清单"**）；修正 `docs/接口清单.md` 的放行白名单结论 |
| 9 | 数据复原与收尾 | 清理自建数据 → 核对基线 → 重跑 `init.sql` → 停 Tomcat，不留后台常驻任务 |

## 十五、AI 给错 / 我方判断错、并被实测纠正的地方（本步 4 条）

> 本步的错误有个共同特征：**都是"从工具/文档输出直接跳到结论"，中间少了"回源头确认"这一跳**。

| # | 谁错 | 错误内容 | 怎么暴露的 | 最终处置 |
|---|---|---|---|---|
| 1 | **我方判断错（断言写反了）** | 部署校验脚本里断言"未登录访问 `health/db-roundtrip` 应该通"，理由是"它权限标识写的是无" | 实跑 **1 项 FAIL**：该请求返回 **401**。排查后确认 **401 是对的、断言是错的** —— `LoginInterceptor` 白名单按最小必要划，只有 `/auth/**`、`system/health`、`health/ping`、`/error`、`favicon.ico` 放行 | 断言改成"**登录后**通（验证连接池 + SQL 通路）"，并**补 4 条反向断言**"未登录必须 401"（防误放行）。<br/>⚠️ 教训：**"权限标识：无"≠"免登录"**，前者只表示不校验细粒度权限码 |
| 2 | **AI（脚本 bug）** | `target/dbsql.py` 的 `-e` 模式带了 `-D cms_db`，**文件模式没带** | 跑清理脚本报 `ERROR 1046 (3D000) No database selected`。**此前一直没暴露**，因为跑的 `init.sql` 自带 `CREATE DATABASE`/`USE` 恰好掩盖了它 | 抽出 `db_name()` 函数（同一个正则取库名，避开 `serverTimezone=Asia/Shanghai` 的坑），**文件模式也强制 `-D <db>`** |
| 3 | **我方操作错（漏参数）** | 做故障演练时手工敲的启动命令**没带 `JAVA_HOME`**，结果应用跑在 PATH 上的 **JDK 25** 上（`system/health` 报 `javaVersion=25.0.2`），与部署基线 `21.0.12.1` 不符 | 演练中途查 `system/health` 时发现版本号不对 | 重启时显式带上 `JAVA_HOME="D:\develop\jdk\jdk-21.0.12.1+1"`，`javaVersion` 回到 `21.0.12.1`。<br/>⚠️ 教训：**环境变量不是"继承了就对"**，每次启动都要显式声明并**用 `system/health` 回读确认** |
| 4 | **我方判断错（路径假设）** | 以为可以像在 Linux 上那样用 `python -c "open('/tmp/x.json')"` 读 Git Bash 的临时文件 | `FileNotFoundError` —— Windows 版 Python 把 `/tmp` 解析成 `C:\tmp`，而 Git Bash 的 `/tmp` 是另一处 | 改用 shell 工具（`head`/`cat`）读，或让校验脚本**自己用 `urllib` 发请求**（`target/s22_*.py` 就是这么写的） |

**本步的额外收获：故障演练的"实测 vs 直觉"**

做之前对"数据库挂了应用会怎样"的直觉是"**启动就失败**"。实测结论完全不同：

| 直觉 | 实测 |
|---|---|
| 容器起不来 | **容器正常起**（`Root WebApplicationContext initialized in 3411 ms`），因为 `JdbcConfig` **刻意不调** `init()`，Druid 懒初始化 |
| 用户请求先发现故障 | **定时任务先发现**：启动后 13 秒，`[pool-2-thread-1]` 的排期任务撞上 `Communications link failure` |
| 健康检查会报 DOWN | `system/health` **仍报 `status:"UP"`** —— 它压根不探测数据库 |
| 失败会"快速失败" | 请求挂 **120003 ms**（`GetConnectionTimeoutException: wait millis 60001 ... createErrorCount 569`），客户端 110s/200s 两次都没等到响应 |
| 影响可控 | 后台每 **500 ms** 打一条带完整堆栈的 ERROR，8 分钟日志涨 **3.49 MB**（约 430 KB/分钟、25 MB/小时） |

> ⚠️ 这条最有价值：**"看起来合理"的推断必须实测**。`createErrorCount 569 × 0.5s ≈ 285s`
> 正好等于"首次失败到请求超时"的间隔 —— 数字对上了，机制才算真的搞清楚了，
> 而不是"猜是重试导致的"。

## 十六、与手册的偏差、以及本步没有做的事（如实登记）

### 16.1 手册 S22 的三件特别验证，第 3 件换了做法

手册原文第 3 件：**"把 MySQL 服务停掉再启动 Tomcat，应用会怎样？"**

实际做法：**没有停 MySQL 服务**，而是把**已解包部署目录**里的连接端口从 `3306` 临时改成不存在的 `3399`。

- **为什么**：`net stop MySQL95` 需要管理员权限（当前 shell 非管理员），且**停用户的系统服务本身有风险**。
- **等价性**：对应用来说，"连 3399 被拒"与"MySQL 停了"的现象完全一致（都是 `Communications link failure`），
  **验证目标（连接池初始化失败会不会拖垮启动）完全相同**。
- **代价**：要在文档里说明用的是哪种手段。已写进 `docs/部署记录.md` §8.2，没有伪装成"停了服务"。

### 16.2 本步没有做的事

| # | 没做的事 | 原因 / 去向 |
|---|---|---|
| 1 | 没跑全量单元测试 | S20 已跑过（21 个类 / 321 用例 / 496 条断言，FAIL=0）；S22 是部署联测，不重复 |
| 2 | 没验证集群/多实例部署 | 课程范围是单机 Tomcat，且 Session 存在单机内存里，多实例需要额外方案 |
| 3 | 没把 §8.5 的 5 条容错建议落地 | 属于**改进项**不是缺陷；其中"改成 `@Bean(initMethod="init")`"会改变"数据库可后启动"的设计取舍，演示场景下**保留原设计**，只把建议写进文档 |
| 4 | 没动前端任何代码 | 44 项校验里前端部分是"验证产物与 SPA 深链"，全部通过，无需改 |
| 5 | 没删 `target/` 下的助手脚本与日志 | 它们**不入库**（`target/` 在 gitignore 里），作为过程证据留在本机 |

**结论**：S22 的含金量不在"跑通了"，而在"**把三种失败形态都逼出来了**" ——
启动阶段的（懒初始化掩盖）、运行阶段的（120 秒挂起）、观测阶段的（健康检查报 UP、日志刷爆）。
这三样如果不实测，答辩时被问"数据库挂了会怎样"只能答"应该起不来吧"，那是错的。

**顺带一条纪律（延续前三部分）**：本步写文档依然是**文本模式读、二进制模式写**（`rb`/`wb`），
两份改动文档实测 **CRLF = 0**；`init.sql` 重跑前先备份 `db.properties`、改完**立刻还原并回读确认**
（`3306` 出现 1 次、`3399` 残留 0），避免实验改动留在部署产物里。
