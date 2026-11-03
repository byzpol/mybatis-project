package com.lab.cms.service;

import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.vo.CmsArticleVersionVO;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
 * S15「版本快照与回滚」的集成测试。
 *
 * <h2>本类钉住的五个"最容易写错"的点</h2>
 * <ol>
 *   <li><b>版本号只增不减</b>：回滚不是"撤销"，它会<b>再产生一版</b>
 *       （{@code max + 1}）。若实现成"删掉后面的版本"，版本号会被复用，
 *       "第 N 版"这个说法就此失效 —— 用 {@code testRollbackAppendsNewVersionInsteadOfDeleting}
 *       把它钉死。</li>
 *   <li><b>回滚只恢复标题与正文</b>：{@code summary / cover_url / category_id / is_top}
 *       不在快照里，回滚<b>不该</b>动它们。这条很容易被"顺手一起恢复"的写法破坏，
 *       而且破坏了也看不出错（值看起来更"一致"）。见
 *       {@code testRollbackRestoresOnlyTitleAndContent}。</li>
 *   <li><b>列表接口不返回正文</b>：用<b>反射</b>断言 VO 上没有正文字段，
 *       比"跑一次看有没有值"强 —— 后者在正文恰好为 null 时会假通过。</li>
 *   <li><b>唯一索引是真兜底</b>：不只是"代码里记得处理冲突"，
 *       而是直接在库里验证索引存在、且真的拒绝重复。见
 *       {@code testUniqueIndexRejectsDuplicateVersionNo}。</li>
 *   <li><b>校验顺序沿用 S14</b>：归属 → 已归档 → 状态白名单。
 *       所以非作者回滚已归档文章拿 11003 而不是 11011。</li>
 * </ol>
 *
 * <h2>数据安全</h2>
 * <p>所有用例都在自建文章上操作，{@code @After} 用 {@code JdbcTemplate} <b>物理删除</b>
 * （先子表后主表），跑完不留痕、不改任何种子数据。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleVersionServiceTest {

    /** 作者：本类造的文章都挂在它名下，因此它是有归属权的那个操作人。 */
    private static final Long AUTHOR_ID = 1L;

    /** 另一个投稿人：用来验证"回滚别人的文章会被拒"。 */
    private static final Long OTHER_AUTHOR_ID = 5L;

    private static final Long CATEGORY_ID = 1L;

    /** 另一个存在的分类（用来验证"回滚不恢复分类"）。 */
    private static final Long CATEGORY_ID_2 = 2L;

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
     * 物理删除本类自建的文章及其版本 / 流水。
     * <p>⚠️ 先删子表再删主表；用 {@code DELETE} 而不是逻辑删除 ——
     * 逻辑删除会留下行，让"跑完基线回到 20 篇"这条验收判据失效。
     */
    @After
    public void cleanUp() {
        if (createdArticleIds.isEmpty()) {
            return;
        }
        String ph = createdArticleIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = createdArticleIds.toArray();
        int versions = jdbc().update(
                "DELETE FROM cms_article_version WHERE article_id IN (" + ph + ")", args);
        int logs = jdbc().update(
                "DELETE FROM cms_article_audit_log WHERE article_id IN (" + ph + ")", args);
        int articles = jdbc().update(
                "DELETE FROM cms_article WHERE id IN (" + ph + ")", args);
        System.out.println("[S15 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    /** 走真实入口造一篇草稿（会产生 version_no = 1 的快照）。 */
    private Long newDraft(String suffix) {
        return newDraft(suffix, "初始摘要", CATEGORY_ID);
    }

    private Long newDraft(String suffix, String summary, Long categoryId) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle("【S15实测】" + suffix + " " + System.nanoTime());
        dto.setSummary(summary);
        dto.setContent("初始正文");
        dto.setCategoryId(categoryId);
        dto.setIsTop(0);
        Long id = articleService.saveDraft(dto, AUTHOR_ID);
        createdArticleIds.add(id);
        return id;
    }

    private CmsArticleSaveDTO saveDto(String title, String summary, String content, Long categoryId) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary(summary);
        dto.setContent(content);
        dto.setCategoryId(categoryId);
        dto.setIsTop(0);
        return dto;
    }

    /** 用 SQL 直改状态。白名单/黑名单要覆盖的状态很多，走链路造太绕，且会引入链路自身的 bug。 */
    private void setStatus(Long id, ArticleStatus status) {
        jdbc().update("UPDATE cms_article SET status = ? WHERE id = ?", status.getCode(), id);
    }

    /** 该文章的版本号列表（正序）。 */
    private List<Integer> versionNosOf(Long articleId) {
        return jdbc().queryForList(
                "SELECT version_no FROM cms_article_version WHERE article_id = ? ORDER BY version_no",
                Integer.class, articleId);
    }

    private Integer maxVersionNoOf(Long articleId) {
        return jdbc().queryForObject(
                "SELECT IFNULL(MAX(version_no), 0) FROM cms_article_version WHERE article_id = ?",
                Integer.class, articleId);
    }

    private String remarkOf(Long articleId, int versionNo) {
        return jdbc().queryForObject(
                "SELECT modify_remark FROM cms_article_version WHERE article_id = ? AND version_no = ?",
                String.class, articleId, versionNo);
    }

    private String snapshotTitleOf(Long articleId, int versionNo) {
        return jdbc().queryForObject(
                "SELECT title_snapshot FROM cms_article_version WHERE article_id = ? AND version_no = ?",
                String.class, articleId, versionNo);
    }

    private String snapshotContentOf(Long articleId, int versionNo) {
        return jdbc().queryForObject(
                "SELECT content_snapshot FROM cms_article_version WHERE article_id = ? AND version_no = ?",
                String.class, articleId, versionNo);
    }

    private String titleOf(Long id) {
        return jdbc().queryForObject("SELECT title FROM cms_article WHERE id = ?", String.class, id);
    }

    private String contentOf(Long id) {
        return jdbc().queryForObject("SELECT content FROM cms_article WHERE id = ?", String.class, id);
    }

    private String summaryOf(Long id) {
        return jdbc().queryForObject("SELECT summary FROM cms_article WHERE id = ?", String.class, id);
    }

    private Long categoryIdOf(Long id) {
        return jdbc().queryForObject("SELECT category_id FROM cms_article WHERE id = ?", Long.class, id);
    }

    private String nicknameOf(Long userId) {
        return jdbc().queryForObject("SELECT nickname FROM sys_user WHERE id = ?", String.class, userId);
    }

    /** 断言某次调用抛出指定业务码（与 S14 测试同口径，返回 message 便于排查）。 */
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

    // ==================== 一、版本快照的产生时机 ====================

    /**
     * 核查项 1：创建草稿 → 改两次 → 版本号 1/2/3。
     *
     * <p>顺带钉住"后端按 versionNo <b>正序</b>返回"这条约定：
     * 倒序展示是前端的事，后端若擅自倒序，前端再排一次就会出现两处方向相反的排序逻辑。
     */
    @Test
    public void testThreeVersionsForCreatePlusTwoUpdates() {
        Long id = newDraft("改两次");
        assertEquals("创建草稿应产生 version_no = 1", "[1]", versionNosOf(id).toString());

        articleService.updateDraft(id, saveDto("【S15实测】改两次-第2版", "第2版摘要", "第2版正文", CATEGORY_ID), AUTHOR_ID);
        articleService.updateDraft(id, saveDto("【S15实测】改两次-第3版", "第3版摘要", "第3版正文", CATEGORY_ID), AUTHOR_ID);

        List<CmsArticleVersionVO> list = articleService.listVersions(id);
        assertEquals("应有 3 个版本", 3, list.size());
        assertEquals("接口应返回 versionNo 正序（1,2,3）",
                "[1, 2, 3]", list.stream().map(CmsArticleVersionVO::getVersionNo)
                        .map(String::valueOf).collect(Collectors.joining(", ", "[", "]")));
        assertEquals("版本说明应与动作对应",
                "[创建草稿, 更新草稿, 更新草稿]",
                list.stream().map(CmsArticleVersionVO::getModifyRemark)
                        .collect(Collectors.joining(", ", "[", "]")));

        System.out.println("[S15] 创建+改两次 → 版本 " + versionNosOf(id)
                + "，说明 " + list.stream().map(CmsArticleVersionVO::getModifyRemark).collect(Collectors.toList()));
    }

    /** {@code isCurrent} 恰好一条为 true，且是版本号最大的那条。 */
    @Test
    public void testIsCurrentMarksExactlyTheMaxVersion() {
        Long id = newDraft("isCurrent");
        articleService.updateDraft(id, saveDto("【S15实测】isCurrent-第2版", "s", "c", CATEGORY_ID), AUTHOR_ID);
        articleService.updateDraft(id, saveDto("【S15实测】isCurrent-第3版", "s", "c", CATEGORY_ID), AUTHOR_ID);

        List<CmsArticleVersionVO> list = articleService.listVersions(id);
        List<Integer> current = list.stream()
                .filter(v -> Boolean.TRUE.equals(v.getIsCurrent()))
                .map(CmsArticleVersionVO::getVersionNo)
                .collect(Collectors.toList());
        assertEquals("恰好一条 isCurrent=true", 1, current.size());
        assertEquals("isCurrent 应落在最大版本号上", Integer.valueOf(3), current.get(0));

        // 反向断言：其余都必须是 false 而不是 null —— null 会让前端 `v-if="row.isCurrent"` 恒假，
        // 看着"没标错"其实是字段没赋值。这类"用 null 冒充 false"的问题只能显式断言。
        long nullCount = list.stream().filter(v -> v.getIsCurrent() == null).count();
        assertEquals("isCurrent 不能是 null（必须显式赋 false）", 0, nullCount);

        System.out.println("[S15] isCurrent 命中 v" + current.get(0) + "，共 " + list.size() + " 条");
    }

    /** 没有任何快照的文章（SQL 直造）返回空列表，而不是 null。 */
    @Test
    public void testEmptyVersionListForArticleWithoutSnapshots() {
        jdbc().update("INSERT INTO cms_article (title, summary, content, category_id, status, author_id,"
                        + " is_top, view_count, version, create_by, update_by, deleted)"
                        + " VALUES (?, '摘要', '正文', ?, 0, ?, 0, 0, 0, ?, ?, 0)",
                "【S15实测】无快照样本 " + System.nanoTime(), CATEGORY_ID, AUTHOR_ID, AUTHOR_ID, AUTHOR_ID);
        Long id = jdbc().queryForObject(
                "SELECT id FROM cms_article WHERE title LIKE '【S15实测】无快照样本%' ORDER BY id DESC LIMIT 1",
                Long.class);
        createdArticleIds.add(id);

        List<CmsArticleVersionVO> list = articleService.listVersions(id);
        assertNotNull("应返回空列表而不是 null（前端不用写 `|| []` 兜底）", list);
        assertTrue("应返回空列表", list.isEmpty());

        System.out.println("[S15] 无快照文章 → 空列表（非 null）");
    }

    /** 查询不存在的文章 → 11001（不是安静地返回空列表）。 */
    @Test
    public void testListVersionsOnMissingArticleThrows11001() {
        expectBusinessCode(11001, () -> articleService.listVersions(99999999L));
        System.out.println("[S15] 不存在的文章查版本 → 11001");
    }

    // ==================== 二、列表不返回正文（核查项 3 的可自动判定版本） ====================

    /**
     * 核查项 3（"版本列表接口不返回正文字段，用 ApiFox 看返回体大小验证"）的
     * <b>强判据版本</b>：直接用反射断言 VO 上不存在任何名字含 {@code content} 的字段。
     *
     * <p>⚠️ 为什么不用"跑一次看 {@code contentSnapshot} 是不是 null"：
     * 正文恰好为 null 的版本会让那种断言<b>假通过</b>。
     * 反射是结构层面的判据：字段根本不存在，就不可能被序列化出去，
     * 与数据内容无关，永远为真。
     */
    @Test
    public void testVersionVOExposesNoContentField() {
        for (Field f : CmsArticleVersionVO.class.getDeclaredFields()) {
            boolean suspicious = f.getName().toLowerCase().contains("content");
            assertFalse("CmsArticleVersionVO 不应出现正文字段："
                    + f.getName() + "（列表接口会因此带上 LONGTEXT 正文）", suspicious);
        }
        try {
            CmsArticleVersionVO.class.getDeclaredField("contentSnapshot");
            fail("CmsArticleVersionVO 不应包含 contentSnapshot 字段");
        } catch (NoSuchFieldException expected) {
            // 期望路径：字段确实不存在
        }
        System.out.println("[S15] 反射核查：VO 字段 = "
                + java.util.Arrays.stream(CmsArticleVersionVO.class.getDeclaredFields())
                        .map(Field::getName).collect(Collectors.joining(", ")));
    }

    /**
     * 同一修改人产生多个版本时，昵称依然正确。
     *
     * <p>说明同 S11 的做法：真正的 SQL 条数断言在真机里读 {@code logs/cms.log} 的
     * {@code Preparing:} 完成（JUnit 里拿不到 SQL 计数）。这条测试换一个<b>可断言的等价性质</b>：
     * 4 条快照的修改人是同一个人，去重后只该查一次 {@code sys_user}；
     * 若实现是"逐条查"，结果同样正确，但会在真机的 SQL 计数里暴露成 N+1。
     * 两者合起来才完整。
     */
    @Test
    public void testModifierNameResolvedForAllVersions() {
        Long id = newDraft("昵称");
        articleService.updateDraft(id, saveDto("【S15实测】昵称-2", "s", "c", CATEGORY_ID), AUTHOR_ID);
        articleService.updateDraft(id, saveDto("【S15实测】昵称-3", "s", "c", CATEGORY_ID), AUTHOR_ID);
        articleService.updateDraft(id, saveDto("【S15实测】昵称-4", "s", "c", CATEGORY_ID), AUTHOR_ID);

        String expected = nicknameOf(AUTHOR_ID);
        List<CmsArticleVersionVO> list = articleService.listVersions(id);
        assertEquals(4, list.size());
        for (CmsArticleVersionVO vo : list) {
            assertEquals("每条快照的修改人昵称都应变出来", expected, vo.getModifierName());
            assertEquals("modifierId 应原样返回", AUTHOR_ID, vo.getModifierId());
        }
        System.out.println("[S15] 4 条快照（同一修改人）昵称全部解析为 " + expected);
    }

    // ==================== 三、回滚（核查项 2 / 4） ====================

    /**
     * 核查项 2：回滚到版本 1 后，文章内容变成版本 1 的内容，
     * 且版本列表<b>多出一条</b>"回滚至版本 1"。
     */
    @Test
    public void testRollbackRestoresContentAndAppendsNewVersion() {
        Long id = newDraft("回滚主路径");
        articleService.updateDraft(id, saveDto("【S15实测】回滚-第2版标题", "第2版摘要", "第2版正文", CATEGORY_ID), AUTHOR_ID);
        assertEquals("[1, 2]", versionNosOf(id).toString());

        String v1Title = snapshotTitleOf(id, 1);
        String v1Content = snapshotContentOf(id, 1);

        articleService.rollbackVersion(id, 1, AUTHOR_ID);

        assertEquals("文章标题应变成 v1 的标题快照", v1Title, titleOf(id));
        assertEquals("文章正文应变成 v1 的正文快照", v1Content, contentOf(id));
        assertEquals("版本号只增不减：应变成 [1, 2, 3]", "[1, 2, 3]", versionNosOf(id).toString());
        assertEquals("新版本的说明应是『回滚至版本 1』（记目标版本，不是新版本号）",
                "回滚至版本 1", remarkOf(id, 3));
        assertEquals("新版本的标题快照 = v1 的标题", v1Title, snapshotTitleOf(id, 3));
        assertEquals("新版本的正文快照 = v1 的正文", v1Content, snapshotContentOf(id, 3));
        assertEquals("v2 必须还在（回滚不是删除历史）", "第2版正文", snapshotContentOf(id, 2));

        List<CmsArticleVersionVO> list = articleService.listVersions(id);
        assertEquals("回滚后 isCurrent 应指向新产生的 v3", Integer.valueOf(3),
                list.stream().filter(v -> Boolean.TRUE.equals(v.getIsCurrent()))
                        .map(CmsArticleVersionVO::getVersionNo).findFirst().orElse(null));

        System.out.println("[S15] 回滚到 v1：版本 " + versionNosOf(id)
                + "，v3 说明=" + remarkOf(id, 3) + "，isCurrent=v3");
    }

    /**
     * 回滚<b>只</b>恢复标题与正文；{@code summary} / {@code categoryId} 保持在现值。
     *
     * <p>⚠️ 这条用例的价值在于"防顺手"：写回滚时很容易把 {@code summary} 一起
     * 带过去（因为 {@code CmsArticleSaveDTO} 里也有 summary），
     * 而快照表<b>根本没存</b> summary —— 带过去只能是"用文章现值覆盖文章现值"，
     * 或者更糟：让人以为回滚能恢复全部字段。把断言写成"不变"才能钉住语义。
     */
    @Test
    public void testRollbackRestoresOnlyTitleAndContent() {
        Long id = newDraft("只回滚正文", "原始摘要", CATEGORY_ID);
        articleService.updateDraft(id,
                saveDto("【S15实测】只回滚正文-第2版", "改后摘要", "改后正文", CATEGORY_ID_2), AUTHOR_ID);
        assertEquals("前置：分类已改成 2", CATEGORY_ID_2, categoryIdOf(id));

        articleService.rollbackVersion(id, 1, AUTHOR_ID);

        assertEquals("标题应被恢复", snapshotTitleOf(id, 1), titleOf(id));
        assertEquals("正文应被恢复", snapshotContentOf(id, 1), contentOf(id));
        assertEquals("⚠️ 摘要不在快照里，回滚不该动它", "改后摘要", summaryOf(id));
        assertEquals("⚠️ 分类不在快照里，回滚不该动它", CATEGORY_ID_2, categoryIdOf(id));

        System.out.println("[S15] 回滚后：标题/正文=v1 值，摘要='" + summaryOf(id)
                + "'、分类=" + categoryIdOf(id) + "（保持现值，未被回滚）");
    }

    /**
     * 回滚到一个<b>正文为 null</b> 的历史版本时，库里的正文必须真的被清空。
     *
     * <p>⚠️ 这是本项目最经典的静默失效点：{@code updateById(entity)} 的
     * NOT_NULL 字段策略会把 null 字段整段跳过 —— 用那种写法，
     * 本用例会返回成功、标题也变了，只有正文没被清掉（200 但没改）。
     * 所以实现必须走 {@code LambdaUpdateWrapper.set(...)} 显式列列。
     */
    @Test
    public void testRollbackRestoresNullContent() {
        Long id = newDraft("回滚到空正文");
        // 把 v1 的正文快照改成 NULL，模拟"历史版本确实没有正文"
        jdbc().update("UPDATE cms_article_version SET content_snapshot = NULL"
                + " WHERE article_id = ? AND version_no = 1", id);
        articleService.updateDraft(id, saveDto("【S15实测】回滚到空正文-第2版", "s", "有正文", CATEGORY_ID), AUTHOR_ID);
        assertEquals("前置：当前正文非空", "有正文", contentOf(id));

        articleService.rollbackVersion(id, 1, AUTHOR_ID);

        assertNull("⚠️ 正文必须被真的置为 NULL（updateById 的 NOT_NULL 策略会把这一步静默跳过）",
                contentOf(id));
        System.out.println("[S15] 回滚到空正文版本 → 库里 content 已置 NULL（显式列列生效）");
    }

    /** 核查项 4：对已发布文章调回滚被拒。 */
    @Test
    public void testRollbackRejectedOnPublished() {
        Long id = newDraft("已发布不可回滚");
        articleService.updateDraft(id, saveDto("【S15实测】已发布不可回滚-第2版", "s", "c", CATEGORY_ID), AUTHOR_ID);
        setStatus(id, ArticleStatus.PUBLISHED);

        String beforeTitle = titleOf(id);
        expectBusinessCode(11002, () -> articleService.rollbackVersion(id, 1, AUTHOR_ID));
        assertEquals("被拒后标题不该变", beforeTitle, titleOf(id));
        assertEquals("被拒后不该产生新版本", "[1, 2]", versionNosOf(id).toString());

        System.out.println("[S15] 已发布回滚被拒 11002（须先撤回再回滚）");
    }

    /** 状态白名单：草稿 / 已驳回 / 已撤回 可回滚；其余一律 11002；已归档 11011。 */
    @Test
    public void testRollbackStatusWhitelist() {
        // 合法：DRAFT（默认）
        Long draft = newDraft("白名单-草稿");
        articleService.rollbackVersion(draft, 1, AUTHOR_ID);
        assertTrue("草稿可回滚", maxVersionNoOf(draft) >= 2);

        // 合法：REJECTED（→ 作者改稿 → 版本 +1，同 DRAFT）
        Long rejected = newDraft("白名单-已驳回");
        setStatus(rejected, ArticleStatus.REJECTED);
        articleService.rollbackVersion(rejected, 1, AUTHOR_ID);
        assertTrue("已驳回可回滚", maxVersionNoOf(rejected) >= 2);

        // 合法：WITHDRAWN
        Long withdrawn = newDraft("白名单-已撤回");
        setStatus(withdrawn, ArticleStatus.WITHDRAWN);
        articleService.rollbackVersion(withdrawn, 1, AUTHOR_ID);
        assertTrue("已撤回可回滚", maxVersionNoOf(withdrawn) >= 2);

        // 非法：其余状态一律 11002
        for (ArticleStatus st : new ArticleStatus[]{
                ArticleStatus.PENDING_EDITOR,
                ArticleStatus.PENDING_CHIEF,
                ArticleStatus.APPROVED,
                ArticleStatus.PUBLISHED,
                ArticleStatus.SCHEDULED,
                ArticleStatus.OFFLINE}) {
            Long id = newDraft("白名单-" + st.name());
            setStatus(id, st);
            int before = maxVersionNoOf(id);
            expectBusinessCode(11002, () -> articleService.rollbackVersion(id, 1, AUTHOR_ID));
            assertEquals("被拒后不该产生新版本 | " + st.name(), before, maxVersionNoOf(id).intValue());
            System.out.println("[S15 白名单] " + st.name() + "(" + st.getCode() + ") → 11002");
        }

        // 非法且更具体：ARCHIVED → 11011
        Long archived = newDraft("白名单-已归档");
        setStatus(archived, ArticleStatus.ARCHIVED);
        expectBusinessCode(11011, () -> articleService.rollbackVersion(archived, 1, AUTHOR_ID));
        System.out.println("[S15 白名单] ARCHIVED(9) → 11011（归档只读比状态非法更具体）");
    }

    /** 目标版本不存在 → 11009。 */
    @Test
    public void testRollbackToMissingVersionThrows11009() {
        Long id = newDraft("版本不存在");
        expectBusinessCode(11009, () -> articleService.rollbackVersion(id, 99, AUTHOR_ID));
        assertEquals("被拒后不该产生新版本", "[1]", versionNosOf(id).toString());
        System.out.println("[S15] 回滚到不存在的 v99 → 11009");
    }

    /** 非作者回滚 → 11003；且顺序上归属校验先于状态校验（非作者对已归档文章拿 11003 而非 11011）。 */
    @Test
    public void testRollbackRequiresOwnership() {
        Long id = newDraft("归属校验");
        articleService.updateDraft(id, saveDto("【S15实测】归属校验-第2版", "s", "c", CATEGORY_ID), AUTHOR_ID);

        expectBusinessCode(11003, () -> articleService.rollbackVersion(id, 1, OTHER_AUTHOR_ID));
        assertEquals("被拒后不该产生新版本", "[1, 2]", versionNosOf(id).toString());

        // 顺序固化佐证：先归档，再由非作者回滚 → 仍然是 11003
        setStatus(id, ArticleStatus.ARCHIVED);
        expectBusinessCode(11003, () -> articleService.rollbackVersion(id, 1, OTHER_AUTHOR_ID));

        System.out.println("[S15] 非作者回滚 → 11003（对已归档文章也是 11003：归属先于状态，与 S14 同序）");
    }

    /** 无操作人（无 HTTP 上下文时拿不到当前用户）→ 401。 */
    @Test
    public void testRollbackWithNullOperatorRejectedWith401() {
        Long id = newDraft("操作人为空");
        expectBusinessCode(401, () -> articleService.rollbackVersion(id, 1, null));
        System.out.println("[S15] 操作人为 null → 401");
    }

    // ==================== 四、版本号并发的兜底 ====================

    /**
     * 核查项 5 的前半："唯一索引真的存在、真的会拒绝重复"。
     *
     * <p>⚠️ 这条判据的思路是<b>"不查代码、直接问数据库"</b>。项目里"唯一索引兜底"这个说法
     * 出现在多处注释里，但注释不是证据 —— 索引被人删掉、建错成非唯一索引，
     * 代码看起来一模一样。这里读 {@code information_schema} 拿到索引列，
     * 再<b>真的插一条重复记录</b>看它是否被拒。
     *
     * <p><b>S18 更新</b>：本断言原来只数"复合 2 列"（{@code COUNT(*) = 2}）。
     * S18 给全库 9 个唯一键统一追加了 {@code deleted} 末列（逻辑删除与唯一索引的冲突，
     * 见 {@code docs/重构记录.md} 第一节），本索引随之变成 3 列，
     * <b>这条用例如实报了 FAIL（expected:&lt;2&gt; but was:&lt;3&gt;）</b> ——
     * 这正是它该有的表现：DDL 改了、断言没改，必须红灯，不能静默通过。
     *
     * <p>趁着修它，把断言从"只数个数"<b>加强</b>为"钉住列名与顺序"：
     * 原来换成 {@code (article_id, title_snapshot)} 之类的错列也能凑够 2 列而通过；
     * 现在列名与顺序都必须完全一致。
     */
    @Test
    public void testUniqueIndexRejectsDuplicateVersionNo() {
        String indexCols = jdbc().queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)"
                        + " FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE() AND table_name = 'cms_article_version'"
                        + " AND index_name = 'uk_article_version' AND non_unique = 0",
                String.class);
        assertEquals("uk_article_version 必须是唯一索引，且列顺序为"
                        + " (article_id, version_no, deleted)（S18 起含 deleted 末列）",
                "article_id,version_no,deleted", indexCols);

        Long id = newDraft("唯一索引");
        try {
            jdbc().update("INSERT INTO cms_article_version"
                            + " (article_id, version_no, title_snapshot, create_time, deleted)"
                            + " VALUES (?, 1, '重复版本号', NOW(), 0)",
                    id);
            fail("重复的 (article_id, version_no) 应被数据库拒绝");
        } catch (DataIntegrityViolationException expected) {
            // 期望路径：唯一索引生效
        }
        assertEquals("被拒后版本列表不应变化", "[1]", versionNosOf(id).toString());
        System.out.println("[S15] 唯一索引 uk_article_version 存在且真的拒绝重复"
                + "（articleId=" + id + ", versionNo=1；S18 后索引列为 " + indexCols + "）");
    }

    /**
     * 核查项 5：并发更新同一篇文章时，<b>版本号不重复</b>。
     *
     * <p>⚠️ 这里断言的是"版本号无重复"这个<b>不变式</b>，不是"5 个请求全部成功"。
     * 原因是并发更新走的是<b>乐观锁</b>（{@code cms_article.version}）：
     * 同一篇文章被同时改，除第一个之外都会拿到 11010 —— 这是设计如此，
     * 不是失败。所以本用例同时记录并打印"成功 / 乐观锁冲突 / 其它"的分布，
     * 把"到底发生了什么"留在测试输出里，而不是靠猜。
     *
     * <p>⚠️ 顺带说明：{@code insertVersionSnapshot} 的"冲突后重新取号重试"分支
     * （{@code attempt > 1}）在本用例里<b>不一定</b>会被走到 —— 要让两个线程
     * 恰好拿到同一个号，必须让它们的 {@code SELECT MAX} 严格交错，
     * 而这是无法在单进程测试里稳定复现的（S09 已把同一条登记为未覆盖）。
     * 本用例覆盖的是"最终数据不脏"这个可断言的结果。
     */
    @Test
    public void testConcurrentUpdatesNeverProduceDuplicateVersionNo() throws Exception {
        final Long id = newDraft("并发取号");
        articleService.updateDraft(id, saveDto("【S15实测】并发取号-起点", "s", "c", CATEGORY_ID), AUTHOR_ID);
        assertEquals("前置：已有 2 个版本", "[1, 2]", versionNosOf(id).toString());

        final int threads = 5;
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger conflict = new AtomicInteger();
        final List<String> others = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        articleService.updateDraft(id,
                                saveDto("【S15实测】并发取号-线程" + n, "s", "c" + n, CATEGORY_ID), AUTHOR_ID);
                        ok.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getCode() == 11010) {
                            conflict.incrementAndGet();
                        } else {
                            others.add("code=" + e.getCode() + " msg=" + e.getMessage());
                        }
                    } catch (Exception e) {
                        others.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
            assertTrue("线程应在 10 秒内就绪", ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue("线程应在 30 秒内结束", pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals("不应出现非 11010 的异常：" + others, 0, others.size());
        assertEquals("成功 + 乐观锁冲突 应等于线程数", threads, ok.get() + conflict.get());
        assertTrue("至少要有一个成功", ok.get() >= 1);

        // 核心不变式：版本号无重复
        Integer total = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_version WHERE article_id = ?", Integer.class, id);
        Integer distinct = jdbc().queryForObject(
                "SELECT COUNT(DISTINCT version_no) FROM cms_article_version WHERE article_id = ?",
                Integer.class, id);
        assertEquals("版本号不得重复（唯一索引兜底）", total, distinct);
        assertEquals("版本号应是连续的 1..N，无空洞",
                Integer.valueOf(total), maxVersionNoOf(id));

        System.out.println("[S15] 并发更新 5 次 | 成功=" + ok.get() + " 11010(乐观锁)=" + conflict.get()
                + " 其它=" + others + " | 版本号=" + versionNosOf(id) + "（无重复、无空洞）");
    }
}
