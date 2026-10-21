package com.lab.cms.service.impl;

import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticleVersion;
import com.lab.cms.mapper.CmsArticleVersionMapper;
import com.lab.cms.service.ArticleService;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.time.LocalDateTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 【S09】版本快照"取号冲突"这条链路的专项测试。
 *
 * <h3>为什么单独一个类，而不是塞进 {@code ArticleDraftServiceTest}</h3>
 * <p>因为 {@code ArticleServiceImpl#isDuplicateKey} 是<b>包级可见</b>
 * （{@code static}，没有 {@code public}）—— 这是刻意的：
 * 它是实现细节，不该出现在任何对外 API 上。Java 的包级访问控制决定了
 * 只有同包的类能调用它，所以测试类必须放在 {@code com.lab.cms.service.impl}。
 *
 * <p>顺带这个类承担了一件事：把"重试逻辑是不是死代码"变成可断言的事实。
 *
 * <h3>⚠️ 为什么这件事必须实测，不能靠常识</h3>
 * <p>{@code insertVersionSnapshot} 里写了"捕获唯一键冲突 → 重新取号重试"。
 * 这段代码要能生效，前提是它真的捕获到了冲突。而"冲突时的异常长什么样"
 * <b>不是能靠推理确定的</b> —— 本次实测就推翻了一次判断：
 * <ul>
 *   <li>写代码时的先验判断是：本项目是手工 {@code MybatisSqlSessionFactoryBean}
 *       + mybatis-spring，Mapper 接口上标的是 MyBatis 的 {@code @Mapper} 而不是
 *       Spring 的 {@code @Repository}，也没有
 *       {@code PersistenceExceptionTranslationPostProcessor}，
 *       所以异常<b>不会</b>被翻译成 {@code DataAccessException}，
 *       抛出来应该是 {@code org.apache.ibatis.exceptions.PersistenceException}
 *       包着 {@code SQLIntegrityConstraintViolationException}。</li>
 *   <li><b>实测结论：判断错了。</b>抛出来的就是
 *       {@code org.springframework.dao.DuplicateKeyException}，
 *       cause 是 {@code java.sql.SQLIntegrityConstraintViolationException}
 *       （见本测试打印的异常全链）。说明这套装配下 mybatis-spring 2.1.2
 *       确实做了翻译。</li>
 * </ul>
 * <p>假设当时按错误判断去写"只 catch {@code PersistenceException}"，
 * 这段重试就是一段<b>永不执行的死代码</b>，而症状只是"并发时偶发提交失败"，
 * 几乎无法复现。这就是本测试存在的意义：
 * <b>与其推理框架会抛什么，不如让它抛一次给你看。</b>
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class VersionSnapshotDuplicateKeyTest {

    private static final Long AUTHOR_ID = 1L;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CmsArticleVersionMapper versionMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Long articleId;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @After
    public void cleanUp() {
        if (articleId == null) {
            return;
        }
        int versions = jdbc().update("DELETE FROM cms_article_version WHERE article_id = ?", articleId);
        int articles = jdbc().update("DELETE FROM cms_article WHERE id = ?", articleId);
        System.out.println("[S09 清理] 物理删除临时数据：cms_article " + articles
                + " 行，cms_article_version " + versions + " 行");
        articleId = null;
    }

    /**
     * 真机（真库）唯一索引冲突的异常形态 + {@code isDuplicateKey} 的判定能力。
     */
    @Test
    public void testRealDuplicateKeyIsRecognized() {
        // 先用正常业务入口造一篇文章，它会顺带写下 versionNo=1 的快照
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle("【S09实测】唯一索引冲突");
        dto.setContent("正文");
        dto.setCategoryId(1L);
        dto.setIsTop(0);
        articleId = articleService.saveDraft(dto, AUTHOR_ID);
        assertNotNull(articleId);

        Integer existing = versionMapper.selectMaxVersionNo(articleId);
        assertTrue("saveDraft 之后应存在 versionNo >= 1 的快照，实际 " + existing,
                existing != null && existing >= 1);

        // 故意插一条同号的快照 —— 试试数据库会不会放行
        CmsArticleVersion duplicate = new CmsArticleVersion();
        duplicate.setArticleId(articleId);
        duplicate.setVersionNo(existing);
        duplicate.setTitleSnapshot("【S09实测】撞号的快照");
        duplicate.setContentSnapshot("x");
        duplicate.setModifierId(AUTHOR_ID);
        duplicate.setModifyRemark("撞号测试");
        duplicate.setCreateTime(LocalDateTime.now());
        duplicate.setDeleted(0);

        try {
            versionMapper.insert(duplicate);
            fail("同一篇文章插入相同 version_no 必须被唯一索引 uk_article_version 拒绝"
                    + "（否则 insertVersionSnapshot 的重试逻辑没有意义）");
        } catch (RuntimeException e) {
            System.out.println("[S09] 真实唯一键冲突的异常全链：");
            for (Throwable t = e; t != null; t = t.getCause()) {
                System.out.println("        " + t.getClass().getName() + " : " + t.getMessage());
                if (t.getCause() == t) {
                    break;
                }
            }
            assertTrue("isDuplicateKey 必须认得这个异常，否则重试是死代码："
                    + e.getClass().getName(), ArticleServiceImpl.isDuplicateKey(e));
        }

        // 撞号失败后数据不应被写脏：仍然是那一条快照
        assertEqualsOneSnapshot(articleId);
    }

    /**
     * {@code isDuplicateKey} 的正反用例（不依赖数据库，纯判定逻辑）。
     *
     * <p>反向用例尤其重要：如果把"任何 RuntimeException 都当作冲突"，
     * 那么"列名写错"这类真错误会被吞掉并空转重试 3 次，
     * 最后抛出的还是同一个错，但中间多了一堆误导性的 WARN 日志。
     */
    @Test
    public void testIsDuplicateKeyPositiveAndNegative() {
        assertTrue("JDBC 标准的完整性约束异常应被识别",
                ArticleServiceImpl.isDuplicateKey(
                        new SQLIntegrityConstraintViolationException("Duplicate entry '1-1' for key 'uk_article_version'")));

        assertTrue("Spring 翻译过的 DuplicateKeyException 也应被识别（将来若开启异常翻译）",
                ArticleServiceImpl.isDuplicateKey(
                        new org.springframework.dao.DuplicateKeyException("Duplicate entry")));

        assertTrue("包装了两层的异常，只要链上有冲突就该识别",
                ArticleServiceImpl.isDuplicateKey(new RuntimeException("外面一层",
                        new IllegalStateException("中间一层",
                                new SQLIntegrityConstraintViolationException("Duplicate entry")))));

        assertFalse("普通运行时异常不该被识别为唯一键冲突",
                ArticleServiceImpl.isDuplicateKey(new IllegalStateException("随便一个错误")));
        assertFalse("语法错误（列不存在）不是唯一键冲突",
                ArticleServiceImpl.isDuplicateKey(
                        new SQLSyntaxErrorException("Unknown column 'x'", "42S22", 1054)));
        assertFalse("null 不应抛异常，直接判定为非冲突",
                ArticleServiceImpl.isDuplicateKey(null));
    }

    private void assertEqualsOneSnapshot(Long id) {
        Integer count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_version WHERE article_id = ? AND deleted = 0",
                Integer.class, id);
        assertNotNull(count);
        System.out.println("[S09] 撞号失败后，该文章的快照条数 = " + count + "（应保持 1）");
        assertTrue("撞号的那条不应被写入，实际条数 " + count, count == 1);
    }
}
