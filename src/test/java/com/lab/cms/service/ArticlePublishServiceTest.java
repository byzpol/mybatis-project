package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.enums.AuditResultEnum;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticlePublishDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S13「排期发布与立即发布」的集成测试。
 *
 * <h2>本类覆盖的规则（对应手册 S13 的 5 条核查项）</h2>
 * <ol>
 *   <li>对 {@code DRAFT} 的文章调发布 → <b>11002</b>
 *       （手册写 11003，本项目 11003 是"只能操作自己创建的文章"，属手册错误码错位区）；</li>
 *   <li>排期时间传过去的时间 → <b>11007</b>
 *       （手册写 11008，本项目 11008 是"该文章不在排期发布状态"）；</li>
 *   <li>排期到点后自动变 {@code PUBLISHED}，且 {@code publish_time} 是<b>实际发布时间</b>；</li>
 *   <li>同一篇文章连点两次立即发布，第二次被拒（不产生两条发布记录）；</li>
 *   <li>定时任务日志里能看到扫描记录（这条只能在真机上验，
 *       见 {@code docs/S13-发布与排期实测记录.md}，本类负责第 1~4 条）。</li>
 * </ol>
 *
 * <h2>⚠️ 本类刻意钉住的三个"看着对但其实错"的地方</h2>
 * <ol>
 *   <li><b>{@code publish_time} 不能填排期时间。</b>填 {@code scheduleTime} 会让
 *       "实际发布时间"与"约定时间"永远相等，看着"很整齐"，但列名说的是事实、不是约定。
 *       本类用"把排期时间改到过去再触发"的方式让两者必然不同，从而能真正区分这两种写法。</li>
 *   <li><b>发布不写审核流水。</b>{@code cms_article_audit_log} 的 {@code audit_stage} 是
 *       NOT NULL 且只允许 1/2（初审/终审），发布事件填不进去 ——
 *       所以"发布成功"的判据里必须包含"流水条数没变"，否则将来有人顺手加一条流水，
 *       表结构上虽然能塞（比如硬写 stage=2），业务上却是错的。</li>
 *   <li><b>过 {@code publishNow} 清空排期时间。</b>这依赖 {@code LambdaUpdateWrapper.set} 的
 *       显式列列；若有人改回 {@code updateById}，null 会被 NOT_NULL 策略静默跳过，
 *       本类的清空断言会失败 —— 这正是它作为守门人的价值。</li>
 * </ol>
 *
 * <h2>数据安全</h2>
 * <p>本类自建的文章用 {@code JdbcTemplate} <b>物理删除</b>（不是逻辑删除），
 * 跑完不留痕；<b>不改动任何种子数据</b>。
 * <p>⚠️ 注意本类<b>不调用</b> {@code ArticlePublishScheduler#publishScheduledArticles()}：
 * 那个方法是"全表扫描到点的排期文章"，而本机库里种子文章 id=14 恰好是到点的排期文章，
 * 直接调会把种子数据改掉（详见 {@code docs/S13-发布与排期实测记录.md} 的"种子数据"一节）。
 * 因此本类只测"单篇触发"（{@code publishScheduled}）与"扫描查询"（{@code findDueScheduledArticleIds}），
 * 把"整轮扫描"留给真机验证 —— 那里才是它的真实运行环境。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticlePublishServiceTest {

    /** 投稿人（种子文章奇数 id 的作者）。 */
    private static final Long AUTHOR_ID = 1L;

    /** 另一个投稿人，用于验证"发布不看归属"。 */
    private static final Long OTHER_AUTHOR_ID = 5L;

    /** 编辑：有 {@code article:audit}，用于把文章推到 PENDING_CHIEF。 */
    private static final Long EDITOR_ID = 2L;

    /** 主编：有 {@code article:publish}，是本类大多数用例的操作人。 */
    private static final Long CHIEF_ID = 3L;

    /** 管理员：同样有 {@code article:publish}。 */
    private static final Long ADMIN_ID = 4L;

    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private final List<Long> createdArticleIds = new ArrayList<>();

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /**
     * 物理删除本类自建的文章。
     * <p>⚠️ 用 {@code DELETE} 而不是 {@code UPDATE deleted = 1}：
     * 逻辑删除会留下行，让"跑完基线回到 20 篇"这条验收判据失效。
     */
    @After
    public void cleanUp() {
        if (createdArticleIds.isEmpty()) {
            return;
        }
        String ph = createdArticleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = createdArticleIds.toArray();
        int logs = jdbc().update(
                "DELETE FROM cms_article_audit_log WHERE article_id IN (" + ph + ")", args);
        int versions = jdbc().update(
                "DELETE FROM cms_article_version WHERE article_id IN (" + ph + ")", args);
        int articles = jdbc().update(
                "DELETE FROM cms_article WHERE id IN (" + ph + ")", args);
        System.out.println("[S13 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    private CmsArticleSaveDTO saveDto(String suffix) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle("【S13实测】" + suffix + " " + System.nanoTime());
        dto.setSummary("S13 发布测试临时数据");
        dto.setContent("S13 发布测试正文");
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    private Long newDraft(String suffix) {
        Long id = articleService.saveDraft(saveDto(suffix), AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    /**
     * 走完整链路把文章推到 {@code APPROVED(3)}：
     * 草稿 → 提交 → 编辑初审通过 → 主编终审通过。
     * <p>用真实链路而不是 SQL 造状态，是为了保证"待发布"这一态与主链路一致
     * （顺带产出 2 条审核流水，可用来验证"发布不写流水"）。
     */
    private Long newApproved(String suffix) {
        Long id = newDraft(suffix);
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "终审通过"), CHIEF_ID);
        return id;
    }

    private ArticleAuditDTO auditDto(int action, String opinion) {
        ArticleAuditDTO dto = new ArticleAuditDTO();
        dto.setAuditAction(action);
        dto.setAuditOpinion(opinion);
        return dto;
    }

    /**
     * 用 SQL 直接造一篇指定状态的文章（作者 = {@code AUTHOR_ID}）。
     * <p>用于构造"不该被允许发布"的状态（已发布/已归档/待初审…），
     * 这些状态要走真实链路造出来代价太高（已归档是终态，压根没法从业务入口到达）。
     */
    private Long insertArticleWithStatus(int status) {
        String title = "【S13实测】状态" + status + "样本 " + System.nanoTime();
        jdbc().update(
                "INSERT INTO cms_article (title, summary, content, category_id, status, author_id,"
                        + " is_top, view_count, version, create_by, update_by, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, 0)",
                title, "摘要", "正文", CATEGORY_ID, status, AUTHOR_ID, AUTHOR_ID, AUTHOR_ID);
        Long id = jdbc().queryForObject(
                "SELECT id FROM cms_article WHERE title = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, title);
        createdArticleIds.add(id);
        return id;
    }

    /** 造一篇 {@code APPROVED} 且<b>带未来排期时间</b>的文章（用于验证"立即发布会清空排期时间"）。 */
    private Long insertApprovedWithScheduleTime(LocalDateTime scheduleTime) {
        Long id = insertArticleWithStatus(ArticleStatus.APPROVED.getCode());
        jdbc().update("UPDATE cms_article SET schedule_time = ? WHERE id = ?",
                toTimestamp(scheduleTime), id);
        return id;
    }

    /**
     * 把某篇文章的排期时间强行改到过去，用来模拟"时间已经走到排期点"。
     *
     * <p>⚠️ 用 <b>Java 侧</b>的时间（{@code LocalDateTime.now().minusMinutes(10)}）
     * 而不是 SQL 的 {@code DATE_SUB(NOW(), ...)}：库里 {@code NOW()} 取的是
     * MySQL 服务器的时钟，Java 取的是 JVM 的时钟。两者一旦有时区/时钟偏差，
     * "改到过去 10 分钟"就可能变成"改到未来"，测试会以"扫描没扫到"的形式
     * 偶发失败 —— 排查时根本想不到是时钟问题。同一个进程内取时间最稳。
     */
    private void forceScheduleTimeToPast(Long id) {
        jdbc().update("UPDATE cms_article SET schedule_time = ? WHERE id = ?",
                toTimestamp(LocalDateTime.now().minusMinutes(10)), id);
    }

    /** {@code LocalDateTime} → {@code Timestamp}，避免依赖驱动对 JDBC 4.2 的支持程度。 */
    private java.sql.Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : java.sql.Timestamp.valueOf(value);
    }

    private Integer statusOf(Long id) {
        return jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, id);
    }

    private Long publisherIdOf(Long id) {
        return jdbc().queryForObject(
                "SELECT publisher_id FROM cms_article WHERE id = ?", Long.class, id);
    }

    private Integer versionOf(Long id) {
        return jdbc().queryForObject(
                "SELECT version FROM cms_article WHERE id = ?", Integer.class, id);
    }

    /**
     * 读 {@code publish_time}。
     * <p>用 {@code Timestamp.class} 而不是 {@code LocalDateTime.class}：
     * {@code Timestamp} 是 JDBC 原生类型，驱动与 Spring 都一定支持，
     * 而 {@code LocalDateTime} 要走 JDBC 4.2 的 {@code getObject(index, Class)}，
     * 支持程度取决于驱动版本 —— 测试代码不该押在这种依赖上。列值为 NULL 时返回 null。
     */
    private LocalDateTime publishTimeOf(Long id) {
        java.sql.Timestamp ts = jdbc().queryForObject(
                "SELECT publish_time FROM cms_article WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private LocalDateTime scheduleTimeOf(Long id) {
        java.sql.Timestamp ts = jdbc().queryForObject(
                "SELECT schedule_time FROM cms_article WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private int auditLogCountOf(Long id) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_audit_log WHERE article_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    /** 断言调用抛指定业务码，返回异常 message（便于打印与排查）。 */
    private String expectBusinessCode(int expectedCode, Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            assertEquals("业务码不符：期望 " + expectedCode + "，实际 " + e.getCode()
                    + "（message=" + e.getMessage() + "）", expectedCode, (int) e.getCode());
            return e.getMessage();
        }
        fail("期望抛出 BusinessException(" + expectedCode + ")，但调用成功了");
        return null;
    }

    // ==================== 一、立即发布：正向 ====================

    /**
     * {@code APPROVED → PUBLISHED}：状态、发布人、发布时间三个字段都要落对，
     * 且乐观锁 {@code version} 递增。
     */
    @Test
    public void testPublishNowFromApprovedSucceeds() {
        Long id = newApproved("立即发布成功");
        int versionBefore = versionOf(id);

        articleService.publishNow(id, CHIEF_ID);

        assertEquals("状态应为已发布(5)",
                ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertEquals("发布人应是操作人", CHIEF_ID, publisherIdOf(id));
        assertNotNull("发布时间必须落库", publishTimeOf(id));
        assertNull("立即发布必须清空排期时间", scheduleTimeOf(id));
        assertEquals("乐观锁版本号应 +1", Integer.valueOf(versionBefore + 1), versionOf(id));
        System.out.println("[S13] APPROVED → PUBLISHED 通过 | articleId=" + id
                + " | publishTime=" + publishTimeOf(id) + " | version " + versionBefore
                + " → " + versionOf(id));
    }

    /**
     * {@code WITHDRAWN → PUBLISHED}：{@code PUBLISH_NOW} 的<b>另一个</b>合法起点。
     * <p>⚠️ 必须有这条正向对照：只测 {@code APPROVED} 的话，一个"只允许
     * {@code APPROVED} 发布"的实现也能让其它用例全绿，而状态机的表里明明写着两条出边。
     */
    @Test
    public void testPublishNowFromWithdrawnSucceeds() {
        Long id = insertArticleWithStatus(ArticleStatus.WITHDRAWN.getCode());

        articleService.publishNow(id, CHIEF_ID);

        assertEquals("已撤回(7) 也应能立即发布", ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertEquals(CHIEF_ID, publisherIdOf(id));
        assertNotNull(publishTimeOf(id));
        System.out.println("[S13] WITHDRAWN → PUBLISHED 通过 | articleId=" + id);
    }

    /**
     * 发布不校验归属：<b>另一个作者</b>的文章，主编也能发。
     * <p>⚠️ 这是与"改草稿/提交"（11003）<b>相反</b>的一条规则，专门钉住：
     * 若将来有人给 {@code publishNow} 顺手加上 {@code checkOwnership}，
     * 本用例会失败 —— 而线上表现会是"主编永远发不了作者的稿"，
     * 整个审核发布链路断在最后一步。
     */
    @Test
    public void testPublishNowDoesNotCheckOwnership() {
        Long id = newDraft("归属校验");
        // 把作者改成"另一个投稿人"，确保它不属于主编（主编反正是谁的文章都不属于）
        jdbc().update("UPDATE cms_article SET author_id = ? WHERE id = ?", OTHER_AUTHOR_ID, id);
        jdbc().update("UPDATE cms_article SET status = ? WHERE id = ?",
                ArticleStatus.APPROVED.getCode(), id);

        articleService.publishNow(id, CHIEF_ID);

        assertEquals(ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        System.out.println("[S13] 发布不校验归属（已通过）| articleId=" + id
                + " | authorId=" + OTHER_AUTHOR_ID + " | operatorId=" + CHIEF_ID);
    }

    /**
     * 立即发布必须把 {@code schedule_time} 清回 NULL。
     * <p>⚠️ 构造方式：先造一篇"已通过 + 带未来排期时间"的文章
     * （模拟"排过期又想立刻发"），发布后断言排期时间被清空。
     */
    @Test
    public void testPublishNowClearsScheduleTime() {
        Long id = insertApprovedWithScheduleTime(LocalDateTime.now().plusDays(1));
        assertNotNull("前置条件：排期时间应先存在", scheduleTimeOf(id));

        articleService.publishNow(id, CHIEF_ID);

        assertNull("立即发布后排期时间必须是 NULL", scheduleTimeOf(id));
        System.out.println("[S13] 排期时间已清空（已通过）| articleId=" + id);
    }

    /**
     * 发布<b>不写审核流水</b>：走完整链路的文章有 2 条流水，发布后仍是 2 条。
     * <p>⚠️ 判据是"前后条数相等"，不是"等于 0" —— 后者在"用 SQL 造的文章"上
     * 天然成立（它本来就没有流水），证明不了"发布不写流水"这件事。
     */
    @Test
    public void testPublishNowWritesNoAuditLog() {
        Long id = newApproved("不写流水");
        int before = auditLogCountOf(id);
        assertEquals("主链路走完后应恰好 2 条流水（初审 + 终审）", 2, before);

        articleService.publishNow(id, CHIEF_ID);

        assertEquals("发布不应新增审核流水", before, auditLogCountOf(id));
        System.out.println("[S13] 发布未写审核流水（已通过）| articleId=" + id
                + " | 流水条数 " + before + " → " + auditLogCountOf(id));
    }

    // ==================== 二、立即发布：拒绝场景 ====================

    /**
     * 核查项 1：对 {@code DRAFT} 调发布 → <b>11002</b>。
     * <p>⚠️ 手册写 11003，本项目 11003 是"只能操作自己创建的文章"
     * （{@code ARTICLE_NOT_OWNER}），状态类问题的码是 {@code ARTICLE_STATUS_ILLEGAL(11002)}。
     */
    @Test
    public void testPublishNowOnDraftRejectedWith11002() {
        Long id = newDraft("草稿不可发布");

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.publishNow(id, CHIEF_ID));

        assertEquals("状态不应被改动", ArticleStatus.DRAFT.getCode(), statusOf(id));
        System.out.println("[S13] 核查项1 DRAFT 发布被拒 → 11002 | message=" + msg);
    }

    /** 待初审 / 待终审都不允许发布（都在审核流转中，只有 APPROVED 才轮得到发布）。 */
    @Test
    public void testPublishNowOnUnderAuditRejectedWith11002() {
        for (ArticleStatus s : new ArticleStatus[]{
                ArticleStatus.PENDING_EDITOR, ArticleStatus.PENDING_CHIEF}) {
            Long id = insertArticleWithStatus(s.getCode());
            expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                    () -> articleService.publishNow(id, CHIEF_ID));
            assertEquals(s.getCode(), statusOf(id));
        }
        System.out.println("[S13] 待初审/待终审 发布均被拒 → 11002");
    }

    /**
     * 核查项 4：<b>连点两次</b>立即发布，第二次被拒。
     * <p>第一次成功把状态变成 {@code PUBLISHED(5)}，第二次因为"已发布不能再发布"被判 11002。
     * <p>⚠️ 这里用的判据是"第二次必须报错"，而不是"没有两条发布记录" ——
     * 因为发布记录根本不存在（发布会写 {@code sys_oper_log} 但不写业务表），
     * "没有两条"这种断言在空集上恒真，等于没测。真正要证的是<b>状态只推进一次</b>：
     * 若第二次静默成功，说明状态校验被绕过。
     */
    @Test
    public void testSecondPublishNowIsRejected() {
        Long id = newApproved("连点两次");
        articleService.publishNow(id, CHIEF_ID);
        LocalDateTime firstPublishTime = publishTimeOf(id);

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.publishNow(id, CHIEF_ID));

        assertEquals(ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertEquals("发布时间不应被第二次调用改写", firstPublishTime, publishTimeOf(id));
        assertEquals("发布人不应被改写", CHIEF_ID, publisherIdOf(id));
        System.out.println("[S13] 核查项4 第二次发布被拒 → 11002 | message=" + msg
                + " | publishTime 未被改写=" + firstPublishTime);
    }

    /** 已经下架的文章不能"重新发布"：{@code OFFLINE(8)} 只有 {@code ARCHIVE} 一条出边。 */
    @Test
    public void testPublishNowOnOfflineRejectedWith11002() {
        Long id = insertArticleWithStatus(ArticleStatus.OFFLINE.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.publishNow(id, CHIEF_ID));

        assertEquals(ArticleStatus.OFFLINE.getCode(), statusOf(id));
        System.out.println("[S13] 已下架不可重新发布 → 11002（状态机无该出边）| message=" + msg);
    }

    /** 已归档是终态，报的是更具体的 <b>11011</b>（"已归档的文章不可修改"），不是 11002。 */
    @Test
    public void testPublishNowOnArchivedRejectedWith11011() {
        Long id = insertArticleWithStatus(ArticleStatus.ARCHIVED.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.publishNow(id, CHIEF_ID));

        assertEquals(ArticleStatus.ARCHIVED.getCode(), statusOf(id));
        System.out.println("[S13] 已归档发布被拒 → 11011 | message=" + msg);
    }

    /** 文章不存在 → 11001。 */
    @Test
    public void testPublishNowOnMissingArticleRejectedWith11001() {
        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.publishNow(99999999L, CHIEF_ID));
        System.out.println("[S13] 不存在的文章发布 → 11001");
    }

    /** 操作人为 null（非 HTTP 入口调用）→ 401，而不是把 null 写进 publisher_id。 */
    @Test
    public void testPublishNowWithNullOperatorRejectedWith401() {
        Long id = newApproved("操作人为空");

        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.publishNow(id, null));

        assertEquals("状态不应被改动", ArticleStatus.APPROVED.getCode(), statusOf(id));
        assertNull("不应写入发布人", publisherIdOf(id));
        System.out.println("[S13] 操作人为 null 发布 → 401，且数据未被改动");
    }

    /**
     * 真并发：两个线程同时发布同一篇 {@code APPROVED} 文章，<b>恰好一个成功</b>。
     *
     * <h3>两个可能的失败形态（都算通过，不能只认一种）</h3>
     * <ul>
     *   <li><b>11010</b>（乐观锁冲突）：两个线程都先读到了 {@code version=0}，
     *       然后各自带 {@code AND version=0} 去更新，后到的那个更新 0 行 ——
     *       <b>这是本用例想验的形态</b>。</li>
     *   <li><b>11002</b>（状态不允许）：后到的线程在"读"的时候就晚了，
     *       读到的是别人已经提交的 {@code status=5}，于是状态机先拒了它。</li>
     * </ul>
     * <p>⚠️ 为什么不干脆只断言 11010：那要能控制"两个线程必须在同一时刻读到同一版本"，
     * 而在不往生产代码里插钩子的前提下做不到 —— 硬写只会得到一条<b>偶发失败</b>的用例
     * （最难查的一类）。真正需要证的是<b>"不会两个都成功"</b>：
     * 如果乐观锁没生效（比如 {@code @Version} 被谁去掉了、或插件没注册），
     * 两次 {@code update} 都会返回 1、都"成功"，本用例立刻红。
     */
    @Test
    public void testConcurrentPublishNowExactlyOneSucceeds() throws Exception {
        Long id = newApproved("并发发布");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict11010 = new AtomicInteger();
        AtomicInteger status11002 = new AtomicInteger();
        List<String> others = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    articleService.publishNow(id, CHIEF_ID);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if ((int) e.getCode() == ErrorCode.ARTICLE_UPDATE_CONFLICT.getCode()) {
                        conflict11010.incrementAndGet();
                    } else if ((int) e.getCode() == ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode()) {
                        status11002.incrementAndGet();
                    } else {
                        others.add(e.getCode() + ":" + e.getMessage());
                    }
                } catch (Exception e) {
                    others.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue("并发任务未在 20 秒内结束", doneGate.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        System.out.println("[S13] 并发发布结果 | 成功=" + success.get()
                + " 11010(乐观锁)=" + conflict11010.get()
                + " 11002(状态)=" + status11002.get() + " 其它=" + others);

        assertEquals("必须恰好一个成功", 1, success.get());
        assertTrue("另一个应被业务码拒绝（11010 或 11002），实际其它异常=" + others, others.isEmpty());
        assertEquals(ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertEquals("发布人只被写一次", CHIEF_ID, publisherIdOf(id));
    }

    // ==================== 三、排期发布 ====================

    /**
     * {@code APPROVED → SCHEDULED}：排期时间落库，且 {@code publish_time} /
     * {@code publisher_id} 必须被显式写回 NULL（此刻还没发布）。
     */
    @Test
    public void testPublishScheduleFromApprovedSucceeds() {
        Long id = newApproved("排期发布成功");
        LocalDateTime scheduleTime = LocalDateTime.now().plusMinutes(30).withNano(0);

        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(scheduleTime);
        articleService.publishSchedule(id, dto, CHIEF_ID);

        assertEquals("状态应为定时待发(6)", ArticleStatus.SCHEDULED.getCode(), statusOf(id));
        assertEquals("排期时间应原样落库", scheduleTime, scheduleTimeOf(id));
        assertNull("尚未发布 → publish_time 必须为 NULL", publishTimeOf(id));
        assertNull("尚未发布 → publisher_id 必须为 NULL", publisherIdOf(id));
        System.out.println("[S13] APPROVED → SCHEDULED 通过 | articleId=" + id
                + " | scheduleTime=" + scheduleTimeOf(id));
    }

    /**
     * 核查项 2：排期时间传<b>过去</b>的时间 → <b>11007</b>。
     * <p>⚠️ 手册写 11008，本项目 11008 是"该文章不在排期发布状态"（用于状态不符），
     * 时间非法用的是 {@code ARTICLE_SCHEDULE_TIME_INVALID(11007)}。
     */
    @Test
    public void testPublishScheduleWithPastTimeRejectedWith11007() {
        Long id = newApproved("排期时间在过去");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().minusHours(1));

        String msg = expectBusinessCode(ErrorCode.ARTICLE_SCHEDULE_TIME_INVALID.getCode(),
                () -> articleService.publishSchedule(id, dto, CHIEF_ID));

        assertEquals("状态不应被改动", ArticleStatus.APPROVED.getCode(), statusOf(id));
        assertNull("失败时不应写入排期时间", scheduleTimeOf(id));
        System.out.println("[S13] 核查项2 过去时间被拒 → 11007 | message=" + msg);
    }

    /**
     * 边界：排期时间在"当前时间 + 59 秒" → 仍拒（要求至少留 1 分钟）。
     *
     * <p>⚠️ 这条用例存在的前提是"最小提前量 = 1 分钟"这个常量不变。
     * 若把常量改小，本条会失败 —— 那正是它要提醒的事：
     * 扫描频率是每分钟一次，提前量小于扫描间隔会让"排期 → 到点发布"的承诺无法兑现。
     */
    @Test
    public void testPublishScheduleWithTimeInsideOneMinuteRejectedWith11007() {
        Long id = newApproved("排期只提前59秒");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusSeconds(59));

        expectBusinessCode(ErrorCode.ARTICLE_SCHEDULE_TIME_INVALID.getCode(),
                () -> articleService.publishSchedule(id, dto, CHIEF_ID));

        assertEquals(ArticleStatus.APPROVED.getCode(), statusOf(id));
        System.out.println("[S13] 排期提前 59 秒被拒 → 11007（最小提前量 1 分钟）");
    }

    /** 边界：刚好过 1 分钟（这里取 +65 秒留出执行耗时余量）→ 成功。 */
    @Test
    public void testPublishScheduleWithTimeJustOverOneMinuteSucceeds() {
        Long id = newApproved("排期提前65秒");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusSeconds(65).withNano(0));

        articleService.publishSchedule(id, dto, CHIEF_ID);

        assertEquals(ArticleStatus.SCHEDULED.getCode(), statusOf(id));
        System.out.println("[S13] 排期提前 65 秒成功（已通过）| articleId=" + id);
    }

    /**
     * 排期时间缺失 → 400（请求本身不完整），<b>不是</b> 11007。
     * <p>两者给用户的下一步动作不同：11007 是"换个时间"，400 是"你没填"。
     */
    @Test
    public void testPublishScheduleWithNullTimeRejectedWith400() {
        Long id = newApproved("排期时间为空");

        // dto 为 null
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.publishSchedule(id, null, CHIEF_ID));

        // dto 非 null 但 scheduleTime 为 null
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.publishSchedule(id, new CmsArticlePublishDTO(), CHIEF_ID));

        assertEquals(ArticleStatus.APPROVED.getCode(), statusOf(id));
        System.out.println("[S13] 排期时间缺失 → 400（而不是 11007）");
    }

    /**
     * 只有 {@code APPROVED} 能排期：{@code DRAFT} / {@code PENDING_EDITOR} /
     * {@code WITHDRAWN} / {@code PUBLISHED} 全部 → 11002。
     * <p>⚠️ 其中 {@code WITHDRAWN} 最容易被想错（"撤回后不是能再发吗"）：
     * 能再发的是<b>立即发布</b>（{@code PUBLISH_NOW}），排期只从 {@code APPROVED} 出发。
     */
    @Test
    public void testPublishScheduleOnNonApprovedRejectedWith11002() {
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusHours(2));
        for (ArticleStatus s : new ArticleStatus[]{
                ArticleStatus.DRAFT, ArticleStatus.PENDING_EDITOR, ArticleStatus.PENDING_CHIEF,
                ArticleStatus.REJECTED, ArticleStatus.PUBLISHED, ArticleStatus.WITHDRAWN,
                ArticleStatus.OFFLINE}) {
            Long id = insertArticleWithStatus(s.getCode());
            String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                    () -> articleService.publishSchedule(id, dto, CHIEF_ID));
            assertEquals(s.getCode(), statusOf(id));
            System.out.println("[S13] 非 APPROVED 排期被拒 | status=" + s.name() + " | message=" + msg);
        }
    }

    /** 重复排期：已经 {@code SCHEDULED} 的文章再排一次 → 11002（幂等性：否）。 */
    @Test
    public void testPublishScheduleTwiceRejectedWith11002() {
        Long id = newApproved("重复排期");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusHours(2).withNano(0));

        articleService.publishSchedule(id, dto, CHIEF_ID);
        LocalDateTime firstScheduleTime = scheduleTimeOf(id);

        CmsArticlePublishDTO again = new CmsArticlePublishDTO();
        again.setScheduleTime(LocalDateTime.now().plusHours(5).withNano(0));
        expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.publishSchedule(id, again, CHIEF_ID));

        assertEquals("第二次排期不应改写第一次的时间", firstScheduleTime, scheduleTimeOf(id));
        System.out.println("[S13] 重复排期被拒 → 11002，排期时间未被改写");
    }

    /** 排期发布不校验归属：主编可以给别人的稿排期（同"立即发布"）。 */
    @Test
    public void testPublishScheduleDoesNotCheckOwnership() {
        Long id = newDraft("排期归属");
        jdbc().update("UPDATE cms_article SET author_id = ? WHERE id = ?", OTHER_AUTHOR_ID, id);
        jdbc().update("UPDATE cms_article SET status = ? WHERE id = ?",
                ArticleStatus.APPROVED.getCode(), id);

        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusHours(1).withNano(0));
        articleService.publishSchedule(id, dto, ADMIN_ID);

        assertEquals(ArticleStatus.SCHEDULED.getCode(), statusOf(id));
        System.out.println("[S13] 排期不校验归属（管理员给他人稿排期，已通过）| articleId=" + id);
    }

    // ==================== 四、定时任务：单篇触发 ====================

    /**
     * 核查项 3：<b>排期到点后自动变 {@code PUBLISHED}</b>，且
     * {@code publish_time} 是<b>实际发布时间</b>（不是排期时间）。
     *
     * <h3>⚠️ 怎么在单元测试里"等到点"</h3>
     * <p>不用 {@code Thread.sleep} 等真实时间（会让测试慢且不稳定），
     * 而是<b>把排期时间改到过去</b>，模拟"时间已经走过排期点"。
     * 这样 {@code schedule_time} 与 {@code publish_time} 必然不相等，
     * 于是"把 publish_time 填成 schedule_time"这种错误写法会立刻被断言抓住。
     */
    @Test
    public void testScheduledTriggerPublishesWhenDue() {
        Long id = newApproved("到点自动发布");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusMinutes(30).withNano(0));
        articleService.publishSchedule(id, dto, CHIEF_ID);

        forceScheduleTimeToPast(id);
        LocalDateTime forcedScheduleTime = scheduleTimeOf(id);

        LocalDateTime beforeTrigger = LocalDateTime.now().withNano(0);
        boolean published = articleService.publishScheduled(id);
        LocalDateTime afterTrigger = LocalDateTime.now().plusSeconds(1);

        assertTrue("到点的排期文章应当被发布", published);
        assertEquals("状态应为已发布(5)", ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertEquals("定时发布的 publisher_id 记为系统用户 0", Long.valueOf(0L), publisherIdOf(id));
        assertNull("发布后排期时间应被清空", scheduleTimeOf(id));

        LocalDateTime publishTime = publishTimeOf(id);
        assertNotNull("发布时间必须落库", publishTime);
        assertFalse("publish_time 不能等于排期时间（那是'约定'，不是'事实'）",
                publishTime.equals(forcedScheduleTime));
        assertTrue("publish_time 应落在本次触发的时间窗内，实际=" + publishTime
                        + "，窗口=[" + beforeTrigger + ", " + afterTrigger + "]",
                !publishTime.isBefore(beforeTrigger) && !publishTime.isAfter(afterTrigger));
        System.out.println("[S13] 核查项3 到点自动发布通过 | articleId=" + id
                + " | scheduleTime=" + forcedScheduleTime + " | publishTime=" + publishTime
                + " | publisherId=0");
    }

    /** 未到点的排期文章：触发时<b>不</b>发布，返回 false，状态与排期时间都不变。 */
    @Test
    public void testScheduledTriggerSkipsWhenNotDue() {
        Long id = newApproved("未到点");
        LocalDateTime scheduleTime = LocalDateTime.now().plusHours(3).withNano(0);
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(scheduleTime);
        articleService.publishSchedule(id, dto, CHIEF_ID);

        boolean published = articleService.publishScheduled(id);

        assertFalse("未到点不应发布", published);
        assertEquals(ArticleStatus.SCHEDULED.getCode(), statusOf(id));
        assertEquals("排期时间不应被改动", scheduleTime, scheduleTimeOf(id));
        assertNull(publishTimeOf(id));
        System.out.println("[S13] 未到点触发被跳过（已通过）| articleId=" + id);
    }

    /**
     * 状态已经变了（比如刚被人撤回或下架）：触发时安静跳过，不抛异常、不改数据。
     * <p>⚠️ 这条对应"为什么用 boolean 而不是异常"的设计决定：
     * 扫描每分钟一次，这类情况属于正常竞态，用异常表达会把错误日志淹没。
     */
    @Test
    public void testScheduledTriggerSkipsWhenStatusChanged() {
        for (ArticleStatus s : new ArticleStatus[]{
                ArticleStatus.DRAFT, ArticleStatus.PUBLISHED, ArticleStatus.ARCHIVED}) {
            Long id = insertArticleWithStatus(s.getCode());
            boolean published = articleService.publishScheduled(id);
            assertFalse("状态为 " + s.name() + " 时不应发布", published);
            assertEquals("状态不应被改动", s.getCode(), statusOf(id));
        }
        System.out.println("[S13] 状态已变时触发被安静跳过（已通过）");
    }

    /** 文章不存在（含被逻辑删除）：触发时跳过，返回 false。 */
    @Test
    public void testScheduledTriggerSkipsMissingArticle() {
        assertFalse(articleService.publishScheduled(99999999L));
        System.out.println("[S13] 文章不存在时触发被跳过（已通过）");
    }

    /** 定时发布同样不写审核流水。 */
    @Test
    public void testScheduledTriggerWritesNoAuditLog() {
        Long id = newApproved("定时不写流水");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusMinutes(30).withNano(0));
        articleService.publishSchedule(id, dto, CHIEF_ID);
        int before = auditLogCountOf(id);

        forceScheduleTimeToPast(id);
        assertTrue(articleService.publishScheduled(id));

        assertEquals("定时发布不应新增审核流水", before, auditLogCountOf(id));
        System.out.println("[S13] 定时发布未写审核流水（已通过）| 流水条数保持 " + before);
    }

    // ==================== 五、扫描查询 ====================

    /**
     * 扫描查询的两个边界：
     * <ol>
     *   <li>截止时间取<b>很早以前</b> → 结果必为空（没有任何文章的排期时间会那么早）；</li>
     *   <li>截止时间取当前 → 包含<b>刚刚被改到过去</b>的那一篇。</li>
     * </ol>
     * <p>⚠️ 不用"结果条数 == N"做断言：那会依赖库里现存多少篇排期文章
     * （本机种子 id=14 就是一篇到点文章），测试会随数据变动而红。
     * 用"包含/不包含特定 id"才稳定。
     */
    @Test
    public void testFindDueScheduledArticleIdsBoundary() {
        List<Long> veryEarly = articleService.findDueScheduledArticleIds(
                LocalDateTime.of(2000, 1, 1, 0, 0));
        assertTrue("2000 年之前不可能有排期文章，结果必须为空，实际=" + veryEarly, veryEarly.isEmpty());

        Long id = newApproved("扫描查询");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusMinutes(30).withNano(0));
        articleService.publishSchedule(id, dto, CHIEF_ID);

        assertFalse("未到点的文章不应出现在扫描结果里",
                articleService.findDueScheduledArticleIds(LocalDateTime.now()).contains(id));

        forceScheduleTimeToPast(id);
        List<Long> due = articleService.findDueScheduledArticleIds(LocalDateTime.now());
        assertTrue("改到过去后应出现在扫描结果里，实际=" + due, due.contains(id));
        System.out.println("[S13] 扫描查询边界通过 | 极早截止=" + veryEarly.size()
                + " 条 | 当前截止=" + due.size() + " 条（含 " + id + "）");
    }

    /** 扫描查询的参数校验：deadline 为 null → 400（而不是拿 null 去查库）。 */
    @Test
    public void testFindDueScheduledArticleIdsWithNullDeadlineRejectedWith400() {
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.findDueScheduledArticleIds(null));
        System.out.println("[S13] 扫描查询 deadline 为 null → 400");
    }

    /**
     * 扫描结果的排序与"只查已到点"这条 SQL 语义的联合验证：
     * 造两篇到点文章（排期时间一先一后），断言结果里的相对顺序正确。
     */
    @Test
    public void testFindDueScheduledArticleIdsOrderedByScheduleTime() {
        Long earlier = newApproved("扫描排序-先");
        Long later = newApproved("扫描排序-后");
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(LocalDateTime.now().plusMinutes(30).withNano(0));
        articleService.publishSchedule(earlier, dto, CHIEF_ID);
        articleService.publishSchedule(later, dto, CHIEF_ID);

        jdbc().update("UPDATE cms_article SET schedule_time = ? WHERE id = ?",
                toTimestamp(LocalDateTime.now().minusMinutes(30)), earlier);
        jdbc().update("UPDATE cms_article SET schedule_time = ? WHERE id = ?",
                toTimestamp(LocalDateTime.now().minusMinutes(20)), later);

        List<Long> due = articleService.findDueScheduledArticleIds(LocalDateTime.now());
        int iEarlier = due.indexOf(earlier);
        int iLater = due.indexOf(later);
        assertTrue("两篇都应出现在结果里，实际=" + due, iEarlier >= 0 && iLater >= 0);
        assertTrue("排期时间更早的应排在前面", iEarlier < iLater);
        System.out.println("[S13] 扫描结果按排期时间正序（已通过）| earlier索引=" + iEarlier
                + " later索引=" + iLater);
    }
}
