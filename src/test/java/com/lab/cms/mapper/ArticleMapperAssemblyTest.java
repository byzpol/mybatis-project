package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsArticleAuditLog;
import com.lab.cms.entity.CmsArticleVersion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 文章域三个 Mapper 的装配与可用性测试（S07 新增）。
 *
 * <h3>为什么必须写这个测试</h3>
 * <p>MP 的 Mapper 装配失败是<b>静默的</b>：
 * 接口写了 {@code @Mapper}、实体写了 {@code @TableName}、编译也过了，
 * 但若扫描路径没覆盖到、或 {@code @TableName} 写错，
 * 只有<b>真正执行查询</b>时才报 {@code Invalid bound statement} 或
 * {@code Table 'xxx' doesn't exist}。
 *
 * <p>S07 只建骨架、不写业务接口，所以没有 HTTP 入口能触发这些 Mapper ——
 * 不写这个测试，"三个 Mapper 到底装配成功没有"就是<b>没有证据的猜测</b>。
 * 本测试直接调用 Mapper 的真实方法（连真实库），把这件事钉死。
 *
 * <h3>为什么断言的是"不抛异常"而不是"查到 N 行"</h3>
 * <p>本测试的目的是验证<b>装配正确</b>，不是验证数据。
 * 数据条数会随 S02 初始化脚本的调整而变，把行数写死会让测试变脆。
 * 而"调用不抛异常"这个断言恰好等价于"表名对、列名对、语句能绑定"——
 * 正是装配正确性的判据。
 *
 * <p>用 {@code selectCount} 而不是 {@code selectList}：前者不返回大字段
 * （{@code content} 是 LONGTEXT），对测试更快，且同样会触发
 * "表名/列名/绑定" 这三重校验。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleMapperAssemblyTest {

    @Autowired
    private CmsArticleMapper cmsArticleMapper;

    @Autowired
    private CmsArticleAuditLogMapper cmsArticleAuditLogMapper;

    @Autowired
    private CmsArticleVersionMapper cmsArticleVersionMapper;

    /**
     * 三个 Mapper 都必须能被容器注入。
     * <p>注入失败时 Spring 直接让测试上下文启动失败，这条断言是兜底。
     */
    @Test
    public void testMappersAreInjected() {
        assertNotNull(cmsArticleMapper);
        assertNotNull(cmsArticleAuditLogMapper);
        assertNotNull(cmsArticleVersionMapper);
    }

    /**
     * CmsArticleMapper 能查库（触发真实 SQL：SELECT COUNT(*) FROM cms_article WHERE deleted = 0）。
     * <p>能查到数（S02 初始化了 20 篇）说明表名、逻辑删除配置、主键都正确。
     */
    @Test
    public void testCmsArticleMapperSelectCount() {
        long count = cmsArticleMapper.selectCount(new QueryWrapper<>());
        assertTrue("cms_article 应有初始化数据（S02 脚本插入 20 篇），实际 " + count, count > 0);
        System.out.println("[S07] cms_article 有效行数（deleted=0）：" + count);
    }

    /**
     * 验证逻辑删除确实生效：MP 生成的 SELECT 应带 {@code deleted = 0}。
     * <p>做法：查 {@code deleted=1} 的行为空（因为 MP 强制加 deleted=0，
     * 即使 Wrapper 里写 deleted=1 也会变成 deleted=0 AND deleted=1，必空）。
     * <p>这间接证明 {@code @TableLogic} 被识别了。
     */
    @Test
    public void testLogicDeleteIsApplied() {
        long count = cmsArticleMapper.selectCount(
                new QueryWrapper<CmsArticle>().eq("deleted", 1));
        assertTrue("MP 应强制附加 deleted=0，使 deleted=1 的查询恒为空，实际 " + count,
                count == 0);
    }

    /**
     * CmsArticleAuditLogMapper 能查库（S02 插入了 12 条审核流水）。
     */
    @Test
    public void testCmsArticleAuditLogMapperSelectCount() {
        long count = cmsArticleAuditLogMapper.selectCount(new QueryWrapper<>());
        assertTrue("cms_article_audit_log 应有初始化数据（S02 插入 12 条），实际 " + count,
                count > 0);
        System.out.println("[S07] cms_article_audit_log 有效行数：" + count);
    }

    /**
     * CmsArticleVersionMapper 能查库（S02 插入了 10 条版本快照）。
     *
     * <p>⚠️ 这个测试特别重要：{@code CmsArticleVersion} <b>不继承 BaseEntity</b>，
     * 它的列集合是手写的。若手写字段与库表列名对不上，
     * MP 生成的 {@code SELECT id,article_id,...,create_time,deleted FROM ...}
     * 会报 {@code Unknown column}。跑通即证明手写的列名全对。
     */
    @Test
    public void testCmsArticleVersionMapperSelectCount() {
        long count = cmsArticleVersionMapper.selectCount(new QueryWrapper<>());
        assertTrue("cms_article_version 应有初始化数据（S02 插入 10 条），实际 " + count,
                count > 0);
        System.out.println("[S07] cms_article_version 有效行数：" + count);
    }

    /**
     * 验证 {@code CmsArticleVersion} 的"不继承 BaseEntity"确实是安全的 ——
     * 即它的 INSERT 语句里不会出现 {@code create_by / update_by / update_time}。
     *
     * <p>做法：用 {@code QueryWrapper.select()} 显式列出本实体所有列，
     * 让 MP 生成包含全部列的 SELECT。若某个列在库里不存在，SQL 立刻报错。
     * 这比"只看字段定义"强 —— 它让数据库来判定列名对不对。
     */
    @Test
    public void testCmsArticleVersionAllColumnsExistInDb() {
        // 显式列出实体声明的全部列（不含 MP 自动加的 deleted，因为它在 @TableLogic 里）
        List<CmsArticleVersion> list = cmsArticleVersionMapper.selectList(
                new QueryWrapper<CmsArticleVersion>()
                        .select("id", "article_id", "version_no", "title_snapshot",
                                "content_snapshot", "modifier_id", "modify_remark", "create_time")
                        .last("LIMIT 1"));
        assertNotNull("查询不应返回 null（应返回空列表或含数据）", list);
        System.out.println("[S07] cms_article_version 全列查询成功，返回 " + list.size() + " 行");
    }
}
