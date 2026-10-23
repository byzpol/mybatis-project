package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.enums.AuditResultEnum;
import com.lab.cms.common.enums.AuditStageEnum;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.vo.ArticleAuditLogVO;
import com.lab.cms.vo.CmsArticleDetailVO;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S11「主编终审与审核流水查询」的集成测试，同时承担 <b>★M3 主链路自测</b>。
 *
 * <h3>为什么把"主链路自测"做成一条测试而不是手工点一遍</h3>
 * <p>手册 S11 要求给一份"步骤 / 操作 / 预期状态 / 预期流水条数 / 实测 / 通过"的表格。
 * 手工点一遍能得到表格，但<b>下一次改动就没人再点第二遍</b>。
 * 写成测试之后，这条链路每次 {@code mvn test} 都会被重新走一遍 ——
 * 它保护的是"两级审核整条链路"这个最核心的资产，而不只是某一个方法。
 *
 * <h3>链路与流水条数的对应关系</h3>
 * <pre>
 *   ① 创建草稿                          → DRAFT(0)          流水 0
 *   ② 提交初审                          → PENDING_EDITOR(1) 流水 0
 *   ③ 初审驳回                          → REJECTED(4)        流水 1  [stage1 result2]
 *   ④ 改稿 + 再提交                     → PENDING_EDITOR(1) 流水 1
 *   ⑤ 初审通过                          → PENDING_CHIEF(2)  流水 2  [stage1 result1]
 *   ⑥ 终审驳回                          → REJECTED(4)        流水 3  [stage2 result2]
 *   ⑦ 改稿 + 再提交                     → PENDING_EDITOR(1) 流水 3
 *   ⑧ 初审通过                          → PENDING_CHIEF(2)  流水 4  [stage1 result1]
 *   ⑨ 终审通过                          → APPROVED(3)        流水 5  [stage2 result1]
 * </pre>
 * 最终 <b>APPROVED 时流水恰好 5 条</b> —— 正是手册核查项里的数字。
 *
 * <h3>⚠️ 为什么连真库</h3>
 * <p>本步要证明的三件事都只有真库能证伪：
 * <ol>
 *   <li><b>流水顺序</b>：{@code audit_time} 是秒精度，主链路 5 次审核很可能落在同一秒内。
 *       只按时间排序时顺序不确定，"第一条是不是初审"这类断言会偶发失败 ——
 *       必须靠 {@code ORDER BY audit_time, id} 里的第二排序键兜住（见实现里的注释）。</li>
 *   <li><b>跨表补数据的正确性</b>：作者名、分类名、审核人昵称来自另外两张表，
 *       只有真库能验证拼出来的名字对不对、分类列名有没有写错
 *       （本项目在 {@code category_name} 上踩过"想当然写成 name"的坑）。</li>
 *   <li><b>逻辑删除的下游影响</b>：文章被软删后详情要返回 11001。</li>
 * </ol>
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleDetailServiceTest {

    /** 投稿人 author（张晓投稿），文章作者。 */
    private static final Long AUTHOR_ID = 1L;

    /** 编辑 editor（李编辑）。 */
    private static final Long EDITOR_ID = 2L;

    /** 主编 chief（王主编）。 */
    private static final Long CHIEF_ID = 3L;

    /** 已有分类：技术资讯（id=1），一级分类。 */
    private static final Long CATEGORY_ID = 1L;

    /** author 的昵称，用来断言跨表补数据的正确性。 */
    private static final String AUTHOR_NICKNAME = "张晓投稿";

    /** editor 的昵称。 */
    private static final String EDITOR_NICKNAME = "李编辑";

    /** chief 的昵称。 */
    private static final String CHIEF_NICKNAME = "王主编";

    /** 一定不存在的文章 id。 */
    private static final Long NOT_EXIST_ID = 99999999L;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    /** 本次测试创建的文章 id，{@link #cleanUp()} 用它们清理三张表。 */
    private final List<Long> createdArticleIds = new ArrayList<>();

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
        int logs = jdbc().update(
                "DELETE FROM cms_article_audit_log WHERE article_id IN (" + placeholders + ")", args);
        int versions = jdbc().update(
                "DELETE FROM cms_article_version WHERE article_id IN (" + placeholders + ")", args);
        int articles = jdbc().update(
                "DELETE FROM cms_article WHERE id IN (" + placeholders + ")", args);
        System.out.println("[S11 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    private CmsArticleSaveDTO saveDto(String title, String content) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary("S11 集成测试临时数据");
        dto.setContent(content);
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    private ArticleAuditDTO auditDto(Integer action, String opinion) {
        ArticleAuditDTO dto = new ArticleAuditDTO();
        dto.setAuditAction(action);
        dto.setAuditOpinion(opinion);
        return dto;
    }

    private Long newDraft(String suffix) {
        Long id = articleService.saveDraft(saveDto("【S11实测】" + suffix, "S11 测试正文"), AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    /** 查某篇文章当前状态码。 */
    private Integer statusOf(Long id) {
        return jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, id);
    }

    /** 断言调用抛指定业务码。 */
    private void expectBusinessCode(int expectedCode, Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            assertEquals("业务码不符：期望 " + expectedCode + "，实际 " + e.getCode()
                    + "（message=" + e.getMessage() + "）", expectedCode, (int) e.getCode());
            return;
        }
        fail("期望抛出 BusinessException(" + expectedCode + ")，但调用成功了");
    }

    /** 把流水列表压成便于断言的字符串，例如 {@code [1/2, 1/1, 2/2]}（stage/result）。 */
    private String shape(List<ArticleAuditLogVO> logs) {
        return logs.stream()
                .map(l -> l.getAuditStage() + "/" + l.getResult())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    // ==================== ★M3 主链路自测 ====================

    /**
     * <b>两级审核主链路走完整一圈</b>，并逐步骤核对状态与流水条数。
     *
     * <p>覆盖"初审驳回 / 终审驳回 / 两次驳回后改稿重提"三种回退路径，
     * 最终状态 {@code APPROVED}，流水恰好 5 条，顺序为
     * {@code [初审驳回, 初审通过, 终审驳回, 初审通过, 终审通过]}。
     *
     * <p>⚠️ 这条链路之所以必须存在，还因为它同时守着"不查 {@code (article_id, audit_stage)}
     * 存在性"这个设计决定：链路里 {@code audit_stage=1} 出现了 <b>3 次</b>，
     * 若有任何"同环节只能审一次"的实现，这里立刻红。
     */
    @Test
    public void testMainChainFromDraftToApprovedHasExactlyFiveLogs() {
        Long id = newDraft("主链路");

        // ① 创建草稿
        assertEquals("① 创建草稿后应为 DRAFT", ArticleStatus.DRAFT.getCode(), statusOf(id));
        assertEquals("① 草稿阶段没有审核流水", 0, articleService.listAuditLogs(id).size());

        // ② 提交初审
        articleService.submitForAudit(id, AUTHOR_ID);
        assertEquals("② 提交后应为待编辑初审",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        assertEquals("② 提交不算审核，流水仍为 0", 0, articleService.listAuditLogs(id).size());

        // ③ 初审驳回
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "初审驳回：摘要太短"),
                EDITOR_ID);
        assertEquals("③ 初审驳回后应为已驳回", ArticleStatus.REJECTED.getCode(), statusOf(id));
        assertEquals("③ 流水应为 1 条", 1, articleService.listAuditLogs(id).size());

        // ④ 改稿 + 再提交
        articleService.updateDraft(id, saveDto("【S11实测】主链路（第2版）", "S11 正文 第2版"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        assertEquals("④ 改稿再提交后应回到待编辑初审",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));

        // ⑤ 初审通过
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR_ID);
        assertEquals("⑤ 初审通过后应为待主编终审",
                ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));
        assertEquals("⑤ 流水应为 2 条", 2, articleService.listAuditLogs(id).size());

        // ⑥ 终审驳回
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "终审驳回：选题不符"),
                CHIEF_ID);
        assertEquals("⑥ 终审驳回后应为已驳回", ArticleStatus.REJECTED.getCode(), statusOf(id));
        assertEquals("⑥ 流水应为 3 条", 3, articleService.listAuditLogs(id).size());

        // ⑦ 改稿 + 再提交
        articleService.updateDraft(id, saveDto("【S11实测】主链路（第3版）", "S11 正文 第3版"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        assertEquals("⑦ 再次改稿提交后应回到待编辑初审",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));

        // ⑧ 初审通过
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "再审通过"), EDITOR_ID);
        assertEquals("⑧ 再审通过后应为待主编终审",
                ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));
        assertEquals("⑧ 流水应为 4 条", 4, articleService.listAuditLogs(id).size());

        // ⑨ 终审通过
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "终审通过，同意发布"), CHIEF_ID);
        assertEquals("⑨ 终审通过后应为审核通过",
                ArticleStatus.APPROVED.getCode(), statusOf(id));

        // ---- 全部流水 ----
        List<ArticleAuditLogVO> logs = articleService.listAuditLogs(id);
        assertEquals("⚠️ 手册核查项：APPROVED 状态下流水应恰好 5 条，实际 " + logs.size(),
                5, logs.size());
        assertEquals("⚠️ 顺序必须是『初审驳回 → 初审通过 → 终审驳回 → 初审通过 → 终审通过』"
                        + "（audit_time 秒精度，同秒内靠 id 兜底），实际 " + shape(logs),
                "[1/2, 1/1, 2/2, 1/1, 2/1]", shape(logs));

        System.out.println("[S11] ★M3 主链路走通：DRAFT → … → APPROVED，"
                + "流水 5 条，shape=" + shape(logs));
    }

    // ==================== 流水字段正确性 ====================

    /**
     * 流水里的展示字段：环节中文、结论中文、审核人昵称、业务版本号。
     *
     * <p>这几项各自会踩不同的坑：
     * <ul>
     *   <li>环节/结论中文：枚举没填或填错，前端显示"未知环节"；</li>
     *   <li>审核人昵称：忘了跨表查 → 前端一片"—"；在循环里单查 → N+1；</li>
     *   <li>业务版本号：错取成乐观锁号（见 S10 的口径）。</li>
     * </ul>
     */
    @Test
    public void testAuditLogDisplayFieldsAreFilled() {
        Long id = newDraft("流水字段");
        articleService.submitForAudit(id, AUTHOR_ID);

        // 此时快照：创建草稿(1) + 提交初审(2) → 本次审核的 articleVersion 应为 2
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "请补充参考文献"),
                EDITOR_ID);

        List<ArticleAuditLogVO> logs = articleService.listAuditLogs(id);
        assertEquals(1, logs.size());
        ArticleAuditLogVO log = logs.get(0);

        assertNotNull("流水 id 不应为空", log.getId());
        assertEquals("环节码应为 1", AuditStageEnum.EDITOR_FIRST.getCode(), log.getAuditStage());
        assertEquals("环节中文应填好", "编辑初审", log.getAuditStageDesc());
        assertEquals("结论码应为 2", AuditResultEnum.REJECT.getCode(), log.getResult());
        assertEquals("结论中文应填好", "驳回", log.getResultDesc());
        assertEquals("审核人 id 应为编辑", EDITOR_ID, log.getAuditorId());
        assertEquals("⚠️ 审核人昵称必须跨表查出来（不能是 null）",
                EDITOR_NICKNAME, log.getAuditorName());
        assertEquals("意见应原样返回", "请补充参考文献", log.getOpinion());
        assertNotNull("审核时间不应为空", log.getAuditTime());
        assertEquals("⚠️ articleVersion 应是业务快照号 2（创建+提交），不是乐观锁号",
                Integer.valueOf(2), log.getArticleVersion());

        System.out.println("[S11] 流水展示字段齐全：stageDesc=" + log.getAuditStageDesc()
                + " resultDesc=" + log.getResultDesc()
                + " auditorName=" + log.getAuditorName()
                + " articleVersion=" + log.getArticleVersion());
    }

    /**
     * 多位审核人的昵称都要能查到（证明"批量查 + 内存回填"对<b>多个</b> id 都成立）。
     *
     * <p>这条是把 N+1 问题"用结果证明"的一部分：如果实现里只查了第一个审核人，
     * 这里第二个人的昵称会是 null。
     */
    @Test
    public void testAuditLogResolvesMultipleAuditorNames() {
        Long id = newDraft("多审核人");
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "终审通过"), CHIEF_ID);

        List<ArticleAuditLogVO> logs = articleService.listAuditLogs(id);
        assertEquals(2, logs.size());
        assertEquals("第一条应是编辑", EDITOR_NICKNAME, logs.get(0).getAuditorName());
        assertEquals("第二条应是主编", CHIEF_NICKNAME, logs.get(1).getAuditorName());
        assertFalse("两个审核人不应被拼成同一个人",
                logs.get(0).getAuditorName().equals(logs.get(1).getAuditorName()));

        System.out.println("[S11] 多审核人昵称均正确：" + logs.stream()
                .map(ArticleAuditLogVO::getAuditorName).collect(Collectors.toList()));
    }

    /** 没有审核记录时返回<b>空列表</b>而不是 null（前端不用写 {@code || []} 兜底）。 */
    @Test
    public void testAuditLogsOfFreshDraftIsEmptyListNotNull() {
        Long id = newDraft("无流水");

        List<ArticleAuditLogVO> logs = articleService.listAuditLogs(id);
        assertNotNull("没有流水时应返回空列表，不能是 null", logs);
        assertTrue("没有流水时列表应为空", logs.isEmpty());

        CmsArticleDetailVO detail = articleService.getDetail(id);
        assertNotNull("详情里的 auditLogs 也不能是 null", detail.getAuditLogs());
        assertTrue(detail.getAuditLogs().isEmpty());
        assertNull("没有驳回过，rejectReason 应为 null", detail.getRejectReason());

        System.out.println("[S11] 无流水时返回空列表（非 null），rejectReason=null");
    }

    // ==================== 详情 VO ====================

    /**
     * 详情的跨表字段与状态文案。
     *
     * <p>⚠️ 这里顺手验证了分类列名的坑：库表是 {@code category_name}。
     * 若实体上写成 {@code name}，这条会直接抛
     * {@code Unknown column 'name' in 'field list'}。
     */
    @Test
    public void testDetailFillsStatusDescAndCrossTableNames() {
        Long id = newDraft("详情字段");
        articleService.submitForAudit(id, AUTHOR_ID);

        CmsArticleDetailVO detail = articleService.getDetail(id);

        assertEquals("id 应回填", id, detail.getId());
        assertEquals("标题应回填", "【S11实测】详情字段", detail.getTitle());
        assertEquals("正文应回填（详情相对列表多出来的字段）", "S11 测试正文", detail.getContent());
        assertEquals("状态码应回填", ArticleStatus.PENDING_EDITOR.getCode(), detail.getStatus());
        assertEquals("状态中文应由枚举填好", "待编辑初审", detail.getStatusDesc());
        assertEquals("状态颜色应由枚举填好（前端直接用）", "warning", detail.getStatusColor());

        assertEquals("作者 id 应回填", AUTHOR_ID, detail.getAuthorId());
        assertEquals("⚠️ 作者昵称必须跨表查出来", AUTHOR_NICKNAME, detail.getAuthorName());
        assertEquals("分类 id 应回填", CATEGORY_ID, detail.getCategoryId());
        assertEquals("⚠️ 分类名必须跨表查出来（库表列名是 category_name）",
                "技术资讯", detail.getCategoryName());

        assertNull("未发布，publisherId 应为 null", detail.getPublisherId());
        assertNull("未发布，publishTime 应为 null", detail.getPublishTime());
        assertEquals("isTop 应回填", Integer.valueOf(0), detail.getIsTop());
        assertEquals("viewCount 起始应为 0", Integer.valueOf(0), detail.getViewCount());
        assertNotNull("createTime 不应为空", detail.getCreateTime());

        System.out.println("[S11] 详情字段：statusDesc=" + detail.getStatusDesc()
                + "(" + detail.getStatusColor() + ") authorName=" + detail.getAuthorName()
                + " categoryName=" + detail.getCategoryName()
                + " content=" + detail.getContent());
    }

    /**
     * {@code rejectReason} 取的是"最近一次<b>驳回</b>意见"，而不是"最后一条流水的意见"。
     *
     * <p>这是最容易写错的一处：终审通过之后最后一条流水的意见是"同意发布"，
     * 若直接取最后一条，详情页会把"同意发布"当成驳回原因展示。
     */
    @Test
    public void testRejectReasonIsLatestRejectNotLatestLog() {
        Long id = newDraft("驳回原因");
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "第一次驳回：结构混乱"),
                EDITOR_ID);
        articleService.updateDraft(id, saveDto("【S11实测】驳回原因（第2版）", "第2版正文"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR_ID);

        // 此刻状态 PENDING_CHIEF：最后一条流水的意见是"初审通过"，但最近一次驳回是"第一次驳回：结构混乱"
        CmsArticleDetailVO detail = articleService.getDetail(id);
        assertEquals("⚠️ rejectReason 应取最近一次驳回的意见，而不是最后一条流水的意见",
                "第一次驳回：结构混乱", detail.getRejectReason());

        // 再终审通过一次：最后一条变成"终审通过，同意发布"，rejectReason 仍应指向那次驳回
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "终审通过，同意发布"), CHIEF_ID);
        CmsArticleDetailVO afterApproved = articleService.getDetail(id);
        assertEquals(ArticleStatus.APPROVED.getCode(), afterApproved.getStatus());
        assertEquals("终审通过后 rejectReason 仍应是最近一次驳回意见（不是『同意发布』）",
                "第一次驳回：结构混乱", afterApproved.getRejectReason());
        assertEquals("此时流水应为 3 条", 3, afterApproved.getAuditLogs().size());

        System.out.println("[S11] rejectReason 口径正确=" + afterApproved.getRejectReason()
                + "（末条流水意见是『终审通过，同意发布』）");
    }

    /** 详情里的流水与单独接口返回的流水<b>完全一致</b>（两条路径共用同一段实现）。 */
    @Test
    public void testDetailAuditLogsEqualStandaloneEndpoint() {
        Long id = newDraft("两条路径一致");
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "驳回一次"), EDITOR_ID);
        articleService.updateDraft(id, saveDto("【S11实测】两条路径一致（第2版）", "第2版"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过"), EDITOR_ID);

        List<ArticleAuditLogVO> fromDetail = articleService.getDetail(id).getAuditLogs();
        List<ArticleAuditLogVO> fromEndpoint = articleService.listAuditLogs(id);

        assertEquals("两个入口的流水条数应一致", fromDetail.size(), fromEndpoint.size());
        assertEquals("两个入口的流水内容应一致", shape(fromDetail), shape(fromEndpoint));
        assertEquals("顺序也一致", fromDetail.get(0).getId(), fromEndpoint.get(0).getId());

        System.out.println("[S11] 详情内嵌流水与独立接口结果一致，共 " + fromDetail.size() + " 条");
    }

    // ==================== 边界 ====================

    /** 文章不存在 → 11001；id 为 null → 400（两个接口都要）。 */
    @Test
    public void testDetailAndLogsOfMissingArticle() {
        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.getDetail(NOT_EXIST_ID));
        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.listAuditLogs(NOT_EXIST_ID));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.getDetail(null));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.listAuditLogs(null));
        System.out.println("[S11] 不存在的 id → 11001；id=null → 400（两个接口均如此）");
    }

    /**
     * 逻辑删除的文章查详情 → 11001（不是返回一条"已删除"的文章）。
     *
     * <p>依据：{@code requireArticle} 用的是 MP 的 {@code selectById}，会自动加
     * {@code deleted = 0}。对外不应该暴露"这条 id 曾经存在过"。
     */
    @Test
    public void testDetailOfSoftDeletedArticleReturnsNotFound() {
        Long id = newDraft("软删除");
        articleService.submitForAudit(id, AUTHOR_ID);

        jdbc().update("UPDATE cms_article SET deleted = 1 WHERE id = ?", id);

        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.getDetail(id));

        System.out.println("[S11] 逻辑删除的文章查详情 → 11001（不暴露『曾经存在过』）");
    }

    /**
     * 脏数据兜底：库里状态码是 0~9 之外时，详情不能 500。
     *
     * <p>做法是直接 UPDATE 一个非法状态码。断言的是"能查出来、且文案是兜底值"，
     * 而不是抛异常 —— 一条脏数据不该让整个详情页打不开。
     */
    @Test
    public void testDetailWithDirtyStatusDoesNotThrow() {
        Long id = newDraft("脏状态");
        jdbc().update("UPDATE cms_article SET status = 99 WHERE id = ?", id);

        CmsArticleDetailVO detail = articleService.getDetail(id);

        assertEquals("脏状态码应原样返回（前端可能需要看到实际值以便排查）",
                Integer.valueOf(99), detail.getStatus());
        assertEquals("脏状态中文应兜底为『未知状态』", "未知状态", detail.getStatusDesc());
        assertEquals("脏状态颜色应兜底为 info", "info", detail.getStatusColor());

        System.out.println("[S11] 脏状态码（99）详情不 500：statusDesc=" + detail.getStatusDesc()
                + " statusColor=" + detail.getStatusColor());
    }

    /**
     * 一篇被审过多次的文章，无论审 1 次还是 5 次，
     * 详情接口需要补数据的<b>关联用户数</b>都不随审核次数线性增长（去重后批量查）。
     *
     * <p>说明：真正的 SQL 条数断言在真机联调里读 {@code logs/cms.log} 的 {@code Preparing:}
     * 完成（JUnit 里拿不到 SQL 计数）。这条测试换一个可断言的等价性质：
     * <b>同一审核人审 3 次，昵称依然正确</b> —— 若实现里是"按流水逐条查"，
     * 虽然结果也对，但会在真机 SQL 计数里暴露。两者合起来才完整。
     */
    @Test
    public void testRepeatedAuditsBySameAuditorStillResolveName() {
        Long id = newDraft("同人多次审");
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "驳回1"), EDITOR_ID);
        articleService.updateDraft(id, saveDto("【S11实测】同人多次审（第2版）", "第2版"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过1"), EDITOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "驳回2"), CHIEF_ID);
        articleService.updateDraft(id, saveDto("【S11实测】同人多次审（第3版）", "第3版"), AUTHOR_ID);
        articleService.submitForAudit(id, AUTHOR_ID);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "通过2"), EDITOR_ID);

        List<ArticleAuditLogVO> logs = articleService.listAuditLogs(id);
        assertEquals(4, logs.size());
        assertEquals("[S11] 同一审核人审了 2 次，昵称仍应正确",
                Arrays.asList(EDITOR_NICKNAME, EDITOR_NICKNAME, CHIEF_NICKNAME, EDITOR_NICKNAME),
                logs.stream().map(ArticleAuditLogVO::getAuditorName).collect(Collectors.toList()));
        assertEquals("4 条流水的环节应是 [1, 1, 2, 1]", "[1/2, 1/1, 2/2, 1/1]", shape(logs));

        System.out.println("[S11] 同人多次审核：4 条流水昵称均正确，shape=" + shape(logs));
    }
}
