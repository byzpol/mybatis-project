package com.lab.cms.common.result;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link PageQuery} 单元测试。
 *
 * <p>重点是 {@code normalize()} 的防注入与防拖库能力 ——
 * 这是"前端传 size=100000 把数据库拖死"的唯一防线。
 */
public class PageQueryTest {

    // ==================== 默认值 ====================

    @Test
    public void testDefaults() {
        PageQuery q = new PageQuery();
        assertEquals(Integer.valueOf(1), q.getPage());
        assertEquals(Integer.valueOf(10), q.getSize());
        assertEquals("desc", q.getSortOrder());
        assertEquals(null, q.getSortField());
    }

    // ==================== page 归一化 ====================

    @Test
    public void testPageNormalization() {
        PageQuery q = new PageQuery();

        q.setPage(null);
        q.normalize();
        assertEquals("null page → 1", Integer.valueOf(1), q.getPage());

        q.setPage(0);
        q.normalize();
        assertEquals("0 page → 1", Integer.valueOf(1), q.getPage());

        q.setPage(-99);
        q.normalize();
        assertEquals("负数 page → 1", Integer.valueOf(1), q.getPage());

        q.setPage(5);
        q.normalize();
        assertEquals("合法 page 保持不变", Integer.valueOf(5), q.getPage());
    }

    // ==================== size 归一化（核心防线） ====================

    /**
     * ⚠️ 最关键的一条：size 超上限必须被截断到 100。
     * <p>若不截断，前端一个 {@code ?size=999999} 就能把数据库拖死。
     */
    @Test
    public void testSizeMaxLimit() {
        PageQuery q = new PageQuery();

        q.setSize(100000);
        q.normalize();
        assertEquals("超过上限应截断到 100",
                Integer.valueOf(PageQuery.MAX_SIZE), q.getSize());

        q.setSize(Integer.MAX_VALUE);
        q.normalize();
        assertEquals(Integer.valueOf(100), q.getSize());
    }

    @Test
    public void testSizeNormalization() {
        PageQuery q = new PageQuery();

        q.setSize(null);
        q.normalize();
        assertEquals("null size → 默认 10", Integer.valueOf(10), q.getSize());

        q.setSize(0);
        q.normalize();
        assertEquals("0 size → 默认 10", Integer.valueOf(10), q.getSize());

        q.setSize(-1);
        q.normalize();
        assertEquals("负数 size → 默认 10", Integer.valueOf(10), q.getSize());

        q.setSize(100);
        q.normalize();
        assertEquals("恰好上限应保留", Integer.valueOf(100), q.getSize());

        q.setSize(50);
        q.normalize();
        assertEquals("合法值保持不变", Integer.valueOf(50), q.getSize());
    }

    // ==================== sortOrder 归一化 ====================

    /**
     * ⚠️ sortOrder 的归一化是防 SQL 注入的一环。
     * <p>因为 sortOrder 会被拼进 {@code ORDER BY xxx ${sortOrder}}，
     * 若前端传 {@code "asc; DROP TABLE cms_article--"} 就会很危险。
     */
    @Test
    public void testSortOrderNormalization() {
        PageQuery q = new PageQuery();

        q.setSortOrder(null);
        q.normalize();
        assertEquals("null → desc", "desc", q.getSortOrder());

        q.setSortOrder("ASC");
        q.normalize();
        assertEquals("大写应统一成小写", "asc", q.getSortOrder());

        q.setSortOrder("DESC");
        q.normalize();
        assertEquals("desc", q.getSortOrder());

        q.setSortOrder("asc");
        q.normalize();
        assertEquals("asc", q.getSortOrder());

        // ⚠️ 关键：非法值必须被拒，不能拼进 SQL
        q.setSortOrder("asc; DROP TABLE cms_article--");
        q.normalize();
        assertEquals("非法 sortOrder 应回退到 desc", "desc", q.getSortOrder());

        q.setSortOrder("random");
        q.normalize();
        assertEquals("未知方向应回退到 desc", "desc", q.getSortOrder());

        q.setSortOrder("");
        q.normalize();
        assertEquals("空串应回退到 desc", "desc", q.getSortOrder());
    }

    // ==================== sortField 不校验（需各域白名单） ====================

    /**
     * ⚠️ 本基类**故意不校验** sortField —— 因为合法字段是业务相关的。
     * <p>这条测试是为了"把设计意图固化成可执行的文档"：
     * 若将来有人以为基类会校验而依赖它，这个测试会失败，提醒他去看类注释。
     */
    @Test
    public void testSortFieldIsNotValidatedByNormalize() {
        PageQuery q = new PageQuery();
        q.setSortField("anything; DROP TABLE x--");
        q.normalize();
        assertEquals("基类不应校验 sortField（须各域白名单）",
                "anything; DROP TABLE x--", q.getSortField());
    }

    @Test
    public void testHasSortField() {
        PageQuery q = new PageQuery();
        assertFalse("null 时应为 false", q.hasSortField());

        q.setSortField("");
        assertFalse("空串应为 false", q.hasSortField());

        q.setSortField("   ");
        assertFalse("纯空白应为 false", q.hasSortField());

        q.setSortField("createTime");
        assertTrue("有值应为 true", q.hasSortField());
    }

    // ==================== 多次 normalize 幂等 ====================

    /**
     * normalize() 应幂等：调用两次结果与调用一次相同。
     * <p>业务代码可能因为重构而在两处都调用（如 Controller 一次、Service 一次），
     * 若非幂等会导致行为不一致。
     */
    @Test
    public void testNormalizeIsIdempotent() {
        PageQuery q = new PageQuery();
        q.setPage(-5);
        q.setSize(99999);
        q.setSortOrder("INVALID");

        q.normalize();
        Integer page1 = q.getPage();
        Integer size1 = q.getSize();
        String order1 = q.getSortOrder();

        q.normalize();

        assertEquals("page 应幂等", page1, q.getPage());
        assertEquals("size 应幂等", size1, q.getSize());
        assertEquals("sortOrder 应幂等", order1, q.getSortOrder());
    }

    /** 继承类应能正常使用（模拟 ArticleQueryDTO extends PageQuery） */
    @Test
    public void testInheritance() {
        class MockQuery extends PageQuery {
            private String keyword;

            public String getKeyword() {
                return keyword;
            }

            public void setKeyword(String keyword) {
                this.keyword = keyword;
            }
        }

        MockQuery q = new MockQuery();
        q.setKeyword("Spring");
        q.setSize(9999);
        q.normalize();

        assertEquals("Spring", q.getKeyword());
        assertEquals("继承类也能享受 normalize 保护",
                Integer.valueOf(100), q.getSize());
    }
}
