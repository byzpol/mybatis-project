package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.enums.AuditResultEnum;
import com.lab.cms.common.enums.AuditStageEnum;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.mapper.CmsArticleMapper;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S10「编辑初审（含主编终审同段实现）」的集成测试。
 *
 * <h3>这一批用例要证的是什么</h3>
 * <ol>
 *   <li><b>环节由状态反推是对的</b>：同一段代码，在 {@code PENDING_EDITOR} 下写出的流水
 *       {@code audit_stage=1}，在 {@code PENDING_CHIEF} 下写出 {@code =2} ——
 *       证明"合并成一段实现"不是靠猜。</li>
 *   <li><b>三个业务码分得清</b>：状态不在审核环节 → 11002；已归档 → 11011；
 *       审自己的稿 → 11004；驳回不写意见 → 11014。
 *       这几条是手册里写错了码的地方（手册写 11003 / 11005 /
 *       一个根本不存在的 {@code ARTICLE_AUDIT_REJECT_REASON_EMPTY}），
 *       所以必须由测试把<b>正确</b>的码钉住。</li>
 *   <li><b>"改状态"与"写流水"真的在同一个事务里</b> —— 本类里
 *       {@link #testAuditRollsBackStatusWhenLogInsertFails()} 是专门为这一条写的，
 *       做法见该方法的注释。</li>
 *   <li><b>"驳回 → 改稿 → 再提交 → 再初审"这条主链路不会被自己的防重复逻辑挡住</b>
 *       —— 见 {@link #testRejectThenResubmitThenAuditAgainStillWorks()}。
 *       这条是"没有照抄 {@code CmsArticleAuditLog} 类注释里那个
 *       '查 (article_id, audit_stage) 存在性'方案"的<b>证据</b>。</li>
 *   <li>⚠️ <b>一个真实缺口（已登记为挂起待定）</b>：同一账号可以把文章从初审一路审到
 *       APPROVED，因为库里文章域只有一个 {@code article:audit} 权限码、
 *       而环节由状态推导，于是"初审归编辑、终审归主编"这条分工在权限模型里表达不出来。
 *       见 {@link #testSecondAuditCallBecomesFinalAuditNotDuplicate()}。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么连真库、为什么不用 {@code @Transactional} 自动回滚</h3>
 * <p>与 {@code ArticleDraftServiceTest} 同样的理由：本步的核心断言里有两条
 * 只有在真库上才能成立 ——
 * 其一"MySQL 严格模式会让超长 {@code opinion} 报错（错误 1406）"，
 * 其二"这一步报错会让前一步的状态更新被回滚"。
 * 用 mock 或自动回滚都测不到。数据真实落库，由 {@link #cleanUp()} 物理清理。
 *
 * <h3>⚠️ 审计字段的期望值</h3>
 * <p>与 S09 一致：测试环境没有 HTTP 会话，{@code AuditMetaObjectHandler} 取不到登录人，
 * 按项目约定落到 {@code SYSTEM_USER_ID = 0}。而业务字段 {@code auditor_id}
 * 存的是方法参数 {@code operatorId}。两者在测试里必须是不同的值（0 vs 1/2/3），
 * 这本身就是"业务操作人 ≠ 审计操作人"这条设计的证据。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleAuditServiceTest {

    /** 投稿人 author（张晓投稿），即文章作者。 */
    private static final Long AUTHOR_ID = 1L;

    /** 编辑 editor（李编辑）：<b>不是</b>作者，用来做初审。 */
    private static final Long EDITOR_ID = 2L;

    /** 主编 chief（王主编）：用来做终审。 */
    private static final Long CHIEF_ID = 3L;

    /** 已有分类：技术资讯。 */
    private static final Long CATEGORY_ID = 1L;

    /** 一定不存在的文章 id。 */
    private static final Long NOT_EXIST_ID = 99999999L;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private DataSource dataSource;

    /** 本次测试创建的文章 id，{@link #cleanUp()} 用它们清理三张表。 */
    private final List<Long> createdArticleIds = new ArrayList<>();

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @After
    public void cleanUp() {
        if (createdArticleIds.isEmpty()) {
            return;
        }
        String placeholders = createdArticleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = createdArticleIds.toArray();

        // 顺序：审核流水 → 版本快照 → 主表。没有物理外键，顺序不影响能否删掉，
        // 但按"从表到主表"写，语义上更容易看懂。
        int logs = jdbc().update(
                "DELETE FROM cms_article_audit_log WHERE article_id IN (" + placeholders + ")", args);
        int versions = jdbc().update(
                "DELETE FROM cms_article_version WHERE article_id IN (" + placeholders + ")", args);
        int articles = jdbc().update(
                "DELETE FROM cms_article WHERE id IN (" + placeholders + ")", args);

        System.out.println("[S10 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    /** 造一个合法的保存入参。 */
    private CmsArticleSaveDTO saveDto(String title, String content) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary("S10 集成测试临时数据");
        dto.setContent(content);
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    /** 造一个审核入参。 */
    private ArticleAuditDTO auditDto(Integer action, String opinion) {
        ArticleAuditDTO dto = new ArticleAuditDTO();
        dto.setAuditAction(action);
        dto.setAuditOpinion(opinion);
        return dto;
    }

    /** 新建一篇草稿（作者 = author），并登记 id 以便清理。 */
    private Long newDraft(String suffix) {
        Long id = articleService.saveDraft(
                saveDto("【S10实测】" + suffix, "S10 测试正文"), AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    /** 新建草稿 → 提交初审，落成 {@code PENDING_EDITOR} 状态（顺带产生 2 条快照）。 */
    private Long newArticleInPendingEditor(String suffix) {
        Long id = newDraft(suffix);
        articleService.submitForAudit(id, AUTHOR_ID);
        return id;
    }

    /** 新建草稿 → 提交 → 初审通过，落成 {@code PENDING_CHIEF} 状态。 */
    private Long newArticleInPendingChief(String suffix) {
        Long id = newArticleInPendingEditor(suffix);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), CHIEF_ID);
        return id;
    }

    /**
     * 直接用 SQL 造一篇指定状态的文章。
     *
     * <p>用来构造 {@code DRAFT / PUBLISHED / ARCHIVED} 这类
     * "不该出现在审核环节"的状态 —— 其中已发布需要走完两级审核 + 发布，
     * 发布接口要到 S13 才有，所以只能用 SQL 造。
     */
    private Long insertArticleWithStatus(int status) {
        String title = "【S10实测】状态" + status + "样本";
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

    /** 查某篇文章当前状态码。 */
    private Integer statusOf(Long id) {
        return jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, id);
    }

    /** 查某篇文章的审核流水（按 id 升序，即发生顺序）。 */
    private List<Map<String, Object>> auditLogsOf(Long id) {
        return jdbc().queryForList(
                "SELECT audit_stage, result, opinion, auditor_id, article_version, create_by"
                        + " FROM cms_article_audit_log WHERE article_id = ? ORDER BY id", id);
    }

    /** 断言调用抛指定业务码，返回异常信息（便于打印与复用）。 */
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

    // ==================== 核查项 1：初审通过 / 驳回 ====================

    /**
     * 初审通过：{@code PENDING_EDITOR(1) → PENDING_CHIEF(2)}，且流水恰好 1 条
     * （{@code audit_stage=1, result=1}）。
     */
    @Test
    public void testFirstAuditPassMovesToPendingChiefAndWritesOneLog() {
        Long id = newArticleInPendingEditor("初审通过");
        assertEquals("前置状态应为待编辑初审", ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));

        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "内容合规，通过"),
                EDITOR_ID);

        assertEquals("初审通过后状态应为待主编终审",
                ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));

        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals("初审通过应写 1 条流水，实际 " + logs, 1, logs.size());
        Map<String, Object> row = logs.get(0);
        assertEquals("流水环节应为 1（编辑初审）",
                AuditStageEnum.EDITOR_FIRST.getCode(), row.get("audit_stage"));
        assertEquals("流水结论应为 1（通过）", AuditResultEnum.PASS.getCode(), row.get("result"));
        assertEquals("审核人应为业务参数里的编辑", EDITOR_ID.intValue(),
                ((Number) row.get("auditor_id")).intValue());
        assertEquals("通过时也应把意见存下来（不是只有驳回才存）",
                "内容合规，通过", row.get("opinion"));

        // 业务操作人（auditor_id）与审计操作人（create_by）刻意是两套东西。
        assertEquals("测试环境无 HTTP 会话，审计人应为 SYSTEM_USER_ID(0)",
                0, ((Number) row.get("create_by")).intValue());

        System.out.println("[S10] 初审通过：" + id + " → PENDING_CHIEF，流水 1 条"
                + "（stage=1,result=1,auditor=" + row.get("auditor_id")
                + ",articleVersion=" + row.get("article_version") + "）");
    }

    /**
     * 初审驳回：{@code PENDING_EDITOR(1) → REJECTED(4)}，流水 {@code result=2} 且意见入库。
     */
    @Test
    public void testFirstAuditRejectMovesToRejectedAndKeepsOpinion() {
        Long id = newArticleInPendingEditor("初审驳回");

        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "标题与正文不符，请修改"),
                EDITOR_ID);

        assertEquals("初审驳回后状态应为已驳回",
                ArticleStatus.REJECTED.getCode(), statusOf(id));

        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals("驳回应写 1 条流水", 1, logs.size());
        assertEquals(AuditStageEnum.EDITOR_FIRST.getCode(), logs.get(0).get("audit_stage"));
        assertEquals(AuditResultEnum.REJECT.getCode(), logs.get(0).get("result"));
        assertEquals("驳回意见必须原样入库（不带首尾空白）",
                "标题与正文不符，请修改", logs.get(0).get("opinion"));

        System.out.println("[S10] 初审驳回：" + id + " → REJECTED，意见=" + logs.get(0).get("opinion"));
    }

    // ==================== 核查项 2：终审（同一段实现的另一条分支） ====================

    /**
     * 终审通过：{@code PENDING_CHIEF(2) → APPROVED(3)}，流水 {@code audit_stage=2}。
     *
     * <p>这条与 {@link #testFirstAuditPassMovesToPendingChiefAndWritesOneLog()} 调用的是
     * <b>完全相同的 Service 方法</b>（没有第二个 audit 方法），
     * 唯一的差别是文章当前状态。若"环节由状态反推"写错了，
     * 这条断言（stage=2）与上一条（stage=1）必然有一条失败。
     */
    @Test
    public void testFinalAuditPassMovesToApprovedWithStage2Log() {
        Long id = newArticleInPendingChief("终审通过");
        assertEquals("前置状态应为待主编终审", ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));

        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "同意发布"),
                CHIEF_ID);

        assertEquals("终审通过后状态应为审核通过",
                ArticleStatus.APPROVED.getCode(), statusOf(id));

        // 此时该文章应有 2 条流水：初审（stage=1）+ 终审（stage=2），按发生顺序
        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals("应累计 2 条流水（初审 + 终审），实际 " + logs, 2, logs.size());
        assertEquals("第一条是初审", AuditStageEnum.EDITOR_FIRST.getCode(),
                logs.get(0).get("audit_stage"));
        assertEquals("第二条是终审", AuditStageEnum.CHIEF_FINAL.getCode(),
                logs.get(1).get("audit_stage"));
        assertEquals("终审通过的结论应为 1", AuditResultEnum.PASS.getCode(),
                logs.get(1).get("result"));
        assertEquals("终审人应为主编", CHIEF_ID.intValue(),
                ((Number) logs.get(1).get("auditor_id")).intValue());

        System.out.println("[S10] 终审通过：" + id + " → APPROVED，累计流水 " + logs.size() + " 条"
                + "（stage 序列 = " + logs.stream().map(r -> r.get("audit_stage"))
                .collect(Collectors.toList()) + "）");
    }

    /**
     * 终审驳回：{@code PENDING_CHIEF(2) → REJECTED(4)}。
     *
     * <p>顺带钉住一条容易搞反的规则：<b>终审驳回后回到的是 REJECTED(4) 而不是草稿</b>，
     * 且这个状态下作者可以改稿再提交（见 {@code ArticleStatus#isEditable()}）。
     */
    @Test
    public void testFinalAuditRejectMovesToRejected() {
        Long id = newArticleInPendingChief("终审驳回");

        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "选题不符合栏目定位"),
                CHIEF_ID);

        assertEquals("终审驳回后状态应为已驳回",
                ArticleStatus.REJECTED.getCode(), statusOf(id));
        assertEquals("驳回后状态应可再次编辑", ArticleStatus.REJECTED.isEditable(), true);

        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals(2, logs.size());
        assertEquals(AuditStageEnum.CHIEF_FINAL.getCode(), logs.get(1).get("audit_stage"));
        assertEquals(AuditResultEnum.REJECT.getCode(), logs.get(1).get("result"));

        System.out.println("[S10] 终审驳回：" + id + " → REJECTED，终审意见=" + logs.get(1).get("opinion"));
    }

    // ==================== 核查项 3：驳回必须写意见 → 11014 ====================

    /**
     * 驳回不填意见 → {@code 11014}（{@code ARTICLE_AUDIT_OPINION_REQUIRED}）。
     *
     * <p>⚠️ 手册 S10 的核查项写的是"驳回不填意见 → {@code 11005}"，
     * 而 {@code ErrorCode} 里 {@code 11005} 是 {@code ARTICLE_ALREADY_AUDITED}
     * （该阶段已审核过）。手册提到的 {@code ARTICLE_AUDIT_REJECT_REASON_EMPTY}
     * 这个码在 {@code ErrorCode} 里<b>根本不存在</b>。
     * 这条测试把<b>正确的 11014</b> 钉住，防止将来有人照手册改回去。
     */
    @Test
    public void testRejectWithoutOpinionIsRejectedWith11014() {
        Long id = newArticleInPendingEditor("驳回缺意见");

        String msg = expectBusinessCode(ErrorCode.ARTICLE_AUDIT_OPINION_REQUIRED.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), null),
                        EDITOR_ID));

        assertEquals("驳回缺意见时状态必须原地不动",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        assertEquals("驳回缺意见时不能写流水", 0, auditLogsOf(id).size());

        System.out.println("[S10] 驳回不填意见被拒，code="
                + ErrorCode.ARTICLE_AUDIT_OPINION_REQUIRED.getCode() + "，message=" + msg);
    }

    /**
     * 驳回只填空白字符 → 同样 11014。
     *
     * <p>这条盯的是"用 {@code StringUtils.hasText} 而不是 {@code !"" .equals()} "的选择：
     * 全空格的意见在业务上等于没写，若判空只比 {@code ""}，
     * 就会出现"流水里存着 5 个空格的意见"这种看着像有、其实没有的记录。
     */
    @Test
    public void testRejectWithBlankOpinionIsRejectedWith11014() {
        Long id = newArticleInPendingEditor("驳回空白意见");

        expectBusinessCode(ErrorCode.ARTICLE_AUDIT_OPINION_REQUIRED.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "   \n  "),
                        EDITOR_ID));

        assertEquals("全空白意见应被视为未填写，状态不动",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        System.out.println("[S10] 全空白驳回意见被拒（hasText 语义），流水 0 条");
    }

    // ==================== 核查项 4：入参值域与非法状态 ====================

    /**
     * {@code auditAction} 不在 {1,2} → {@code 400}。
     *
     * <p>{@code @NotNull} 只挡"没传"，挡不住"传了 3"。这条验证 Service 里的第二道值域校验。
     */
    @Test
    public void testIllegalAuditActionIsRejectedWith400() {
        Long id = newArticleInPendingEditor("结论非法");

        String msg = expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.audit(id, auditDto(3, "随便写的意见"), EDITOR_ID));

        assertEquals("状态必须原地不动", ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        System.out.println("[S10] auditAction=3 被拒，code=400，message=" + msg);
    }

    /** {@code auditAction} 为 null → 400（Controller 上还有 {@code @NotNull}，这里是第二道）。 */
    @Test
    public void testNullAuditActionIsRejectedWith400() {
        Long id = newArticleInPendingEditor("结论为空");

        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.audit(id, auditDto(null, "x"), EDITOR_ID));

        assertEquals(ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        System.out.println("[S10] auditAction=null 被拒（Service 第二道），状态未变");
    }

    /** 草稿状态不能审核 → 11002（不在审核环节）。 */
    @Test
    public void testAuditDraftIsRejectedWith11002() {
        Long id = newDraft("草稿不能审");

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"),
                        EDITOR_ID));

        assertEquals("状态必须原地不动", ArticleStatus.DRAFT.getCode(), statusOf(id));
        assertTrue("提示应说明当前状态与'不在审核环节'，实际：" + msg,
                msg.contains("不在审核环节"));
        System.out.println("[S10] 草稿不能审核，code=11002，message=" + msg);
    }

    /** 已发布状态不能审核 → 11002。 */
    @Test
    public void testAuditPublishedIsRejectedWith11002() {
        Long id = insertArticleWithStatus(ArticleStatus.PUBLISHED.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"),
                        EDITOR_ID));

        assertEquals(ArticleStatus.PUBLISHED.getCode(), statusOf(id));
        assertTrue("提示应带出当前状态名「已发布」，实际：" + msg, msg.contains("已发布"));
        System.out.println("[S10] 已发布不能审核，code=11002，message=" + msg);
    }

    /**
     * 已归档（终态）→ {@code 11011}，<b>不是</b> 11002。
     *
     * <p>两种拒绝给不同码，是为了让前端提示指向不同的"下一步"：
     * 已归档 = 没救了（终态），其余 = 现在轮不到审核。
     */
    @Test
    public void testAuditArchivedIsRejectedWith11011() {
        Long id = insertArticleWithStatus(ArticleStatus.ARCHIVED.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"),
                        EDITOR_ID));

        assertEquals("状态必须原地不动", ArticleStatus.ARCHIVED.getCode(), statusOf(id));
        System.out.println("[S10] 已归档不能审核，code=11011，message=" + msg);
    }

    /** 文章不存在 → 11001；id 为 null → 400（由 {@code requireArticle} 拦下）。 */
    @Test
    public void testAuditMissingArticleIsRejected() {
        String msg1 = expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.audit(NOT_EXIST_ID,
                        auditDto(AuditResultEnum.PASS.getCode(), "通过"), EDITOR_ID));
        System.out.println("[S10] 不存在的文章 → 11001，message=" + msg1);

        String msg2 = expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.audit(null,
                        auditDto(AuditResultEnum.PASS.getCode(), "通过"), EDITOR_ID));
        System.out.println("[S10] id=null → 400，message=" + msg2);
    }

    // ==================== 核查项 5：作者不能审自己的稿 → 11004 ====================

    /**
     * 作者审自己的稿 → {@code 11004}（{@code ARTICLE_CANNOT_AUDIT_SELF}）。
     *
     * <p>⚠️ 注意这里用的是 {@code AUTHOR_ID} 而不是 {@code EDITOR_ID}。
     * 这条规则与 {@code checkOwnership}（改草稿必须<b>是</b>作者）方向相反：
     * 那条不是作者就拒（11003），这条是作者就拒（11004）。
     * 用同一个码会让前端给不出正确提示。
     */
    @Test
    public void testAuthorCannotAuditOwnArticleWith11004() {
        Long id = newArticleInPendingEditor("自审自稿");

        String msg = expectBusinessCode(ErrorCode.ARTICLE_CANNOT_AUDIT_SELF.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "自己给自己过"),
                        AUTHOR_ID));

        assertEquals("自审被拒后状态必须原地不动",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        assertEquals("自审被拒不能留下流水", 0, auditLogsOf(id).size());

        System.out.println("[S10] 作者审自己的稿被拒，code=11004，message=" + msg);
    }

    /**
     * {@code operatorId} 为 null → 401（不是 NPE）。
     *
     * <p>对应"从 Session 取不到用户时不抛 NPE"这条通用要求；
     * Service 层先 {@code requireOperator} 拦一道，是因为它可能被没有 HTTP 上下文的
     * 定时任务/脚本调用。
     */
    @Test
    public void testAuditWithNullOperatorReturns401() {
        Long id = newArticleInPendingEditor("无操作人");

        String msg = expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"), null));

        System.out.println("[S10] operatorId=null → 401（无 NPE），message=" + msg);
    }

    // ==================== 核查项 6：防重复审核靠状态，不靠查流水 ====================

    /**
     * ⚠️ <b>一审到底：同一账号可以把文章从待初审一路审到"审核通过"。</b>
     *
     * <h3>这条测试是"写错了第一版、被实测纠正"留下的记录</h3>
     * <p>第一版写的是"初审通过后再初审应被拒（11002）"，跑出来<b>失败</b>了 ——
     * 因为"再初审"这件事<b>根本表达不出来</b>：
     * <pre>
     *   第 1 次 audit → 状态 PENDING_EDITOR(1) → 推环节=编辑初审 → 通过 → 状态变 PENDING_CHIEF(2)
     *   第 2 次 audit → 状态 PENDING_CHIEF(2)  → 推环节=主编终审 → 通过 → 状态变 APPROVED(3)
     * </pre>
     * 环节是<b>由状态推出来的</b>，而审核成功后状态必然不再是原来那个 ——
     * 所以"在同一个环节上审第二次"在结构上就不存在。
     * 想让状态回到 PENDING_EDITOR，只能走"驳回 → 作者改稿 → 重新提交"，
     * 而那是<b>新的一轮</b>审核（见 {@link #testRejectThenResubmitThenAuditAgainStillWorks()}）。
     *
     * <h3>由此得出两条结论</h3>
     * <ol>
     *   <li><b>"防重复审核"靠状态机就够，不需要查流水表。</b>
     *       这也是 {@code audit} 里没有实现 {@code CmsArticleAuditLog} 类注释中
     *       "查 {@code (article_id, audit_stage)} 存在性"方案的理由之一。
     *       真正并发的重复请求由乐观锁兜住（第二个请求 {@code version} 不匹配 → 11010）。</li>
     *   <li>⚠️ <b>同时暴露一个真实缺口（已登记为"挂起待定"）：</b>
     *       只要账号有 {@code article:audit} 权限，就能把一篇文章从初审一路审到 APPROVED。
     *       因为库里文章域<b>只有一个审核权限码</b>
     *       （{@code article:audit}，没有 first/final 之分），
     *       而环节又由状态推导 —— 于是"初审归编辑、终审归主编"这条业务分工
     *       <b>在当前权限模型里表达不出来</b>。
     *       编辑（{@code ROLE_EDITOR}）与主编（{@code ROLE_CHIEF}）都有 {@code article:audit}，
     *       所以编辑也能做终审。本测试把这个事实<b>显式钉住</b>而不是假装不存在，
     *       免得评审时才发现、或者将来有人无意中改坏。
     *       要修的话有两条路（都需组内定，且都不属 S10 范围）：
     *       ① 在 {@code sys_permission} 增加 {@code article:audit:final}；
     *       ② 加"同一篇文章的初审人与终审人不能是同一人"的规则（四眼原则）。</li>
     * </ol>
     */
    @Test
    public void testSecondAuditCallBecomesFinalAuditNotDuplicate() {
        Long id = newArticleInPendingEditor("一审到底");
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));

        // 第 1 次：以"编辑初审"的身份通过
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR_ID);
        assertEquals(ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));

        // 第 2 次：同一个账号再调一次 —— 被解释成"主编终审"，因此合法地成功
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "终审通过"), EDITOR_ID);

        assertEquals("第 2 次调用被当作终审，状态走到 APPROVED",
                ArticleStatus.APPROVED.getCode(), statusOf(id));

        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals("两次审核各写一条流水，阶段不同", 2, logs.size());
        assertEquals(AuditStageEnum.EDITOR_FIRST.getCode(), logs.get(0).get("audit_stage"));
        assertEquals(AuditStageEnum.CHIEF_FINAL.getCode(), logs.get(1).get("audit_stage"));
        assertEquals("⚠️ 两条流水的审核人是同一个人（当前权限模型下的已知缺口）",
                logs.get(0).get("auditor_id"), logs.get(1).get("auditor_id"));

        System.out.println("[S10] ⚠️ 一审到底：同一账号(" + EDITOR_ID
                + ") 完成 stage=[1,2]，状态 → APPROVED。"
                + "「初审归编辑、终审归主编」当前权限模型表达不了，已登记为挂起待定");
    }

    /**
     * ⚠️ <b>本类最重要的一条</b>：驳回 → 改稿 → 再提交 → 再初审，必须成功。
     *
     * <h3>它在证明什么</h3>
     * <p>{@code CmsArticleAuditLog} 的类注释把"防重复审核"描述为
     * "查 {@code (article_id, audit_stage)} 是否已有记录，有则拒绝"。
     * <b>若真按字面实现，这条测试会失败</b>：走完"驳回 → 改稿 → 再提交"之后，
     * 该文章在 {@code audit_stage=1} 上<b>已经有了一条历史流水</b>，
     * 存在性检查会把合法的第二次初审误判成"重复审核"。
     * <p>而这一步恰恰是 S11 主链路自测清单里必须走通的（"初审驳回 → 改稿再提交 →
     * 初审通过 → 终审驳回 → 改稿再提交 → 初审通过 → 终审通过"）。
     * <p>所以本测试的价值不只是"测功能"，而是<b>把"不查流水存在性"这个设计决定
     * 变成一条可执行的证据</b>：将来有人照类注释补上那个检查，这里立刻红。
     *
     * <p>同时它还验证了：<b>同一环节可以有多条流水</b>（stage=1 会出现两次），
     * 库表 {@code idx_article_stage} 是普通索引而非唯一索引，正好支持这一点。
     */
    @Test
    public void testRejectThenResubmitThenAuditAgainStillWorks() {
        Long id = newArticleInPendingEditor("驳回后改稿再提交");

        // 第一次初审：驳回
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "第一次驳回：摘要太短"),
                EDITOR_ID);
        assertEquals(ArticleStatus.REJECTED.getCode(), statusOf(id));

        // 作者改稿后重新提交
        articleService.updateDraft(id, saveDto("【S10实测】驳回后改稿再提交（第2版）",
                "S10 测试正文（已按意见修改）"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        assertEquals("改稿再提交后应回到待编辑初审",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));

        // 第二次初审：通过 —— 若实现了"查 (article_id, 1) 是否存在"的防重复，这里会 11004/11005 直接失败
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "第二次通过"), EDITOR_ID);
        assertEquals(ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));

        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals("应累计 2 条初审流水（1 驳回 + 1 通过），实际 " + logs, 2, logs.size());
        assertEquals(AuditStageEnum.EDITOR_FIRST.getCode(), logs.get(0).get("audit_stage"));
        assertEquals(AuditResultEnum.REJECT.getCode(), logs.get(0).get("result"));
        assertEquals(AuditStageEnum.EDITOR_FIRST.getCode(), logs.get(1).get("audit_stage"));
        assertEquals(AuditResultEnum.PASS.getCode(), logs.get(1).get("result"));

        System.out.println("[S10] 驳回→改稿→再提交→再初审 走通；同一环节累计 2 条流水"
                + "（result 序列 = " + logs.stream().map(r -> r.get("result"))
                .collect(Collectors.toList()) + "）");
    }

    // ==================== 核查项 7：事务 —— 改状态与写流水同成功同失败 ====================

    /**
     * ⚠️ <b>把"状态改了但流水丢了"这条不变式变成一条可执行的断言。</b>
     *
     * <h3>怎么构造"写流水失败"</h3>
     * <p>给一个 600 字的 {@code auditOpinion}。库表 {@code opinion VARCHAR(500)}，
     * 本机 {@code sql_mode} 含 {@code STRICT_TRANS_TABLES}，因此 MySQL 会直接报
     * 错误 1406（Data too long for column 'opinion'）而不是静默截断。
     * 这是一个<b>确定性、可复现</b>的失败点，不需要 mock 任何框架内部。
     *
     * <h3>为什么这条能成立，取决于实现里"先改状态、后写流水"的顺序</h3>
     * <p>若按手册的顺序（先流水后状态），这个失败会发生在<b>任何写操作之前</b>，
     * 于是"状态有没有被回滚"根本无从谈起 —— 测试通过的也是废话。
     * 实现里把顺序倒过来之后，这个失败点上游已经有了一次成功的状态更新，
     * 于是它才真正考验 {@code @Transactional}。
     * <p>断言两件事：状态回到 PENDING_EDITOR、流水表一行都没多。
     */
    @Test
    public void testAuditRollsBackStatusWhenLogInsertFails() {
        Long id = newArticleInPendingEditor("事务回滚");

        // 600 个字符，超过 opinion 列的 500 → MySQL 1406
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append('x');
        }
        final String oversizeOpinion = big.toString();
        assertEquals("构造的超长意见应为 600 字", 600, oversizeOpinion.length());

        try {
            articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), oversizeOpinion),
                    EDITOR_ID);
            fail("超长 opinion 应当触发数据库错误，但调用成功了");
        } catch (Exception e) {
            // 不在这里断言异常类型：我们关心的是"它抛了"以及"抛了之后数据是什么样"。
            // 真正的断言在下面两条。
            System.out.println("[S10] 超长 opinion 触发的异常：" + e.getClass().getName()
                    + " | " + String.valueOf(e.getMessage()).split("\n")[0]);
        }

        assertEquals("⚠️ 写流水失败后，状态必须被回滚回待编辑初审（否则就是'状态改了但流水丢了'）",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        assertEquals("⚠️ 失败的那次不能留下流水", 0, auditLogsOf(id).size());

        System.out.println("[S10] 事务验证通过：流水写入失败 → 状态回滚，流水 0 条");
    }

    // ==================== 核查项 8：审核不改内容、不写快照 ====================

    /**
     * 审核只改状态 + 写流水，<b>不产生版本快照</b>，也不动标题正文。
     *
     * <p>这条界定了"快照由谁负责"：创建 / 修改 / 提交三个动作写快照（S09），
     * 审核不写。理由：审核不改内容，快照记录的是"第几版内容"，
     * 审核没有产生新的一版内容，凭空多一条只会让版本链出现无意义的空档。
     */
    @Test
    public void testAuditDoesNotCreateVersionSnapshotNorChangeContent() {
        Long id = newArticleInPendingEditor("审核不写快照");

        Integer versionsBefore = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_version WHERE article_id = ? AND deleted = 0",
                Integer.class, id);
        assertEquals("提交后应有 2 条快照（创建 + 提交）", Integer.valueOf(2), versionsBefore);

        CmsArticle before = articleMapper.selectById(id);

        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"), EDITOR_ID);

        Integer versionsAfter = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_version WHERE article_id = ? AND deleted = 0",
                Integer.class, id);
        assertEquals("审核不应新增快照", versionsBefore, versionsAfter);

        CmsArticle after = articleMapper.selectById(id);
        assertEquals("审核不应改动标题", before.getTitle(), after.getTitle());
        assertEquals("审核不应改动正文", before.getContent(), after.getContent());
        assertEquals("审核不应改动分类", before.getCategoryId(), after.getCategoryId());
        assertNotNull("审核后 update_time 应被填充", after.getUpdateTime());
        assertEquals("审核会推进乐观锁版本号（updateById 会 +1）",
                Integer.valueOf(before.getVersion() + 1), after.getVersion());

        System.out.println("[S10] 审核不改内容：快照数 " + versionsBefore + " → " + versionsAfter
                + "，乐观锁 version " + before.getVersion() + " → " + after.getVersion());
    }

    /**
     * 流水里的 {@code article_version} 记的是"业务快照号"（{@code version_no}），
     * <b>不是</b> {@code cms_article.version}（乐观锁号）。
     *
     * <p>这两者极易混：新建草稿 → 提交初审后，业务快照号是 2（创建 + 提交），
     * 而乐观锁号是 1（只有提交改过一次）。这条断言把两者的区别钉住。
     */
    @Test
    public void testAuditLogArticleVersionIsSnapshotNoNotOptimisticLockVersion() {
        Long id = newArticleInPendingEditor("版本号口径");

        CmsArticle article = articleMapper.selectById(id);
        Integer maxSnapshotNo = jdbc().queryForObject(
                "SELECT MAX(version_no) FROM cms_article_version WHERE article_id = ? AND deleted = 0",
                Integer.class, id);
        assertEquals("提交后业务快照号应为 2", Integer.valueOf(2), maxSnapshotNo);
        assertEquals("此时乐观锁号应为 1（只有提交这一步改过）",
                Integer.valueOf(1), article.getVersion());

        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"), EDITOR_ID);

        Object recorded = auditLogsOf(id).get(0).get("article_version");
        assertNotNull("流水里的 article_version 不应为 null（该文章有快照）", recorded);
        assertEquals("流水记的应是业务快照号 2，不是乐观锁号 1",
                maxSnapshotNo.intValue(), ((Number) recorded).intValue());

        System.out.println("[S10] article_version 口径正确：流水记 " + recorded
                + "（业务快照号），而非乐观锁号 " + article.getVersion());
    }

    /** 审核意见为 null（通过时允许）→ 流水里 opinion 为 NULL，不报错。 */
    @Test
    public void testPassWithoutOpinionStoresNullOpinion() {
        Long id = newArticleInPendingEditor("通过不填意见");

        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), null), EDITOR_ID);

        assertEquals(ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));
        List<Map<String, Object>> logs = auditLogsOf(id);
        assertEquals(1, logs.size());
        assertNull("通过时未填意见，入库应为 NULL", logs.get(0).get("opinion"));

        System.out.println("[S10] 通过不填意见 → 落库 NULL，状态仍推进到 PENDING_CHIEF");
    }
}
