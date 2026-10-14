# S06 实测证据：MyBatis-Plus 插件真的改写了 SQL

> 采集时间：2026-10-08 23:49
> 采集方式：`MpPluginIntegrationTest` 跑完后，从 `logs/cms.log` 里 grep `Preparing:`
> 日志配置：`logback.xml` 中 `com.lab.cms.mapper` 为 DEBUG 级别（见该文件第 115 行）

下面每一段都是**程序实际执行的 SQL**，不是推测。用来说明
S06 注册的三个插件确实在工作 —— 因为"插件没生效"是静默的，
不抓 SQL 根本看不出来（注解在、配置在、编译能过、跑起来也不报错，就是没效果）。

---

## 一、分页插件：追加 LIMIT + 另发一条 COUNT

这是手册要求验证的核心项。关键点是**一次分页请求产生了两条 SQL**：

```sql
-- ① 先查总数（这是 MP 自动加的，业务代码里没有写）
SELECT COUNT(*) AS total FROM sys_oper_log WHERE deleted = 0

-- ② 再查当前页数据，末尾多了 LIMIT ?
SELECT id, module, oper_type, oper_desc, request_method, request_url,
       request_param, response_result, oper_id, oper_name, oper_ip,
       cost_time, status, error_msg, oper_time,
       create_by, create_time, update_by, update_time, deleted
FROM sys_oper_log
WHERE deleted = 0
ORDER BY id DESC
LIMIT ?
```

**怎么读这段证据**：

- `COUNT(*)` 那条不是我们写的 —— 是 `PaginationInnerInterceptor`
  为了算 `total`（前端分页组件需要）自动补发的。测试里断言
  `records=3, total=16, pages=6`，就是从这两条 SQL 的结果拼出来的。
- `LIMIT ?` 是参数化的占位符（不是拼死的数字），说明分页是真的走
  JDBC 的 `setMaxRows`/`LIMIT`，而不是"查全表再在内存里切"。
  后者在数据量大时会直接 OOM，是这个插件要解决的原始问题。
- `ORDER BY id DESC` 来自 `PageQuery.toMpPage()` 里设置的
  `OrderItem.desc("id")`，验证了"用对象而非拼字符串"这条路是通的。

---

## 二、逻辑删除：`deleteById` 被改写成 UPDATE

业务代码调的是 `mapper.deleteById(id)`，看起来是"删数据"，实际执行的 SQL 是：

```sql
UPDATE sys_oper_log SET deleted=1 WHERE id=? AND deleted=0
```

**为什么这很重要**：

- 数据没有被物理删除 —— 这是"逻辑删除"的核心价值（可追溯、可恢复）。
  测试里同时验证了物理行仍在，且 `deleted` 列值是 1。
- `WHERE` 里带 `AND deleted=0` 说明它也不会去"重复删除"已删除的行。
- 如果没配 `@TableLogic`，这条 SQL 会是 `DELETE FROM sys_oper_log WHERE id=?`，
  数据就真的没了。**配没配的差别，只有看 SQL 才知道。**

## 三、所有查询自动带上逻辑删除条件

```sql
SELECT ... FROM sys_oper_log WHERE id=? AND deleted=0
```

`selectById` 这种 MP 自带方法会自动加 `deleted=0`。

⚠️ **但手写的 Mapper XML 不会自动加**，必须自己写 `AND deleted = 0`。
这是 MP 逻辑删除最容易踩的坑，已记在 `BaseEntity` 的类注释里。

---

## 四、插入语句里没有 deleted 列（一个值得注意的细节）

```sql
INSERT INTO sys_oper_log ( module, oper_type, oper_desc, request_method,
                           request_url, oper_id, oper_name, oper_ip,
                           status, oper_time,
                           create_by, create_time, update_by, update_time )
VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )
```

注意字段列表里**没有 `deleted`** —— 因为该列在数据库里有 `DEFAULT 0`，
MP 不会把它列进 INSERT。

这带来一个结论，值得记下来：

> `AuditMetaObjectHandler.insertFill` 里那句 `setFieldValByName("deleted", 0)`
> **在本项目的表结构下其实是多余的**（数据库 DEFAULT 已经兜住了）。
>
> 但它不是错的，保留的理由是"防御式"：万一将来某张表漏写 DEFAULT 0，
> 或者换成 PostgreSQL/其他不允许"列不在 INSERT 列表里就用默认值"的方言，
> 这句填充就成了唯一保障。**多余的保险比缺一道保险好。**
>
> 这个判断是从实际 SQL 里读出来的，不是翻文档猜的。

---

## 五、带 WHERE 的 UPDATE 正常执行（对照组）

```sql
UPDATE sys_oper_log SET oper_desc=? WHERE deleted=0 AND (id = ?)
```

与下面"被拦截的全表更新"对照着看：**同样是 UPDATE，
带了 WHERE 就正常执行，不带就被拦**。有这条对照才能确定
`BlockAttackInnerInterceptor` 拦的是"没有 WHERE"这件事本身，
而不是把所有 UPDATE 都拒绝掉。

---

## 六、不带 WHERE 的 UPDATE / DELETE 被拦截（无 SQL 产生）

这一类**没有对应的 SQL 日志** —— 正是因为它被插件在"生成 SQL 之前"就拦下了：

```
### Error updating database.  Cause: ...PersistenceException
### Cause: com.baomidou.mybatisplus.core.exceptions.MybatisPlusException:
    Prohibition of table update operation
```

测试断言捕获的就是这个异常。**"日志里查不到 SQL"本身就是被拦截的证据** ——
如果它放行了，`logs/cms.log` 里必然留下一条没有 WHERE 的 UPDATE。

---

## 附：怎么自己复现这份证据

```bash
# 1. 跑集成测试（会真的连 cms_db 库，插几条带 [S06-TEST] 标记的数据，跑完自动清理）
将 mvn 换成你的 Maven 路径，例如：
   C:/Users/admin/.m2/wrapper/dists/apache-maven-3.9.6-bin/439sdfsg2nbdof.../bin/mvn.cmd
mvn test -Dtest=MpPluginIntegrationTest

# 2. 从日志里抓 SQL
grep "Preparing:" logs/cms.log | tail -20

# 3. 确认没有残留测试数据
mysql -uroot -p cms_db -e "SELECT COUNT(*) FROM sys_oper_log WHERE oper_desc LIKE '[S06-TEST]%'"
```
