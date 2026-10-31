# S17 SQL 优化：N+1 治理与索引命中 · 实测记录

> 日期：2026-10-31（第 21 天）· 手册步骤：S17 · WBS A-09
> 本步产出：`GET /api/v1/articles` 分页检索（补齐 S16 的能力缺口 **D-01**）+ 索引 2 处（**1 增 1 换**）
> + `ArticlePageQueryServiceTest`（21 例）+ 本文档
> **每条结论都有实测依据；手册口径与实测不一致的地方在 §九 逐条登记。**

---

## 一、产出清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `service/ArticleService.java` | 改 | 追加"十、分页检索（S17）"接口方法 |
| `service/impl/ArticleServiceImpl.java` | 改 | `pageArticles` + `likePattern` + `selectCategoryNames` + `LIST_SORTABLE_COLUMNS` |
| `controller/ArticleController.java` | 改 | `GET /articles`（**路径为空**，`article:view`，**不加** `@OperLog`） |
| `test/.../ArticlePageQueryServiceTest.java` | **新增** | **21 条**集成测试（真库 + `JdbcTemplate` 物理清理） |
| `sql/init.sql` | 改 | `cms_article` 增 `idx_deleted_create_time`；`cms_article_audit_log` 的 `idx_article_stage` → `idx_article_time` |
| `docs/接口分类与鉴权表.md` | 改 | 第 8 行"⏳ 未实现" → **S17 已实现**；统计 14→15 |
| `docs/联调报告.md` | 改 | D-01 复验结果 |
| `docs/Git提交规约.md` | 改 | 进度表补 S17 |
| `target/dbpipe.py` | **新增（不入库）** | `init.sql` 728 行超过 Windows 命令行上限，改为从 stdin 喂 |

**没有新增任何 Java 依赖、没有改任何冻结签名、没有动主干。**

---

## 二、怎么定位问题（手册【一】逐条落实）

### 2.1 开启 MyBatis 的 SQL 日志并读耗时

`src/main/resources/logback.xml` 里已经有：

```xml
<logger name="com.lab.cms.mapper" level="DEBUG"/>
```

判据不是"日志文件里有字"，而是 **`Preparing:` 这一行**。本步全程用它做证据，例如：

```
18:51:12.959 [http-nio-8080-exec-9] DEBUG c.l.c.m.C.selectList_mpCount - ==>  Preparing: SELECT COUNT(*) AS total FROM cms_article WHERE deleted = 0
18:51:12.959 [http-nio-8080-exec-9] DEBUG c.l.c.m.C.selectList_mpCount - ==> Parameters:
18:51:12.960 [http-nio-8080-exec-9] DEBUG c.l.c.m.C.selectList_mpCount - <==      Total: 1
```

> ⚠️ **实测踩到的坑（S17 新增）**：`logback.xml` 的 `LOG_PATH` 是**相对路径 `./logs`**，
> 所以日志落在 **Tomcat 进程的工作目录**下，和 `CATALINA_HOME` 无关。
> 本步第一次启动时没 `cd` 到工程根，日志落到了启动目录的 `logs/`，
> 按"日志应该在 `<Tomcat>/bin/logs/`"去找会**一条都找不到**（文件根本不存在）。
> 正确姿势：`cd <工程根> && "%CATALINA_HOME%\bin\catalina.bat" run`。
> `Preparing:` 里**没有耗时**，耗时只能靠 `EXPLAIN ANALYZE`（见 2.2）。

### 2.2 EXPLAIN 重点看哪几列、什么算"有问题"

本项目 MySQL 9.5 的 `EXPLAIN` 默认输出 **TREE 格式**（`-> Table scan on ...`），
**看不到 `type` 列**。要看手册要的 `type/key/rows/Extra`，必须显式写 `FORMAT=TRADITIONAL`：

```sql
EXPLAIN FORMAT=TRADITIONAL SELECT ... ;
```

| 列 | 什么算有问题 | 本项目的具体表现 |
|---|---|---|
| `type` | **`ALL`** = 全表扫；`index` 次之（扫全索引）；`range`/`ref`/`const` 才算好 | §三 逐场景列 |
| `key` | **`NULL`** = 没用上任何索引 | E2/E7 优化前就是 `NULL` |
| `rows` | 估算扫描行数，**接近表总行数**即异常 | 优化前 E2 是 20/20、基准表 48546/50000 |
| `Extra` | **`Using filesort`**（多一次排序）、**`Using temporary`**（建临时表） | E2/E5/E9/E10 优化前都有 `Using filesort` |
| `Extra` | `Using index`（覆盖索引，好）、`Backward index scan`（反向扫，用于 `ORDER BY ... DESC`） | 优化后 E1/E2 出现 |

**阈值口径（本项目采用）**：`type=ALL` 或 `Extra` 含 `Using filesort`/`Using temporary` → 记为**待优化**；
`rows` 超过 1000 且 `filtered` 很低 → 复查索引。**只有 `ALL` 是硬性红线**。

### 2.3 开启慢查询日志

```
mysql> SHOW VARIABLES LIKE 'slow_query_log';      -- 实测：ON
mysql> SHOW VARIABLES LIKE 'long_query_time';     -- 实测：10
mysql> SHOW VARIABLES LIKE 'slow_query_log_file';
```

本机**已经是开着的**（`long_query_time=10` 秒）。本步没有改它 —— 因为 10 秒的阈值对本项目的
数据量等于"永不触发"，这就是**为什么要用 `EXPLAIN ANALYZE` 而不是等慢日志**：
本步最大的性能问题（列表页 19.3ms）连 10 秒阈值的百分之一都不到，
慢查询日志从头到尾**一条都不会记**。

> 若要临时收紧（会话级，不改配置、不影响别人）：
> ```sql
> SET GLOBAL slow_query_log = ON;
> SET GLOBAL long_query_time = 0.1;   -- 100ms
> ```

---

## 三、七个场景逐个体检（手册【二】）

| # | 场景 | 现状判断 | 期望执行计划 | 需要的索引 | 结论 |
|---|---|---|---|---|---|
| 1 | **文章列表分页**（带分类名、作者名） | ⚠️ **两条问题**：① `ORDER BY create_time DESC, id DESC` 无索引支撑 → `type=ALL` + `Using filesort`（基准表 33.2ms）② 分类名/作者名若逐条查就是 N+1 | `type=ref`/`index` + `Backward index scan`，无 filesort | **`(deleted, create_time)`**（本步新增） | **已修**：索引 + 方案 B 批量拼装 |
| 2 | **详情页审核流水**（带审核人昵称） | ① N+1 早在 S11 解决（固定 4 条 SQL）② 排序 `ORDER BY audit_time ASC, id ASC` 有 `Using filesort` | `type=ref` + 无 filesort | **`(article_id, audit_time)`**（本步把 `idx_article_stage` 换掉） | **已修** |
| 3 | **按标题模糊检索** `like '%kw%'` | `type=ALL`（**前缀通配符让索引彻底失效**） | 无法用 B-Tree 索引；只能全表扫 | **不加**（加也白加） | **接受**（见下方说明） |
| 4 | **按状态 + 创建时间范围** | ✅ 已命中 `idx_status_create_time`，`Backward index scan`，无 filesort | `type=range` | 已有 | **无需改** |
| 5 | **批量提交审核**（循环调用单个方法） | ⚠️ N 篇文章 = N 组 SQL（**不是**同一页渲染的 N+1） | 逐条独立事务，**刻意为之** | 不涉及索引 | **刻意保留**（见下方说明） |
| 6 | **版本列表**（带修改人昵称） | ✅ 命中 `uk_article_version`，N+1 在 S15 已解决（1 个或 21 个版本都是 3 条 SQL） | `type=ref` | 已有（唯一索引即索引） | **无需改** |
| 7 | **排期定时任务扫描** | ✅ 命中 `idx_status_schedule_time`，`type=range`，无全表扫无 filesort | `type=range` | 已有（S13 建的） | **无需改** |

### 3.1 场景 3：标题模糊为什么"不加索引"是对的

`title LIKE '%内容%'` 的**通配符在左**，B-Tree 索引是按前缀有序的，
左边一模糊就没法定位起点 → 只能全表扫或全索引扫。三条常见出路都不适用：

- **改成前缀匹配** `LIKE '内容%'`：能用 `idx_title`，但**产品语义变了**（用户搜"内容"想搜到
  "后台内容管理"这类中间匹配），不能为了性能偷偷改语义。
- **加全文索引 `FULLTEXT`**：MySQL 的全文索引对中文默认分词是**按空格**的，
  中文标题不切词，`MATCH ... AGAINST` 基本搜不到；要么装 ngram 分词器（额外配置），
  要么上 ES（手册明确说"不要建议加 Redis/分库分表"这类过度设计）。
- **反向索引/生成列**：为一个"标题里找子串"的需求改表结构，代价与收益不成比例。

**结论**：保留全表扫。当前 20 行数据量下这是最优解；
真到了几十万行的量级，正确做法是引入搜索组件（成员D 的 `cms_article_index` 就是为此预留的），
而不是硬塞一个索引。**这也解释了为什么 `idx_title` 还在**：它服务的是
`sortField=title` 的排序和未来的标题等值匹配，不是这个模糊查询。

### 3.2 场景 5：批量提交的"N×k 条 SQL"是设计，不是缺陷

`POST /articles/batch-submit` 逐条调用单篇 `submit`（S09 定），所以 N 篇文章产生 N 组 SQL。
这**不是**列表页那种"一次查询跟着 N 次补充查询"的 N+1（那 N 次查的是**同一批数据**，
所以能合并）；批量提交的 N 组 SQL 写的是**不同的文章**，合并成一条 SQL 就要放弃：

1. **逐条容错**（§二.4 定的：自己的成功、别人的记入失败清单，不整批回滚）；
2. **逐条独立事务**（单篇失败不拖累别篇）；
3. **逐条写审核流水**（每篇的 `article_version`、`audit_stage` 都不同）。

拿这三样去换"SQL 条数变少"，是典型的**用正确性换指标**。所以不改，登记理由。

---

## 四、列表页"显示分类名和作者名"的三方案对比（手册【三】）

| 维度 | 方案 A：XML 里 JOIN 直接映射 VO | **方案 B：主表分页 + `selectBatchIds` 批量补（**本步采用**）** | 方案 C：`@TableField(exist=false)` 逐条填充 |
|---|---|---|---|
| 可维护性 | 差：VO 的每个字段都要在 XML 里手写列名，改 VO 要同步改 XML 与 `resultMap` | **好**：Java 侧一眼看完，复用了 S11 的 `selectNicknames` | 差：字段藏在实体里，看不出是"查出来的"还是"填进去的" |
| 性能 | 理论最优（1 条 SQL，DB 侧完成），但要手工保证 `countSql` 正确 | **好**：固定 3~4 条，且条数不随数据量变 | **差：N+1**（每条记录 2 次查询） |
| MP 分页插件兼容性 | ⚠️ **有坑**：自定义 JOIN 的 `count(*,*)` 自动生成会错（笛卡尔积导致 total 偏大），**必须手写 `countSql`**，还要额外写一条 `selectPageVO`；`resultMap` 里 `LEFT JOIN` 少一个都影响 total | **好**：`baseMapper.selectPage` 天然支持，`countSql` 由插件生成（见 §七 实测） | 好：用 `selectPage` 就行 |
| 与 S11 的一致性 | 不一致（详情页走的是"主查 + 批量补"） | **一致** | 不一致 |
| 是否引入 N+1 | 否 | **否** | **是** |

**选 B 的三个理由**：
1. 手册自己也"倾向方案 B（与 S11 保持一致）"；
2. 方案 A 的 `countSql` 坑是**静默的**——total 会算成笛卡尔积行数，只表现为"分页页码数不对"，
   不是报错，最容易漏；
3. 本项目列表页的外键只有两个（`author_id`、`category_id`），
   两个 `selectBatchIds` 已经把 N+1 彻底消灭，**JOIN 带来的收益只剩"省 2 条 SQL"**，
   而代价是把 VO 的字段绑定搬到 XML 里（可维护性下降）。

**实现要点**（`ArticleServiceImpl#pageArticles`）：

```java
IPage<CmsArticle> page = baseMapper.selectPage(q.toMpPage(LIST_SORTABLE_COLUMNS), wrapper);
List<CmsArticle> rows = page.getRecords();
if (rows.isEmpty()) {                       // ⚠️ 必须提前返回：selectBatchIds(空) → IN () 是语法错误
    return new PageResult<>(new ArrayList<>(), page.getTotal(), page.getCurrent(), page.getSize());
}
Set<Long> userIds = new LinkedHashSet<>();   // 本页出现的外键，去重后批量查
Set<Long> categoryIds = new LinkedHashSet<>();
// ... 收集 → selectNicknames(userIds) + selectCategoryNames(categoryIds) → 内存拼装 VO
```

---

## 五、"该加什么索引"清单（手册【四】）

| 查询场景 | 涉及表 | 建议索引 | 类型 | 加它的理由 | 加索引的副作用 |
|---|---|---|---|---|---|
| 列表默认排序 `WHERE deleted=0 ORDER BY create_time DESC, id DESC` | `cms_article` | **`idx_deleted_create_time (deleted, create_time)`** | **联合**（前缀 `deleted` 是等值常量） | ① 直接提供 `(deleted, create_time, id)` 的顺序（InnoDB 二级索引隐含主键），消 `filesort`；② `COUNT(deleted=0)` 变**索引覆盖**（`Using index`）；③ 分类/作者筛选 + 默认排序也一并走它（**实测**：E9/E10 从 `Using filesort` 变成 `range + index condition`） | 每行多 1 条索引记录（`deleted` 1 字节 + `create_time` 8 字节 + 主键）；插入/更新多维护一个 B-Tree。本表读多写少，可接受 |
| 详情页审核流水 `WHERE article_id=? ORDER BY audit_time ASC, id ASC` | `cms_article_audit_log` | **`idx_article_time (article_id, audit_time)`**（**替换**原 `idx_article_stage`） | **联合** | 原索引第二列 `audit_stage` 是**死列**（全项目无任何 SQL 按它过滤，`getAuditStage()` 只用于出参映射）；换成 `audit_time` 后既保留 `article_id` 前缀能力，又消掉排序的 `filesort` | **索引条数不变**（一换一），无额外写放大 |
| 状态 + 创建时间范围 | `cms_article` | `idx_status_create_time`（**已有**） | 联合 | S02 建的，实测 `type=range` + `Backward index scan`，无 filesort | — |
| 排期定时扫描 | `cms_article` | `idx_status_schedule_time`（**已有**） | 联合 | S13 建的，实测 `type=range`，无全表扫无 filesort | — |
| 版本列表 | `cms_article_version` | `uk_article_version (article_id, version_no)`（**已有**） | 唯一 | 兼做查询索引（`article_id` 前缀），实测 `type=ref` | — |
| 标题模糊 `LIKE '%kw%'` | `cms_article` | **不加** | — | 前缀通配符用不上 B-Tree，见 §3.1 | — |

### 5.1 ⚠️ 两条原则（手册要求提醒）

- **索引不是越多越好，写多读少的表要克制**。本步只**净增 1 条**索引（`idx_deleted_create_time`），
  另一处是**替换**（条数不变）。评估"要不要给 `(category_id, create_time)`、
  `(author_id, create_time)` 各加一条"时，实测发现 `idx_deleted_create_time` 已经
  把这两个场景一起解决了（§六 6.1 的 B2/B3）→ **不加**，避免每行多背两条索引。
- **联合索引要讲最左前缀**。`idx_deleted_create_time` 的前缀能力是：
  ✅ 只用 `deleted`；✅ `deleted` + `create_time`（含排序）；
  ❌ **单独用 `create_time` 用不上**（因为 `deleted` 在最左）。
  这在别的项目里会是个隐患，但本项目**所有**查询都带 `deleted = 0`（逻辑删除约定），
  前导列永远是等值常量，所以不构成问题。**如果哪天有人写了一条不带 `deleted` 的查询，
  它不会走这个索引** —— 这是应该知道的事。

---

## 六、优化前后对比数据（手册核查项 4）

### 6.1 5 万行基准表的成对对照（`EXPLAIN ANALYZE` 实测）

**为什么要建基准表**：本项目的种子库只有 **20 行**（见 6.2），
20 行时优化器认为"全表扫 + 排序"比"走索引回表"更便宜，**它是对的**。
要证明索引有效，必须在**真实规模**上看计划会不会翻转。
基准表用 `CREATE TABLE cms_article_bench LIKE cms_article` 复制（5 万行），
并**显式 `DROP KEY idx_deleted_create_time`** —— 因为 `LIKE` 会把新索引一起复制过去，
不删掉的话"优化前"数据就是假的（这一步第一次就踩了）。
脚本：`target/s17-bench-setup.sql` + `target/s17-bench-queries.sql`，数据在 `target/s17-bench-before.txt` / `-after.txt`。

| 查询 | 优化前（无 `idx_deleted_create_time`） | 优化后 | 提升 |
|---|---|---|---|
| **默认排序翻页** `deleted=0 ORDER BY create_time DESC, id DESC LIMIT 10` | `type=ALL`，Table scan **48546 行** → `Using filesort`，实际 **33.2 ms** | `type=ref`，`Backward index scan`，**只读 10 行**，实际 **0.26 ms** | **约 128×** |
| **分类筛选 + 默认排序** `category_id=1` | `type=ref`（`idx_category_id`）扫 **5555 行** → `Using filesort`，实际 **8.65 ms** | `type=range`（`idx_deleted_create_time`）扫 90 行 → 无 filesort，实际 **0.151 ms** | **约 57×** |
| **作者筛选 + 默认排序** `author_id=1` | `type=ref`（`idx_author_id`）扫 **10000 行** → `Using filesort`，实际 **14.0 ms** | `type=range`（`idx_deleted_create_time`）扫 50 行 → 无 filesort，实际 **0.0935 ms** | **约 150×** |
| **列表 COUNT** `COUNT(*) WHERE deleted=0` | `type=ALL` 扫 48546 行，`Using where` | `type=ref`，**`Using index`（索引覆盖）** | 不再读数据行 |
| 状态 + 时间范围（对照，未改动） | `type=range`（`idx_status_create_time`），无 filesort | **同左（本来就好）** | — |

### 6.2 20 行种子库上的真实计划（以及为什么"文章列表 `type` 仍是 ALL"）

`target/s17-explain-before-trad.txt` → `target/s17-explain-after.txt`（脚本 `target/s17-explain.sql`）：

| 场景 | 优化前 | 优化后 |
|---|---|---|
| E1 列表 COUNT | `ALL`，key=NULL | **`ref`，key=`idx_deleted_create_time`，`Extra=Using index`** |
| E2 列表主查询 | `ALL`，key=NULL，`Using filesort` | **仍是 `ALL` + `Using filesort`**（见下方说明） |
| E3/E4 状态+时间 | `ref`/`range`，`idx_status`/`idx_status_create_time` | 同左（`possible_keys` 里多了新索引，未选） |
| E5 审核流水 | `ref idx_article_stage` + **`Using filesort`** | **`ref idx_article_time`，无 filesort** |
| E6 排期扫描 | `range idx_status_schedule_time` | 同左 |
| E7 标题模糊 | `ALL` | `ALL`（预期，见 §3.1） |
| E8 版本列表 | `ref uk_article_version` | 同左 |
| E9/E10 分类/作者筛选+排序 | `ref idx_category_id/idx_author_id` + **`Using filesort`** | 仍 `ref` + filesort（20 行；5 万行时翻转，见 6.1） |

> ⚠️ **关于手册核查项"文章列表 `type` 不是 `ALL`"**：在**种子库 20 行**上，
> 列表主查询的 `type` **仍是 `ALL`**，这是**优化器的正确判断**，不是索引没建好：
> `possible_keys` 里已经列出 `idx_deleted_create_time`，优化器只是算出来
> "扫 20 行再排 20 行"比"走索引再回表 10 次"便宜。**硬要它走索引，反而更慢。**
> 这一条与手册的字面要求不符，按"实测优先"如实记录：
> 索引的有效性在 **5 万行基准表**上证明（6.1：计划翻转为 `Backward index scan`，**128×**）；
> 同一次请求里 **COUNT 已经改用新索引**（`ref` + `Using index`）。
> 其余两个被点名查询（审核流水、排期扫描）在 20 行上就已经是 `ref`/`range`。

### 6.3 审核流水排序的量化（压力分布）

真实分布下每篇文章的流水只有 1~5 条，排序成本可忽略；
为了**量化** `(article_id, audit_time)` 的价值，用"每篇 1 万条"的压力分布建基准表
（`target/s17-bench-audit.sql`，5 万行 / 5 篇）：

| 查询 | 优化前（`idx_article_stage`） | 优化后（`idx_article_time`） |
|---|---|---|
| `WHERE article_id=1 ORDER BY audit_time ASC, id ASC` | `ref`，扫 18498 行 → **`Using filesort`**，实际 **20.1 ms** | `ref`，扫 18498 行 → **无 filesort**，实际 **14.4 ms** |

即使在这种极端分布下，收益也只是"省掉排序的 5.7ms"，**真实分布下基本为 0**。
真正让这次替换值得的是**另一件事**：`audit_stage` 是死列，
这次不过是把一条"半废"的索引换成"完全有用"的，**条数不变，零代价**。

---

## 七、真机验证：SQL 条数（手册核查项 1，N+1 的唯一判据）

脚本：`target/s17-live.sh`，原始输出：`target/s17-live-output.txt`，
单次请求的 SQL 明细：`target/s17/sql-*.txt`。

| 请求 | 响应 | 本次请求的 SQL 条数 | 明细 |
|---|---|---|---|
| `page=1&size=10`（本页 **10** 条） | 200，total=20，records=10 | **4** | COUNT + 主查询 + `sys_user` 批查 + `cms_category` 批查 |
| `page=1&size=100`（本页 **20** 条） | 200，total=20，records=20 | **4** | 同上 |
| `page=99&size=10`（越界） | 200，total=20，records=0 | **1** | 只有 COUNT（见下方 ⚠️） |
| `status=5` | 200，total=3 | **4** | 同上（筛选条件进 WHERE，不增加条数） |
| `title=内容` | 200，total=2 | **4** | 同上 |
| `sortField=createTime`（非法驼峰） | **400** | **0** | 参数绑定/校验阶段就被拒，没进 Service |

**结论**：SQL 条数与**数据量无关**、与**本页条数无关**（10 条与 20 条都是 4 条）
→ **没有 N+1**。登录后请求里**没有任何鉴权相关的 SQL**（权限在会话里已缓存），
所以这 4 条就是列表接口的全部开销。

> ⚠️ **越界页是 1 条，不是 2 条（本步实测订正了自己先前的写法）**：
> 我原本在 Javadoc 里写"空结果 2 条（COUNT + 主查询）"，真机打脸 ——
> `page=99&size=10` 只留下 1 条 `Preparing:`。原因是 MP
> `PaginationInnerInterceptor.continuePage()`：跑完 count 后若
> `offset >= total`（`(99-1)*10 = 980 >= 20`）就**连主查询一起跳过**。
> 总数本身为 0 时同理。所以准确口径是：**有数据页 3~4 条 / 越界或空结果页 1 条**。
> 手册写的"3~4 条"指的是有数据页（3 条对应"本页 `category_id` 全为 null"）。

---

## 八、DECIMAL / DATETIME 时区 / 字符集自查清单（手册【五】）

| 检查项 | 怎么看 | 本项目实测结果 | 判定 |
|---|---|---|---|
| **DATETIME 时区** | `SELECT @@time_zone, @@system_time_zone;` | `time_zone = SYSTEM`，`system_time_zone = 中国标准时间` | ✅ 一致。`DATETIME` **不带时区、不做转换**（与 `TIMESTAMP` 不同），只要"写入端/读取端/DB 端"三者时区一致就不会差 8 小时。本项目 JDBC 串里**没有** `serverTimezone` 之类的强制转换，JDK 与 MySQL 都取系统时区 → 一致 |
| | 连接串是否强行指定时区 | `jdbc:mysql://localhost:3306/cms_db?useSSL=false&allowPublicKeyRetrieval=true&...`（**无** `serverTimezone`） | ✅ 不引入第二种时区口径 |
| | 时间字段类型是否统一 | 全库时间列统一 `DATETIME`（`create_time`/`update_time`/`publish_time`/`schedule_time`/`audit_time`/`oper_time`/`login_time`）；`create_time DEFAULT CURRENT_TIMESTAMP`、`update_time ... ON UPDATE CURRENT_TIMESTAMP` | ✅ 无 `TIMESTAMP`，无混合 |
| | Java 侧类型 | 实体统一 `LocalDateTime`（`java.time`，无时区），序列化/展示统一 `JacksonConfig` + `DISPLAY_TIME_FORMATTER`（`yyyy-MM-dd HH:mm:ss`） | ✅ 无 `Date`/`Calendar` 混用 |
| | ⚠️ 前端传参 | `el-date-picker` 必须 `value-format="YYYY-MM-DD HH:mm:ss"`（**空格**，不是 `T`） | ⚠️ S13 修过：带 `T` 会被 `JacksonConfig` 判 400 |
| **DECIMAL 精度** | 库里到底有没有 `DECIMAL` | 实测全库列类型分布：`bigint 76 / datetime 43 / varchar 41 / tinyint 37 / int 9 / longtext 2 / json 2 / text 2` —— **`DECIMAL` 一个都没有**，`float`/`double` 也没有 | ✅ 无精度风险（不是"没检查"，是查完确认没有） |
| | 计数字段的溢出与精度 | `cms_article.view_count INT`、`cms_article.version INT`、`cms_article_index.hot_score INT`（实测 `COLUMN_TYPE`，**不是** `DECIMAL`） | ✅ `INT` 上限 21 亿，计数与热度排序足够；不用 `DECIMAL` 也就没有"小数位不一致"的问题 |
| | 除法/平均值/比例 | 业务 SQL 里没有 `AVG`/`SUM/COUNT` 这类可能产生隐式舍入的表达式；分页 `offset` 在 Java 侧按 `long` 计算 | ✅ |
| | ⚠️ 如果将来要加金额列 | 本项目没有金额业务；若以后加，`DECIMAL(M,N)` 必须**两端一致**（DDL 与 Java 用 `BigDecimal`，**不要用 `double`**） | 登记备忘 |
| **字符集** | 库/表/列三级是否一致 | `@@character_set_database = utf8mb4`，`@@collation_database = utf8mb4_general_ci`；建表统一 `DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci`（`init.sql` 17 张表无例外） | ✅ 三级一致 |
| | 客户端连接字符集 | 查询脚本一律 `--default-character-set=utf8mb4` | ✅ 否则中文注释/数据乱码 |
| | ⚠️ `LIKE` 转义与 `sql_mode` | `@@sql_mode = ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION` —— **没有 `NO_BACKSLASH_ESCAPES`** | ✅ 所以 `likePattern()` 里用反斜杠转义 `%`/`_`/`\` 是有效的（若有这个模式，反斜杠变成普通字符，转义会静默失效） |
| | 排序规则对查询的影响 | 标题检索走 `LIKE` 子串匹配，不用 `=`，不受 `_ci`/`_bin` 影响 | ✅ |
| | ⚠️ `STRICT_TRANS_TABLES` | 已开启 → 超长 varchar 抛 `DataIntegrityViolationException`（1406）而不是静默截断 | ✅ 是本项目"确定性失败点"的来源（S10 用它验证事务回滚） |
| | ⚠️ `longtext` 大字段的位置 | 只有 2 个：`cms_article.content`、`cms_article_version.content_snapshot`；`text` 2 个在 `sys_oper_log`；`json` 2 个在 `sys_apply_approval.apply_content` / `sys_report.result_json` | ⚠️ 见下方"大字段带来的真问题" |

### 8.1 ⚠️ 本步顺手查出的真问题：列表 SQL 把 `LONGTEXT` 正文查出来又丢掉

真机日志里列表主查询的实际 SQL 是：

```sql
SELECT id, title, summary, cover_url, content, category_id, status, author_id, publisher_id,
       publish_time, schedule_time, is_top, view_count, version, create_by, create_time,
       update_by, update_time, deleted
FROM cms_article WHERE deleted = 0 ORDER BY create_time DESC, id DESC LIMIT ?
```

**列清单里有 `content`（`LONGTEXT`）** —— 而列表 VO（`CmsArticleVO`）**根本不暴露正文**
（§七 实测 `含正文键=False`）。原因是 `baseMapper.selectPage` 按**实体全列**生成 SQL，
所以这列正文是"从 MySQL 读出来 → 走网络 → 在 Java 里丢掉"。

- **当前影响**：≈0（种子正文只有几十字，本页 10~20 条）。
- **风险**：如果列表页放到 100 条、且正文是真实长文（几十 KB/篇），
  一次列表请求就是**几 MB 的无用 IO**（DB → 应用服务器的网络与内存）。
- **可选优化**（本步**不做**，登记）：给 Mapper 加一个只选列表所需列的
  `selectPageVo`（自定义列清单，仍走 MP 分页）。不做的理由是
  **性价比与风险**：一旦自定义 SQL，`count` 语句的自动生成就要重新论证（§四 方案 A 的坑），
  而当前收益是 0；等列表页真的要展示更多字段、或正文明显变长时再做更合适。

---

## 九、与手册 / 与预期的偏差（逐条登记）

| # | 手册或预期说 | 实测是什么 | 处置 |
|---|---|---|---|
| 1 | 核查项"文章列表、审核流水、排期扫描三个查询 `type` 不是 `ALL`" | 20 行种子库上**列表主查询仍是 ALL**（优化器的正确选择）；COUNT 已改用新索引；另两个本来就是 `ref`/`range` | **如实记录**，用 5 万行基准表证明索引有效（§6.1） |
| 2 | 核查项"SQL 条数固定 3~4 条" | 有数据页 **4 条**（本页无分类时 3 条）；**越界/空结果页 1 条** | 口径订正为"两头固定"，并把 Javadoc 里的"空结果 2 条"改掉 |
| 3 | 手册 S17 说"列表页要显示分类名和作者名" | S16 时 `GET /articles` **根本不存在**（依赖 D-01） | 本步先把它实现出来，再做性能体检 |
| 4 | 慢查询日志（阈值 10s） | 本步最大问题 19.3ms，**一条都不会被记** | 结论：定位问题必须靠 `EXPLAIN ANALYZE`，不能等慢日志 |
| 5 | `EXPLAIN` 默认输出 | MySQL 9.5 默认 **TREE** 格式，**没有 `type` 列** | 所有存档的 EXPLAIN 一律加 `FORMAT=TRADITIONAL` |
| 6 | `CREATE TABLE ... LIKE` | 会把**索引一起复制**，导致"优化前"基线其实是带新索引的 | 基准表建好后显式 `DROP KEY`，否则前后对比数据是假的 |

---

## 十、复现方式

```bash
# 1) 索引前后对比（业务库，20 行）
python target/dbq.py -f target/s17-explain.sql

# 2) 5 万行基准表的成对数据（建表 → 优化前 → 加索引 → 优化后）
python target/dbq.py -f target/s17-bench-setup.sql     # 内含 DROP KEY，保证是"优化前"
python target/dbq.py -f target/s17-bench-queries.sql   # → target/s17-bench-before.txt
python target/dbq.py "ALTER TABLE cms_article_bench ADD KEY idx_deleted_create_time (deleted, create_time); ANALYZE TABLE cms_article_bench;"
python target/dbq.py -f target/s17-bench-queries.sql   # → target/s17-bench-after.txt
python target/dbq.py "DROP TABLE IF EXISTS cms_article_bench;"

# 3) 审核流水压力分布（5 万行 / 5 篇）
python target/dbq.py -f target/s17-bench-audit.sql
python target/dbq.py "DROP TABLE IF EXISTS cms_article_audit_log_bench;"

# 4) 真机 SQL 条数（需先 cd 工程根再起 Tomcat，否则 ./logs 落到别处）
cd /d/mybatis-demo/project1 && "$CATALINA_HOME/bin/catalina.bat" run   # 另开一个终端
bash target/s17-live.sh
```

> `target/dbpipe.py` 是为 `sql/init.sql`（728 行）准备的：`mysql -e "<整段SQL>"` 会触发
> Windows 的 `WinError 206 文件名或扩展名太长`，改成把 SQL 从 stdin 喂进去就绕开了。
> 两者都不打印凭据。
