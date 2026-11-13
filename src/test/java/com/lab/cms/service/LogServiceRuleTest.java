package com.lab.cms.service;

import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.SpringConfig;
import com.lab.cms.dto.LoginLogQueryDTO;
import com.lab.cms.dto.OperLogQueryDTO;
import com.lab.cms.vo.DailyLoginCountVO;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.LogStatsVO;
import com.lab.cms.vo.LoginLogVO;
import com.lab.cms.vo.OperLogVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 【S24】日志查询与统计接口的业务规则测试（真库，事务内回滚）。
 *
 * <h3>本类的写法与另外两个测试类不同：全部"自造数据 + 唯一关键字"</h3>
 * <p>日志表是全项目<b>唯一会被真实流量持续写入</b>的表
 * （{@code WebLogAspect} 每个请求写 {@code sys_oper_log}、每次登录写 {@code sys_login_log}），
 * 所以任何"库里一共有 N 条日志"的断言都会在真机联调之后变成假失败。
 * <p>本类因此一律：
 * <ul>
 *   <li>用带随机后缀的唯一 {@code module} / {@code username} 插入自己的行，
 *       再按该关键字过滤 —— 结果只含自己的数据，与库里已有多少条无关；</li>
 *   <li>{@code stats()} 是全库统计、无法隔离，改为断言<b>关系不变量</b>
 *       （"统计值 == 直接 SQL 数出来的值"），而不是硬编码数字。</li>
 * </ul>
 *
 * <h3>⚠️ 本类钉住的一条最容易写错的规则</h3>
 * <p>{@code sys_oper_log.status} 的失败是 <b>0</b>，
 * 而 {@code sys_login_log.login_status} 的失败是 <b>2</b>（不是 0）。
 * 两张表两套口径，见 {@link #testLoginStatusDescDistinguishesTwoFromZero()}。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = SpringConfig.class)
@Transactional
public class LogServiceRuleTest {

    @Autowired
    private LogService logService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /** 唯一关键字：保证只命中本用例自己插入的行。 */
    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void insertOperLog(String module, String operType, String operName, int status,
                              String requestParam) {
        jdbc().update("INSERT INTO sys_oper_log "
                        + "(module, oper_type, oper_desc, oper_name, status, cost_time, request_param, oper_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                module, operType, "S24测试操作", operName, status, 12L, requestParam);
    }

    private void insertLoginLog(String username, int loginStatus, String userAgent) {
        jdbc().update("INSERT INTO sys_login_log "
                        + "(username, user_id, ip, user_agent, login_status, message, login_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                username, 2L, "127.0.0.1", userAgent, loginStatus, "S24测试登录");
    }

    // ==================== 一、操作日志查询 ====================

    @Test
    public void testPageOperLogsFiltersByModule() {
        String module = uniqueKey("S24模块");
        String operName = uniqueKey("S24操作员");
        insertOperLog(module, "CREATE", operName, 1, "{}");
        insertOperLog(module, "UPDATE", operName, 1, "{}");
        insertOperLog(module, "UPDATE", operName, 0, "{}");
        insertOperLog(module, "DELETE", operName, 1, "{}");

        OperLogQueryDTO q = new OperLogQueryDTO();
        q.setModule(module);
        q.setSize(100);
        assertEquals("按唯一模块名应正好命中自己造的 4 条",
                Long.valueOf(4L), logService.pageOperLogs(q).getTotal());

        // 模块名是"包含"匹配
        OperLogQueryDTO partial = new OperLogQueryDTO();
        partial.setModule(module.substring(0, 6));
        partial.setSize(100);
        assertTrue("模块名应当支持模糊匹配", logService.pageOperLogs(partial).getTotal() >= 4);

        System.out.println("[S24] 操作日志按模块过滤：命中 4 条");
    }

    @Test
    public void testPageOperLogsFiltersByStatusAndType() {
        String module = uniqueKey("S24状态");
        String operName = uniqueKey("S24操作员");
        insertOperLog(module, "CREATE", operName, 1, "{}");
        insertOperLog(module, "UPDATE", operName, 1, "{}");
        insertOperLog(module, "UPDATE", operName, 0, "{}");

        OperLogQueryDTO success = new OperLogQueryDTO();
        success.setModule(module);
        success.setStatus(1);
        success.setSize(100);
        assertEquals("成功 2 条", Long.valueOf(2L), logService.pageOperLogs(success).getTotal());

        OperLogQueryDTO fail = new OperLogQueryDTO();
        fail.setModule(module);
        fail.setStatus(0);
        fail.setSize(100);
        List<OperLogVO> failRows = logService.pageOperLogs(fail).getRecords();
        assertEquals("失败 1 条", 1, failRows.size());
        assertEquals("statusDesc 应为「失败」", "失败", failRows.get(0).getStatusDesc());

        OperLogQueryDTO byType = new OperLogQueryDTO();
        byType.setModule(module);
        byType.setOperType("UPDATE");
        byType.setSize(100);
        assertEquals("UPDATE 2 条", Long.valueOf(2L), logService.pageOperLogs(byType).getTotal());

        System.out.println("[S24] 操作日志按 status/operType 过滤正确（1 成功↔0 失败）");
    }

    @Test
    public void testPageOperLogsFiltersByOperNameAndTimeRange() {
        String module = uniqueKey("S24时间");
        String operName = uniqueKey("S24时间操作员");
        insertOperLog(module, "CREATE", operName, 1, "{}");

        OperLogQueryDTO byName = new OperLogQueryDTO();
        byName.setOperName(operName);
        byName.setSize(100);
        assertEquals("按唯一操作人应命中 1 条", Long.valueOf(1L), logService.pageOperLogs(byName).getTotal());

        // ⚠️ 时间过滤打在 oper_time（业务时间），不是 create_time
        OperLogQueryDTO inRange = new OperLogQueryDTO();
        inRange.setModule(module);
        inRange.setBeginTime(LocalDateTime.now().minusMinutes(10));
        inRange.setEndTime(LocalDateTime.now().plusMinutes(10));
        inRange.setSize(100);
        assertEquals("区间内应命中", Long.valueOf(1L), logService.pageOperLogs(inRange).getTotal());

        OperLogQueryDTO outOfRange = new OperLogQueryDTO();
        outOfRange.setModule(module);
        outOfRange.setEndTime(LocalDateTime.now().minusDays(1));
        assertEquals("区间外应 0 条", Long.valueOf(0L), logService.pageOperLogs(outOfRange).getTotal());

        System.out.println("[S24] 操作日志按 oper_name 与 oper_time 区间过滤正确");
    }

    @Test
    public void testPageOperLogsRejectsInvertedTimeRange() {
        OperLogQueryDTO q = new OperLogQueryDTO();
        q.setBeginTime(LocalDateTime.now());
        q.setEndTime(LocalDateTime.now().minusDays(1));
        try {
            logService.pageOperLogs(q);
            fail("开始时间晚于结束时间应被拒（静默返回空列表会让人误判「这段时间没日志」）");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
            System.out.println("[S24] 时间区间倒置被拒：" + e.getMessage());
        }
    }

    @Test
    public void testOperLogTruncatesLongFields() {
        String module = uniqueKey("S24截断");
        StringBuilder longParam = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longParam.append('x');
        }
        insertOperLog(module, "CREATE", "S24截断", 1, longParam.toString());

        OperLogQueryDTO q = new OperLogQueryDTO();
        q.setModule(module);
        q.setSize(100);
        OperLogVO vo = logService.pageOperLogs(q).getRecords().get(0);

        assertEquals("库里的原始长度是 500", 500, longParam.length());
        assertTrue("出参应被截断到 200 字符 + 长度提示，实际长度=" + vo.getRequestParam().length(),
                vo.getRequestParam().length() < 500);
        assertTrue("被截断时必须带上原始长度提示（否则会以为日志本来就短）："
                        + vo.getRequestParam(),
                vo.getRequestParam().contains("共 500 字符"));
        System.out.println("[S24] 操作日志长参数已截断：" + vo.getRequestParam().length() + " 字符，含长度提示");
    }

    @Test
    public void testOperLogTypeDescResolvedFromEnumName() {
        String module = uniqueKey("S24枚举");
        insertOperLog(module, "CREATE", "S24枚举", 1, "{}");
        insertOperLog(module, "AUDIT", "S24枚举", 1, "{}");
        insertOperLog(module, "S24_不存在的类型", "S24枚举", 1, "{}");

        OperLogQueryDTO q = new OperLogQueryDTO();
        q.setModule(module);
        q.setSize(100);
        List<OperLogVO> rows = logService.pageOperLogs(q).getRecords();
        assertEquals(3, rows.size());

        boolean sawCreate = false;
        boolean sawAudit = false;
        boolean sawDirty = false;
        for (OperLogVO vo : rows) {
            if ("CREATE".equals(vo.getOperType())) {
                assertEquals("操作类型中文应来自 OperType 枚举", "新增", vo.getOperTypeDesc());
                sawCreate = true;
            } else if ("AUDIT".equals(vo.getOperType())) {
                assertEquals("审核", vo.getOperTypeDesc());
                sawAudit = true;
            } else {
                // ⚠️ 脏枚举名不给中文、也不报错 —— 类型展示不出来不该让整页日志打不开
                assertNull("查不到的枚举名应给 null 而不是抛异常", vo.getOperTypeDesc());
                sawDirty = true;
            }
        }
        assertTrue(sawCreate && sawAudit && sawDirty);
        System.out.println("[S24] 操作类型中文反查：CREATE→新增、AUDIT→审核、脏值→null（未 500）");
    }

    // ==================== 二、登录日志查询 ====================

    @Test
    public void testPageLoginLogsFiltersByUsernameAndStatus() {
        String username = uniqueKey("s24user");
        insertLoginLog(username, 1, "Mozilla/5.0 S24");
        insertLoginLog(username, 1, "Mozilla/5.0 S24");
        insertLoginLog(username, 2, "Mozilla/5.0 S24");

        LoginLogQueryDTO all = new LoginLogQueryDTO();
        all.setUsername(username);
        all.setSize(100);
        assertEquals("按唯一用户名应命中 3 条", Long.valueOf(3L), logService.pageLoginLogs(all).getTotal());

        LoginLogQueryDTO ok = new LoginLogQueryDTO();
        ok.setUsername(username);
        ok.setLoginStatus(1);
        ok.setSize(100);
        assertEquals("登录成功 2 条", Long.valueOf(2L), logService.pageLoginLogs(ok).getTotal());

        LoginLogQueryDTO bad = new LoginLogQueryDTO();
        bad.setUsername(username);
        bad.setLoginStatus(2);
        bad.setSize(100);
        List<LoginLogVO> badRows = logService.pageLoginLogs(bad).getRecords();
        assertEquals("登录失败 1 条", 1, badRows.size());
        assertEquals("loginStatusDesc 应为「失败」", "失败", badRows.get(0).getLoginStatusDesc());
        assertEquals(username, badRows.get(0).getUsername());

        System.out.println("[S24] 登录日志按用户名/状态过滤正确：3 条中 2 成功 1 失败");
    }

    /**
     * ⚠️ 本用例钉住两张表"失败值不同"这条最容易混淆的规则。
     *
     * <p>{@code sys_oper_log.status}：1 成功 / <b>0</b> 失败。
     * <p>{@code sys_login_log.login_status}：1 成功 / <b>2</b> 失败 ——
     * 所以 {@code login_status = 0} 既不是成功也不是失败，中文给"未知"。
     */
    @Test
    public void testLoginStatusDescDistinguishesTwoFromZero() {
        String username = uniqueKey("s24status");
        insertLoginLog(username, 1, "UA");
        insertLoginLog(username, 2, "UA");
        insertLoginLog(username, 0, "UA");   // 脏值：不是 1 也不是 2

        LoginLogQueryDTO q = new LoginLogQueryDTO();
        q.setUsername(username);
        q.setSize(100);
        List<LoginLogVO> rows = logService.pageLoginLogs(q).getRecords();
        assertEquals(3, rows.size());

        int success = 0;
        int fail = 0;
        int unknown = 0;
        for (LoginLogVO vo : rows) {
            if (Integer.valueOf(1).equals(vo.getLoginStatus())) {
                assertEquals("成功", vo.getLoginStatusDesc());
                success++;
            } else if (Integer.valueOf(2).equals(vo.getLoginStatus())) {
                assertEquals("登录失败值是 2（不是 0）", "失败", vo.getLoginStatusDesc());
                fail++;
            } else {
                assertEquals("0 不是登录失败的取值，中文应给「未知」", "未知", vo.getLoginStatusDesc());
                unknown++;
            }
        }
        assertEquals(Integer.valueOf(1), Integer.valueOf(success));
        assertEquals(Integer.valueOf(1), Integer.valueOf(fail));
        assertEquals(Integer.valueOf(1), Integer.valueOf(unknown));
        System.out.println("[S24] 登录状态口径已钉住：1→成功、2→失败、0→未知");
    }

    @Test
    public void testLoginLogUserAgentTruncated() {
        String username = uniqueKey("s24ua");
        StringBuilder ua = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            ua.append('u');
        }
        insertLoginLog(username, 1, ua.toString());

        LoginLogQueryDTO q = new LoginLogQueryDTO();
        q.setUsername(username);
        q.setSize(100);
        LoginLogVO vo = logService.pageLoginLogs(q).getRecords().get(0);
        assertTrue("user_agent 出参应被截断（库中仍是 300）", vo.getUserAgent().length() < 300);
        assertTrue("被截断要带原始长度：" + vo.getUserAgent(), vo.getUserAgent().contains("共 300 字符"));
        System.out.println("[S24] 登录日志 UA 已截断：" + vo.getUserAgent().length() + " 字符");
    }

    @Test
    public void testPageLoginLogsRejectsInvertedTimeRange() {
        LoginLogQueryDTO q = new LoginLogQueryDTO();
        q.setBeginTime(LocalDateTime.now());
        q.setEndTime(LocalDateTime.now().minusHours(1));
        try {
            logService.pageLoginLogs(q);
            fail("开始时间晚于结束时间应被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        }
    }

    // ==================== 三、统计（全库口径，断言关系不变量） ====================

    @Test
    public void testStatsCountsMatchDatabase() {
        String module = uniqueKey("S24统计");
        insertOperLog(module, "CREATE", "S24统计", 1, "{}");
        insertOperLog(module, "UPDATE", "S24统计", 0, "{}");

        LogStatsVO vo = logService.stats(7);

        Long dbOperTotal = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE deleted = 0", Long.class);
        Long dbOperSuccess = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE deleted = 0 AND status = 1", Long.class);
        Long dbOperFail = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE deleted = 0 AND status = 0", Long.class);

        // ⚠️ 断言"统计值 == 直接 SQL 数出来的值"，而不是硬编码数字：
        //    日志表会被真实流量持续写入，硬编码必假失败。
        assertEquals("operTotal 应等于未删除的操作日志总行数", dbOperTotal, vo.getOperTotal());
        assertEquals("operSuccess 应等于 status=1 的行数", dbOperSuccess, vo.getOperSuccess());
        assertEquals("operFail 应等于 status=0 的行数", dbOperFail, vo.getOperFail());

        Long dbLoginTotal = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE deleted = 0", Long.class);
        Long dbLoginSuccess = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE deleted = 0 AND login_status = 1", Long.class);
        Long dbLoginFail = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE deleted = 0 AND login_status = 2", Long.class);

        assertEquals(dbLoginTotal, vo.getLoginTotal());
        assertEquals(dbLoginSuccess, vo.getLoginSuccess());
        assertEquals("loginFail 取的是 login_status = 2（不是 0）", dbLoginFail, vo.getLoginFail());

        // 自洽性：成功 + 失败 不得超过总数（有脏状态值时 total 会更大）
        assertTrue("operSuccess + operFail 不应超过 operTotal",
                vo.getOperSuccess() + vo.getOperFail() <= vo.getOperTotal());
        assertTrue("loginSuccess + loginFail 不应超过 loginTotal",
                vo.getLoginSuccess() + vo.getLoginFail() <= vo.getLoginTotal());

        System.out.println("[S24] 统计口径与库一致：操作 " + vo.getOperTotal()
                + "（成功 " + vo.getOperSuccess() + " / 失败 " + vo.getOperFail() + "）；登录 "
                + vo.getLoginTotal() + "（成功 " + vo.getLoginSuccess()
                + " / 失败 " + vo.getLoginFail() + "）");
    }

    @Test
    public void testStatsFailRateMatchesFormula() {
        LogStatsVO vo = logService.stats(7);

        Long total = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE deleted = 0", Long.class);
        Long fail = jdbc().queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE deleted = 0 AND login_status = 2", Long.class);

        double expected = (total == null || total <= 0) ? 0.0D
                : BigDecimal.valueOf(fail * 100.0D / total)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();

        assertNotNull("失败率不应为 null", vo.getLoginFailRate());
        assertEquals("失败率应为 两位小数、HALF_UP", expected, vo.getLoginFailRate().doubleValue(), 0.0001D);
        System.out.println("[S24] 登录失败率 = " + vo.getLoginFailRate() + "%（失败 " + fail + "/" + total + "）");
    }

    @Test
    public void testStatsRecentDaysNormalization() {
        assertEquals("null → 7（默认值，且不抛异常）", Integer.valueOf(7), logService.stats(null).getRecentDays());
        assertEquals("0 → 7（低于下界）", Integer.valueOf(7), logService.stats(0).getRecentDays());
        assertEquals("-5 → 7（低于下界）", Integer.valueOf(7), logService.stats(-5).getRecentDays());
        assertEquals("3 → 3（区间内原样）", Integer.valueOf(3), logService.stats(3).getRecentDays());
        assertEquals("200 → 90（截到上界，防止拉全表）", Integer.valueOf(90), logService.stats(200).getRecentDays());
        System.out.println("[S24] 走势窗口归一化：null/0/-5→7，3→3，200→90");
    }

    @Test
    public void testStatsTopModulesAndTrendConsistent() {
        String module = uniqueKey("S24排行");
        insertOperLog(module, "CREATE", "S24排行", 1, "{}");
        insertOperLog(module, "CREATE", "S24排行", 1, "{}");
        insertOperLog(module, "CREATE", "S24排行", 1, "{}");

        String username = uniqueKey("s24trend");
        insertLoginLog(username, 1, "UA");
        insertLoginLog(username, 2, "UA");

        LogStatsVO vo = logService.stats(7);
        assertNotNull(vo.getTopModules());
        assertNotNull(vo.getRecentLoginTrend());

        // 我的模块应在 Top 榜里且计数为 3（库里模块数远小于 10，不会被截掉）
        boolean found = false;
        long moduleSum = 0L;
        for (LabelCountVO row : vo.getTopModules()) {
            assertNotNull("模块名不应为 null", row.getLabel());
            assertTrue("计数应为正", row.getCnt() > 0);
            moduleSum += row.getCnt();
            if (module.equals(row.getLabel())) {
                assertEquals("自己造的 3 条应被统计到", Long.valueOf(3L), row.getCnt());
                found = true;
            }
        }
        assertTrue("自造模块应出现在 Top 模块榜里", found);
        assertTrue("Top 模块计数之和不应超过总数（受 LIMIT 10 截断只会更小）",
                moduleSum <= vo.getOperTotal());

        // 走势：窗口内的日期必须都 >= 起始日，且今天的行里应有我插入的成功/失败
        LocalDate since = LocalDate.now().minusDays(vo.getRecentDays() - 1L);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        boolean sawToday = false;
        for (DailyLoginCountVO day : vo.getRecentLoginTrend()) {
            assertTrue("走势日期格式应为 yyyy-MM-dd：" + day.getDay(),
                    day.getDay().matches("\\d{4}-\\d{2}-\\d{2}"));
            assertTrue("走势不应包含窗口外的日期：" + day.getDay(),
                    !LocalDate.parse(day.getDay()).isBefore(since));
            if (today.equals(day.getDay())) {
                sawToday = true;
                assertTrue("今天应有登录成功记录", day.getSuccessCnt() >= 1);
                assertTrue("今天应有登录失败记录", day.getFailCnt() >= 1);
            }
        }
        assertTrue("走势里应包含今天（刚插入过登录记录）", sawToday);

        assertNotNull("generatedAt 应被填充", vo.getGeneratedAt());
        System.out.println("[S24] Top 模块榜含自造模块（3 条）；走势含今天，成功/失败均已统计");
    }

    @Test
    public void testStatsWorksWithEmptyFilterParamsNotRequired() {
        // stats 的入参只有 recentDays，不传也不该抛异常
        LogStatsVO vo = logService.stats(null);
        assertNotNull(vo);
        assertNotNull(vo.getOperTotal());
        assertNotNull(vo.getLoginTotal());
        assertNotNull(vo.getTopModules());
        assertNotNull(vo.getRecentLoginTrend());
    }

    @Test
    public void testNullQueryFallsBackToDefaults() {
        // query 传 null 应当等价于空条件（而不是 NPE）
        assertNotNull(logService.pageOperLogs(null));
        assertNotNull(logService.pageLoginLogs(null));
        assertTrue("空条件下应有操作日志", logService.pageOperLogs(null).getTotal() > 0);
    }
}
