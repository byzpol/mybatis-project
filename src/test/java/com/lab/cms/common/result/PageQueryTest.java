package com.lab.cms.common.result;

import com.lab.cms.common.exception.BusinessException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link PageQuery} 单元测试。
 *
 * <p>三个重点：
 * <ol>
 *   <li>{@code normalize()} 的防拖库能力 —— "前端传 size=100000 把数据库拖死"的唯一防线</li>
 *   <li>{@code checkSortField()} 的防注入能力 —— 拒绝 {@code "create_time; drop table"} 这类输入</li>
 *   <li>{@code toMpPage()} 是否正确传递分页与排序，且拒绝非法输入</li>
 * </ol>
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

    // ==================== sortField 校验（S06 新增） ====================

    /**
     * ⚠️ normalize() **故意不校验** sortField —— 它只管数值归一化。
     *
     * <p>校验被拆成独立方法 {@code checkSortField()}，由 {@code toMpPage()} 自动调用。
     * 这样拆的理由：normalize() 是"修正成合法值"（静默降级），
     * 而 sortField 非法应当**明确报错**（否则前端以为排序生效了）。
     * 两种语义混在一个方法里会让调用方困惑"到底会不会抛异常"。
     */
    @Test
    public void testNormalizeDoesNotTouchSortField() {
        PageQuery q = new PageQuery();
        q.setSortField("anything; DROP TABLE x--");
        q.normalize();
        assertEquals("normalize() 不应校验 sortField（校验在 checkSortField）",
                "anything; DROP TABLE x--", q.getSortField());
    }

    /**
     * ⚠️ 核心防注入测试：非法 sortField 必须被拒绝。
     *
     * <p>这是手册核查清单里明确要求的一条：
     * {@code sortField="create_time; drop table"} 必须被拒。
     */
    @Test
    public void testCheckSortFieldRejectsInjection() {
        // 冒号 + 空格 + 分号 + 关键字 —— 典型的注入尝试
        assertRejected("create_time; drop table cms_article");
        // 注释符截断
        assertRejected("id--");
        // 带括号的函数调用
        assertRejected("count(id)");
        // 空格分隔多列
        assertRejected("id, username");
        // 单引号
        assertRejected("id'");
        // 反引号
        assertRejected("`id`");
        // 首位是数字（可构造 "1=1" 这类表达式）
        assertRejected("1=1");
        // union 注入
        assertRejected("id union select 1");
        // 空字节（NUL 截断攻击）—— 这类控制字符不能靠 trim() 洗掉
        assertRejected("id\u0000");
    }

    /**
     * ⚠️ 控制字符必须被拒绝，即使它夹在合法标识符中间/末尾。
     *
     * <p>这条测试锁死一个具体的坑：{@code String.trim()} 会去掉所有
     * 码点 &lt;= U+0020 的字符，包括 NUL(0x00) 和 TAB(0x09)。
     * 如果用 trim 处理输入，{@code "id\0"} 会被洗成 {@code "id"} 从而通过校验，
     * 而原始串在某些 MySQL 配置下是"另一个标识符"—— NUL 截断绕过的经典手法。
     *
     * <p>实现上因此改成只剔除两端普通空格（0x20），控制字符原样保留给正则拒绝。
     * 若将来有人"优化"成 trim()，这条测试会红。
     */
    @Test
    public void testRejectsControlCharactersRegardlessOfPosition() {
        assertRejected("id\u0000");      // NUL 在末尾
        assertRejected("i\u0000d");      // NUL 在中间
        assertRejected("\u0000id");      // NUL 在开头
        assertRejected("id\t");          // TAB（trim 也会吃掉它）
        assertRejected("id\n");          // 换行
        assertRejected("id\r");          // 回车
        assertRejected("id\u007F");      // DEL
    }

    /** 合法 sortField 应当通过校验，且前后空白被 trim。 */
    @Test
    public void testCheckSortFieldAcceptsLegal() {
        assertAccepted("id");
        assertAccepted("create_time");
        assertAccepted("publish_time");
        assertAccepted("_private");
        assertAccepted("col1");

        // 前后空白应被 trim 掉
        PageQuery q = new PageQuery();
        q.setSortField("  create_time  ");
        q.checkSortField();
        assertEquals("应 trim 前后空白", "create_time", q.getSortField());
    }

    /** 不传 sortField 时校验应直接放行（不算错误）。 */
    @Test
    public void testCheckSortFieldAllowsBlank() {
        PageQuery q = new PageQuery();
        q.setSortField(null);
        q.checkSortField();   // 不应抛异常

        q.setSortField("   ");
        q.checkSortField();   // 不应抛异常
    }

    /** 超长标识符应被拒（MySQL 列名上限 64，超过必然不是合法列名）。 */
    @Test
    public void testCheckSortFieldRejectsOverlong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            sb.append('a');
        }
        assertRejected(sb.toString());
    }

    /** checkSortFieldIn：白名单外的列名应被拒，且报错信息里带上可选值。 */
    @Test
    public void testCheckSortFieldIn() {
        PageQuery q = new PageQuery();
        q.setSortField("view_count");
        // 白名单里没有 view_count → 应拒绝
        try {
            q.checkSortFieldIn("create_time", "publish_time");
            fail("白名单外的字段应被拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(400), e.getCode());
            assertTrue("报错信息应包含可选字段",
                    e.getMessage().contains("create_time"));
        }

        // 白名单内有 → 通过
        PageQuery q2 = new PageQuery();
        q2.setSortField("publish_time");
        q2.checkSortFieldIn("create_time", "publish_time");  // 不应抛异常
    }

    // ==================== toMpPage（S06 新增） ====================

    /** toMpPage 应正确传递 page/size。 */
    @Test
    public void testToMpPageBasics() {
        PageQuery q = new PageQuery();
        q.setPage(3);
        q.setSize(20);
        q.normalize();

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp = q.toMpPage();
        assertEquals("页码应传递", 3L, mp.getCurrent());
        assertEquals("每页条数应传递", 20L, mp.getSize());
    }

    /** normalize 后 size 超限时，toMpPage 拿到的应是截断后的值。 */
    @Test
    public void testToMpPageUsesNormalizedSize() {
        PageQuery q = new PageQuery();
        q.setSize(9999);
        q.normalize();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp = q.toMpPage();
        assertEquals("应为截断后的 100", 100L, mp.getSize());
    }

    /** 负值兜底：即使调用方忘了 normalize，toMpPage 也不应产生非法分页。 */
    @Test
    public void testToMpPageDefensiveOnNegative() {
        PageQuery q = new PageQuery();
        q.setPage(-5);
        q.setSize(-1);
        // 刻意不调 normalize()，验证防御式兜底
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp = q.toMpPage();
        assertEquals("负页码应兜底为 1", 1L, mp.getCurrent());
        assertEquals("负条数应兜底为默认 10", 10L, mp.getSize());
    }

    /** sortField 为空时不应产生 ORDER BY。 */
    @Test
    public void testToMpPageNoOrderWhenNoSortField() {
        PageQuery q = new PageQuery();
        q.setSortField(null);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp = q.toMpPage();
        assertTrue("无 sortField 时 orders 应为空", mp.orders().isEmpty());
    }

    /** sortField 有值时，应按方向生成对应的 OrderItem。 */
    @Test
    public void testToMpPageWithOrder() {
        PageQuery q = new PageQuery();
        q.setSortField("create_time");
        q.setSortOrder("asc");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp = q.toMpPage();

        assertEquals("应生成一个排序项", 1, mp.orders().size());
        com.baomidou.mybatisplus.core.metadata.OrderItem item = mp.orders().get(0);
        assertEquals("列名应传递", "create_time", item.getColumn());
        assertTrue("应为升序", item.isAsc());

        // desc 方向
        PageQuery q2 = new PageQuery();
        q2.setSortField("publish_time");
        q2.setSortOrder("desc");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp2 = q2.toMpPage();
        assertFalse("应为降序", mp2.orders().get(0).isAsc());
    }

    /** ⚠️ toMpPage 遇到非法 sortField 必须抛异常，不能让它流到 SQL。 */
    @Test
    public void testToMpPageRejectsInjection() {
        PageQuery q = new PageQuery();
        q.setSortField("create_time; drop table cms_article");
        try {
            q.toMpPage();
            fail("非法 sortField 应被 toMpPage 拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(400), e.getCode());
        }
    }

    /** toMpPage(allowedFields) 应做精确白名单校验。 */
    @Test
    public void testToMpPageWithWhitelist() {
        PageQuery q = new PageQuery();
        q.setSortField("secret_column");
        try {
            q.toMpPage("create_time", "publish_time");
            fail("白名单外字段应被拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(400), e.getCode());
        }

        PageQuery q2 = new PageQuery();
        q2.setSortField("publish_time");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Object> mp =
                q2.toMpPage("create_time", "publish_time");
        assertEquals("publish_time", mp.orders().get(0).getColumn());
    }

    // ==================== 断言辅助 ====================

    /** 断言 sortField 被拒绝（抛 400 业务异常）。 */
    private void assertRejected(String field) {
        PageQuery q = new PageQuery();
        q.setSortField(field);
        try {
            q.checkSortField();
            fail("应拒绝非法排序字段：" + field);
        } catch (BusinessException e) {
            assertEquals("错误码应为 400（参数错误）", Integer.valueOf(400), e.getCode());
            assertTrue("报错信息不应为空", e.getMessage() != null && !e.getMessage().isEmpty());
            // 报错信息必须是单行 —— 含换行的非法输入不能把日志撑成多行
            assertFalse("报错信息不应包含换行",
                    e.getMessage().contains("\n") || e.getMessage().contains("\r"));
        }
    }

    /** 断言 sortField 被接受。 */
    private void assertAccepted(String field) {
        PageQuery q = new PageQuery();
        q.setSortField(field);
        try {
            q.checkSortField();
        } catch (BusinessException e) {
            fail("不应拒绝合法排序字段：" + field + "，原因：" + e.getMessage());
        }
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
