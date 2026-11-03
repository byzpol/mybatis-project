package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.enums.AuditResultEnum;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.vo.BatchSubmitResultVO;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S12「审核鉴权与防越权拦截」的<b>水平越权（数据级越权）</b>集成测试。
 *
 * <h2>为什么水平越权只能在 Service 层测</h2>
 * <p>垂直越权（低权限角色调用高权限接口）由 {@code @RequirePermission} + 切面在
 * Controller 之外拦掉，所以它只能在<b>真机 HTTP 链路</b>上验（见 {@code target/s12-live.sh}）。
 * 而水平越权看的是"这条数据是不是你的"——切面只看得到权限标识、看不到数据归属，
 * 因此必须在 Service 里判断，也就可以在本类里直接测。
 *
 * <h2>本类覆盖的规则（每条都有正向对照）</h2>
 * <table border="1">
 *   <tr><th>规则</th><th>违规时的码</th><th>正向对照</th></tr>
 *   <tr><td>改草稿 / 提交初审必须是作者本人</td><td>11003</td>
 *       <td>作者改自己的草稿 → 成功</td></tr>
 *   <tr><td>不能审核自己创建的文章</td><td>11004</td>
 *       <td>别人审我的稿 → 成功</td></tr>
 *   <tr><td>已归档不可写</td><td>11011</td>
 *       <td>—（终态，没有正向）</td></tr>
 *   <tr><td>未登录</td><td>401</td><td>—</td></tr>
 * </table>
 *
 * <p>⚠️ 每条"拒绝"用例都配一条"允许"的正向用例，否则一个"把所有请求都拒掉"的
 * 实现也能让本类全绿 —— 那不是通过，是测试没写对。
 *
 * <h2>⚠️ 本类同时钉住两个容易想错的地方</h2>
 * <ol>
 *   <li><b>没有"超级管理员短路"。</b>{@code checkOwnership} 是作者比对，
 *       admin(4) 改别人的草稿同样被 11003 拒。契约 §6.4 的示例片段里有"超管直接 return"，
 *       本项目不采纳（原因见 {@code ArticleServiceImpl#checkOwnership} 的注释）。
 *       这条用例就是那个决定的守门人。</li>
 *   <li><b>归属校验排在状态校验之前。</b>非作者改一篇<b>已归档</b>的文章，
 *       返回的是 <b>11003</b>（不是 11011）——因为 {@code updateDraft} 里
 *       {@code checkOwnership} 在 {@code requireEditable} 之前。
 *       两个码都会"拒绝"，但给用户的提示不同（"这不是你的稿" / "已归档不能改"），
 *       先答哪个是可观测的行为，所以专门写一条钉住。</li>
 * </ol>
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleOwnershipAuthzTest {

    /** 投稿人 A：张晓投稿（种子文章 1/3/5/… 的作者）。 */
    private static final Long AUTHOR_A = 1L;

    /** 投稿人 B：赵投稿（种子文章 2/4/6/… 的作者）。 */
    private static final Long AUTHOR_B = 5L;

    /** 编辑：李编辑。有 {@code article:update} 与 {@code article:audit}，但**不是**文章作者。 */
    private static final Long EDITOR = 2L;

    /** 主编：王主编。有 {@code article:update/manage/publish}，但仍**不是**文章作者。 */
    private static final Long CHIEF = 3L;

    /** 管理员：有全部权限，但 {@code checkOwnership} 刻意不给它开后门。 */
    private static final Long ADMIN = 4L;

    private static final Long CATEGORY_ID = 1L;

    /** 种子里的已归档文章（id=19，作者 = AUTHOR_A）。 */
    private static final Long SEED_ARCHIVED_OWNED_BY_A = 19L;

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
        System.out.println("[S12 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    private CmsArticleSaveDTO saveDto(String title) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary("S12 越权测试临时数据");
        dto.setContent("S12 越权测试正文");
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    private Long newDraftAs(Long authorId, String title) {
        Long id = articleService.saveDraft(saveDto(title), authorId);
        createdArticleIds.add(id);
        return id;
    }

    private Integer statusOf(Long id) {
        return jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, id);
    }

    private String titleOf(Long id) {
        return jdbc().queryForObject(
                "SELECT title FROM cms_article WHERE id = ?", String.class, id);
    }

    /** 断言调用抛指定业务码；返回异常本身以便再断言文案。 */
    private BusinessException expectCode(int expectedCode, Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            assertEquals("业务码不符：期望 " + expectedCode + "，实际 " + e.getCode()
                    + "（message=" + e.getMessage() + "）", expectedCode, (int) e.getCode());
            return e;
        }
        fail("期望抛出 BusinessException(" + expectedCode + ")，但调用成功了");
        return null; // 不可达
    }

    private ArticleAuditDTO auditDto(int action, String opinion) {
        ArticleAuditDTO dto = new ArticleAuditDTO();
        dto.setAuditAction(action);
        dto.setAuditOpinion(opinion);
        return dto;
    }

    // ==================== 一、改草稿：必须是作者本人 ====================

    /** 用例 1：投稿人 B 改投稿人 A 的草稿 → 11003（手册 S12 这里写 11004，实测是 11003）。 */
    @Test
    public void testCase01_AuthorBCannotUpdateAuthorADraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】A的草稿");
        BusinessException e = expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(id, saveDto("【S12实测】B越权改的"), AUTHOR_B));
        assertEquals("码文案应是『只能操作自己创建的文章』", "只能操作自己创建的文章", e.getMessage());
        assertEquals("越权被拒后标题不应被改动", "【S12实测】A的草稿", titleOf(id));
        System.out.println("[S12] 用例1 B 改 A 的草稿 → " + e.getCode() + "，" + e.getMessage());
    }

    /** 用例 2：投稿人 B 提交投稿人 A 的草稿 → 11003，且状态保持 DRAFT。 */
    @Test
    public void testCase02_AuthorBCannotSubmitAuthorADraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】A的待提交稿");
        BusinessException e = expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_B));
        assertEquals("越权被拒后状态应保持草稿", ArticleStatus.DRAFT.getCode(), statusOf(id));
        assertTrue("越权时应留下 WARN 日志便于追责（见 checkOwnership）",
                e.getMessage().contains("只能操作自己创建的文章"));
        System.out.println("[S12] 用例2 B 提交 A 的草稿 → " + e.getCode());
    }

    /**
     * 用例 3：<b>编辑</b>改别人草稿 → 11003。
     *
     * <p>这条最重要：编辑<b>有</b> {@code article:update} 权限，
     * 垂直越权那一层拦不住他 —— 拦住他的是本层的数据归属校验。
     * 手册 S12 的问题 3"编辑/主编能否修改别人的文章内容"答的就是"不能"。
     */
    @Test
    public void testCase03_EditorCannotUpdateOthersDraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】编辑要改的A稿");
        BusinessException e = expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(id, saveDto("【S12实测】编辑改的"), EDITOR));
        assertEquals("正文不应被编辑改动", "【S12实测】编辑要改的A稿", titleOf(id));
        System.out.println("[S12] 用例3 编辑改 A 的草稿 → " + e.getCode()
                + "（编辑有 article:update，靠归属校验拦住）");
    }

    /** 用例 4：<b>主编</b>改别人草稿 → 11003（主编有 manage/publish 也不行）。 */
    @Test
    public void testCase04_ChiefCannotUpdateOthersDraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】主编要改的A稿");
        expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(id, saveDto("【S12实测】主编改的"), CHIEF));
        assertEquals("正文不应被主编改动", "【S12实测】主编要改的A稿", titleOf(id));
        System.out.println("[S12] 用例4 主编改 A 的草稿 → 11003");
    }

    /**
     * 用例 5：<b>管理员</b>改别人草稿 → 11003。
     * <p>⚠️ 这条是"没有超管短路"这个设计决定的守门人（契约 §6.4 的示例里写的是"超管直接 return"）。
     */
    @Test
    public void testCase05_AdminAlsoCannotUpdateOthersDraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】管理员要改的A稿");
        expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(id, saveDto("【S12实测】管理员改的"), ADMIN));
        assertEquals("正文不应被管理员改动", "【S12实测】管理员要改的A稿", titleOf(id));
        System.out.println("[S12] 用例5 管理员改 A 的草稿 → 11003（刻意无超管短路）");
    }

    /** 用例 6（<b>正向对照</b>）：作者改自己的草稿 → 成功。没有这条，"全拒"也能蒙混过关。 */
    @Test
    public void testCase06_OwnerCanUpdateOwnDraft() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】A自己的稿");
        articleService.updateDraft(id, saveDto("【S12实测】A自己改的（应成功）"), AUTHOR_A);
        assertEquals("作者改自己的草稿应成功", "【S12实测】A自己改的（应成功）", titleOf(id));
        System.out.println("[S12] 用例6 正向：A 改自己的草稿 → 成功");
    }

    // ==================== 二、审核：不能审自己创建的 ====================

    /** 用例 7：投稿人审自己的稿 → 11004（不是 11003，两条规则刻意分开报码）。 */
    @Test
    public void testCase07_AuthorCannotAuditOwnArticle() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】自审稿");
        articleService.submitForAudit(id, AUTHOR_A);
        BusinessException e = expectCode(ErrorCode.ARTICLE_CANNOT_AUDIT_SELF.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "自审通过"), AUTHOR_A));
        assertEquals("不能审核自己创建的文章", e.getMessage());
        assertEquals("被拒后状态应停在待编辑初审",
                ArticleStatus.PENDING_EDITOR.getCode(), statusOf(id));
        assertEquals("被拒后不应留下审核流水", 0, articleService.listAuditLogs(id).size());
        System.out.println("[S12] 用例7 A 审自己的稿 → " + e.getCode() + "，" + e.getMessage());
    }

    /** 用例 8：编辑审自己创建的稿 → 11004（编辑有 audit 权限，仍不能审自己的）。 */
    @Test
    public void testCase08_EditorCannotAuditOwnArticle() {
        Long id = newDraftAs(EDITOR, "【S12实测】编辑自己的稿");
        articleService.submitForAudit(id, EDITOR);
        expectCode(ErrorCode.ARTICLE_CANNOT_AUDIT_SELF.getCode(),
                () -> articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "驳回自己"), EDITOR));
        System.out.println("[S12] 用例8 编辑审自己创建的稿 → 11004");
    }

    /** 用例 9（<b>正向对照</b>）：别人审我的稿 → 成功，且流水里审核人是别人。 */
    @Test
    public void testCase09_OthersCanAuditMyArticle() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】别人来审");
        articleService.submitForAudit(id, AUTHOR_A);
        articleService.audit(id, auditDto(AuditResultEnum.PASS.getCode(), "初审通过"), EDITOR);
        assertEquals(ArticleStatus.PENDING_CHIEF.getCode(), statusOf(id));
        assertEquals(1, articleService.listAuditLogs(id).size());
        assertEquals("流水审核人应是编辑(2)", EDITOR,
                articleService.listAuditLogs(id).get(0).getAuditorId());
        System.out.println("[S12] 用例9 正向：别人审 A 的稿 → 成功（auditorId=2）");
    }

    // ==================== 三、已归档 / 未登录 ====================

    /**
     * 用例 10：<b>非作者</b>改一篇<b>已归档</b>的文章 → <b>11003</b>（不是 11011）。
     *
     * <p>顺序证据：{@code updateDraft} 里 {@code checkOwnership} 排在 {@code requireEditable} 之前。
     * 两个码都拒绝，但提示不同，先答哪个是可观测行为。
     */
    @Test
    public void testCase10_OwnershipCheckedBeforeArchivedStatus() {
        assertEquals("前置检查：19 号应确实是已归档",
                ArticleStatus.ARCHIVED.getCode(), statusOf(SEED_ARCHIVED_OWNED_BY_A));
        BusinessException e = expectCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(SEED_ARCHIVED_OWNED_BY_A,
                        saveDto("【S12实测】B改归档稿"), AUTHOR_B));
        assertFalse("不该报成 11011（归档只读）—— 归属校验在前",
                e.getCode() == ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode());
        System.out.println("[S12] 用例10 非作者改已归档稿 → " + e.getCode()
                + "（证明归属校验排在状态校验之前，不是 11011）");
    }

    /** 用例 11：<b>作者本人</b>改已归档的文章 → 11011（这次才是归档只读）。 */
    @Test
    public void testCase11_OwnerUpdatingArchivedGetsAnswered11011() {
        BusinessException e = expectCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.updateDraft(SEED_ARCHIVED_OWNED_BY_A,
                        saveDto("【S12实测】A改自己的归档稿"), AUTHOR_A));
        assertEquals("已归档的文章不可修改", e.getMessage());
        System.out.println("[S12] 用例11 作者本人改已归档稿 → " + e.getCode() + "，" + e.getMessage());
    }

    /**
     * 用例 12：三个写入口都传 {@code operatorId = null} → <b>401</b>（不是 NPE）。
     *
     * <p>模拟"定时任务 / 脚本直接调 Service"的场景：那些入口没有 HTTP 上下文，
     * {@code operatorId} 完全可能是 null。若不拦住，{@code author_id} 会写进 null，
     * 一直到数据库报 {@code Column 'author_id' cannot be null} 才暴露。
     */
    @Test
    public void testCase12_NullOperatorRejectedWith401OnAllWriteEntries() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】空操作人");

        int code = ErrorCode.UNAUTHORIZED.getCode();
        expectCode(code, () -> articleService.updateDraft(id, saveDto("x"), null));
        expectCode(code, () -> articleService.submitForAudit(id, null));
        expectCode(code, () -> articleService.audit(id, auditDto(1, "x"), null));
        expectCode(code, () -> articleService.batchSubmit(Arrays.asList(id), null));
        System.out.println("[S12] 用例12 四个写入口 operatorId=null → 401「请先登录」（不是 NPE）");
    }

    // ==================== 四、读接口刻意不设归属限制 ====================

    /**
     * 用例 13：别人<b>可以</b>读我的文章详情与流水 → 成功。
     *
     * <p>这是<b>刻意</b>的设计，不是漏写：后台是协作场景，编辑/主编必须能打开
     * 任何一篇文章才能审、才能核对。只读不构成越权，被限制的是"改"。
     * 写这条用例是为了把"我们想过这件事"留下证据 —— 否则将来有人
     * 看到"详情没做归属校验"会以为是漏洞。
     */
    @Test
    public void testCase13_ReadEndpointsAreDeliberatelyNotOwnershipRestricted() {
        Long id = newDraftAs(AUTHOR_A, "【S12实测】B来读A的稿");
        articleService.submitForAudit(id, AUTHOR_A);
        articleService.audit(id, auditDto(AuditResultEnum.REJECT.getCode(), "请补充数据来源"), EDITOR);

        assertNotNull("B 能读 A 的详情（协作需要）", articleService.getDetail(id));
        assertEquals("B 读到的作者仍是 A", AUTHOR_A, articleService.getDetail(id).getAuthorId());
        assertEquals("B 能读 A 的审核流水", 1, articleService.listAuditLogs(id).size());
        System.out.println("[S12] 用例13 只读接口刻意不设归属限制（B 能读 A 的详情与流水）");
    }

    // ==================== 五、批量：逐条容错，不做整批拒绝 ====================

    /**
     * 用例 14：批量提交里混进别人的文章 → <b>自己的成功、别人的记入失败清单</b>，
     * 且别人的文章状态<b>不变</b>。
     *
     * <p>为什么不做"整批拒绝"：用户勾了 5 篇自己的 + 1 篇别人的，
     * 整批拒绝会让他以为自己一篇都提交不了。逐条容错更符合本意。
     * 而"别人的那篇状态没变"才是安全性的真正判据 —— 不能只是返回一条失败消息。
     */
    @Test
    public void testCase14_BatchSubmitMixedOwnershipPartialFail() {
        Long a1 = newDraftAs(AUTHOR_A, "【S12实测】批量A稿1");
        Long a2 = newDraftAs(AUTHOR_A, "【S12实测】批量A稿2");
        Long b1 = newDraftAs(AUTHOR_B, "【S12实测】批量B稿（不该被提交）");

        BatchSubmitResultVO result = articleService.batchSubmit(
                Arrays.asList(a1, b1, a2), AUTHOR_A);

        assertEquals("总数应是去重后的 3", Integer.valueOf(3), result.getTotal());
        assertEquals("A 的两篇应成功", Integer.valueOf(2), result.getSuccessCount());
        assertEquals("B 的那篇应失败", Integer.valueOf(1), result.getFailCount());
        assertEquals("失败清单应恰好是 B 的那篇", 1, result.getFailList().size());
        assertEquals("失败项 id 应是 b1", b1, result.getFailList().get(0).getId());
        assertTrue("失败原因应说明不是自己的文章，实际=" + result.getFailList().get(0).getReason(),
                result.getFailList().get(0).getReason().contains("只能操作自己创建的文章"));

        assertEquals("A 的第 1 篇应已提交", ArticleStatus.PENDING_EDITOR.getCode(), statusOf(a1));
        assertEquals("A 的第 2 篇应已提交", ArticleStatus.PENDING_EDITOR.getCode(), statusOf(a2));
        assertEquals("⚠️ B 的那篇状态必须保持不变（安全性判据，不是只看返回值）",
                ArticleStatus.DRAFT.getCode(), statusOf(b1));
        assertEquals("B 的那篇也不该产生版本快照以外的副作用",
                0, articleService.listAuditLogs(b1).size());

        System.out.println("[S12] 用例14 批量混合归属：total=3 success=2 fail=1，"
                + "失败 id=" + result.getFailList().get(0).getId()
                + "，他人文章状态保持 DRAFT");
    }
}
