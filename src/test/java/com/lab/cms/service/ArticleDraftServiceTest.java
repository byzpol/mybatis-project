package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsArticleVersion;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsArticleVersionMapper;
import com.lab.cms.vo.BatchSubmitResultVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S09「草稿保存与投稿提交」的集成测试。
 *
 * <h3>为什么这些用例必须连真库</h3>
 * <p>本步要证明的东西有一半是"框架行为"，只有真库才能证伪：
 * <ul>
 *   <li><b>MP 乐观锁在 version 不匹配时返回 0 而不抛异常</b> ——
 *       这是 {@code updateById(article) == 0 → 11010} 这行代码存在的全部理由。
 *       用 mock 只能测到"我们写了 if"，测不到"MP 真的会返回 0"。</li>
 *   <li><b>唯一索引 {@code uk_article_version} 真的会让重复插失败</b> ——
 *       重试逻辑的兜底依赖这一条。而且"抛出来的异常长什么样"
 *       （是 {@code DuplicateKeyException} 还是 {@code PersistenceException} 包着
 *       {@code SQLIntegrityConstraintViolationException}）必须实测，
 *       猜错就会写出一个永远不触发的 catch。</li>
 *   <li><b>审计字段有没有被填</b> —— {@code create_by} 为 null 是"静默失效"的典型，
 *       只有查库才知道。</li>
 * </ul>
 *
 * <h3>⚠️ 为什么不用 {@code @Transactional} 让测试自动回滚</h3>
 * <p>用回滚的话，"事务真的提交了吗"这件事就测不到了 —— 而本步的核心正是
 * "改状态与写快照必须同成功同失败"。所以这里让数据真实落库，
 * 由 {@link #cleanUp()} 在测试结束后物理删除本次创建的记录
 * （用自建的 {@code JdbcTemplate}，绕过 {@code @TableLogic}，
 * 否则"逻辑删除"会把记录留在表里，跑几轮后测试数据越积越多）。
 *
 * <h3>⚠️ 关于审计字段的期望值：这里是 0，不是 operatorId</h3>
 * <p>{@code AuditMetaObjectHandler} 填的是"<b>当前登录用户</b>"，
 * 它从 {@code RequestContextHolder} 里取 Session —— 而本测试没有 HTTP 请求，
 * 所以取不到，按项目约定落到 {@code SYSTEM_USER_ID = 0}。
 * <p>也就是说 {@code operatorId}（业务上的操作人）与
 * {@code create_by}（审计上的操作人）在这一层<b>故意是两套东西</b>：
 * 前者是方法参数（可测试、可被定时任务复用），后者只反映"HTTP 会话里的登录人"。
 * 断言用 0 而不是 operatorId，正是为了把这个区别钉在测试里 ——
 * 若哪天真有人把两者混为一谈，这条断言会失败。
 * <p>HTTP 链路上的 {@code create_by} 是否为登录用户，由 S09 的真机联调部分验证。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleDraftServiceTest {

    /** 演示数据里的投稿人：author（张晓投稿）。 */
    private static final Long AUTHOR_ID = 1L;

    /** 另一个投稿人：author2（赵投稿），用来构造水平越权场景。 */
    private static final Long AUTHOR2_ID = 5L;

    /** 已有分类：技术资讯。 */
    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private CmsArticleVersionMapper versionMapper;

    @Autowired
    private DataSource dataSource;

    /** 本次测试创建的 article id，{@link #cleanUp()} 用它们物理删除。 */
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
        // ⚠️ 顺序不能反：版本表有 article_id 的逻辑外键（无物理外键），
        // 先删主表也能删掉（没有约束），但语义上先删从表更清晰。
        int versions = jdbc().update(
                "DELETE FROM cms_article_version WHERE article_id IN (" + placeholders + ")", args);
        int articles = jdbc().update(
                "DELETE FROM cms_article WHERE id IN (" + placeholders + ")", args);
        System.out.println("[S09 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    /** 造一个合法的保存入参。 */
    private CmsArticleSaveDTO dto(String title, String content) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary("S09 集成测试临时数据");
        dto.setContent(content);
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    /** 新建一篇草稿并登记 id 以便清理。 */
    private Long newDraft(String titleSuffix, String content) {
        Long id = articleService.saveDraft(
                dto("【S09实测】" + titleSuffix, content), AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    /**
     * 直接用 SQL 造一篇指定状态的文章（用来构造"已发布/已归档"这类
     * 无法通过正常接口到达的状态）。
     *
     * <p>为什么用 SQL 而不是先提交再审核：审核链要到 S10/S11 才有，
     * 本步没有"把文章推进到已发布"的合法入口。
     */
    private Long insertArticleWithStatus(int status) {
        jdbc().update(
                "INSERT INTO cms_article (title, summary, content, category_id, status, author_id,"
                        + " is_top, view_count, version, create_by, update_by, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, 0)",
                "【S09实测】状态" + status + "样本", "摘要", "正文",
                CATEGORY_ID, status, AUTHOR_ID, AUTHOR_ID, AUTHOR_ID);
        Long id = jdbc().queryForObject(
                "SELECT id FROM cms_article WHERE title = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                Long.class, "【S09实测】状态" + status + "样本");
        createdArticleIds.add(id);
        return id;
    }

    /** 查某篇文章的全部版本号（升序）。 */
    private List<Integer> versionNosOf(Long articleId) {
        return jdbc().queryForList(
                "SELECT version_no FROM cms_article_version WHERE article_id = ? AND deleted = 0"
                        + " ORDER BY version_no", Integer.class, articleId);
    }

    /** 断言调用会抛指定业务码，并返回异常信息以便打印。 */
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

    // ==================== 核查项 3：保存草稿后 versionNo=1 ====================

    /**
     * 保存草稿 → cms_article 一行（status=DRAFT、authorId=操作人、viewCount=0）
     * 且 cms_article_version 里有 versionNo=1 的记录（对应手册核查项 3）。
     */
    @Test
    public void testSaveDraftCreatesDraftAndFirstSnapshot() {
        Long id = newDraft("保存草稿", "草稿正文");

        assertNotNull("saveDraft 必须返回新 id（返回 null 会让调用方带着 null 继续走）", id);
        assertTrue("新 id 应为正整数，实际 " + id, id > 0);

        CmsArticle saved = articleMapper.selectById(id);
        assertNotNull(saved);
        assertEquals("新建后状态必须是 DRAFT(0)", ArticleStatus.DRAFT.getCode(), saved.getStatus());
        assertEquals("authorId 必须等于操作人", AUTHOR_ID, saved.getAuthorId());
        assertEquals("viewCount 必须是 0", Integer.valueOf(0), saved.getViewCount());
        assertEquals("version 起点必须是 0", Integer.valueOf(0), saved.getVersion());
        assertNull("未发布的文章不该有 publishTime", saved.getPublishTime());
        assertNull("未发布的文章不该有 publisherId", saved.getPublisherId());

        // ---- 审计字段：硬指标是 create_by / update_by，不是 create_time ----
        // create_time 库表有 DEFAULT CURRENT_TIMESTAMP 兜底，填没填根本看不出来；
        // create_by 没有默认值，为 null 就说明 MetaObjectHandler 没生效。
        // 测试环境无 HTTP 会话 → 期望 SYSTEM_USER_ID(0)。
        Integer createBy = jdbc().queryForObject(
                "SELECT create_by FROM cms_article WHERE id = ?", Integer.class, id);
        Integer updateBy = jdbc().queryForObject(
                "SELECT update_by FROM cms_article WHERE id = ?", Integer.class, id);
        assertNotNull("create_by 不能被填成 null（说明审计填充器没生效）", createBy);
        assertNotNull("update_by 不能被填成 null", updateBy);
        assertEquals("测试环境无登录会话，审计人应为 SYSTEM_USER_ID(0)", Integer.valueOf(0), createBy);

        // ---- 首个版本快照 ----
        List<CmsArticleVersion> snapshots = versionMapper.selectList(
                new QueryWrapper<CmsArticleVersion>().eq("article_id", id).orderByAsc("version_no"));
        assertEquals("新建草稿后应恰好有 1 条版本快照，实际 " + snapshots.size(), 1, snapshots.size());
        CmsArticleVersion first = snapshots.get(0);
        assertEquals("首个快照的 versionNo 必须是 1", Integer.valueOf(1), first.getVersionNo());
        assertEquals("创建草稿", first.getModifyRemark());
        assertEquals("快照的修改人应是操作人（这是业务字段，不走审计填充）",
                AUTHOR_ID, first.getModifierId());
        assertEquals("【S09实测】保存草稿", first.getTitleSnapshot());
        assertEquals("草稿正文", first.getContentSnapshot());
        // CmsArticleVersion 不继承 BaseEntity，create_time 没有人自动填 ——
        // 这条断言就是在证明"我们在代码里显式 setCreateTime"是必要的且生效了。
        assertNotNull("快照的 create_time 不能被填成 null（该表无自动填充，靠代码显式赋值）",
                first.getCreateTime());

        System.out.println("[S09] 保存草稿：id=" + id + "，快照 versionNo=" + first.getVersionNo()
                + "，remark=" + first.getModifyRemark() + "，createBy=" + createBy);
    }

    // ==================== 核查项 4：改两次草稿后版本号 1/2/3，无重复 ====================

    /**
     * 保存 + 改两次 → 版本号严格是 1、2、3，且无重复（对应手册核查项 4）。
     *
     * <p>顺带钉住一个容易写错的地方：<b>版本号是"这篇的"而不是"全局的"</b>。
     * 若取号时漏了 {@code WHERE article_id = ?}（或 XML 里漏了 {@code deleted = 0}），
     * 第二个测试用例再跑一次就会从 4 开始 —— 这条断言能立刻发现。
     */
    @Test
    public void testUpdateDraftTwiceGivesVersionNumbers1To3() {
        Long id = newDraft("版本号递增", "正文 v1");

        articleService.updateDraft(id, dto("【S09实测】版本号递增（改一次）", "正文 v2"), AUTHOR_ID);
        articleService.updateDraft(id, dto("【S09实测】版本号递增（改两次）", "正文 v3"), AUTHOR_ID);

        List<Integer> nos = versionNosOf(id);
        assertEquals("应有 3 条快照（创建 + 改两次），实际 " + nos, Arrays.asList(1, 2, 3), nos);

        // 无重复：用 DISTINCT 数量与总数比对（比 Set.size() 更直接）
        Integer distinctCount = jdbc().queryForObject(
                "SELECT COUNT(DISTINCT version_no) FROM cms_article_version WHERE article_id = ?",
                Integer.class, id);
        assertEquals("版本号不允许重复", nos.size(), distinctCount.intValue());

        // 最新一条快照的内容应是最后一次修改的结果
        CmsArticleVersion latest = versionMapper.selectOne(
                new QueryWrapper<CmsArticleVersion>().eq("article_id", id).eq("version_no", 3));
        assertNotNull(latest);
        assertEquals("更新草稿", latest.getModifyRemark());
        assertEquals("正文 v3", latest.getContentSnapshot());

        // 主表也要是最后一次修改的内容
        CmsArticle article = articleMapper.selectById(id);
        assertEquals("正文 v3", article.getContent());
        assertEquals("【S09实测】版本号递增（改两次）", article.getTitle());

        System.out.println("[S09] 版本号序列：" + nos + "（无重复，DISTINCT=" + distinctCount + "）");
    }

    // ==================== 核查项 1：非作者调 updateDraft 被拒 ====================

    /**
     * 用另一个投稿人的身份改别人的草稿 → {@code ARTICLE_NOT_OWNER(11003)}。
     *
     * <h3>⚠️ 与手册的偏差（必须记录）</h3>
     * <p>手册 S09 的核查项写的是"返回 <b>11004</b>"，S12 的用例集也写"编辑别人的草稿 → 11004"。
     * 但 {@code ErrorCode} 里 <b>11004 是"不能审核自己创建的文章"</b>，
     * 水平越权是 <b>11003</b>（{@code ARTICLE_NOT_OWNER}）。
     * <p>同类错位在手册里成串出现：
     * <ul>
     *   <li>手册说"对已发布的文章提交 → 11003（状态非法）"，实际状态非法是 <b>11002</b>，
     *       11003 是"不是自己的文章"；</li>
     *   <li>手册说"归档后写操作 → 11010"，实际归档只读是 <b>11011</b>，
     *       11010 是乐观锁冲突。</li>
     * </ul>
     * <p>项目口径是"代码与库表为准，手册是设计草稿"，故此处按 11003 实现与断言。
     */
    @Test
    public void testUpdateDraftByNonOwnerIsRejected() {
        Long id = newDraft("越权改草稿", "正文");

        String msg = expectBusinessCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.updateDraft(id, dto("【S09实测】越权改草稿", "被篡改的正文"),
                        AUTHOR2_ID));

        assertEquals("只能操作自己创建的文章", msg);
        // 反向确认：确实没被改
        CmsArticle article = articleMapper.selectById(id);
        assertEquals("越权请求不得产生任何修改", "正文", article.getContent());

        System.out.println("[S09] 水平越权（改草稿）已拦截，返回 11003，message=" + msg);
    }

    /** 非作者提交别人的草稿 → 同样 11003。 */
    @Test
    public void testSubmitForAuditByNonOwnerIsRejected() {
        Long id = newDraft("越权提交", "正文");

        expectBusinessCode(ErrorCode.ARTICLE_NOT_OWNER.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR2_ID));

        assertEquals("越权提交后状态不得变化", ArticleStatus.DRAFT.getCode(),
                articleMapper.selectById(id).getStatus());
        System.out.println("[S09] 水平越权（提交）已拦截，返回 11003");
    }

    // ==================== 状态校验 ====================

    /**
     * 对"已发布"的文章改草稿 → 11002（不是 500，也不是 11003）。
     * <p>与手册核查项 2 同类，只是换成了 updateDraft 入口。
     */
    @Test
    public void testUpdateDraftOnPublishedIsRejected() {
        Long id = insertArticleWithStatus(ArticleStatus.PUBLISHED.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.updateDraft(id, dto("【S09实测】改已发布", "改不动"), AUTHOR_ID));

        assertTrue("提示里应包含当前状态中文，实际：" + msg, msg.contains("已发布"));
        assertTrue("提示里应说明什么状态可以改，实际：" + msg, msg.contains("草稿"));
        System.out.println("[S09] 已发布文章不可编辑，返回 11002，message=" + msg);
    }

    /**
     * 对"已发布"的文章调 submitForAudit → {@code ARTICLE_STATUS_ILLEGAL(11002)}，
     * <b>而不是 500</b>（对应手册核查项 2；手册写的码是 11003，实际是 11002，见用例
     * {@link #testUpdateDraftByNonOwnerIsRejected} 的说明）。
     *
     * <p>为什么"不是 500"本身就值得单独测：状态机若写成
     * {@code nextStatus(...)} 后用返回的 null 去 {@code Integer.parseInt} 或直接
     * 拆箱，就会变成 NPE → 500。这条断言守的正是那个坑。
     */
    @Test
    public void testSubmitForAuditOnPublishedIsRejected() {
        Long id = insertArticleWithStatus(ArticleStatus.PUBLISHED.getCode());

        String msg = expectBusinessCode(ErrorCode.ARTICLE_STATUS_ILLEGAL.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_ID));

        assertTrue("提示应点名当前状态，实际：" + msg, msg.contains("已发布"));
        // ⚠️ 断言整句而不是只断言关键词：这句话是 ArticleStateMachine 拼出来的
        // （"文章当前状态为「%s」，不允许执行「%s」"），把动作名也钉住，
        // 才能证明"状态"与"动作"两个占位符都填对了。
        // 注意动作文案取自 ArticleAction.SUBMIT 的 description。
        // S18 之前它是"提交审核"，与系统里其余 12+ 处（前端按钮、@OperLog 描述、
        // 版本快照 remark、接口成功消息、接口分类与鉴权表）用的"提交初审"不一致 ——
        // 同一个动作，用户在成功与失败时看到两个名字。
        // S18 已把 description 订正为"提交初审"，本条断言随之更新，从此只有一个名字。
        // 判据与影响面见 docs/重构记录.md 第二节。
        assertEquals("文章当前状态为「已发布」，不允许执行「提交初审」", msg);
        assertEquals("状态不得被改动", ArticleStatus.PUBLISHED.getCode(),
                articleMapper.selectById(id).getStatus());
        System.out.println("[S09] 已发布文章不可提交，返回 11002，message=" + msg);
    }

    /**
     * 已归档（终态）的两种入口 → 都用 {@code ARTICLE_ARCHIVED_READONLY(11011)}。
     *
     * <p>这是本步对状态机 {@code transfer} 三种错误码分工的验证：
     * "终态"要单独给码，而不是混进笼统的 11002。
     * （手册 S12 把归档写成 11010，实测为 11011，见上文偏差说明。）
     */
    @Test
    public void testArchivedIsReadOnlyWithCode11011() {
        Long id = insertArticleWithStatus(ArticleStatus.ARCHIVED.getCode());

        expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.updateDraft(id, dto("【S09实测】改已归档", "改不动"), AUTHOR_ID));
        expectBusinessCode(ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_ID));

        System.out.println("[S09] 已归档文章两处入口均返回 11011");
    }

    // ==================== 内容完整性 ====================

    /**
     * 只有标题、没有正文的草稿可以保存，但不能提交初审。
     *
     * <p>这是"宽松保存 + 严格提交"这条设计取舍的正面验证：
     * {@code saveDraft} 必须成功（草稿允许只占个标题），
     * {@code submitForAudit} 必须被拒。
     */
    @Test
    public void testDraftWithoutContentCanBeSavedButNotSubmitted() {
        Long id = newDraft("只有标题的草稿", null);

        CmsArticle saved = articleMapper.selectById(id);
        assertNull("草稿的正文允许为空", saved.getContent());

        String msg = expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_ID));
        assertTrue("提示应说明缺的是正文，实际：" + msg, msg.contains("正文"));
        assertEquals("提交失败后状态必须还是草稿", ArticleStatus.DRAFT.getCode(),
                articleMapper.selectById(id).getStatus());
        System.out.println("[S09] 无正文草稿可保存不可提交，返回 400，message=" + msg);
    }

    /** 正文只有空白字符（空格/换行/tab）也算"没有正文"。 */
    @Test
    public void testSubmitRejectsWhitespaceOnlyContent() {
        Long id = newDraft("空白正文", "   \n\t  ");

        String msg = expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_ID));
        assertTrue("全空白正文应被判为空，实际：" + msg, msg.contains("正文"));
        System.out.println("[S09] 空白正文被拒，message=" + msg);
    }

    // ==================== 正常提交（状态机推进 + 快照） ====================

    /**
     * 草稿 → 提交 → 状态变 {@code PENDING_EDITOR(1)}，并多出一条"提交初审"快照。
     *
     * <p>顺带验证一件事：提交产生的快照是 <b>versionNo=2</b>（在"创建草稿"之后），
     * 而不是覆盖掉第 1 条 —— 版本链只增不改。
     */
    @Test
    public void testSubmitMovesToPendingEditorAndAddsSnapshot() {
        Long id = newDraft("正常提交", "正文");

        articleService.submitForAudit(id, AUTHOR_ID);

        CmsArticle article = articleMapper.selectById(id);
        assertEquals("提交后应进入待编辑初审", ArticleStatus.PENDING_EDITOR.getCode(), article.getStatus());

        List<Integer> nos = versionNosOf(id);
        assertEquals("创建 + 提交 共 2 条快照，实际 " + nos, Arrays.asList(1, 2), nos);

        CmsArticleVersion submitSnapshot = versionMapper.selectOne(
                new QueryWrapper<CmsArticleVersion>().eq("article_id", id).eq("version_no", 2));
        assertNotNull(submitSnapshot);
        assertEquals("提交初审", submitSnapshot.getModifyRemark());
        assertEquals("正文", submitSnapshot.getContentSnapshot());

        // 已驳回状态也能提交（状态机的第 2 条迁移，很容易被漏掉）
        jdbc().update("UPDATE cms_article SET status = ? WHERE id = ?",
                ArticleStatus.REJECTED.getCode(), id);
        articleService.submitForAudit(id, AUTHOR_ID);
        assertEquals("已驳回也能重新提交", ArticleStatus.PENDING_EDITOR.getCode(),
                articleMapper.selectById(id).getStatus());

        System.out.println("[S09] 提交初审：0 → 1（含 4 → 1），快照序列 " + versionNosOf(id));
    }

    // ==================== 核查项 5：batchSubmit ====================

    /** ids 为空 / null → 400（手册核查项 5 的前半）。 */
    @Test
    public void testBatchSubmitRejectsEmptyIds() {
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.batchSubmit(new ArrayList<>(), AUTHOR_ID));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.batchSubmit(null, AUTHOR_ID));
        System.out.println("[S09] batchSubmit 空数组/null 均返回 400");
    }

    /**
     * 超过 100 条 → 400；恰好 100 条不拒。
     *
     * <p>⚠️ 注意上限判的是"<b>去重后要处理几篇</b>"，不是"传了几个"。
     * 所以传 100 个相同 id 只算 1 篇，不该被拒（见 {@code batchSubmit} 的注释）。
     */
    @Test
    public void testBatchSubmitRejectsOverLimit() {
        List<Long> tooMany = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            tooMany.add((long) i);
        }
        String msg = expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.batchSubmit(tooMany, AUTHOR_ID));
        assertTrue("提示应说明上限，实际：" + msg, msg.contains("100"));

        // 100 个**同一个** id：去重后只有 1 篇，不该因为"传了 100 个"被拒。
        // 它会被真正处理，所以这里用一个真实草稿，之后照常清理。
        Long id = newDraft("去重上限边界", "正文");
        List<Long> hundredSameIds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundredSameIds.add(id);
        }
        BatchSubmitResultVO result = articleService.batchSubmit(hundredSameIds, AUTHOR_ID);
        assertEquals("100 个相同 id 去重后只处理 1 篇", Integer.valueOf(1), result.getTotal());
        assertEquals(Integer.valueOf(1), result.getSuccessCount());

        System.out.println("[S09] 101 条被拒（" + msg + "）；100 个相同 id 去重后按 1 篇处理");
    }

    /**
     * 部分成功：3 篇（2 篇合法 + 1 篇不存在）→ 成功 2、失败 1，且失败原因可读
     * （手册核查项 5 的后半）。
     */
    @Test
    public void testBatchSubmitPartialSuccess() {
        Long a = newDraft("批量A", "正文A");
        Long b = newDraft("批量B", "正文B");
        // 用一个绝不存在的 id：先确认它查不到
        Long ghostId = 99999999L;
        assertNull("前提：该 id 在库里不存在", articleMapper.selectById(ghostId));

        BatchSubmitResultVO result = articleService.batchSubmit(
                Arrays.asList(a, b, ghostId), AUTHOR_ID);

        assertEquals("总数应为 3", Integer.valueOf(3), result.getTotal());
        assertEquals("成功 2 篇", Integer.valueOf(2), result.getSuccessCount());
        assertEquals("失败 1 篇", Integer.valueOf(1), result.getFailCount());
        assertEquals("失败清单应恰好 1 条", 1, result.getFailList().size());
        assertEquals(ghostId, result.getFailList().get(0).getId());
        assertEquals("文章不存在", result.getFailList().get(0).getReason());
        assertEquals("计数关系必须是 total = success + fail",
                result.getTotal().intValue(),
                result.getSuccessCount() + result.getFailCount());

        // 关键：合法的两篇**必须真的提交成功了** —— 这才叫"逐条容错"，
        // 而不是"有一条失败就整批回滚"。
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode(),
                articleMapper.selectById(a).getStatus());
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode(),
                articleMapper.selectById(b).getStatus());

        System.out.println("[S09] batchSubmit 部分成功：total=" + result.getTotal()
                + " success=" + result.getSuccessCount()
                + " fail=" + result.getFailCount()
                + " failReason=" + result.getFailList().get(0).getReason());
    }

    /**
     * 别人的文章混在批量里 → 只有那一条失败，自己的照常成功。
     *
     * <p>这条守的是"批量接口里的越权处理"：既不能整批拒绝
     * （用户只是多勾了一篇），也不能把别人的也提交了。
     */
    @Test
    public void testBatchSubmitIsolatesNonOwnedArticles() {
        Long mine = newDraft("批量我的", "正文");
        Long others = newDraft("批量别人的", "正文");
        // 把第二篇的作者改成 author2（模拟"别人的草稿被误勾选"）
        jdbc().update("UPDATE cms_article SET author_id = ? WHERE id = ?", AUTHOR2_ID, others);

        BatchSubmitResultVO result = articleService.batchSubmit(Arrays.asList(mine, others), AUTHOR_ID);

        assertEquals(Integer.valueOf(1), result.getSuccessCount());
        assertEquals(Integer.valueOf(1), result.getFailCount());
        assertEquals(others, result.getFailList().get(0).getId());
        assertEquals("只能操作自己创建的文章", result.getFailList().get(0).getReason());
        assertEquals("自己的那篇必须提交成功", ArticleStatus.PENDING_EDITOR.getCode(),
                articleMapper.selectById(mine).getStatus());
        assertEquals("别人的那篇状态不得变化", ArticleStatus.DRAFT.getCode(),
                articleMapper.selectById(others).getStatus());

        System.out.println("[S09] 批量提交内逐条越权判断：1 成功 1 失败（11003）");
    }

    // ==================== 核查项 6：取不到用户时不抛 NPE ====================

    /**
     * {@code operatorId = null} → 抛 {@code BusinessException(401)}，不是 NPE。
     *
     * <p>手册核查项 6 的完整形态是"用无 Cookie 的请求试一次"（HTTP 层要得到 401），
     * 那部分在真机联调里验（{@code curl} 不带 JSESSIONID）。
     * 这一条守的是**服务层**：万一拦截器被绕过、或定时任务误调用，
     * 得到的也必须是一个 401 业务码，而不是一个堆栈指向某行空指针。
     */
    @Test
    public void testNullOperatorYields401InsteadOfNpe() {
        CmsArticleSaveDTO dto = dto("【S09实测】无操作人", "正文");

        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.saveDraft(dto, null));
        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.updateDraft(1L, dto, null));
        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.submitForAudit(1L, null));
        expectBusinessCode(ErrorCode.UNAUTHORIZED.getCode(),
                () -> articleService.batchSubmit(Arrays.asList(1L, 2L), null));

        System.out.println("[S09] operatorId=null 的四个入口都返回 401，无 NPE");
    }

    /** 查不存在的文章 → 11001；id 为 null → 400。 */
    @Test
    public void testNotFoundAndNullId() {
        Long ghostId = 99999999L;
        expectBusinessCode(ErrorCode.ARTICLE_NOT_FOUND.getCode(),
                () -> articleService.submitForAudit(ghostId, AUTHOR_ID));
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.submitForAudit(null, AUTHOR_ID));
        System.out.println("[S09] 不存在的文章 → 11001；id=null → 400");
    }

    // ==================== 清空字段与乐观锁（本轮实测抓到的两个问题） ====================

    /**
     * 修改草稿时，传 {@code null} 必须把列<b>真正清空</b>。
     *
     * <h3>⚠️ 这条用例是"先证伪、再修复"的产物</h3>
     * <p>第一版实现用的是 {@code baseMapper.updateById(article)}。真机实测时发现：
     * 请求 {@code PUT /articles/76/draft} 带 {@code "summary": null}，
     * 接口返回 <b>200「草稿已保存」</b>，而数据库里 {@code summary} 还是旧值 ——
     * 一个字都没改。根因是 MP 的 {@code updateById} 默认字段策略
     * {@code NOT_NULL}：值为 null 的字段被整段跳过，不进 SET 子句。
     *
     * <p>这类"返回成功但数据没变"的静默失效比报错难查得多，所以修复后立刻补上这条断言，
     * 并且断言的是<b>数据库里的值</b>（不是内存里的实体）—— 内存里的实体永远是"对的"，
     * 只有查库才能看见这个 bug。
     */
    @Test
    public void testUpdateDraftCanClearNullableFields() {
        Long id = newDraft("清空字段", "原始正文");

        // 第一步：把摘要、封面写进去（此时三列都非空）
        CmsArticleSaveDTO withValues = dto("【S09实测】清空字段", "原始正文");
        withValues.setSummary("原始摘要");
        withValues.setCoverUrl("/a.png");
        articleService.updateDraft(id, withValues, AUTHOR_ID);

        assertNotNull("前提：摘要应已被写入",
                jdbc().queryForObject("SELECT summary FROM cms_article WHERE id = ?", String.class, id));

        // 第二步：三个字段全部传 null
        CmsArticleSaveDTO withNulls = dto("【S09实测】清空字段", null);
        withNulls.setSummary(null);
        withNulls.setCoverUrl(null);
        articleService.updateDraft(id, withNulls, AUTHOR_ID);

        // 第三步：查库 —— 必须是 NULL，不能是旧值
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT summary, cover_url, content FROM cms_article WHERE id = ?", id);
        assertNull("summary 应被清空为 NULL（若仍是旧值，说明 updateById 跳过了 null 字段）",
                row.get("summary"));
        assertNull("cover_url 应被清空为 NULL", row.get("cover_url"));
        assertNull("content 应被清空为 NULL", row.get("content"));

        // 顺带确认：清空也是一次真实修改，版本号照常递增
        List<Integer> nos = versionNosOf(id);
        assertEquals("两次 updateDraft 应有 1/2/3 三条快照，实际 " + nos, Arrays.asList(1, 2, 3), nos);

        // 清空后该草稿不能再提交（正文没了）—— 与 requireSubmittable 的行为一致
        expectBusinessCode(ErrorCode.PARAM_ERROR.getCode(),
                () -> articleService.submitForAudit(id, AUTHOR_ID));

        System.out.println("[S09] 清空字段实测：summary/cover_url/content 三列均已置为 NULL");
    }

    /**
     * 改用 {@code update(entity, wrapper)} 之后，**乐观锁必须仍然生效**。
     *
     * <p>这条是上一条修复的"安全网"：{@code updateById} 的乐观锁是 MP 插件的既有行为，
     * 而改用 wrapper 之后，插件还认不认这个写法（能不能从实体上读到 version 并追加
     * {@code SET version = version+1 ... AND version = 旧值}）必须实测。
     * 若不生效，就成了"为了修一个 bug 引入另一个更隐蔽的 bug"——
     * 并发编辑会静默互相覆盖。
     *
     * <p>这里直接对着 Mapper 做探针（而不是绕 Service），
     * 因为要验证的正是"这个写法的 SQL 长什么样"。
     */
    @Test
    public void testOptimisticLockStillWorksWithUpdateWrapper() {
        Long id = newDraft("wrapper乐观锁", "正文");
        CmsArticle loaded = articleMapper.selectById(id);
        assertNotNull(loaded);

        // 模拟"别人先改了"：直接把 version 推高，绕开乐观锁
        jdbc().update("UPDATE cms_article SET version = version + 1 WHERE id = ?", id);

        // 用**过期**的 version 通过 wrapper 路径更新
        CmsArticle carrier = new CmsArticle();
        carrier.setId(id);
        carrier.setVersion(loaded.getVersion());
        int rows = articleMapper.update(carrier,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CmsArticle>lambdaUpdate()
                        .eq(CmsArticle::getId, id)
                        .set(CmsArticle::getTitle, "【S09实测】过期覆盖（不该生效）"));

        assertEquals("update(entity, wrapper) 路径下乐观锁也必须生效，version 不匹配应返回 0", 0, rows);
        assertFalse("过期写入不得生效", "【S09实测】过期覆盖（不该生效）"
                .equals(articleMapper.selectById(id).getTitle()));

        System.out.println("[S09] 乐观锁在 update(entity, wrapper) 路径下同样生效（返回 0）");
    }

    // ==================== 乐观锁（框架行为实测） ====================

    /**
     * 实测 MP 乐观锁：用**过期的 version** 更新，{@code updateById} 返回 0 且不抛异常。
     *
     * <p>这条断言的意义不在于"测我们自己的代码"，而在于钉住
     * "为什么 {@code updateDraft} 里必须写 {@code if (updateById(...) == 0) throw ...}"。
     * 如果哪天 MP 升级后改成"冲突即抛异常"，这条测试会失败，
     * 提醒我们回头审视那行判 0 的代码 —— 而不是让"静默丢数据"重新出现。
     *
     * <p>⚠️ 唯一索引冲突与 {@code isDuplicateKey} 识别的验证在
     * {@code com.lab.cms.service.impl.VersionSnapshotDuplicateKeyTest} ——
     * 那个方法在实现类里是包级可见（刻意不开放成 public API），
     * 只有同包的测试类才够得着。
     */
    @Test
    public void testMybatisPlusOptimisticLockReturnsZeroOnStaleVersion() {
        Long id = newDraft("乐观锁", "正文");

        // 模拟"别人先改了" —— 用 SQL 直接把 version 推高，绕开乐观锁
        jdbc().update("UPDATE cms_article SET version = version + 1 WHERE id = ?", id);

        CmsArticle stale = articleMapper.selectById(id);
        assertNotNull(stale);
        stale.setTitle("【S09实测】乐观锁（过期版本写入）");
        // 把内存里的 version 手动调回旧值，等价于"我在别人改之前读到的数据"
        stale.setVersion(stale.getVersion() - 1);

        int rows = articleMapper.updateById(stale);
        assertEquals("version 不匹配时 updateById 应返回 0（这是 MP 的行为，不是我们的）", 0, rows);
        assertFalse("过期写入不得生效",
                "【S09实测】乐观锁（过期版本写入）".equals(articleMapper.selectById(id).getTitle()));

        System.out.println("[S09] MP 乐观锁实测：version 不匹配时 updateById 返回 0，不抛异常");
    }
}
