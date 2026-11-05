package com.lab.cms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.result.PageQuery;
import org.junit.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 分页与多条件检索的<b>接口层</b>测试（S19）。对应手册 S19 四类测试中的
 * 「4. ArticleQueryTest —— 分页与多条件查询」。
 *
 * <h2>它与已有两个测试类的分工（不是重复）</h2>
 * <table border="1">
 *   <tr><th>层次</th><th>已有/新增</th><th>保证什么</th></tr>
 *   <tr>
 *     <td>纯单元（无容器无库）</td>
 *     <td>{@code PageQueryTest}（22 例）</td>
 *     <td>{@code normalize()} / {@code checkSortField()} 的<b>规则</b>本身正确</td>
 *   </tr>
 *   <tr>
 *     <td>Service + 真库</td>
 *     <td>{@code ArticlePageQueryServiceTest}（21 例）</td>
 *     <td>拼出来的 SQL 确实按条件过滤、排序字段走白名单</td>
 *   </tr>
 *   <tr>
 *     <td><b>HTTP 接口（本类）</b></td>
 *     <td>{@code ArticleQueryTest}</td>
 *     <td>query string 能<b>绑定到 DTO</b>、越界值被归一化后<b>仍然返回统一返回体</b>、
 *         非法排序字段在 HTTP 上是 <b>400 而不是 500</b></td>
 *   </tr>
 * </table>
 *
 * <h2>为什么"接口层"这一格非补不可</h2>
 * <p>前两层都直接 {@code new PageQuery()} 然后设值，<b>绕过了 Web 绑定</b>。
 * 而"查询参数怎么从 query string 落到 DTO"恰好是一个独立的失败点：
 * <ul>
 *   <li>{@code CmsArticleQueryDTO} 的 {@code beginTime} 是 {@code LocalDateTime}，
 *       走的是 {@code JacksonConfig} 之外的另一条转换链（{@code Converter}/
 *       {@code DateTimeFormatter}），与 {@code @RequestBody} 的 JSON 解析
 *       <b>完全是两套代码</b>；</li>
 *   <li>参数名写错（如 {@code ?creatTime=}）不会有任何报错，只是条件静默失效
 *       —— 这正是"查全表"与"查到自己想看的数据"之间的差别。</li>
 * </ul>
 * <p>本类用真实 HTTP query string 把这些点都走一遍。
 *
 * <p>回滚机制、上下文装配、登录态来源见父类 {@link AbstractArticleApiTest} 的类注释。
 */
public class ArticleQueryTest extends AbstractArticleApiTest {

    // ==================== 一、分页边界 ====================

    /**
     * {@code page=0}：应当被归一化为第 1 页，而不是传给 SQL 让 MySQL 报语法错误。
     */
    @Test
    public void testPageZeroIsNormalizedToOne() throws Exception {
        JsonNode data = list("admin", "page=0&size=5").get("data");
        assertEquals("page=0 应归一化为 1", 1L, data.get("current").asLong());
        assertTrue("归一化后仍应有数据", data.get("records").size() > 0);
    }

    /** {@code page=-1}：同样归一化为 1（负数页码是典型的越界输入）。 */
    @Test
    public void testNegativePageIsNormalizedToOne() throws Exception {
        JsonNode data = list("admin", "page=-1&size=5").get("data");
        assertEquals(1L, data.get("current").asLong());
    }

    /** {@code size=0}：归一化为默认 10，不能变成"查 0 条"。 */
    @Test
    public void testSizeZeroFallsBackToDefault() throws Exception {
        JsonNode data = list("admin", "page=1&size=0").get("data");
        assertEquals("size=0 应回落到默认值 " + PageQuery.DEFAULT_SIZE,
                (long) PageQuery.DEFAULT_SIZE, data.get("size").asLong());
    }

    /** {@code size=-1}：同样回落到默认值。 */
    @Test
    public void testNegativeSizeFallsBackToDefault() throws Exception {
        JsonNode data = list("admin", "page=1&size=-1").get("data");
        assertEquals((long) PageQuery.DEFAULT_SIZE, data.get("size").asLong());
    }

    /**
     * {@code size=9999}：必须被截到上限 100。
     *
     * <p>这是<b>防拖库</b>的最后一道：没有这条，前端一个
     * {@code ?size=999999} 就能把整表拉出来。
     */
    @Test
    public void testHugeSizeIsCappedToMax() throws Exception {
        JsonNode data = list("admin", "page=1&size=9999").get("data");
        assertEquals("size 上限应为 " + PageQuery.MAX_SIZE,
                (long) PageQuery.MAX_SIZE, data.get("size").asLong());
        assertTrue("返回条数不能超过上限", data.get("records").size() <= PageQuery.MAX_SIZE);
    }

    /** 完全不传分页参数：走默认值（page=1, size=10），接口不能因为缺参而报错。 */
    @Test
    public void testMissingPagingParamsUsesDefaults() throws Exception {
        JsonNode data = list("admin", "").get("data");
        assertEquals(1L, data.get("current").asLong());
        assertEquals((long) PageQuery.DEFAULT_SIZE, data.get("size").asLong());
    }

    /** {@code pages} 字段应是"总页数 = ceil(total / size)"，前端翻页组件直接用它。 */
    @Test
    public void testPagesIsCeilOfTotalOverSize() throws Exception {
        JsonNode data = list("admin", "page=1&size=5").get("data");
        long total = data.get("total").asLong();
        long size = data.get("size").asLong();
        long expected = (total + size - 1) / size;
        assertEquals("总页数应为 ceil(total/size)", expected, data.get("pages").asLong());
    }

    // ==================== 二、排序字段 ====================

    /** 合法排序字段 + 方向 → 200，且真的按该列排序。 */
    @Test
    public void testLegalSortFieldWorks() throws Exception {
        JsonNode asc = list("admin", "page=1&size=20&sortField=id&sortOrder=asc").get("data");
        JsonNode desc = list("admin", "page=1&size=20&sortField=id&sortOrder=desc").get("data");

        long firstAsc = asc.get("records").get(0).get("id").asLong();
        long firstDesc = desc.get("records").get(0).get("id").asLong();
        assertTrue("升序时第一条 id 应小于降序时第一条 id（" + firstAsc + " vs " + firstDesc + "）",
                firstAsc < firstDesc);
    }

    /** 排序方向传乱值 → 静默回落为 desc（不报错，但也不会拼进 SQL）。 */
    @Test
    public void testIllegalSortOrderFallsBackToDesc() throws Exception {
        JsonNode data = list("admin", "page=1&size=5&sortField=id&sortOrder=sideways").get("data");
        assertTrue("非法方向应回落为 desc（id 最大的在前）", data.get("records").size() > 0);
        assertTrue("desc 时首条 id 应大于第二条",
                data.get("records").get(0).get("id").asLong()
                        > data.get("records").get(1).get("id").asLong());
    }

    /**
     * ★ {@code sortField} 传 SQL 注入串 → <b>HTTP 400</b>，而不是 500、更不是把串拼进 ORDER BY。
     *
     * <p>三种写法覆盖三种典型手法：分号截断、注释截断、UNION。
     * <p>⚠️ 判据是"<b>400 且响应体是统一 Result 结构</b>"：
     * 若实现里忘了调 {@code checkSortField()}，这条会变成"200 且返回全表"或
     * "500 语法错误"，两种都很难在真实环境里被发现（后者还会把 SQL 片段暴露给前端）。
     */
    @Test
    public void testSqlInjectionInSortFieldReturns400() throws Exception {
        String[] payloads = {
                "id; drop table sys_user",
                "id-- ",
                "(select 1) union select 2",
        };
        for (String payload : payloads) {
            MvcResult result = mockMvc.perform(get("/api/v1/articles")
                            .param("page", "1")
                            .param("size", "5")
                            .param("sortField", payload)
                            .session(loginAs(ADMIN_ID)))
                    .andReturn();

            assertEquals("注入串应被拒绝为 400：" + payload,
                    400, result.getResponse().getStatus());
            assertCode(result, 400);
            assertNotNull("必须返回统一返回体而不是容器的错误页", body(result).get("message"));
        }

        // 反向确认：注入串没有被写进任何地方，表还在（sys_user 仍可查）
        Integer users = jdbc().queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        assertNotNull("sys_user 表应依然存在", users);
        assertTrue(users > 0);
    }

    /**
     * 排序字段用<b>库表里真实存在、但不在业务白名单里</b>的列 → 400。
     *
     * <p>与上一条的区别：上一条挡的是"格式像标识符但内容是恶意 SQL"，
     * 这一条挡的是"格式完全合法、列也确实存在，但它不是给列表排序用的"
     * （例如 {@code content} 是 LONGTEXT，按它排序会全表 filesort）。
     * 白名单校验（{@code checkSortFieldIn}）才能挡住这一类。
     */
    @Test
    public void testNonWhitelistedColumnReturns400() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sortField", "content")
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("content 不在列表排序白名单里", 400, result.getResponse().getStatus());
        assertCode(result, 400);
    }

    // ==================== 三、多条件组合 ====================

    /**
     * 单条件：按 {@code status} 过滤。
     *
     * <p>⚠️ 用"库里真实存在的状态"而不是写死数字：先查库看某个状态有几篇，
     * 再用接口查同一个状态，两边必须一致 —— 这样断言不会因为种子数据微调而失效。
     */
    @Test
    public void testFilterByStatus() throws Exception {
        int expected = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article WHERE deleted = 0 AND status = ?",
                Integer.class, ArticleStatus.PUBLISHED.getCode());

        JsonNode data = list("admin",
                "page=1&size=100&status=" + ArticleStatus.PUBLISHED.getCode()).get("data");

        assertEquals("按状态过滤的 total 应与库表一致", (long) expected, data.get("total").asLong());
        for (JsonNode record : data.get("records")) {
            assertEquals("所有返回项的状态都应是已发布",
                    ArticleStatus.PUBLISHED.getCode().intValue(), record.get("status").asInt());
        }
    }

    /** 单条件：按 {@code authorId} 过滤。 */
    @Test
    public void testFilterByAuthorId() throws Exception {
        int expected = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article WHERE deleted = 0 AND author_id = ?",
                Integer.class, AUTHOR_ID);

        JsonNode data = list("admin", "page=1&size=100&authorId=" + AUTHOR_ID).get("data");
        assertEquals((long) expected, data.get("total").asLong());
        for (JsonNode record : data.get("records")) {
            assertEquals("所有返回项的作者都应是目标作者",
                    AUTHOR_ID.longValue(), record.get("authorId").asLong());
        }
    }

    /**
     * 单条件：按 {@code title} 模糊查。
     *
     * <p>为了不依赖种子标题，先造一篇标题带随机串的文章，再用这个串去查 ——
     * 命中数必须 ≥1，且每条标题都含这个串。
     */
    @Test
    public void testFilterByTitleKeyword() throws Exception {
        String marker = "S19模糊查询" + System.nanoTime();
        articleService.saveDraft(saveDto(marker + "-甲"), AUTHOR_ID);
        articleService.saveDraft(saveDto(marker + "-乙"), AUTHOR_ID);

        JsonNode data = list("admin", "page=1&size=100&title=" + marker).get("data");
        assertEquals("应命中自己造的两篇", 2L, data.get("total").asLong());
        for (JsonNode record : data.get("records")) {
            assertTrue("返回的标题应含查询串",
                    record.get("title").asText().contains(marker));
        }
    }

    /** ★ 多条件组合：状态 + 作者 + 分类 三者同时生效（不是"只有一个生效"）。 */
    @Test
    public void testCombinedConditionsAllTakeEffect() throws Exception {
        // 造两篇同作者同分类的草稿，其中一篇留在草稿、另一篇提交走人
        String marker = "S19组合查询" + System.nanoTime();
        Long stay = articleService.saveDraft(saveDto(marker + "-留"), AUTHOR_ID);
        Long move = articleService.saveDraft(saveDto(marker + "-走"), AUTHOR_ID);
        articleService.submitForAudit(move, AUTHOR_ID);

        JsonNode drafts = list("admin", "page=1&size=100&title=" + marker
                + "&authorId=" + AUTHOR_ID
                + "&categoryId=" + CATEGORY_ID
                + "&status=" + ArticleStatus.DRAFT.getCode()).get("data");

        assertEquals("组合条件后应只剩 1 篇", 1L, drafts.get("total").asLong());
        assertEquals("留下的应是没提交的那篇", stay.longValue(),
                drafts.get("records").get(0).get("id").asLong());

        // 同一组条件换个状态 → 应命中另一篇（证明 status 这个条件真的在起作用）
        JsonNode pending = list("admin", "page=1&size=100&title=" + marker
                + "&authorId=" + AUTHOR_ID
                + "&categoryId=" + CATEGORY_ID
                + "&status=" + ArticleStatus.PENDING_EDITOR.getCode()).get("data");
        assertEquals(1L, pending.get("total").asLong());
        assertEquals(move.longValue(), pending.get("records").get(0).get("id").asLong());
    }

    /** 条件故意查不到东西 → 200 + 空列表（不是 404、也不是 500）。 */
    @Test
    public void testNoMatchReturnsEmptyPageNotError() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "10")
                        .param("title", "绝对不存在的标题-" + System.nanoTime())
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("查不到不等于出错", 200, result.getResponse().getStatus());
        JsonNode data = body(result).get("data");
        assertEquals(0L, data.get("total").asLong());
        assertEquals("records 应是空数组而不是 null（前端 v-for 直接遍历）",
                0, data.get("records").size());
        assertEquals("空结果的 pages 应为 0", 0L, data.get("pages").asLong());
    }

    // ==================== 四、时间区间条件（本类抓到真缺陷的地方） ====================

    /**
     * ★★ S19 抓到并修掉的跨模块缺陷：query string 里的时间参数认不认格式。
     *
     * <h3>缺陷原貌（修之前实测到的报错）</h3>
     * <pre>
     * GET /api/v1/articles?beginTime=2026-10-14&amp;endTime=2026-10-20
     * → 400 参数校验失败：beginTime Failed to convert property value of type
     *   'java.lang.String' to required type 'java.time.LocalDateTime'
     *   for value [2026-10-14]; ... Parse attempt failed for value [2026-10-14]
     * </pre>
     *
     * <p>根因：项目里有两条互不相干的"字符串 → 时间"转换链 ——
     * {@code @RequestBody} 的 JSON 走 {@code JacksonConfig}（只认 {@code yyyy-MM-dd HH:mm:ss}），
     * 而 query string 绑定 DTO 走 Spring 默认的 {@code DateTimeFormatterRegistrar}（只认 ISO 的 {@code T}）。
     * 前端 {@code ArticleList.vue} 的创建时间范围选择器配的是
     * {@code type="daterange" + value-format="YYYY-MM-DD"}，传的正是这种"纯日期"
     * —— 于是<b>列表页的时间筛选一用就 400</b>。
     *
     * <p>修复：新增 {@code StringToLocalDateTimeConverter} 注册到
     * {@code SpringMvcConfig#addFormatters}，接受三种写法
     * （{@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd HH:mm} / {@code yyyy-MM-dd}）。
     *
     * <p>本用例是这条修复的判据：三种写法都必须 200。
     */
    @Test
    public void testThreeAcceptedTimeFormatsInQueryString() throws Exception {
        String[] dates = {"2026-10-14 00:00:00", "2026-10-14 00:00", "2026-10-14"};
        for (String date : dates) {
            MvcResult result = mockMvc.perform(get("/api/v1/articles")
                            .param("page", "1")
                            .param("size", "5")
                            .param("beginTime", date)
                            .session(loginAs(ADMIN_ID)))
                    .andReturn();

            assertEquals("query string 的时间应接受写法 [" + date + "]，实际响应体：" + body(result),
                    200, result.getResponse().getStatus());
        }
    }

    /**
     * ⚠️ 反向钉住：ISO-8601 的 {@code T} 分隔写法在 query string 上<b>不</b>被接受。
     *
     * <p>这不是"功能缺失"，而是刻意的口径统一：S13 已经定过
     * "不要在某个接口单独做格式兼容，否则项目里会出现两套时间口径"。
     * 若哪天有人"顺手"给转换器加上 {@code T} 支持，这条用例会失败 ——
     * 失败是好事，因为那意味着前端又会分裂出"JSON 传空格、query 传 T"两种写法。
     */
    @Test
    public void testIsoSeparatorInQueryStringIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "5")
                        .param("beginTime", "2026-10-14T00:00:00")
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("ISO 的 T 分隔写法应被拒绝（保持项目只有一种时间口径）",
                400, result.getResponse().getStatus());
        assertCode(result, 400);
    }

    /** 完全不合法的时间串 → 400（而不是 500、也不是静默当成没传）。 */
    @Test
    public void testGarbageTimeValueReturns400() throws Exception {
        for (String bad : new String[]{"2026/10/14", "昨天", "2026-10-14 25:00:00"}) {
            MvcResult result = mockMvc.perform(get("/api/v1/articles")
                            .param("page", "1")
                            .param("size", "5")
                            .param("beginTime", bad)
                            .session(loginAs(ADMIN_ID)))
                    .andReturn();
            assertEquals("非法时间串应 400：" + bad, 400, result.getResponse().getStatus());
            assertCode(result, 400);
        }
    }

    /** 空串等同于"没传这个条件"，不能 400（前端偶尔会带出空参数）。 */
    @Test
    public void testBlankTimeValueIsTolerated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "5")
                        .param("beginTime", "")
                        .param("endTime", "  ")
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("空串时间参数应被当成「没传」，实际响应体：" + body(result),
                200, result.getResponse().getStatus());
    }

    /**
     * ★★ 右边界语义的实测证据：纯日期当 {@code endTime} 会漏掉当天。
     *
     * <p>前一条证明了"纯日期能解析"，但能解析 ≠ 语义对。转换器把
     * {@code 2026-10-14} 解析成 {@code 2026-10-14 00:00:00}，
     * SQL 是 {@code create_time <= ?} —— 于是<b>当天创建的文章一条都查不到</b>。
     *
     * <p>本用例用"今天刚建的一篇文章"把这个坑钉死：
     * <ol>
     *   <li>{@code endTime = 今天}（纯日期，解析成 00:00:00）→ <b>查不到</b>；</li>
     *   <li>{@code endTime = 今天 23:59:59} → <b>查到</b>。</li>
     * </ol>
     * <p>这就是前端 {@code ArticleList.vue} 必须在 setter 里补 {@code 23:59:59} 的理由 ——
     * 用户选"到 10-20"的自然语义是"含 20 日全天"。
     * 补这件事只能由"知道自己是区间右端"的那一层做，底层转换器无从判断。
     */
    @Test
    public void testPureDateEndTimeMissesSameDayRecords() throws Exception {
        String marker = "S19右边界" + System.nanoTime();
        articleService.saveDraft(saveDto(marker), AUTHOR_ID);

        java.time.LocalDate today = java.time.LocalDate.now();

        // ① 纯日期右边界 → 解析成 00:00:00 → 漏掉今天创建的文章
        JsonNode pureDate = list("admin", "page=1&size=100&title=" + marker
                + "&endTime=" + today).get("data");
        assertEquals("纯日期 endTime 会被当成当天 0 点，因此查不到当天创建的文章",
                0L, pureDate.get("total").asLong());

        // ② 补到 23:59:59 → 查得到
        JsonNode closedBound = list("admin", "page=1&size=100&title=" + marker
                + "&beginTime=" + today + " 00:00:00"
                + "&endTime=" + today + " 23:59:59").get("data");
        assertEquals("补到 23:59:59 后应查到", 1L, closedBound.get("total").asLong());
    }

    /**
     * 区间条件确实进了 SQL（不是被静默忽略）：区间在很远的未来 → 命中 0 条。
     */
    @Test
    public void testFutureRangeYieldsEmptyResult() throws Exception {
        String begin = LocalDateTime.now().plusDays(365).withNano(0).format(TIME_FORMAT);
        String end = LocalDateTime.now().plusDays(366).withNano(0).format(TIME_FORMAT);

        JsonNode data = list("admin", "page=1&size=10&beginTime=" + begin + "&endTime=" + end)
                .get("data");

        assertEquals("未来区间不可能有文章，说明 beginTime 真的生效了",
                0L, data.get("total").asLong());
    }

    /**
     * 区间本身反了（begin 晚于 end）→ 400「开始时间不能晚于结束时间」。
     *
     * <p>{@code ArticleServiceImpl} 在拼 SQL 之前显式做了这个校验；
     * 不校验的话会返回一个"空列表"，用户会以为"这段时间真的没有文章"。
     */
    @Test
    public void testReversedRangeReturns400() throws Exception {
        String begin = LocalDateTime.now().plusDays(10).withNano(0).format(TIME_FORMAT);
        String end = LocalDateTime.now().plusDays(1).withNano(0).format(TIME_FORMAT);

        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "5")
                        .param("beginTime", begin)
                        .param("endTime", end)
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("区间反了应明确报错，实际响应体：" + body(result),
                400, result.getResponse().getStatus());
        assertCode(result, 400);
    }

    // ==================== 五、列表接口的"不该出现"清单 ====================

    /** 列表 VO 不含正文（LONGTEXT），也不应把逻辑删除标记暴露出去。 */
    @Test
    public void testListDoesNotLeakHeavyOrInternalFields() throws Exception {
        JsonNode data = list("admin", "page=1&size=5").get("data");
        assertTrue(data.get("records").size() > 0);

        JsonNode first = data.get("records").get(0);
        assertFalse("列表不应带正文（LONGTEXT）", first.has("content"));
        assertFalse("列表不应暴露逻辑删除标记", first.has("deleted"));
        assertFalse("列表不应暴露乐观锁版本号", first.has("version"));

        // 该有的展示字段必须有
        assertTrue("列表应带作者名（详情页/列表页都要显示）", first.has("authorName"));
        assertTrue("列表应带状态中文描述，前端不自己映射", first.has("statusDesc"));
        assertTrue("列表应带状态颜色", first.has("statusColor"));
    }

    /** 状态中文描述与颜色必须与状态枚举一致（前端不自己维护一份映射表）。 */
    @Test
    public void testStatusDescMatchesEnumOverHttp() throws Exception {
        JsonNode data = list("admin", "page=1&size=100").get("data");
        Set<Integer> seen = new HashSet<>();
        for (JsonNode record : data.get("records")) {
            int status = record.get("status").asInt();
            seen.add(status);
            ArticleStatus expected = ArticleStatus.of(status);
            assertEquals("状态中文描述应与枚举一致（status=" + status + "）",
                    expected.getDescription(), record.get("statusDesc").asText());
            assertEquals("状态颜色应与枚举一致（status=" + status + "）",
                    expected.getColor(), record.get("statusColor").asText());
        }
        assertTrue("这一页应覆盖到多种状态，否则这条断言的说服力不足", seen.size() >= 5);
    }

    // ==================== 私有辅助 ====================

    /**
     * 发一次列表查询并返回解析后的完整响应体。
     *
     * @param role  登录角色（admin / chief / editor / author）
     * @param query query string（不含 {@code ?}），可为空串
     */
    private JsonNode list(String role, String query) throws Exception {
        String url = "/api/v1/articles" + (query == null || query.isEmpty() ? "" : "?" + query);
        MvcResult result = mockMvc.perform(get(url).session(loginAs(userIdOf(role)))).andReturn();
        assertResultShape(result);
        assertCode(result, 200);
        assertNotNull("data 不应为 null：" + url, body(result).get("data"));
        return body(result);
    }

    private Long userIdOf(String role) {
        switch (role) {
            case "author":
                return AUTHOR_ID;
            case "author2":
                return AUTHOR2_ID;
            case "editor":
                return EDITOR_ID;
            case "chief":
                return CHIEF_ID;
            case "admin":
                return ADMIN_ID;
            default:
                throw new IllegalArgumentException("未知角色：" + role);
        }
    }
}
