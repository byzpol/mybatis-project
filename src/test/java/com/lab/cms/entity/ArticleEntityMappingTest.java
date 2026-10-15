package com.lab.cms.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 文章域三个实体的字段映射测试（S07 新增）。
 *
 * <h3>为什么这个测试比"肉眼看代码"可靠</h3>
 * <p>MP 的列名推导<b>不直接看字段名，而是看 getter/setter 名</b>。
 * 本项目的实体用 Lombok {@code @Data} 生成 getter，
 * 而 Lombok 对 {@code isXxx} 前缀有特殊处理规则 ——
 * 所以"字段叫 isTop，列名是不是 is_top"这件事<b>读代码看不出来</b>，
 * 必须让 MP 自己算一遍才知道。
 *
 * <p>{@code is_top} 这种列名一旦推错，症状是运行时
 * {@code Unknown column 'top' in 'field list'}，且只有真正查询时才发现。
 * 本测试在编译期之后、部署之前就把这个风险消掉。
 *
 * <h3>为什么从容器里拿 TableInfo 而不是自己 new</h3>
 * <p>MP 3.5.5 的 {@code TableInfoHelper} 需要一个装配好的
 * {@code MybatisConfiguration}（含 {@code GlobalConfig}，里面才是
 * 逻辑删除值、主键策略等）。用真实容器（{@code SpringConfig}）
 * 拿到的 {@code TableInfo} 与生产运行时<b>完全同一份</b>，
 * 自己 new 一个配置可能缺配置项、得出与生产不一致的结论 —— 那就白测了。
 *
 * <h3>期望值从哪来</h3>
 * <p>列名与 {@code sql/init.sql} 的建表语句逐列对照，
 * 并用 {@code information_schema.COLUMNS} 实查确认过
 * （见 docs/S07-文章模块骨架实测记录.md 的对账表）。
 * <b>不是从实体代码里抄的</b> —— 从代码抄期望值等于自己考自己。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ArticleEntityMappingTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    /** 取出 MP 对某实体解析出的列名集合（含主键、逻辑删除列） */
    private List<String> columnsOf(Class<?> entityClass) {
        TableInfo info = tableInfoOf(entityClass);

        // ⚠️ 用 LinkedHashSet 去重：逻辑删除列 deleted 既在 getFieldList() 里，
        //    又被 getLogicDeleteFieldInfo() 单独返回一次（实测发现）。
        //    若直接往 List 里加会出现两遍 deleted，导致列数多 1 —— 是测试代码的坑，不是实体的问题。
        java.util.Set<String> columns = new java.util.LinkedHashSet<>();
        columns.add(info.getKeyColumn());
        info.getFieldList().forEach(f -> columns.add(f.getColumn()));
        if (info.getLogicDeleteFieldInfo() != null) {
            columns.add(info.getLogicDeleteFieldInfo().getColumn());
        }
        return new ArrayList<>(columns);
    }

    private TableInfo tableInfoOf(Class<?> entityClass) {
        // ⚠️ MP 3.5.5 的 TableInfoHelper 只有 getTableInfo(Class) 与 getTableInfo(String)
        //    两个重载，**没有** getTableInfo(Configuration, Class)（实测 javap 确认）。
        //    这里用单参数版：容器已由 SpringConfig 启动，实体也已被 mapper 扫描注册，
        //    TableInfoHelper 内部维护的缓存里能直接取到。
        TableInfo info = TableInfoHelper.getTableInfo(entityClass);
        assertNotNull("MP 未能解析实体（检查 @TableName / 是否被 mapper 扫描到）："
                + entityClass.getName(), info);
        return info;
    }

    // ==================== 一、CmsArticle ====================

    /**
     * {@code cms_article} 的列集合必须与实际库表一致（19 列）。
     * <p>期望值来源：information_schema 实查结果。
     */
    @Test
    public void testCmsArticleColumns() {
        String[] expected = {
                "id", "title", "summary", "cover_url", "content", "category_id", "status",
                "author_id", "publisher_id", "publish_time", "schedule_time", "is_top",
                "view_count", "version",
                "create_by", "create_time", "update_by", "update_time", "deleted"
        };
        List<String> actual = columnsOf(CmsArticle.class);

        assertEquals("@TableName 应为 cms_article",
                "cms_article", tableInfoOf(CmsArticle.class).getTableName());

        List<String> missing = new ArrayList<>();
        for (String c : expected) {
            if (!actual.contains(c)) {
                missing.add(c);
            }
        }
        assertTrue("实体缺少库表已有的列（运行时会 Unknown column）：" + missing, missing.isEmpty());

        List<String> extra = new ArrayList<>();
        for (String c : actual) {
            if (!Arrays.asList(expected).contains(c)) {
                extra.add(c);
            }
        }
        assertTrue("实体多出库表没有的列（运行时会 Unknown column）：" + extra, extra.isEmpty());

        assertEquals("列数应为 19，实际列：" + actual, 19, actual.size());
    }

    /** 打印三张表 MP 实际解析出的列名（辅助排查用，同时是断言可读性的兜底） */
    @Test
    public void testPrintResolvedColumns() {
        System.out.println("[S07] cms_article            -> " + columnsOf(CmsArticle.class));
        System.out.println("[S07] cms_article_audit_log  -> " + columnsOf(CmsArticleAuditLog.class));
        System.out.println("[S07] cms_article_version    -> " + columnsOf(CmsArticleVersion.class));
    }

    /** is_top 必须被 MP 识别为 is_top，而不是 top —— 这是本类最要紧的一条断言 */
    @Test
    public void testIsTopColumnMapping() {
        List<String> columns = columnsOf(CmsArticle.class);
        assertTrue("isTop 字段必须映射为列名 is_top", columns.contains("is_top"));
        assertFalse("isTop 不应被错误推导为 top", columns.contains("top"));
        assertFalse("isTop 不应被错误推导为 isTop", columns.contains("isTop"));
    }

    /** version 列必须被识别为乐观锁字段 */
    @Test
    public void testVersionIsOptimisticLock() {
        TableInfo info = tableInfoOf(CmsArticle.class);
        assertNotNull("@Version 未被 MP 识别，乐观锁会静默失效", info.getVersionFieldInfo());
        assertEquals("乐观锁列名应为 version", "version", info.getVersionFieldInfo().getColumn());
    }

    /** 逻辑删除列与取值必须正确（当初实验一的坑：默认值语义反转） */
    @Test
    public void testLogicDeleteConfig() {
        TableInfo info = tableInfoOf(CmsArticle.class);
        assertNotNull("@TableLogic 未被 MP 识别", info.getLogicDeleteFieldInfo());
        assertEquals("逻辑删除列应为 deleted", "deleted", info.getLogicDeleteFieldInfo().getColumn());
        assertEquals("未删除值应为 0", "0", info.getLogicDeleteFieldInfo().getLogicNotDeleteValue());
        assertEquals("已删除值应为 1", "1", info.getLogicDeleteFieldInfo().getLogicDeleteValue());
    }

    /** 主键必须用数据库自增（不是雪花 ID） */
    @Test
    public void testIdTypeIsAuto() {
        TableInfo info = tableInfoOf(CmsArticle.class);
        assertEquals("主键列应为 id", "id", info.getKeyColumn());
        assertEquals("主键策略应为 AUTO（数据库自增）",
                com.baomidou.mybatisplus.annotation.IdType.AUTO, info.getIdType());
    }

    // ==================== 二、CmsArticleAuditLog ====================

    @Test
    public void testCmsArticleAuditLogColumns() {
        String[] expected = {
                "id", "article_id", "audit_stage", "auditor_id", "result", "opinion",
                "audit_time", "article_version",
                "create_by", "create_time", "update_by", "update_time", "deleted"
        };
        List<String> actual = columnsOf(CmsArticleAuditLog.class);
        assertEquals("@TableName 应为 cms_article_audit_log",
                "cms_article_audit_log", tableInfoOf(CmsArticleAuditLog.class).getTableName());

        for (String c : expected) {
            assertTrue("缺少列：" + c, actual.contains(c));
        }
        assertEquals("列数应为 13", 13, actual.size());
        // 这张表带齐了审计四字段，所以应该继承 BaseEntity
        assertTrue("审核流水表应有 update_by（故继承 BaseEntity）", actual.contains("update_by"));
        assertTrue("审核流水表应有 update_time", actual.contains("update_time"));
    }

    // ==================== 三、CmsArticleVersion ====================

    @Test
    public void testCmsArticleVersionColumns() {
        String[] expected = {
                "id", "article_id", "version_no", "title_snapshot", "content_snapshot",
                "modifier_id", "modify_remark", "create_time", "deleted"
        };
        List<String> actual = columnsOf(CmsArticleVersion.class);
        assertEquals("@TableName 应为 cms_article_version",
                "cms_article_version", tableInfoOf(CmsArticleVersion.class).getTableName());

        for (String c : expected) {
            assertTrue("缺少列：" + c, actual.contains(c));
        }
        assertEquals("列数应为 9", 9, actual.size());
    }

    /**
     * ⭐ 本测试的核心断言：{@code CmsArticleVersion} <b>不能</b>有
     * {@code update_by / update_time / create_by} 这三个列。
     *
     * <p>这正是它不继承 {@code BaseEntity} 的原因 ——
     * 库表根本没有这三列（它是只增不改的快照表）。
     * 若继承 {@code BaseEntity}，MP 会把这三列拼进 INSERT/SELECT，
     * 数据库直接报 {@code Unknown column}。
     *
     * <p>把"不该有什么"也写成断言，是为了让"哪天有人好心把它改成继承
     * BaseEntity"这种改动<b>立刻被测试拦下</b>。
     */
    @Test
    public void testCmsArticleVersionDoesNotInheritBaseEntity() {
        assertFalse("CmsArticleVersion 不应继承 BaseEntity（库表无审计更新字段）",
                com.lab.cms.base.BaseEntity.class.isAssignableFrom(CmsArticleVersion.class));

        List<String> actual = columnsOf(CmsArticleVersion.class);
        assertFalse("版本快照表不应有 update_by 列", actual.contains("update_by"));
        assertFalse("版本快照表不应有 update_time 列", actual.contains("update_time"));
        assertFalse("版本快照表不应有 create_by 列", actual.contains("create_by"));
    }

    /** 对照：另两个实体都应该继承 BaseEntity */
    @Test
    public void testOtherTwoEntitiesInheritBaseEntity() {
        assertTrue("CmsArticle 应继承 BaseEntity",
                com.lab.cms.base.BaseEntity.class.isAssignableFrom(CmsArticle.class));
        assertTrue("CmsArticleAuditLog 应继承 BaseEntity",
                com.lab.cms.base.BaseEntity.class.isAssignableFrom(CmsArticleAuditLog.class));
    }
}
