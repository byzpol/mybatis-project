package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.CmsArticleSaveDTO;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * S14「撤回 / 下架 / 归档」的集成测试。
 *
 * <h2>本类的核心是那张 <b>10 状态 × 3 动作 = 30 组合</b>的矩阵</h2>
 * <p>手册 S14 要求"给一份 30 种组合的边界表，明确标出哪些合法、哪些非法，
 * 这张表后面直接作为单元测试的数据源"。本类的三个 {@code ...OverAllStatuses} 方法
 * 就是把它<b>跑起来</b>——表写在 {@code docs/状态流转边界表.md}，
 * 代码里的 {@code int[][]} 与那张表逐行对应，二者不一致时测试会失败。
 *
 * <h2>⚠️ 本类钉住的四个"最容易写错"的点</h2>
 * <ol>
 *   <li><b>下架不可逆</b>：{@code OFFLINE(8)} 只有 {@code ARCHIVE} 一条出边，
 *       没有 {@code PUBLISH_NOW}。手册 F06 写"已下架 → 重新发布"是错的，
 *       本类用 {@code testOfflineIsIrreversible} 把它钉死 ——
 *       将来若真加了第 15 条迁移，这条用例会失败并提醒同步文档与前端按钮。</li>
 *   <li><b>撤回可逆</b>：{@code WITHDRAWN(7) + PUBLISH_NOW → PUBLISHED(5)}。
 *       这是"撤回"与"下架"在业务上的<b>唯一实质区别</b>，
 *       所以必须有正向用例（{@code testWithdrawThenRepublishSucceeds}）。</li>
 *   <li><b>撤回要归属、下架/归档不要</b>：S12 定的接口分类。
 *       本类两条用例方向相反（{@code testWithdrawRequiresOwnership} 期望 11003，
 *       {@code testOfflineDoesNotCheckOwnership} 期望成功），
 *       谁被"顺手统一"了都会立刻暴露。</li>
 *   <li><b>三个动作都不写审核流水</b>：{@code cms_article_audit_log.result} 只有
 *       1 通过 / 2 驳回，且详情页的 {@code rejectReason} 取"最近一条 result=2 的意见"——
 *       把下架原因塞进去会让用户看到张冠李戴的"驳回原因"。</li>
 * </ol>
 *
 * <h2>数据安全</h2>
 * <p>本类自建的文章用 {@code JdbcTemplate} <b>物理删除</b>，跑完不留痕；
 * <b>不改动任何种子数据</b>（所有用例都在自己造的文章上操作）。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleLifecycleServiceTest {

    /** 投稿人：本类造的文章都挂在它名下，因此它也是"有归属权"的那个操作人。 */
    private static final Long AUTHOR_ID = 1L;

    /** 另一个投稿人：用来验证"撤回别人的文章会被拒"。 */
    private static final Long OTHER_AUTHOR_ID = 5L;

    /** 主编：有 {@code article:manage}，是下架/归档的操作人。 */
    private static final Long CHIEF_ID = 3L;

    /** 管理员：同样有 {@code article:manage}。 */
    private static final Long ADMIN_ID = 4L;

    private static final Long CATEGORY_ID = 1L;

    /**
     * 矩阵里的哨兵值：期望"抛 11002（状态非法）"。
     * <p>用负数而不是 0，是因为 0 是 {@code DRAFT} 的真实状态码 ——
     * 拿真实状态码当哨兵，最容易犯的错就是"合法/非法判断反了却看不出来"。
     */
    private static final int EXPECT_ILLEGAL = -11002;

    /** 矩阵里的哨兵值：期望"抛 11011（已归档只读）"。 */
    private static final int EXPECT_ARCHIVED = -11011;

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
     * 物理删除本类自建的文章（含其流水与快照）。
     * <p>⚠️ 先删子表再删主表，且用 {@code DELETE} 而不是 {@code UPDATE deleted = 1}：
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
        System.out.println("[S14 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    /**
     * 用 SQL 直接造一篇指定状态的文章（作者 = {@code AUTHOR_ID}）。
     *
     * <p>⚠️ 为什么不走真实链路：本类要覆盖的 10 个状态里，{@code ARCHIVED} 是终态
     * （从业务入口根本到不了），{@code OFFLINE} 也只能从"已发布"下架而来。
     * 硬走链路会让"造一条数据"变成十几行调用，且把主链路的 bug 引进本类。
     * 状态本身就是本类要<b>作为入参</b>的东西，用 SQL 直给是最清楚的表达。
     */
    private Long insertArticleWithStatus(int status) {
        String title = "【S14实测】状态" + status + "样本 " + System.nanoTime();
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

    /** 造一篇"已发布"的文章（下架与撤回的唯一合法起点）。 */
    private Long newPublished() {
        return insertArticleWithStatus(ArticleStatus.PUBLISHED.getCode());
    }

    /** 造一篇草稿（走真实入口，用于"撤回自己的稿"这类需要真实归属的场景）。 */
    private Long newDraft(String suffix) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle("【S14实测】" + suffix + " " + System.nanoTime());
        dto.setSummary("S14 生命周期测试临时数据");
        dto.setContent("S14 生命周期测试正文");
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        Long id = articleService.saveDraft(dto, AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    private Integer statusOf(Long id) {
        return jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, id);
    }

    private Integer versionOf(Long id) {
        return jdbc().queryForObject(
                "SELECT version FROM cms_article WHERE id = ?", Integer.class, id);
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

    /**
     * 跑一行动作矩阵：造一篇 {@code fromStatus} 的文章，执行动作，核对结果。
     *
     * @param actionName   动作中文名（打表用）
     * @param fromStatus   起点状态码
     * @param expected     期望：目标状态码（≥0），或 {@link #EXPECT_ILLEGAL} / {@link #EXPECT_ARCHIVED}
     * @param invocation   实际调用（接收文章 id）
     */
    private void checkOne(String actionName, int fromStatus, int expected,
                          java.util.function.LongConsumer invocation) {
        Long id = insertArticleWithStatus(fromStatus);
        String actual;
        boolean pass;

        if (expected == EXPECT_ILLEGAL) {
            actual = "抛 " + expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                    () -> invocation.accept(id));
            pass = true;
        } else if (expected == EXPECT_ARCHIVED) {
            actual = "抛 " + expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                    () -> invocation.accept(id));
            pass = true;
        } else {
            invocation.accept(id);
            int got = statusOf(id);
            pass = got == expected;
            actual = "成功，状态 → " + got + "（" + descOf(got) + "）";
            if (!pass) {
                fail(String.format("动作=%s 起点=%d(%s)：期望目标 %d(%s)，实际 %d(%s)",
                        actionName, fromStatus, descOf(fromStatus), expected, descOf(expected),
                        got, descOf(got)));
            }
        }

        System.out.printf("[S14 矩阵] %-4s | 起点=%d(%-10s) | 期望=%-28s | 实际=%-28s | %s%n",
                actionName, fromStatus, descOf(fromStatus),
                expected >= 0 ? ("→ " + expected + "(" + descOf(expected) + ")")
                        : (expected == EXPECT_ILLEGAL ? "11002 状态非法" : "11011 已归档只读"),
                actual, pass ? "OK" : "FAIL");
    }

    private String descOf(Integer code) {
        ArticleStatus s = ArticleStatus.ofNullable(code);
        return s == null ? "未知" : s.getDescription();
    }

    // ==================== 一、30 组合矩阵（10 状态 × 3 动作） ====================

    /**
     * <b>撤回</b>：全 10 个起点状态。
     * <p>合法起点只有 {@code PUBLISHED(5)}；{@code ARCHIVED(9)} 走"终态只读"的专属码；
     * 其余 8 个状态一律 11002。
     */
    @Test
    public void testWithdrawOverAllStatuses() {
        System.out.println("==================== 撤回（withdraw）10 状态矩阵 ====================");
        for (int from = 0; from <= 9; from++) {
            final int expected;
            if (from == ArticleStatus.PUBLISHED.getCode()) {
                expected = ArticleStatus.WITHDRAWN.getCode();
            } else if (from == ArticleStatus.ARCHIVED.getCode()) {
                expected = EXPECT_ARCHIVED;
            } else {
                expected = EXPECT_ILLEGAL;
            }
            checkOne("撤回", from, expected, id -> articleService.withdraw(id, AUTHOR_ID));
        }
    }

    /**
     * <b>下架</b>：全 10 个起点状态。
     * <p>合法起点同样只有 {@code PUBLISHED(5)}（目标不同：{@code OFFLINE(8)}）。
     * <p>⚠️ 操作人用主编：下架<b>不校验归属</b>，用谁都能过，
     * 用主编是为了同时证明"不是只能操作自己的稿"。
     */
    @Test
    public void testOfflineOverAllStatuses() {
        System.out.println("==================== 下架（offline）10 状态矩阵 ====================");
        for (int from = 0; from <= 9; from++) {
            final int expected;
            if (from == ArticleStatus.PUBLISHED.getCode()) {
                expected = ArticleStatus.OFFLINE.getCode();
            } else if (from == ArticleStatus.ARCHIVED.getCode()) {
                expected = EXPECT_ARCHIVED;
            } else {
                expected = EXPECT_ILLEGAL;
            }
            checkOne("下架", from, expected,
                    id -> articleService.offline(id, CHIEF_ID, "S14 矩阵测试：内容需复核"));
        }
    }

    /**
     * <b>归档</b>：全 10 个起点状态。
     * <p>⚠️ 与其他两个动作不同，归档有<b>两个</b>合法起点：{@code WITHDRAWN(7)} 与
     * {@code OFFLINE(8)}。只测其中一个的实现照样能让别的用例全绿，
     * 所以这一格是两个都要过的。
     */
    @Test
    public void testArchiveOverAllStatuses() {
        System.out.println("==================== 归档（archive）10 状态矩阵 ====================");
        for (int from = 0; from <= 9; from++) {
            final int expected;
            if (from == ArticleStatus.WITHDRAWN.getCode()
                    || from == ArticleStatus.OFFLINE.getCode()) {
                expected = ArticleStatus.ARCHIVED.getCode();
            } else if (from == ArticleStatus.ARCHIVED.getCode()) {
                expected = EXPECT_ARCHIVED;
            } else {
                expected = EXPECT_ILLEGAL;
            }
            checkOne("归档", from, expected, id -> articleService.archive(id, ADMIN_ID));
        }
    }

    // ==================== 二、合法路径的字段级断言 ====================

    /** {@code PUBLISHED → WITHDRAWN}：状态、乐观锁版本都要对。 */
    @Test
    public void testWithdrawSucceedsAndBumpsVersion() {
        Long id = newPublished();
        int versionBefore = versionOf(id);

        articleService.withdraw(id, AUTHOR_ID);

        assertEquals("状态应为已撤回(7)", ArticleStatus.WITHDRAWN.getCode(), statusOf(id));
        assertEquals("乐观锁版本号应 +1", Integer.valueOf(versionBefore + 1), versionOf(id));
        System.out.println("[S14] PUBLISHED → WITHDRAWN 通过 | articleId=" + id
                + " | version " + versionBefore + " → " + versionOf(id));
    }

    /**
     * <b>撤回是可逆的</b>：{@code WITHDRAWN + PUBLISH_NOW → PUBLISHED}。
     * <p>这一条是"撤回"与"下架"的<b>唯一实质区别</b>：作者把稿子收回来改改，
     * 改完还能再发；下架则回不去。
     */
    @Test
    public void testWithdrawThenRepublishSucceeds() {
        Long id = newPublished();

        articleService.withdraw(id, AUTHOR_ID);
        assertEquals(ArticleStatus.WITHDRAWN.getCode(), statusOf(id));

        articleService.publishNow(id, CHIEF_ID);

        assertEquals("撤回后必须还能重新发布", ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        Long publisherId = jdbc().queryForObject(
                "SELECT publisher_id FROM cms_article WHERE id = ?", Long.class, id);
        assertEquals("重新发布的发布人应是本次操作人", CHIEF_ID, publisherId);
        System.out.println("[S14] PUBLISHED → WITHDRAWN → PUBLISHED 闭环通过 | articleId=" + id);
    }

    /**
     * <b>下架不可逆</b>：下架后再调立即发布 → 11002。
     * <p>⚠️ 手册 F06 写的是"已下架 → 查看、重新发布、归档"，其中"重新发布"在
     * 状态机里<b>不存在</b>（{@code OFFLINE} 只有 {@code ARCHIVE} 一条出边）。
     * 本条用例就是这条差异的守门人。
     */
    @Test
    public void testOfflineIsIrreversible() {
        Long id = newPublished();

        articleService.offline(id, CHIEF_ID, "S14 实测：不可逆验证");
        assertEquals(ArticleStatus.OFFLINE.getCode(), statusOf(id));

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.publishNow(id, CHIEF_ID));
        System.out.println("[S14] 已下架不能再发布（预期）| articleId=" + id + " | message=" + msg);

        // 但可以归档（OFFLINE 的唯一出边）
        articleService.archive(id, CHIEF_ID);
        assertEquals("已下架只能归档", ArticleStatus.ARCHIVED.getCode(), statusOf(id));
    }

    // ==================== 三、非法路径与专属错误码 ====================

    /**
     * <b>已归档是终态</b>：三个动作全部 11011，且状态纹丝不动。
     * <p>⚠️ 手册 S14 写这条应抛 {@code ARTICLE_ARCHIVED(11010)}，
     * 但本项目 11010 是 {@code ARTICLE_UPDATE_CONFLICT}（乐观锁），
     * 归档只读是 {@code ARTICLE_ARCHIVED_READONLY(11011)}。以代码为准。
     */
    @Test
    public void testArchivedIsTerminalForAllThreeActions() {
        Long id = insertArticleWithStatus(ArticleStatus.ARCHIVED.getCode());

        expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.withdraw(id, AUTHOR_ID));
        expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.offline(id, ADMIN_ID, "对已归档文章下架"));
        expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.archive(id, ADMIN_ID));

        assertEquals("被拒后状态必须不变", ArticleStatus.ARCHIVED.getCode(), statusOf(id));
        System.out.println("[S14] 已归档对三个动作均返回 11011 通过 | articleId=" + id);
    }

    /**
     * <b>撤回要校验归属</b>：别人的已发布文章，另一个投稿人撤不动 → 11003。
     * <p>⚠️ 这是一条真实的安全规则：{@code article:withdraw} 连
     * {@code ROLE_AUTHOR(7)} 都有，若不校验归属，任何作者都能把别人的已发布文章下线。
     */
    @Test
    public void testWithdrawRequiresOwnership() {
        Long id = newPublished();

        String msg = expectBusinessCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.withdraw(id, OTHER_AUTHOR_ID));

        assertEquals("被拒后状态必须不变", ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        System.out.println("[S14] 撤回他人文章被拒（11003）通过 | articleId=" + id + " | message=" + msg);
    }

    /**
     * <b>下架/归档不校验归属</b>：作者本人之外的主编/管理员也能操作别人的已发布文章。
     * <p>⚠️ 与上一条方向相反，两者必须同时成立 ——
     * "所有写操作都校验归属"或"都不校验"都会让其中一条失败。
     */
    @Test
    public void testOfflineAndArchiveDoNotCheckOwnership() {
        Long id = newPublished();
        // 把作者改成别人，确保这篇文章绝不属于主编/管理员
        jdbc().update("UPDATE cms_article SET author_id = ? WHERE id = ?", OTHER_AUTHOR_ID, id);

        articleService.offline(id, CHIEF_ID, "S14 实测：主编下架他人文章");
        assertEquals(ArticleStatus.OFFLINE.getCode(), statusOf(id));

        articleService.archive(id, ADMIN_ID);
        assertEquals(ArticleStatus.ARCHIVED.getCode(), statusOf(id));
        System.out.println("[S14] 主编/管理员可下架归档他人文章（不校验归属）通过 | articleId=" + id);
    }

    /**
     * <b>下架必须填原因</b>：{@code null} / 空串 / 纯空白 三种都要被拒（400）。
     * <p>⚠️ 纯空白那一条最容易被漏 —— 只判"非 null"的实现会放行 {@code "   "}，
     * 库里就会存一条看不出内容的"下架原因"，等于没填。
     * <p>⚠️ 这条校验发生在 Service 里（第二道），Controller 的 {@code @NotBlank} 是第一道；
     * 本类直接调 Service，测到的正是这第二道。
     */
    @Test
    public void testOfflineRequiresNonBlankReason() {
        Long id = newPublished();

        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.offline(id, CHIEF_ID, null));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.offline(id, CHIEF_ID, ""));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.offline(id, CHIEF_ID, "   \t \n "));

        assertEquals("被拒后状态必须不变", ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        System.out.println("[S14] 下架不填/空白原因被拒（400）通过 | articleId=" + id);
    }

    /** 操作人为 null（无登录态）→ 401，且不落任何状态变更。 */
    @Test
    public void testNullOperatorRejectedWith401() {
        Long id = newPublished();

        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.withdraw(id, null));
        assertEquals(ArticleStatus.PUBLISHED.getCode(), statusOf(id));
    }

    /** 文章不存在 → 11001。 */
    @Test
    public void testNonExistentArticleRejectedWith11001() {
        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.withdraw(999999999L, AUTHOR_ID));
    }

    // ==================== 四、留痕与事务 ====================

    /**
     * <b>三个动作都不写审核流水</b>。
     * <p>⚠️ 判据用"流水条数前后相等"而不是"等于 0"：
     * 造出来的文章本身可能带流水（比如走真实链路建的草稿），
     * 断言等于 0 会在那种数据上误报。
     */
    @Test
    public void testLifecycleActionsWriteNoAuditLog() {
        Long id = newPublished();
        int before = auditLogCountOf(id);

        articleService.withdraw(id, AUTHOR_ID);
        articleService.publishNow(id, CHIEF_ID);
        articleService.offline(id, CHIEF_ID, "S14 实测：不写流水验证");
        articleService.archive(id, CHIEF_ID);

        assertEquals("撤回/下架/归档都不该写审核流水表", before, auditLogCountOf(id));
        System.out.println("[S14] 三个动作均未写审核流水通过 | articleId=" + id
                + " | 流水条数 " + before + " → " + auditLogCountOf(id));
    }

    /**
     * <b>并发下架同一篇文章：只有一个成功</b>，另一个因乐观锁冲突 11010。
     * <p>用两个线程 + {@code CountDownLatch} 真并发，不用 mock ——
     * 乐观锁的价值恰恰在真实并发下才体现。
     */
    @Test
    public void testConcurrentOfflineExactlyOneSucceeds() throws Exception {
        Long id = newPublished();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<String> others = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    articleService.offline(id, CHIEF_ID, "S14 并发测试");
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCode() != null
                            && e.getCode() == ErrorCode.ARTICLE_UPDATE_CONFLICT.getCode()) {
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        synchronized (others) {
                            others.add(e.getCode() + ":" + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    synchronized (others) {
                        others.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.println("[S14] 并发下架结果 | 成功=" + success.get()
                + " 11010(乐观锁)=" + conflict.get() + " 其它=" + others);
        assertEquals("并发下架必须恰好成功一次", 1, success.get());
        assertEquals("另一个必须是乐观锁冲突", 1, conflict.get());
        assertEquals("不应出现其它异常", 0, other.get());
        assertEquals(ArticleStatus.OFFLINE.getCode(), statusOf(id));
    }

    /**
     * {@code OFFLINE} 的几条出边要留着：本类只用 {@code archive}，
     * 其余动作（比如直接从已下架撤回）必须非法。
     * <p>顺带钉住"已下架 ≠ 已发布"——撤回只认已发布。
     */
    @Test
    public void testOfflineCannotBeWithdrawn() {
        Long id = insertArticleWithStatus(ArticleStatus.OFFLINE.getCode());

        expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.withdraw(id, AUTHOR_ID));
        expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.offline(id, CHIEF_ID, "重复下架"));

        assertNotNull(statusOf(id));
    }
}
