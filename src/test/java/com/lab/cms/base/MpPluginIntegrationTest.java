package com.lab.cms.base;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lab.cms.common.result.PageQuery;
import com.lab.cms.entity.SysOperLog;
import com.lab.cms.mapper.SysOperLogMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S06 主干实测：MP 插件行为与审计填充。
 *
 * <p>这个类**不同于纯单元测试** —— 它真的连数据库（cms_db），
 * 目的是把手册里"试一下应该会……"的说法变成**可重复执行的断言**。
 * 手工在浏览器里点一遍也能验证，但过一个月没人记得当初点过什么；
 * 写成测试后，任何改动只要碰坏这些行为，`mvn test` 立刻报红。
 *
 * <h3>覆盖的核查项（对应手册 S06「核查」小节）</h3>
 * <ol>
 *   <li>{@code BlockAttackInnerInterceptor} 拦下不带 WHERE 的 update</li>
 *   <li>MP 分页插件真的改写了 SQL（验证 LIMIT 生效、返回条数受限）</li>
 *   <li>{@code MetaObjectHandler} 在**没有** HttpServletRequest 时不抛异常，
 *       且按约定填 {@code SYSTEM_USER_ID = 0}</li>
 *   <li>审计四字段 + deleted 确实被填上（此前踩过"全 null 导致 NOT NULL 报错"的坑）</li>
 * </ol>
 *
 * <h3>⚠️ 关于"没有请求上下文"这个测试场景</h3>
 * <p>本类由 {@code SpringJUnit4ClassRunner} 启动，**没有 DispatcherServlet**，
 * 因此 {@code RequestContextHolder.getRequestAttributes()} 返回 null ——
 * 这正是我们要测的场景（定时任务、单测里的真实处境）。
 * 所以本类里所有插入操作的 {@code createBy} 都应该是 0（系统操作）。
 *
 * <h3>⚠️ 测试数据自清理</h3>
 * <p>本类会往 {@code sys_oper_log} 插数据，用 {@code oper_desc} 打上唯一标记，
 * 在每个测试末尾按标记删除。保证反复运行不会累积累赘数据
 * （否则跑十遍就多十条脏数据，最后 demo 演示时日志列表里全是测试痕迹）。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class MpPluginIntegrationTest {

    /** 测试数据标记：所有本类插入的记录都带这个前缀，便于精确清理 */
    private static final String TEST_TAG = "[S06-TEST]";

    /** 系统操作用户哨兵值，与 {@link AuditMetaObjectHandler} 中的常量一致 */
    private static final Long SYSTEM_USER_ID = 0L;

    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    /**
     * 数据源 —— 用来现场构造 {@link JdbcTemplate}。
     *
     * <p>为什么不在生产配置里注册 JdbcTemplate Bean：
     * 本项目用 MP 访问数据库，业务代码完全不需要 JdbcTemplate。
     * 只因为测试要"绕过逻辑删除做物理清理"就把它注册成生产 Bean，
     * 属于为测试改生产代码 —— 而且一旦它进了容器，
     * 后续就可能有人图省事用它写裸 SQL 绕开 MP 的逻辑删除与审计填充。
     *
     * <p>所以这里注入 DataSource，在 {@code @Before} 里自己 new 一个。
     * 测试的便利不应以放宽生产约束为代价。
     */
    @Autowired
    private javax.sql.DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @org.junit.Before
    public void setUpJdbcTemplate() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // ==================== 核查项 1：BlockAttackInnerInterceptor ====================

    /**
     * ⚠️ 手册核查项：不带 WHERE 的 UPDATE 必须被拦下。
     *
     * <p>场景还原：有人写 {@code update()} 时忘了传 wrapper，或传了空 wrapper。
     * 不加这个插件的话，SQL 就是 {@code UPDATE sys_oper_log SET ...}，
     * **整张表的数据被改成同一个值** —— 这是真实事故里排位极靠前的一类。
     *
     * <p>本测试断言：MP 抛出异常而不是静默执行。
     * 异常信息里应包含 {@code BlockAttackInnerInterceptor} 的特征字样
     * （它的实现是抛 {@code MybatisPlusException}，内容是 "Prohibition of table update operation"）。
     */
    @Test
    public void testBlockAttackRejectsUpdateWithoutWhere() {
        try {
            // 空 UpdateWrapper → 没有 WHERE 子句 → 应被拦截
            sysOperLogMapper.update(null, new UpdateWrapper<SysOperLog>()
                    .set("oper_desc", TEST_TAG + "全表更新尝试"));
            fail("不带 WHERE 的 UPDATE 应被 BlockAttackInnerInterceptor 拦下，但它执行成功了");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertNotNull("异常信息不应为空", msg);
            // MP 的原始信息是英文 Prohibition of table update operation；
            // 不同版本措辞可能略有差异，这里做宽松匹配
            assertTrue("异常信息应表明这是「防全表更新」拦截，实际：" + msg,
                    msg.contains("Prohibition of table update")
                            || msg.contains("BlockAttack")
                            || msg.contains("全表"));
            System.out.println("[实测] BlockAttackInnerInterceptor 拦截成功，异常信息：" + msg);
        }
    }

    /**
     * 对照组：**带** WHERE 的 UPDATE 应正常执行。
     *
     * <p>没有这条对照，上面那条测试无法排除"其实是别的原因导致失败"的可能。
     * 有对照组才能确定：拦住的是"没有 WHERE"这件事本身，而不是 UPDATE 这个操作。
     */
    @Test
    public void testUpdateWithWhereStillWorks() {
        // 先插一条带标记的记录
        Long id = insertTaggedLog("对照组-更新前");

        // 带 WHERE 的更新应正常执行
        int affected = sysOperLogMapper.update(null, new UpdateWrapper<SysOperLog>()
                .eq("id", id)
                .set("oper_desc", TEST_TAG + "对照组-更新后"));

        assertEquals("带 WHERE 的 UPDATE 应影响 1 行", 1, affected);
        System.out.println("[实测] 带 WHERE 的 UPDATE 正常执行，影响行数=" + affected);
    }

    /**
     * 对照组 2：不带 WHERE 的 DELETE 也应被拦。
     * <p>同一个插件同时管 UPDATE 和 DELETE，两边都验证一下。
     */
    @Test
    public void testBlockAttackRejectsDeleteWithoutWhere() {
        try {
            sysOperLogMapper.delete(new UpdateWrapper<SysOperLog>());
            fail("不带 WHERE 的 DELETE 应被拦下");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
            System.out.println("[实测] 不带 WHERE 的 DELETE 被拦："
                    + e.getMessage().split("\n")[0]);
        }
    }

    // ==================== 核查项 2：分页插件真的改写了 SQL ====================

    /**
     * ⚠️ 手册核查项：PaginationInnerInterceptor 确实在改写 SQL。
     *
     * <p>怎么证明它"改写了"而不是"恰好查出来就这么多"：
     * 库里共有 20 条左右的 sys_oper_log（含历史数据），
     * 我们请求 size=3，如果插件生效，返回的 records 应是 3 条、
     * 而 total 是真实总数（远大于 3）。两者同时对得上，才说明
     * "SQL 被加了 LIMIT，且另发了一条 COUNT 语句"。
     *
     * <p>另一个证据是 {@code pages}（总页数）= ceil(total / size) ——
     * 这个值只有真的执行了 count 查询才算得出来。
     */
    @Test
    public void testPaginationInterceptorRewritesSql() {
        Page<SysOperLog> page = new Page<>(1, 3);

        Page<SysOperLog> result = sysOperLogMapper.selectPage(page, null);

        assertEquals("第 1 页应返回 3 条（LIMIT 生效）", 3, result.getRecords().size());
        assertTrue("total 应大于单页条数（说明是分页查询而非全表）", result.getTotal() > 3);
        assertEquals("总页数应等于 ceil(total/size)",
                (result.getTotal() + 2) / 3, result.getPages());

        System.out.printf("[实测] 分页改写生效：records=%d, total=%d, pages=%d, size=%d%n",
                result.getRecords().size(), result.getTotal(), result.getPages(), result.getSize());
    }

    /**
     * 分页的 maxLimit 应与 PageQuery.MAX_SIZE(100) 一致 —— 双重保险的第二层。
     *
     * <p>即使有人绕过 DTO 直接 new Page(1, 100000)，插件也会把它截到 100。
     * 这条测试证明"绕过 Service 层也拖不垮数据库"。
     */
    @Test
    public void testPaginationMaxLimitTruncates() {
        // 刻意传一个超大的 size
        Page<SysOperLog> page = new Page<>(1, 100000);

        Page<SysOperLog> result = sysOperLogMapper.selectPage(page, null);

        assertTrue("size 应被 maxLimit 截断到 100 以内，实际=" + result.getSize(),
                result.getSize() <= 100);
        assertTrue("返回条数也应受限", result.getRecords().size() <= 100);

        System.out.println("[实测] maxLimit 截断生效，实际 size=" + result.getSize());
    }

    /**
     * ⚠️ PageQuery 与插件联动：这段是业务代码的"标准写法"模板。
     *
     * <p>展示 {@code normalize()} → {@code toMpPage(白名单)} → {@code selectPage}
     * 的完整链路，并验证 size 被截断、排序被正确应用。
     * 后续各业务域的 Service 都照这个模式写。
     */
    @Test
    public void testPageQueryToMpPageEndToEnd() {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(9999);                       // 应被截成 100
        query.setSortField("id");                  // 白名单内
        query.setSortOrder("desc");

        query.normalize();
        assertEquals("size 应被截断", Integer.valueOf(100), query.getSize());

        // 标准三段式：normalize → toMpPage(白名单) → selectPage
        Page<SysOperLog> mp = query.toMpPage("id", "oper_time");
        Page<SysOperLog> result = sysOperLogMapper.selectPage(mp, null);

        assertTrue("应查到数据", result.getRecords().size() > 0);
        // 验证排序真的生效：desc 时第一条 id 应大于最后一条
        List<SysOperLog> records = result.getRecords();
        if (records.size() >= 2) {
            Long first = records.get(0).getId();
            Long last = records.get(records.size() - 1).getId();
            assertTrue("desc 排序应使 id 递减，实际 first=" + first + ", last=" + last,
                    first > last);
            System.out.printf("[实测] PageQuery→toMpPage 链路生效，size=%d, 首条 id=%d, 末条 id=%d%n",
                    result.getSize(), first, last);
        }
    }

    // ==================== 核查项 3：审计填充（无请求上下文） ====================

    /**
     * ⚠️⚠️ 手册核查项：没有 HttpServletRequest 时，MetaObjectHandler 不能抛异常。
     *
     * <p>这是本项目最要紧的一条设计约束。真实场景：
     * <ul>
     *   <li>S13 的排期发布定时任务 —— 没有 HTTP 请求</li>
     *   <li>单元测试 —— 没有 HTTP 请求</li>
     *   <li>数据初始化脚本 —— 没有 HTTP 请求</li>
     * </ul>
     * 若填充器在拿不到用户时抛异常，这三类场景全部无法写库。
     *
     * <p>本测试所在的 JUnit 环境**天然满足**"无请求上下文"（没有 DispatcherServlet），
     * 所以这里插入成功本身就是一个证明。同时断言：
     * <ul>
     *   <li>{@code createBy / updateBy} 被填成 {@link #SYSTEM_USER_ID}(0)，而不是 null</li>
     *   <li>{@code createTime / updateTime} 被填成当前时间（不是 null）</li>
     *   <li>{@code deleted} 被填成 0（否则会撞 NOT NULL 约束）</li>
     * </ul>
     */
    @Test
    public void testInsertFillWorksWithoutRequestContext() {
        SysOperLog log = new SysOperLog();
        log.setOperId(1L);
        log.setOperName("s06-test");
        log.setModule("参数校验基座");
        log.setOperType("TEST");
        log.setOperDesc(TEST_TAG + "无请求上下文插入");
        log.setRequestMethod("GET");
        log.setRequestUrl("/api/v1/test");
        log.setOperIp("127.0.0.1");
        log.setStatus(1);
        log.setOperTime(LocalDateTime.now());

        // 关键：这个 insert 在没有 HTTP 请求的线程里执行。
        // 若 MetaObjectHandler 抛异常，这里就会失败。
        int affected = sysOperLogMapper.insert(log);
        assertEquals("插入应成功", 1, affected);

        // 断言审计字段真的被填了（这是 S05 踩过的坑：全 null 导致 NOT NULL 报错）
        assertNotNull("主键应回填", log.getId());
        assertNotNull("createTime 必须被填充（S05 的坑）", log.getCreateTime());
        assertNotNull("updateTime 必须被填充", log.getUpdateTime());
        assertNotNull("createBy 必须被填充", log.getCreateBy());
        assertNotNull("updateBy 必须被填充", log.getUpdateBy());
        assertEquals("无登录用户时 createBy 应为系统哨兵值 0",
                SYSTEM_USER_ID, log.getCreateBy());
        assertEquals("无登录用户时 updateBy 应为系统哨兵值 0",
                SYSTEM_USER_ID, log.getUpdateBy());
        assertEquals("deleted 应被填成 0", Integer.valueOf(0), log.getDeleted());

        // createTime 应约等于当前时间（允许 5 秒误差，避免 CI 环境慢导致偶发失败）
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(
                log.getCreateTime(), LocalDateTime.now()));
        assertTrue("createTime 应接近当前时间，实际差 " + diffSeconds + " 秒", diffSeconds < 5);

        System.out.printf("[实测] 无请求上下文插入成功：id=%d, createBy=%d, createTime=%s, deleted=%d%n",
                log.getId(), log.getCreateBy(), log.getCreateTime(), log.getDeleted());

        // ⚠️ 额外验证：直接查库确认 deleted 落库为 0。
        // 不只看 Java 对象（那个可能是 MP 填充的"内存值"而非真实入库值），
        // 要读数据库里的实际存储 —— 这才是"逻辑删除能正常工作"的前提。
        Integer dbDeleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_oper_log WHERE id = ?", Integer.class, log.getId());
        assertEquals("库里的 deleted 应为 0（不是 null）", Integer.valueOf(0), dbDeleted);
        System.out.println("[实测] 库里 deleted 实际存储值 = " + dbDeleted);
    }

    /**
     * 更新时审计填充：{@code updateTime} 必须被刷新。
     *
     * <p>这里刻意用 {@code UpdateWrapper} 只更新一列，复现 S05 那个真实场景 ——
     * 当时的 bug 是 {@code strictUpdateFill} 被绕过，导致 update_time 为 null，
     * 报 {@code Column 'update_time' cannot be null}。
     * 现在改用 {@code setFieldValByName} 无条件覆盖，应当能正常更新。
     *
     * <p>⚠️ 注意这个测试**无法**通过实体上的 updateTime 断言 —— 因为走 UpdateWrapper
     * 时回填不到实体（MP 只在传入实体对象时才回填）。所以要重新查一遍库来验证。
     */
    @Test
    public void testUpdateFillRefreshesUpdateTime() {
        Long id = insertTaggedLog("更新填充-前");
        SysOperLog before = sysOperLogMapper.selectById(id);
        assertNotNull(before);
        LocalDateTime beforeUpdate = before.getUpdateTime();
        assertNotNull("插入后应有 updateTime", beforeUpdate);

        // 等 1 秒，确保时间戳可区分（MySQL DATETIME 精度到秒）
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ⚠️ 这正是 S05 出事的那种写法：只更新一列，实体为 null
        int affected = sysOperLogMapper.update(null, new UpdateWrapper<SysOperLog>()
                .eq("id", id)
                .set("oper_desc", TEST_TAG + "更新填充-后"));
        assertEquals("更新应影响 1 行（若报 update_time cannot be null 说明填充失效）",
                1, affected);

        // 重新查库验证 updateTime 真的被刷新了
        SysOperLog after = sysOperLogMapper.selectById(id);
        assertNotNull(after.getUpdateTime());
        assertTrue("updateTime 应被刷新（这是 S05 踩过的坑）",
                after.getUpdateTime().isAfter(beforeUpdate));
        assertEquals("updateBy 应为系统哨兵值 0",
                SYSTEM_USER_ID, after.getUpdateBy());

        System.out.printf("[实测] 更新填充生效：updateTime %s → %s%n",
                beforeUpdate, after.getUpdateTime());
    }

    /**
     * 逻辑删除：{@code deleteById} 应改写成 UPDATE ... SET deleted=1。
     *
     * <p>验证两件事：
     * <ol>
     *   <li>删除后 {@code selectById} 查不到（MP 自动加了 {@code deleted=0} 条件）</li>
     *   <li>但物理行还在（用原生 SQL 绕过 MP 能查到）</li>
     * </ol>
     * 第二点是逻辑删除的核心价值 —— 数据可追溯、可恢复。
     * 只看第一条的话，无法区分"逻辑删除"和"真的删了"。
     */
    @Test
    public void testLogicDeleteHidesButKeepsRow() {
        Long id = insertTaggedLog("逻辑删除测试");

        // 删除前能查到
        assertNotNull("删除前应能查到", sysOperLogMapper.selectById(id));

        // 逻辑删除（MP 会把它改写成 UPDATE ... SET deleted=1）
        int affected = sysOperLogMapper.deleteById(id);
        assertEquals("逻辑删除应影响 1 行", 1, affected);

        // ① MP 查不到了
        assertNull("逻辑删除后 selectById 应查不到", sysOperLogMapper.selectById(id));

        // ② 但物理行还在 —— 用原生 SQL 绕过 MP 的逻辑删除条件来确认
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_oper_log WHERE id = ?", Integer.class, id);
        assertEquals("物理行的 deleted 标记应被置为 1", Integer.valueOf(1), deletedFlag);

        System.out.println("[实测] 逻辑删除生效：MP 查不到，但物理行仍在且 deleted=1");
    }

    // ==================== 辅助方法 ====================

    /** 插入一条带测试标记的操作日志，返回主键。 */
    private Long insertTaggedLog(String suffix) {
        SysOperLog log = new SysOperLog();
        log.setOperId(1L);
        log.setOperName("s06-test");
        log.setModule("参数校验基座");
        log.setOperType("TEST");
        log.setOperDesc(TEST_TAG + suffix);
        log.setRequestMethod("GET");
        log.setRequestUrl("/api/v1/test");
        log.setOperIp("127.0.0.1");
        log.setStatus(1);
        log.setOperTime(LocalDateTime.now());
        sysOperLogMapper.insert(log);
        return log.getId();
    }

    /**
     * 物理清理本类产生的测试数据。
     *
     * <p>⚠️ 必须用原生 SQL 做**物理删除**：
     * <ul>
     *   <li>MP 的 {@code delete} 会走逻辑删除，删不掉</li>
     *   <li>已逻辑删除的行（deleted=1）MP 默认查不出来，也删不掉</li>
     * </ul>
     * 所以直接用 {@link JdbcTemplate} 执行 {@code DELETE}。
     *
     * <p>为什么不给 Mapper 加个 {@code physicalDeleteById}：那是**为测试而改生产代码**，
     * 而且"物理删除日志"这个能力一旦在 Mapper 上存在，就可能被业务代码误用
     * （日志表的设计意图是只增不删）。测试的清理需求应该在测试里自己解决。
     */
    private int cleanupTagged() {
        return jdbcTemplate.update(
                "DELETE FROM sys_oper_log WHERE oper_desc LIKE ?", TEST_TAG + "%");
    }

    /**
     * 兜底清理：**每个测试方法跑完后**都清理一次，确保不留测试数据。
     *
     * <p>为什么放在 {@code @After} 而不是每个测试末尾自己调：
     * 测试中途 {@code fail()} 或抛异常时，末尾的清理代码根本不会执行 ——
     * 而"测试失败"恰恰是最需要清理干净的时候（否则残留数据会干扰下一次排查）。
     * 用 {@code @After} 由框架保证执行，无论测试成败。
     *
     * <p>另外这也让每个测试方法不必关心清理，专注在自己的断言上。
     */
    @org.junit.After
    public void cleanUpAfterEachTest() {
        int removed = cleanupTagged();
        if (removed > 0) {
            System.out.println("[清理] 测试结束，物理删除测试数据 " + removed + " 行");
        }
    }

    /**
     * 验证"测试数据确实清得干净"—— 这是给上面那些测试的元测试。
     *
     * <p>思路：插一条带标记的数据，手动清理，再确认库里一条都不剩。
     * 如果这条测试失败，说明 {@link #cleanUpAfterEachTest()} 的清理逻辑有问题，
     * 那时就不该信任其它测试"跑完不留痕"的假设了。
     */
    @Test
    public void testCleanupActuallyRemovesTestData() {
        insertTaggedLog("待清理");

        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE oper_desc LIKE ?",
                Integer.class, TEST_TAG + "%");
        assertTrue("清理前应有测试数据", before != null && before > 0);

        cleanupTagged();

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE oper_desc LIKE ?",
                Integer.class, TEST_TAG + "%");
        assertEquals("清理后不应有残留测试数据", Integer.valueOf(0), after);

        System.out.println("[实测] 测试数据清理有效：清理前 " + before + " 行 → 清理后 0 行");
    }
}
