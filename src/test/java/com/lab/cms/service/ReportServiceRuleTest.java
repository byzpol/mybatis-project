package com.lab.cms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.SpringConfig;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.ReportOverviewVO;
import com.lab.cms.vo.ReportTypeVO;
import com.lab.cms.vo.StatusCountVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 【S24】统计报表接口的业务规则测试（真库，事务内回滚）。
 *
 * <h3>本类的重点：{@code sys_report.result_json} 是 MySQL 的 JSON 列</h3>
 * <p>建表时它是 {@code json} 类型，写入必须给<b>合法 JSON 文本</b>，
 * 而不是随便一个字符串。手拼 JSON（{@code "{\"a\":" + x + "}"}）的失败方式是
 * 运行期 {@code Invalid JSON text}，且只在某字段恰好含引号/换行时触发 ——
 * 典型的"测试都对、线上偶发 500"。
 * <p>本类因此不只断言"能写进去"，还<b>回读并用 MySQL 自己的
 * {@code JSON_VALID()} 校验</b>，再用 {@code JSON_EXTRACT} 取值对比 ——
 * 由数据库做第三方裁判，而不是只信应用层。
 *
 * <h3>⚠️ 本类断言的口径</h3>
 * <p>报表的"总数"一律指"未逻辑删除的数据"。断言用"直接 SQL 数出来的值"对比，
 * 不硬编码 20 / 9 / 10 —— 联调会往库里加数据。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = SpringConfig.class)
@Transactional
public class ReportServiceRuleTest {

    /** 报表类型：文章状态分布。 */
    private static final int TYPE_ARTICLE_STATUS = 1;

    /** 报表类型：分类分布。 */
    private static final int TYPE_CATEGORY_DIST = 2;

    /** 种子报表 id（admin 于 2026-10-05 生成）。 */
    private static final Long SEED_REPORT_ID = 1L;

    private static final Long OPERATOR_ID = 4L;

    @Autowired
    private ReportService reportService;

    /** ⚠️ 用项目自带的 Bean（注册了 JavaTimeModule），不是 new ObjectMapper()。 */
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    // ==================== 一、类型清单 ====================

    @Test
    public void testListTypesMarksImplementedHonestly() {
        List<ReportTypeVO> types = reportService.listTypes();
        assertNotNull(types);
        assertEquals("类型清单共 5 项（含 1 项占位的「自定义」）", 5, types.size());

        int implemented = 0;
        boolean sawStatusDist = false;
        boolean sawCategoryDist = false;
        boolean sawUnimplemented = false;
        for (ReportTypeVO t : types) {
            assertNotNull("类型名不应为空", t.getName());
            assertNotNull("必须如实标注是否已实现（前端据此置灰）", t.getImplemented());
            if (Boolean.TRUE.equals(t.getImplemented())) {
                implemented++;
                if (t.getType() == TYPE_ARTICLE_STATUS) {
                    sawStatusDist = true;
                }
                if (t.getType() == TYPE_CATEGORY_DIST) {
                    sawCategoryDist = true;
                }
            } else {
                sawUnimplemented = true;
            }
        }
        assertEquals("本次只实现 2 种", 2, implemented);
        assertTrue("1 文章状态分布应标为已实现", sawStatusDist);
        assertTrue("2 分类分布应标为已实现", sawCategoryDist);
        assertTrue("3/4/9 应标为未实现，让前端置灰而不是点了才报 14001", sawUnimplemented);
        System.out.println("[S24] 报表类型清单：5 项，已实现 2 项（1 文章状态分布、2 分类分布）");
    }

    // ==================== 二、实时概览 ====================

    @Test
    public void testOverviewMatchesDatabase() {
        ReportOverviewVO vo = reportService.overview();

        Long dbArticle = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article WHERE deleted = 0", Long.class);
        Long dbCategory = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_category WHERE deleted = 0", Long.class);
        Long dbTag = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_tag WHERE deleted = 0", Long.class);
        Long dbRelation = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_tag WHERE deleted = 0", Long.class);

        assertEquals("文章总数应等于未逻辑删除的文章数", dbArticle, vo.getArticleTotal());
        assertEquals("分类总数", dbCategory, vo.getCategoryTotal());
        assertEquals("标签总数", dbTag, vo.getTagTotal());
        assertEquals("文章-标签关联总数", dbRelation, vo.getArticleTagTotal());

        String expectedPeriod = DateTimeFormatter.ofPattern("yyyy-MM").format(LocalDateTime.now());
        assertEquals("统计周期格式为 yyyy-MM，应与当前月一致", expectedPeriod, vo.getStatPeriod());
        assertNotNull("generatedAt 应被填充", vo.getGeneratedAt());

        System.out.println("[S24] 报表概览与库一致：文章 " + vo.getArticleTotal()
                + "、分类 " + vo.getCategoryTotal()
                + "、标签 " + vo.getTagTotal()
                + "、关联 " + vo.getArticleTagTotal()
                + "，周期 " + vo.getStatPeriod());
    }

    @Test
    public void testArticleStatusDistributionSumsToTotal() {
        ReportOverviewVO vo = reportService.overview();
        List<StatusCountVO> dist = vo.getArticleStatusDistribution();
        assertNotNull(dist);
        assertTrue("应有状态分布行", !dist.isEmpty());

        long sum = 0L;
        for (StatusCountVO row : dist) {
            assertNotNull("状态码不应为空", row.getStatus());
            assertNotNull("计数不应为空", row.getCnt());
            assertTrue("计数应为正（GROUP BY 的语义：没有数据就没有行）", row.getCnt() > 0);
            assertNotNull("状态中文应由 ArticleStatus 补上（脏状态给「未知状态」而不报错）",
                    row.getStatusDesc());
            sum += row.getCnt();
        }
        assertEquals("各状态计数之和必须等于文章总数（否则统计漏了状态）",
                vo.getArticleTotal(), Long.valueOf(sum));
        System.out.println("[S24] 文章状态分布共 " + dist.size() + " 组，合计 " + sum + " = 文章总数");
    }

    /**
     * ⚠️ 本用例顺带验证分类聚合里最容易踩的一处：
     * {@code c.deleted = 0} 必须写在 {@code LEFT JOIN ... ON} 里，
     * <b>不能</b>写在 {@code WHERE} —— 写进 WHERE 会让"分类已被删除"的文章整行被过滤掉，
     * 于是各分类之和小于文章总数。
     */
    @Test
    public void testCategoryDistributionSumsToArticleTotal() {
        ReportOverviewVO vo = reportService.overview();
        List<LabelCountVO> dist = vo.getCategoryDistribution();
        assertNotNull(dist);

        long sum = 0L;
        for (LabelCountVO row : dist) {
            assertNotNull("分类名不应为空（未归类会给「未分类」而不是 null）", row.getLabel());
            assertNotNull(row.getCnt());
            sum += row.getCnt();
        }
        assertEquals("各分类计数之和必须等于文章总数 —— 若小于，说明 deleted 过滤被错放进了 WHERE",
                vo.getArticleTotal(), Long.valueOf(sum));
        System.out.println("[S24] 分类分布共 " + dist.size() + " 组，合计 " + sum + " = 文章总数");
    }

    // ==================== 三、落快照 ====================

    @Test
    public void testSaveSnapshotTypeOneWritesValidJson() throws Exception {
        Long id = reportService.saveSnapshot(TYPE_ARTICLE_STATUS, OPERATOR_ID);
        assertNotNull(id);

        String name = jdbc().queryForObject(
                "SELECT report_name FROM sys_report WHERE id = ?", String.class, id);
        Integer type = jdbc().queryForObject(
                "SELECT report_type FROM sys_report WHERE id = ?", Integer.class, id);
        Long generator = jdbc().queryForObject(
                "SELECT generator_id FROM sys_report WHERE id = ?", Long.class, id);
        String period = jdbc().queryForObject(
                "SELECT stat_period FROM sys_report WHERE id = ?", String.class, id);
        String json = jdbc().queryForObject(
                "SELECT result_json FROM sys_report WHERE id = ?", String.class, id);

        assertEquals("文章状态分布统计", name);
        assertEquals(Integer.valueOf(TYPE_ARTICLE_STATUS), type);
        assertEquals("生成人应落库", OPERATOR_ID, generator);
        assertEquals("周期应与概览一致",
                DateTimeFormatter.ofPattern("yyyy-MM").format(LocalDateTime.now()), period);
        assertNotNull(json);

        // ---- 由 MySQL 自己校验 JSON 合法性（第三方裁判，不只信应用层） ----
        Integer valid = jdbc().queryForObject(
                "SELECT JSON_VALID(result_json) FROM sys_report WHERE id = ?", Integer.class, id);
        assertEquals("result_json 必须是合法 JSON（JSON 列会拒绝非法文本）",
                Integer.valueOf(1), valid);

        // ---- 应用层再解析一次，核对内容确实是概览 ----
        JsonNode node = objectMapper.readTree(json);
        assertTrue("应含 articleTotal 字段", node.has("articleTotal"));
        assertTrue("应含文章状态分布", node.has("articleStatusDistribution"));

        Integer dbExtracted = jdbc().queryForObject(
                "SELECT JSON_EXTRACT(result_json, '$.articleTotal') FROM sys_report WHERE id = ?",
                Integer.class, id);
        assertEquals("JSON 里存的文章总数应与 SQL 直查一致",
                Integer.valueOf(node.get("articleTotal").asInt()), dbExtracted);

        String serializedPeriod = node.get("statPeriod").asText();
        assertEquals("LocalDateTime 能序列化成功，说明用的是注册了 JavaTimeModule 的 ObjectMapper",
                period, serializedPeriod);

        System.out.println("[S24] 报表快照落库成功：id=" + id
                + "、名称=" + name + "、JSON_VALID=1、JSON 长度=" + json.length());
    }

    @Test
    public void testSaveSnapshotTypeTwoStoresCategoryPartOnly() throws Exception {
        Long id = reportService.saveSnapshot(TYPE_CATEGORY_DIST, OPERATOR_ID);
        String name = jdbc().queryForObject(
                "SELECT report_name FROM sys_report WHERE id = ?", String.class, id);
        String json = jdbc().queryForObject(
                "SELECT result_json FROM sys_report WHERE id = ?", String.class, id);

        assertEquals("分类分布统计", name);
        JsonNode node = objectMapper.readTree(json);
        assertTrue("应含 categoryTotal", node.has("categoryTotal"));
        assertTrue("应含 categoryDistribution", node.has("categoryDistribution"));
        // ⚠️ 两种类型的 payload 刻意不同：若类型 2 也存整份概览，
        //    report_type 这一列就失去筛选意义（按类型查会查到内容相同的重复数据）
        assertFalse("类型 2 不应包含文章状态分布（否则两类的 payload 就没有区别了）",
                node.has("articleStatusDistribution"));

        Integer valid = jdbc().queryForObject(
                "SELECT JSON_VALID(result_json) FROM sys_report WHERE id = ?", Integer.class, id);
        assertEquals(Integer.valueOf(1), valid);
        System.out.println("[S24] 分类分布快照：只含分类相关字段，JSON_VALID=1");
    }

    @Test
    public void testSaveSnapshotDefaultsToTypeOneWhenNull() {
        Long id = reportService.saveSnapshot(null, OPERATOR_ID);
        Integer type = jdbc().queryForObject(
                "SELECT report_type FROM sys_report WHERE id = ?", Integer.class, id);
        assertEquals("reportType 为 null 应默认按类型 1 处理", Integer.valueOf(TYPE_ARTICLE_STATUS), type);
    }

    @Test
    public void testSaveSnapshotRejectsUnsupportedType() {
        try {
            reportService.saveSnapshot(3, OPERATOR_ID);   // 3 审核效率 —— 未实现
            fail("未实现的报表类型应报 14001，而不是落一份空快照");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.REPORT_TYPE_UNSUPPORTED.getCode(), e.getCode());
            System.out.println("[S24] 未实现类型被拒：" + e.getMessage());
        }
    }

    @Test
    public void testSaveSnapshotRejectsNullOperator() {
        try {
            reportService.saveSnapshot(TYPE_ARTICLE_STATUS, null);
            fail("未登录不应能落快照（否则报表里查不出是谁生成的）");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.UNAUTHORIZED.getCode(), e.getCode());
        }
    }

    @Test
    public void testSnapshotInsertDoesNotDisturbSeedReport() {
        // 种子报表 id=1 是"这篇文章之前唯一一行"，本步只是新增写入方，不修改它
        String before = jdbc().queryForObject(
                "SELECT result_json FROM sys_report WHERE id = ?", String.class, SEED_REPORT_ID);
        assertNotNull("种子报表应仍在", before);

        reportService.saveSnapshot(TYPE_ARTICLE_STATUS, OPERATOR_ID);

        String after = jdbc().queryForObject(
                "SELECT result_json FROM sys_report WHERE id = ?", String.class, SEED_REPORT_ID);
        assertEquals("新增快照不应改动已有报表", before, after);
        System.out.println("[S24] 新增快照未影响种子报表 id=" + SEED_REPORT_ID);
    }
}
