package com.lab.cms.service;

import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.dto.CmsArticleQueryDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.vo.CmsArticleDetailVO;
import com.lab.cms.vo.CmsArticleVO;
import com.lab.cms.common.result.PageResult;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * S17「文章分页检索」的集成测试。
 *
 * <h2>本类钉住的六个"最容易写错"的点</h2>
 * <ol>
 *   <li><b>模糊检索必须转义通配符</b>：用户搜 {@code "%"} 时，
 *       未转义的 {@code LIKE '%%%'} 会变成"匹配所有文章" ——
 *       搜索框输入一个字面量百分号，返回全站文章，且<b>没有任何报错</b>。
 *       见 {@code testTitleKeywordEscapesWildcards}，用两条对照数据把它钉死。</li>
 *   <li><b>排序字段必须走白名单</b>：{@code sortField} 是字符串拼进 {@code ORDER BY} 的，
 *       是 SQL 注入的唯一入口。既要测"格式非法被拒"，也要测
 *       <b>"格式合法但不在白名单"</b>（如驼峰 {@code createTime}）也被拒。</li>
 *   <li><b>翻页不能丢行也不能重复行</b>：没有 {@code ORDER BY} 时 MySQL 不保证顺序，
 *       分页会出现"某行出现在两页、某行一次都不出现"。用"逐页取全量、断言 id 集合
 *       无重复且总数相符"来验证默认排序是确定的全序。</li>
 *   <li><b>逻辑删除的行不能出现在列表</b>：{@code @TableLogic} 只对 MP 生成的 SQL 生效，
 *       这条要真删一行（置 {@code deleted = 1}）再看列表里有没有它。</li>
 *   <li><b>列表 VO 不含正文</b>：用<b>反射</b>断言，比"跑一次看值"强 ——
 *       正文恰好为 null 时后者会假通过。同时断言详情 VO <b>有</b>正文字段，
 *       证明这条断言真的能区分两个类（否则"两个都没有"也会通过）。</li>
 *   <li><b>空结果不报错</b>：{@code selectBatchIds(空集合)} 会拼出 {@code IN ()},
 *       那是 MySQL <b>语法错误</b>（S11 踩过）。所以"总数为 0 的那一页"必须能正常返回。</li>
 * </ol>
 *
 * <h2>数据安全</h2>
 * <p>用例自建文章（标题前缀 {@code 【S17实测】}），{@code @After} 用 {@code JdbcTemplate}
 * <b>物理删除</b>（先子表后主表）。跑完基线回到 {@code 20/10/12}，不改任何种子数据。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticlePageQueryServiceTest {

    /** 作者 id（本类造的文章都挂在它名下）。 */
    private static final Long AUTHOR_ID = 1L;

    private static final Long CATEGORY_ID = 1L;
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

    /** 物理删除本类自建的文章及其版本 / 流水（先子表后主表）。 */
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
        int articles = jdbc().update("DELETE FROM cms_article WHERE id IN (" + ph + ")", args);
        System.out.println("[S17 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行，cms_article_audit_log " + logs + " 行");
        createdArticleIds.clear();
    }

    // ==================== 辅助 ====================

    /**
     * 造一篇草稿。
     * <p>标题带 {@code nanoTime} 后缀，避免与上一次运行残留（或别的用例）重名 ——
     * 名字不唯一会让"按标题模糊检索"的断言数不准。
     */
    private Long newDraft(String suffix, String title, Long categoryId, Long authorId) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title != null ? title : "【S17实测】" + suffix + " " + System.nanoTime());
        dto.setSummary("S17摘要");
        dto.setContent("S17正文");
        dto.setCategoryId(categoryId);
        dto.setIsTop(0);
        Long id = articleService.saveDraft(dto, authorId);
        createdArticleIds.add(id);
        return id;
    }

    private Long newDraft(String suffix) {
        return newDraft(suffix, null, CATEGORY_ID, AUTHOR_ID);
    }

    private PageResult<CmsArticleVO> query(CmsArticleQueryDTO q) {
        return articleService.pageArticles(q);
    }

    private Set<Long> idsOf(PageResult<CmsArticleVO> page) {
        Set<Long> ids = new HashSet<>();
        for (CmsArticleVO vo : page.getRecords()) {
            ids.add(vo.getId());
        }
        return ids;
    }

    private CmsArticleVO findIn(PageResult<CmsArticleVO> page, Long id) {
        for (CmsArticleVO vo : page.getRecords()) {
            if (id.equals(vo.getId())) {
                return vo;
            }
        }
        return null;
    }

    /** 断言某次调用抛出指定业务码（与 S14/S15 同口径，返回 message 便于排查）。 */
    private String expectBusinessCode(int expectedCode, Runnable action) {
        try {
            action.run();
            fail("期望抛出业务码 " + expectedCode + "，但调用成功了");
            return null;
        } catch (BusinessException e) {
            assertEquals("业务码不符", expectedCode, (int) e.getCode());
            return e.getMessage();
        }
    }

    // ==================== 一、基本分页 ====================

    /** 默认查询（不传任何条件）应当返回分页结果，且字段完整。 */
    @Test
    public void testDefaultQueryReturnsPagedResult() {
        Long mine = newDraft("默认查询");

        PageResult<CmsArticleVO> page = query(new CmsArticleQueryDTO());

        assertNotNull("分页结果不应为 null", page);
        assertNotNull(page.getRecords());
        assertTrue("种子数据就有 20 篇，默认查询不该是空", page.getTotal() >= 20);
        assertEquals("默认每页 10 条", Long.valueOf(10L), page.getSize());
        assertEquals("默认第 1 页", Long.valueOf(1L), page.getCurrent());
        assertTrue("默认页不应超过 size 条", page.getRecords().size() <= 10);
        assertNotNull("默认查询必须能找到刚建的文章（按 create_time 倒序，它在第一页）",
                findIn(page, mine));
    }

    /** `size` 超过上限必须被截断到 100，`page<1` 必须归 1。 */
    @Test
    public void testPageAndSizeNormalized() {
        CmsArticleQueryDTO over = new CmsArticleQueryDTO();
        over.setSize(9999);
        over.setPage(0);
        PageResult<CmsArticleVO> page = query(over);

        assertEquals("size 超上限应截到 MAX_SIZE=100", Long.valueOf(100L), page.getSize());
        assertEquals("page<1 应归为 1", Long.valueOf(1L), page.getCurrent());
        assertTrue("截断后的 size 必须真的生效（最多返回 100 条）", page.getRecords().size() <= 100);
    }

    /** 取值超出总数的页码：返回空 records，但 total 仍然是真实总数（不是 0）。 */
    @Test
    public void testOutOfRangePageReturnsEmptyRecordsWithRealTotal() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setPage(9999);
        q.setSize(10);
        PageResult<CmsArticleVO> page = query(q);

        assertTrue("越界页应当没有数据", page.getRecords().isEmpty());
        assertTrue("越界页的 total 仍应是真实总数", page.getTotal() >= 20);
        assertEquals(Long.valueOf(9999L), page.getCurrent());
    }

    /** 总数为 0 的查询必须正常返回（不能因为 `selectBatchIds(空)` 拼出 `IN ()` 而报错）。 */
    @Test
    public void testEmptyResultDoesNotThrow() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        // 用一个几乎不可能存在的标题关键字造出 0 条结果
        q.setTitle("__S17_必然查不到的标题_" + System.nanoTime() + "__");
        PageResult<CmsArticleVO> page = query(q);

        assertEquals("应当是 0 条", Long.valueOf(0L), page.getTotal());
        assertTrue(page.getRecords().isEmpty());
        assertEquals("空结果的 pages 应当是 0", Long.valueOf(0L), page.getPages());
    }

    // ==================== 二、过滤条件 ====================

    /** 标题模糊匹配（子串）。 */
    @Test
    public void testTitleFilterMatchesSubstring() {
        String marker = "S17标题唯一标记" + System.nanoTime();
        Long target = newDraft("标题命中", "【S17实测】前缀" + marker + "后缀", CATEGORY_ID, AUTHOR_ID);
        Long other = newDraft("标题不命中", "【S17实测】完全无关的标题 " + System.nanoTime(),
                CATEGORY_ID, AUTHOR_ID);

        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setTitle(marker);
        PageResult<CmsArticleVO> page = query(q);

        assertNotNull("子串也能命中（LIKE %kw%）", findIn(page, target));
        assertNull("无关文章不该出现在结果里", findIn(page, other));
        assertEquals("这个标记是唯一的，应当只命中 1 条", Long.valueOf(1L), page.getTotal());
    }

    /**
     * ⚠️ 本类最重要的一条：关键字里的 {@code %} 与 {@code _} 必须当**字面量**处理。
     *
     * <p>不转义时：{@code title="%"} → {@code LIKE '%%%'} → 匹配**所有**文章，
     * 用户在搜索框里打一个百分号就能把全站文章列出来，而且**不报任何错**。
     * <p>对照数据：A 的标题里<b>含</b> {@code %}，B 的标题里<b>没有</b> {@code %}。
     * 断言"搜 % 能命中 A、不能命中 B"，两条一起才说明转义真的生效
     * （只断言"能命中 A"在没有转义时也会通过）。
     */
    @Test
    public void testTitleKeywordEscapesWildcards() {
        String nano = String.valueOf(System.nanoTime());
        Long withPercent = newDraft("含百分号", "【S17实测】折扣 50% 特惠 " + nano, CATEGORY_ID, AUTHOR_ID);
        Long plain = newDraft("不含通配符", "【S17实测】普通标题 " + nano, CATEGORY_ID, AUTHOR_ID);

        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setTitle("%");
        PageResult<CmsArticleVO> page = query(q);
        assertNotNull("标题里含字面量 % 的文章应当被命中", findIn(page, withPercent));
        assertNull("⚠️ 不含 % 的文章不该被命中 —— 命中说明 % 被当成通配符了", findIn(page, plain));

        CmsArticleQueryDTO q2 = new CmsArticleQueryDTO();
        q2.setTitle("_");
        PageResult<CmsArticleVO> page2 = query(q2);
        assertNull("⚠️ 不含 _ 的文章不该被单字通配符命中", findIn(page2, plain));
    }

    /** 状态过滤。 */
    @Test
    public void testStatusFilter() {
        Long draft = newDraft("状态过滤");
        // 种子里 id=11/12/13 是已发布(5)，用它验证"筛已发布能筛到东西"
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setStatus(5);
        PageResult<CmsArticleVO> published = query(q);

        assertNotNull("应当能筛到已发布的种子文章", findIn(published, 11L));
        assertNull("草稿不该出现在 status=5 的结果里", findIn(published, draft));
        for (CmsArticleVO vo : published.getRecords()) {
            assertEquals("结果里每一条的状态都必须是 5", Integer.valueOf(5), vo.getStatus());
        }
    }

    /** 分类过滤。 */
    @Test
    public void testCategoryIdFilter() {
        Long inCat1 = newDraft("分类1", null, CATEGORY_ID, AUTHOR_ID);
        Long inCat2 = newDraft("分类2", null, CATEGORY_ID_2, AUTHOR_ID);

        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setCategoryId(CATEGORY_ID);
        PageResult<CmsArticleVO> page = query(q);

        assertNotNull("分类 1 的文章应当在结果里", findIn(page, inCat1));
        assertNull("分类 2 的文章不该出现", findIn(page, inCat2));
        for (CmsArticleVO vo : page.getRecords()) {
            assertEquals("结果的分类必须都是 1", CATEGORY_ID, vo.getCategoryId());
        }
    }

    /** 作者过滤（前端"我的投稿"页就是这么用的）。 */
    @Test
    public void testAuthorIdFilter() {
        Long mine = newDraft("作者过滤");
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setAuthorId(AUTHOR_ID);
        PageResult<CmsArticleVO> page = query(q);

        assertNotNull(findIn(page, mine));
        for (CmsArticleVO vo : page.getRecords()) {
            assertEquals("结果的作者必须都是入参那个", AUTHOR_ID, vo.getAuthorId());
        }
    }

    /** 创建时间区间（闭区间）。 */
    @Test
    public void testCreateTimeRangeFilter() {
        Long target = newDraft("时间区间");

        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setBeginTime(LocalDateTime.now().minusDays(1));
        q.setEndTime(LocalDateTime.now().plusDays(1));
        assertNotNull("昨天到今天之间建的文章应当被命中", findIn(query(q), target));

        CmsArticleQueryDTO past = new CmsArticleQueryDTO();
        past.setBeginTime(LocalDateTime.now().minusDays(10));
        past.setEndTime(LocalDateTime.now().minusDays(5));
        assertNull("5 天前到 10 天前这个窗口里不该有刚建的文章",
                findIn(query(past), target));
    }

    /** 只传一端是合法的（"此后所有"/"此前所有"），不该报 400。 */
    @Test
    public void testOpenEndedTimeRangeIsAllowed() {
        Long target = newDraft("单端时间");

        CmsArticleQueryDTO onlyBegin = new CmsArticleQueryDTO();
        onlyBegin.setBeginTime(LocalDateTime.now().minusMinutes(1));
        assertNotNull("只传 beginTime 应当合法", findIn(query(onlyBegin), target));

        CmsArticleQueryDTO onlyEnd = new CmsArticleQueryDTO();
        onlyEnd.setEndTime(LocalDateTime.now().plusMinutes(1));
        assertNotNull("只传 endTime 应当合法", findIn(query(onlyEnd), target));
    }

    /** 区间颠倒（begin > end）必须明确报 400，而不是静默返回空列表。 */
    @Test
    public void testBeginAfterEndRejectedWith400() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setBeginTime(LocalDateTime.now());
        q.setEndTime(LocalDateTime.now().minusDays(1));

        String msg = expectBusinessCode(400, () -> query(q));
        assertTrue("提示里应说明是时间区间的问题，实际：" + msg,
                msg != null && msg.contains("时间"));
    }

    // ==================== 三、排序与注入防御 ====================

    /** 白名单内的列名可以正常排序，且真的按该列排了。 */
    @Test
    public void testSortByWhitelistedColumnWorks() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setSortField("id");
        q.setSortOrder("desc");
        q.setSize(10);
        PageResult<CmsArticleVO> page = query(q);

        assertEquals(10, page.getRecords().size());
        for (int i = 1; i < page.getRecords().size(); i++) {
            long prev = page.getRecords().get(i - 1).getId();
            long cur = page.getRecords().get(i).getId();
            assertTrue("应当按 id 倒序：第 " + i + " 条 " + cur + " 不大于前一条 " + prev, prev > cur);
        }
    }

    /** `sortOrder` 传垃圾值应当回落成 desc，而不是拼进 SQL。 */
    @Test
    public void testIllegalSortOrderFallsBackToDesc() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setSortField("id");
        q.setSortOrder("'; DROP TABLE cms_article");
        PageResult<CmsArticleVO> page = query(q);

        assertTrue("不该抛异常，应当回落", page.getRecords().size() > 0);
        assertTrue("表还在（回落成 desc 而不是拼进 SQL）", page.getTotal() >= 20);
    }

    /** 注入样本：格式非法 → 400。 */
    @Test
    public void testSortFieldInjectionRejectedWith400() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setSortField("create_time; DROP TABLE cms_article");
        String msg = expectBusinessCode(400, () -> query(q));
        assertTrue("应说明排序字段非法，实际：" + msg, msg != null && msg.contains("排序字段"));

        CmsArticleQueryDTO q2 = new CmsArticleQueryDTO();
        q2.setSortField("1=1");
        expectBusinessCode(400, () -> query(q2));

        // 表必须完好无损
        Integer rows = jdbc().queryForObject("SELECT COUNT(*) FROM cms_article", Integer.class);
        assertTrue("注入样本不该影响表", rows != null && rows >= 20);
    }

    /**
     * ⚠️ "格式合法但不在白名单"也必须拒 —— 这条最容易漏。
     * <p>驼峰属性名 {@code createTime} 在正则下是合法标识符，
     * 若只做格式校验就会放过它，然后在数据库层报 {@code Unknown column}（500）。
     */
    @Test
    public void testCamelCaseSortFieldRejectedWith400() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setSortField("createTime");
        String msg = expectBusinessCode(400, () -> query(q));
        assertTrue("应提示不支持按该列排序并列出可选项，实际：" + msg,
                msg != null && msg.contains("不支持按"));
        assertTrue("提示里应给出可选的列名", msg.contains("create_time"));
    }

    /** 白名单外的真实列名（内容正文）也要拒 —— 按正文排序没有业务意义且代价极高。 */
    @Test
    public void testNonWhitelistedRealColumnRejectedWith400() {
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setSortField("content");
        expectBusinessCode(400, () -> query(q));
    }

    // ==================== 四、翻页正确性 ====================

    /**
     * ⚠️ 翻页不能丢行、也不能重复行。
     *
     * <p>做法：按 size=10 逐页取完整个结果集，把 id 收进一个 Set，
     * 断言"去重后的条数 == 总条数"。若默认排序不唯一（比如只按 create_time 排，
     * 而种子数据里有同秒创建的行），就会出现重复/遗漏，这条断言立刻失败。
     */
    @Test
    public void testPagingCoversEveryRowExactlyOnce() {
        newDraft("翻页1");
        newDraft("翻页2");
        newDraft("翻页3");

        CmsArticleQueryDTO first = new CmsArticleQueryDTO();
        first.setSize(10);
        PageResult<CmsArticleVO> p1 = query(first);
        long total = p1.getTotal();
        long pages = p1.getPages();
        assertTrue("种子 20 篇 + 自建 3 篇，至少 2 页", pages >= 2);

        Set<Long> seen = new HashSet<>();
        int collected = 0;
        for (long p = 1; p <= pages; p++) {
            CmsArticleQueryDTO q = new CmsArticleQueryDTO();
            q.setSize(10);
            q.setPage((int) p);
            PageResult<CmsArticleVO> page = query(q);
            for (CmsArticleVO vo : page.getRecords()) {
                assertTrue("⚠️ 第 " + p + " 页出现了重复行 id=" + vo.getId()
                        + " —— 默认排序不是确定的全序", seen.add(vo.getId()));
                collected++;
            }
        }
        assertEquals("逐页取到的总行数必须等于总数（既不丢行也不重复）",
                total, collected);
        assertEquals("去重后条数必须等于总条数", total, (long) seen.size());
    }

    // ==================== 五、逻辑删除与 VO 形状 ====================

    /** 逻辑删除的文章不能出现在列表里（`@TableLogic` 要真的生效）。 */
    @Test
    public void testSoftDeletedArticleNotInList() {
        Long doomed = newDraft("逻辑删除");
        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setAuthorId(AUTHOR_ID);
        assertNotNull("删除前应当在列表里", findIn(query(q), doomed));

        // 直接置 deleted = 1：走 Service 的删除入口会给"仅草稿可删"等规则牵扯，
        // 这里只想验证"列表 SQL 有没有带 deleted = 0"
        jdbc().update("UPDATE cms_article SET deleted = 1 WHERE id = ?", doomed);

        assertNull("⚠️ 逻辑删除后的文章不该出现在列表 —— 出现说明查询漏了 deleted = 0",
                findIn(query(q), doomed));
    }

    /**
     * 列表 VO **不含正文**；详情 VO **有**正文。
     *
     * <p>两条一起断言才有意义：只测"列表 VO 没有 content"的话，
     * 万一两个 VO 都被改成没有 content，测试仍然通过，看不出异常。
     */
    @Test
    public void testListVoHasNoContentField() {
        assertFalse("列表 VO 不该有 content 字段（响应体会从几百字节涨到几十 KB）",
                hasField(CmsArticleVO.class, "content"));
        assertTrue("详情 VO 应当有 content 字段（否则这条对照断言失去意义）",
                hasField(CmsArticleDetailVO.class, "content"));
    }

    private boolean hasField(Class<?> clazz, String name) {
        for (Field f : clazz.getDeclaredFields()) {
            if (name.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 列表要能拼出分类名与作者名（否则前端只能显示 id）。 */
    @Test
    public void testCategoryNameAndAuthorNameResolved() {
        Long id = newDraft("名称拼装");

        CmsArticleQueryDTO q = new CmsArticleQueryDTO();
        q.setTitle("S17实测】名称拼装");
        CmsArticleVO vo = findIn(query(q), id);

        assertNotNull("应当能查到刚建的文章", vo);
        assertNotNull("分类名不能为 null（否则前端只能显示分类 id）", vo.getCategoryName());
        assertNotNull("作者名不能为 null", vo.getAuthorName());
        assertEquals("分类名应当与库里一致",
                jdbc().queryForObject("SELECT category_name FROM cms_category WHERE id = ?",
                        String.class, CATEGORY_ID),
                vo.getCategoryName());
        assertEquals("作者名应当与库里一致",
                jdbc().queryForObject("SELECT nickname FROM sys_user WHERE id = ?",
                        String.class, AUTHOR_ID),
                vo.getAuthorName());
        assertNotNull("状态文案由枚举填好，不该让前端映射", vo.getStatusDesc());
        assertNotNull("状态颜色同理", vo.getStatusColor());
    }
}
