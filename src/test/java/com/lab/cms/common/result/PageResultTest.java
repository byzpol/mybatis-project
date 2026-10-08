package com.lab.cms.common.result;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link PageResult} 单元测试。
 *
 * <p>重点是 pages 的计算与空值兜底 —— 前者错了前端分页控件会显示错误的页数，
 * 后者错了前端会 NPE。
 */
public class PageResultTest {

    // ==================== pages 计算 ====================

    /** 100 条 / 每页 10 → 10 页 */
    @Test
    public void testPagesExactDivision() {
        PageResult<String> r = new PageResult<>(Collections.emptyList(), 100L, 1L, 10L);
        assertEquals(Long.valueOf(10), r.getPages());
    }

    /** 101 条 / 每页 10 → 需要 11 页（向上取整） */
    @Test
    public void testPagesRoundUp() {
        PageResult<String> r = new PageResult<>(Collections.emptyList(), 101L, 1L, 10L);
        assertEquals("101 条应需要 11 页", Long.valueOf(11), r.getPages());
    }

    /** 1 条 / 每页 10 → 1 页 */
    @Test
    public void testPagesSingleRecord() {
        PageResult<String> r = new PageResult<>(Collections.emptyList(), 1L, 1L, 10L);
        assertEquals(Long.valueOf(1), r.getPages());
    }

    /** 0 条 → 0 页（不是 1 页） */
    @Test
    public void testPagesZeroTotal() {
        PageResult<String> r = new PageResult<>(Collections.emptyList(), 0L, 1L, 10L);
        assertEquals("0 条数据应为 0 页", Long.valueOf(0), r.getPages());
    }

    /** 整除边界：恰好是每页条数的整数倍 */
    @Test
    public void testPagesBoundary() {
        // 20/10 = 2
        assertEquals(Long.valueOf(2),
                new PageResult<>(null, 20L, 1L, 10L).getPages());
        // 21/10 → 3
        assertEquals(Long.valueOf(3),
                new PageResult<>(null, 21L, 1L, 10L).getPages());
        // 99/10 → 10
        assertEquals(Long.valueOf(10),
                new PageResult<>(null, 99L, 1L, 10L).getPages());
        // 100/10 → 10
        assertEquals(Long.valueOf(10),
                new PageResult<>(null, 100L, 1L, 10L).getPages());
    }

    /** 每页 1 条时：总数 = 页数 */
    @Test
    public void testPagesSizeOne() {
        assertEquals(Long.valueOf(7),
                new PageResult<>(null, 7L, 1L, 1L).getPages());
    }

    // ==================== 空值兜底 ====================

    /** records 为 null → 空集合（前端可无脑 forEach） */
    @Test
    public void testNullRecordsBecomesEmptyList() {
        PageResult<String> r = new PageResult<>(null, 50L, 1L, 10L);
        assertNotNull("records 不应为 null", r.getRecords());
        assertTrue("records 应为空集合", r.getRecords().isEmpty());
    }

    /** total 为 null → 0 */
    @Test
    public void testNullTotalBecomesZero() {
        PageResult<String> r = new PageResult<>(null, null, 1L, 10L);
        assertEquals(Long.valueOf(0), r.getTotal());
        assertEquals(Long.valueOf(0), r.getPages());
    }

    /** current 为 null → 1 */
    @Test
    public void testNullCurrentBecomesOne() {
        PageResult<String> r = new PageResult<>(null, 10L, null, 10L);
        assertEquals(Long.valueOf(1), r.getCurrent());
    }

    /** size 为 null 或 ≤0 → 10（默认值） */
    @Test
    public void testInvalidSizeBecomesDefault() {
        assertEquals(Long.valueOf(10),
                new PageResult<>(null, 10L, 1L, null).getSize());
        assertEquals(Long.valueOf(10),
                new PageResult<>(null, 10L, 1L, 0L).getSize());
        assertEquals(Long.valueOf(10),
                new PageResult<>(null, 10L, 1L, -5L).getSize());
    }

    /** size 非法时 pages 也应正确（用兜底后的 10 计算） */
    @Test
    public void testPagesWithInvalidSize() {
        PageResult<String> r = new PageResult<>(null, 25L, 1L, null);
        assertEquals(Long.valueOf(10), r.getSize());
        assertEquals("25 条 / 每页 10 → 3 页", Long.valueOf(3), r.getPages());
    }

    // ==================== empty() ====================

    @Test
    public void testEmptyFactory() {
        PageResult<String> r = PageResult.empty(2L, 20L);
        assertNotNull(r.getRecords());
        assertTrue(r.getRecords().isEmpty());
        assertEquals(Long.valueOf(0), r.getTotal());
        assertEquals(Long.valueOf(2), r.getCurrent());
        assertEquals(Long.valueOf(20), r.getSize());
        assertEquals(Long.valueOf(0), r.getPages());
    }

    // ==================== of(IPage) ====================

    /**
     * 用 MP 的 Page 对象测试 of() 转换。
     * <p>用真实的 {@code com.baomidou.mybatisplus.extension.plugins.pagination.Page}
     * 而不是 mock，确保字段映射与 MP 实际行为一致。
     */
    @Test
    public void testOfFromMpPage() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<String> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2L, 5L);
        List<String> records = Arrays.asList("r1", "r2", "r3");
        mpPage.setRecords(records);
        mpPage.setTotal(13L);

        PageResult<String> r = PageResult.of(mpPage);
        assertEquals(3, r.getRecords().size());
        assertEquals(Long.valueOf(13), r.getTotal());
        assertEquals(Long.valueOf(2), r.getCurrent());
        assertEquals(Long.valueOf(5), r.getSize());
        assertEquals("13 条 / 每页 5 → 3 页", Long.valueOf(3), r.getPages());
    }

    /** of(null) 不应抛异常 */
    @Test
    public void testOfNullPage() {
        PageResult<String> r = PageResult.of(null);
        assertNotNull(r.getRecords());
        assertTrue(r.getRecords().isEmpty());
        assertEquals(Long.valueOf(0), r.getTotal());
    }

    // ==================== 无参构造 ====================

    /** 无参构造（Jackson 反序列化用）应能正常创建对象 */
    @Test
    public void testNoArgConstructor() {
        PageResult<String> r = new PageResult<>();
        assertNotNull(r);
        // 各字段为 null（未设置），这是可接受的（Jackson 反序列化后会 setter 填充）
        assertEquals(null, r.getRecords());
    }
}
