package com.lab.cms.common.result;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link Result} 单元测试。
 *
 * <p>统一响应体是全项目"契约主干"的基石 —— 它错了，前端所有接口的处理逻辑都要改。
 */
public class ResultTest {

    // ==================== 成功 ====================

    @Test
    public void testSuccessWithoutData() {
        Result<Void> result = Result.success();
        assertEquals(Integer.valueOf(200), result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertNull("无数据时应为 null", result.getData());
        assertNotNull("timestamp 不应为空", result.getTimestamp());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testSuccessWithData() {
        String data = "hello";
        Result<String> result = Result.success(data);
        assertEquals(Integer.valueOf(200), result.getCode());
        assertSame("data 应是同一个对象引用", data, result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testSuccessWithCustomMessage() {
        Result<Integer> result = Result.success("已提交审核", 42);
        assertEquals(Integer.valueOf(200), result.getCode());
        assertEquals("已提交审核", result.getMessage());
        assertEquals(Integer.valueOf(42), result.getData());
    }

    // ==================== 失败 ====================

    @Test
    public void testFailWithErrorCode() {
        Result<Void> result = Result.fail(
                com.lab.cms.common.exception.ErrorCode.ARTICLE_NOT_FOUND);
        assertEquals(Integer.valueOf(11001), result.getCode());
        assertEquals("文章不存在", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    public void testFailWithErrorCodeAndCustomMessage() {
        Result<Void> result = Result.fail(
                com.lab.cms.common.exception.ErrorCode.ARTICLE_STATUS_ILLEGAL,
                "文章当前状态为「已发布」，不允许提交审核");
        assertEquals("错误码应来自枚举", Integer.valueOf(11002), result.getCode());
        assertEquals("提示信息应使用自定义的",
                "文章当前状态为「已发布」，不允许提交审核", result.getMessage());
    }

    @Test
    public void testFailWithRawCode() {
        Result<Void> result = Result.fail(404, "请求的资源不存在");
        assertEquals(Integer.valueOf(404), result.getCode());
        assertEquals("请求的资源不存在", result.getMessage());
        assertFalse(result.isSuccess());
    }

    // ==================== 边界 ====================

    /** 每个 Result 都应带独立的时间戳（不是共享的静态值） */
    @Test
    public void testTimestampIsPerInstance() {
        Result<Void> r1 = Result.success();
        Result<Void> r2 = Result.success();
        assertNotNull(r1.getTimestamp());
        assertNotNull(r2.getTimestamp());
        // 两次调用时间戳应递增或相等（不应倒退）
        assertTrue("时间戳应单调不减", r2.getTimestamp() >= r1.getTimestamp());
    }

    /** data 可以是任意类型，包括集合与 null 元素 */
    @Test
    public void testDataCanBeCollection() {
        List<String> list = Arrays.asList("a", "b", "c");
        Result<List<String>> result = Result.success(list);
        assertEquals(3, result.getData().size());
        assertEquals("b", result.getData().get(1));

        Result<List<String>> empty = Result.success(Collections.emptyList());
        assertNotNull(empty.getData());
        assertTrue(empty.getData().isEmpty());
    }

    /** isSuccess() 只认 200，其他码都算失败 */
    @Test
    public void testIsSuccessOnlyFor200() {
        assertTrue(Result.success().isSuccess());
        assertFalse(Result.fail(199, "x").isSuccess());
        assertFalse(Result.fail(201, "x").isSuccess());
        assertFalse(Result.fail(500, "x").isSuccess());
        assertFalse(Result.fail(11001, "x").isSuccess());
    }

    /** 失败的结果 data 必须为 null（不给前端留"失败也带数据"的歧义） */
    @Test
    public void testFailDataIsAlwaysNull() {
        assertNull(Result.fail(
                com.lab.cms.common.exception.ErrorCode.SYSTEM_ERROR).getData());
        assertNull(Result.fail(400, "bad").getData());
        assertNull(Result.fail(
                com.lab.cms.common.exception.ErrorCode.PARAM_ERROR, "自定义").getData());
    }
}
