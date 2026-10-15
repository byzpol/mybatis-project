package com.lab.cms.common.enums;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 审核环节 / 审核结论枚举单元测试（S07 新增）。
 *
 * <h3>为什么这两个只有 2 个值的枚举也值得写测试</h3>
 * <p>因为它们的 code 必须与数据库严格一致（{@code 1/2}），
 * 而"数据库注释里写 1=通过 2=驳回"这件事<b>编译器管不了</b>。
 * 一旦有人在枚举里把两个值的 code 写反，症状是"审核通过的文章全被驳回"，
 * 并且只有真跑一遍审核流程才能发现。
 * 用测试把 code 钉死，成本 5 行，收益是整个审核域的语义基线。
 *
 * <p>另一个测试重点是"编码不存在时不抛异常"——
 * 这是 S07 明确的设计要求（历史脏数据不应让列表接口 500）。
 */
public class AuditEnumTest {

    // ==================== 一、code 与数据库一致 ====================

    /** 核对 audit_stage 的 code（库表注释：1=编辑初审 2=主编终审） */
    @Test
    public void testAuditStageCodes() {
        assertEquals("编辑初审编码应为 1",
                Integer.valueOf(1), AuditStageEnum.EDITOR_FIRST.getCode());
        assertEquals("主编终审编码应为 2",
                Integer.valueOf(2), AuditStageEnum.CHIEF_FINAL.getCode());
        assertEquals("审核环节总数应为 2", 2, AuditStageEnum.values().length);
    }

    /** 核对 audit_log.result 的 code（库表注释：1=通过 2=驳回） */
    @Test
    public void testAuditResultCodes() {
        assertEquals("通过编码应为 1",
                Integer.valueOf(1), AuditResultEnum.PASS.getCode());
        assertEquals("驳回编码应为 2",
                Integer.valueOf(2), AuditResultEnum.REJECT.getCode());
        assertEquals("审核结论总数应为 2", 2, AuditResultEnum.values().length);
    }

    // ==================== 二、反查与描述 ====================

    @Test
    public void testAuditStageLookup() {
        assertEquals(AuditStageEnum.EDITOR_FIRST, AuditStageEnum.ofNullable(1));
        assertEquals(AuditStageEnum.CHIEF_FINAL, AuditStageEnum.ofNullable(2));

        assertEquals("编辑初审", AuditStageEnum.getDesc(1));
        assertEquals("主编终审", AuditStageEnum.getDesc(2));

        assertTrue(AuditStageEnum.isValid(1));
        assertTrue(AuditStageEnum.isValid(2));
        assertFalse("0 不是合法环节码", AuditStageEnum.isValid(0));
        assertFalse("3 不是合法环节码", AuditStageEnum.isValid(3));
    }

    @Test
    public void testAuditResultLookup() {
        assertEquals(AuditResultEnum.PASS, AuditResultEnum.ofNullable(1));
        assertEquals(AuditResultEnum.REJECT, AuditResultEnum.ofNullable(2));

        assertEquals("通过", AuditResultEnum.getDesc(1));
        assertEquals("驳回", AuditResultEnum.getDesc(2));

        assertTrue(AuditResultEnum.isValid(1));
        assertFalse(AuditResultEnum.isValid(3));
    }

    /** isReject —— Service 层靠它判断"驳回必填意见" */
    @Test
    public void testIsReject() {
        assertTrue("code=2 是驳回", AuditResultEnum.isReject(2));
        assertFalse("code=1 是通过，不是驳回", AuditResultEnum.isReject(1));
        assertFalse("null 不是驳回", AuditResultEnum.isReject(null));
        assertFalse("未知码不是驳回", AuditResultEnum.isReject(99));
    }

    // ==================== 三、非法输入不抛异常（S07 的明确要求）====================

    /**
     * 编码不存在时<b>不抛异常</b>，返回 null 或兜底文案。
     *
     * <p>这是刻意的设计：这两个枚举主要用于"把数据库里的历史值翻成中文展示"。
     * 若抛异常，一条人工改坏的脏数据就能让整个审核历史列表 500。
     * 返回"未知环节/未知结论"既保住了页面，也把问题暴露在界面上一眼可见。
     */
    @Test
    public void testUnknownCodeDoesNotThrow() {
        assertNull("未知环节码应返回 null", AuditStageEnum.ofNullable(99));
        assertNull("null 环节码应返回 null", AuditStageEnum.ofNullable(null));
        assertNull("负数环节码应返回 null", AuditStageEnum.ofNullable(-1));

        assertNull("未知结论码应返回 null", AuditResultEnum.ofNullable(99));
        assertNull("null 结论码应返回 null", AuditResultEnum.ofNullable(null));

        // getDesc 给兜底文案，不抛异常、不返回 null
        assertEquals("未知环节", AuditStageEnum.getDesc(99));
        assertEquals("未知环节", AuditStageEnum.getDesc(null));
        assertEquals("未知结论", AuditResultEnum.getDesc(99));
        assertEquals("未知结论", AuditResultEnum.getDesc(null));
    }

    // ==================== 四、每个值的描述非空（前端展示前提）====================

    @Test
    public void testDescriptionsNotEmpty() {
        for (AuditStageEnum s : AuditStageEnum.values()) {
            assertNotNull(s.getDescription());
            assertFalse("环节 " + s + " 的描述不应为空串",
                    s.getDescription().trim().isEmpty());
        }
        for (AuditResultEnum r : AuditResultEnum.values()) {
            assertNotNull(r.getDescription());
            assertFalse("结论 " + r + " 的描述不应为空串",
                    r.getDescription().trim().isEmpty());
        }
    }

    /** code 唯一性：不允许两个枚举值共用一个 code（否则反查行为不确定） */
    @Test
    public void testCodesAreUnique() {
        assertEquals("AuditStageEnum 的 code 应互不相同",
                AuditStageEnum.values().length, distinctCodeCount(AuditStageEnum.values().length,
                        i -> AuditStageEnum.values()[i].getCode()));
        assertEquals("AuditResultEnum 的 code 应互不相同",
                AuditResultEnum.values().length, distinctCodeCount(AuditResultEnum.values().length,
                        i -> AuditResultEnum.values()[i].getCode()));
    }

    private int distinctCodeCount(int total, java.util.function.IntFunction<Integer> getter) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (int i = 0; i < total; i++) {
            set.add(getter.apply(i));
        }
        return set.size();
    }
}
