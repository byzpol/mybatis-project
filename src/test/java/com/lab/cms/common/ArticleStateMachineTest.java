package com.lab.cms.common;

import com.lab.cms.common.enums.ArticleAction;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 文章状态机单元测试。
 *
 * <h3>这是全项目最重要的一组测试</h3>
 * <p>状态机是"业务规则"的集中体现。它错了，整个审核流程就错了，
 * 而且错误会以"某篇文章状态莫名其妙变了"的形式出现，极难排查。
 *
 * <p>本测试覆盖四个层次：
 * <ol>
 *   <li><b>表结构校验</b>：迁移表恰好 14 条；每个状态的定义与文档一致</li>
 *   <li><b>14 条合法迁移逐一验证</b>：每条都能正确算出目标状态</li>
 *   <li><b>非法迁移矩阵</b>：10 状态 × 9 动作 = 90 种组合，
 *       非法的 76 种**都必须抛异常**（不是静默通过）</li>
 *   <li><b>边界与终态</b>：ARCHIVED 不可迁移；null 入参的处理</li>
 * </ol>
 *
 * <p>用 JUnit 4（指导书指定），不用 JUnit 5。
 */
public class ArticleStateMachineTest {

    // ==================== 一、表结构校验 ====================

    /**
     * 迁移表必须恰好 14 条。
     * <p>这条断言是"文档 ↔ 代码"一致性的守门人：若有人改了迁移表却忘了改文档，
     * 这个测试会失败，强制他去同步文档。
     */
    @Test
    public void testTransitionCountIs14() {
        assertEquals("合法迁移必须是 14 条（见 docs/状态机图.md）",
                14, ArticleStateMachine.transitionCount());
        assertEquals("allTransitions() 的条目数也应为 14",
                14, ArticleStateMachine.allTransitions().size());
    }

    /**
     * 状态枚举的 code 必须与数据库注释严格一致（0~9）。
     */
    @Test
    public void testStatusCodesMatchDatabase() {
        assertEquals(0, ArticleStatus.DRAFT.getCode().intValue());
        assertEquals(1, ArticleStatus.PENDING_EDITOR.getCode().intValue());
        assertEquals(2, ArticleStatus.PENDING_CHIEF.getCode().intValue());
        assertEquals(3, ArticleStatus.APPROVED.getCode().intValue());
        assertEquals(4, ArticleStatus.REJECTED.getCode().intValue());
        assertEquals(5, ArticleStatus.PUBLISHED.getCode().intValue());
        assertEquals(6, ArticleStatus.SCHEDULED.getCode().intValue());
        assertEquals(7, ArticleStatus.WITHDRAWN.getCode().intValue());
        assertEquals(8, ArticleStatus.OFFLINE.getCode().intValue());
        assertEquals(9, ArticleStatus.ARCHIVED.getCode().intValue());

        assertEquals("状态总数应为 10", 10, ArticleStatus.values().length);
    }

    /**
     * code ↔ 枚举的双向映射必须自洽（《企业开发规范清单》4-8 条）。
     */
    @Test
    public void testStatusOfRoundTrip() {
        for (ArticleStatus status : ArticleStatus.values()) {
            assertEquals("ArticleStatus.of() 反查失败：" + status,
                    status, ArticleStatus.of(status.getCode()));
        }
    }

    /** 未知状态码必须抛异常（而不是返回 null 让调用方 NPE） */
    @Test
    public void testStatusOfUnknownCodeThrows() {
        try {
            ArticleStatus.of(99);
            fail("未知状态码 99 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应包含状态码", e.getMessage().contains("99"));
        }
        // ofNullable 则不抛异常
        assertEquals(null, ArticleStatus.ofNullable(99));
        assertEquals(null, ArticleStatus.ofNullable(null));
    }

    // ==================== 二、14 条合法迁移逐一验证 ====================

    /**
     * 14 条合法迁移的期望表。
     * <p>写成 {@code "当前状态:动作" → 目标状态} 的形式，与
     * {@code ArticleStateMachine} 的实现、与文档中的列表三者一致。
     */
    private static Map<String, ArticleStatus> expectedTransitions() {
        Map<String, ArticleStatus> expected = new HashMap<>();
        expected.put("DRAFT:SUBMIT", ArticleStatus.PENDING_EDITOR);
        expected.put("REJECTED:SUBMIT", ArticleStatus.PENDING_EDITOR);
        expected.put("PENDING_EDITOR:AUDIT_PASS", ArticleStatus.PENDING_CHIEF);
        expected.put("PENDING_EDITOR:AUDIT_REJECT", ArticleStatus.REJECTED);
        expected.put("PENDING_CHIEF:AUDIT_PASS", ArticleStatus.APPROVED);
        expected.put("PENDING_CHIEF:AUDIT_REJECT", ArticleStatus.REJECTED);
        expected.put("APPROVED:PUBLISH_NOW", ArticleStatus.PUBLISHED);
        expected.put("APPROVED:PUBLISH_SCHEDULE", ArticleStatus.SCHEDULED);
        expected.put("SCHEDULED:PUBLISH_SCHEDULED_TRIGGER", ArticleStatus.PUBLISHED);
        expected.put("PUBLISHED:WITHDRAW", ArticleStatus.WITHDRAWN);
        expected.put("PUBLISHED:OFFLINE", ArticleStatus.OFFLINE);
        expected.put("WITHDRAWN:PUBLISH_NOW", ArticleStatus.PUBLISHED);
        expected.put("WITHDRAWN:ARCHIVE", ArticleStatus.ARCHIVED);
        expected.put("OFFLINE:ARCHIVE", ArticleStatus.ARCHIVED);
        return expected;
    }

    /**
     * 14 条合法迁移：逐条验证 transfer() 返回正确的目标状态。
     */
    @Test
    public void testAllLegalTransitions() {
        Map<String, ArticleStatus> expected = expectedTransitions();
        assertEquals("期望表本身应有 14 条", 14, expected.size());

        expected.forEach((key, targetStatus) -> {
            String[] parts = key.split(":");
            ArticleStatus from = ArticleStatus.valueOf(parts[0]);
            ArticleAction action = ArticleAction.valueOf(parts[1]);

            assertTrue(String.format("应能迁移：%s + %s", from, action),
                    ArticleStateMachine.canTransfer(from, action));

            ArticleStatus actual = ArticleStateMachine.transfer(from, action);
            assertEquals(String.format("迁移目标错误：%s + %s", from, action),
                    targetStatus, actual);
        });
    }

    // ==================== 三、非法迁移矩阵（核心） ====================

    /**
     * 10 状态 × 10 动作 = 100 种组合，逐一验证：
     * 在期望表里的必须是合法的，不在的**必须抛 BusinessException**。
     *
     * <p>这是"穷举法"测试 —— 它比"只测几条典型路径"强得多：
     * 任何一条不该存在的迁移被误加进去，这个测试都会立刻失败。
     *
     * <p>注意 ROLLBACK 动作不参与状态机迁移（只回滚内容、不改状态），
     * 所以它在任何状态下都应非法（由 Service 单独处理）。
     */
    @Test
    public void testAllActionStatusCombinations() {
        Map<String, ArticleStatus> legal = expectedTransitions();
        int legalCount = 0;
        int illegalCount = 0;

        for (ArticleStatus from : ArticleStatus.values()) {
            for (ArticleAction action : ArticleAction.values()) {
                String key = from.name() + ":" + action.name();
                boolean shouldBeLegal = legal.containsKey(key);

                if (shouldBeLegal) {
                    legalCount++;
                    assertTrue("应有合法迁移但 canTransfer 返回 false：" + key,
                            ArticleStateMachine.canTransfer(from, action));
                    // transfer 不应抛异常
                    ArticleStatus target = ArticleStateMachine.transfer(from, action);
                    assertNotNull("合法迁移的目标状态不应为 null：" + key, target);
                } else {
                    illegalCount++;
                    assertFalse("不应存在合法迁移但 canTransfer 返回 true：" + key,
                            ArticleStateMachine.canTransfer(from, action));
                    // transfer 必须抛异常 —— 这是最关键的一条断言
                    try {
                        ArticleStateMachine.transfer(from, action);
                        fail("非法迁移应抛 BusinessException：" + key);
                    } catch (BusinessException e) {
                        // 期望的异常类型
                        assertNotNull("业务异常应带错误码：" + key, e.getCode());
                        assertNotNull("业务异常应带提示信息：" + key, e.getMessage());
                    }
                }
            }
        }

        assertEquals("合法组合数应为 14", 14, legalCount);
        assertEquals("非法组合数应为 100 - 14 = 86（10 状态 × 10 动作）",
                100 - 14, illegalCount);
    }

    // ==================== 四、终态与边界 ====================

    /**
     * ARCHIVED 是终态：任何动作都非法。
     */
    @Test
    public void testArchivedIsFinalState() {
        assertTrue("ARCHIVED 应被标记为终态", ArticleStatus.ARCHIVED.isFinal());

        for (ArticleAction action : ArticleAction.values()) {
            assertFalse("已归档状态不应允许任何动作：" + action,
                    ArticleStateMachine.canTransfer(ArticleStatus.ARCHIVED, action));
            try {
                ArticleStateMachine.transfer(ArticleStatus.ARCHIVED, action);
                fail("已归档执行 " + action + " 应抛异常");
            } catch (BusinessException e) {
                // 应使用专门的"已归档只读"错误码，而不是笼统的"状态非法"
                assertEquals("已归档应使用 ARTICLE_ARCHIVED_READONLY 错误码",
                        ErrorCode.ARTICLE_ARCHIVED_READONLY.getCode(), e.getCode());
                assertTrue("提示信息应说明已归档",
                        e.getMessage().contains("归档"));
            }
        }
    }

    /** 其他状态不应被误判为终态 */
    @Test
    public void testOnlyArchivedIsFinal() {
        for (ArticleStatus status : ArticleStatus.values()) {
            if (status == ArticleStatus.ARCHIVED) {
                continue;
            }
            assertFalse("非终态被误判为终态：" + status, status.isFinal());
        }
    }

    /**
     * 出度校验（矩阵校验的一部分）：
     * <ul>
     *   <li>ARCHIVED 出度 = 0（终态）</li>
     *   <li>DRAFT 出度 = 1（只能提交）</li>
     *   <li>PENDING_EDITOR 出度 = 2（通过 / 驳回）</li>
     *   <li>PUBLISHED 出度 = 2（撤回 / 下架）</li>
     * </ul>
     */
    @Test
    public void testOutDegree() {
        assertEquals("ARCHIVED 出度应为 0", 0, ArticleStateMachine.outDegree(ArticleStatus.ARCHIVED));
        assertEquals("DRAFT 出度应为 1", 1, ArticleStateMachine.outDegree(ArticleStatus.DRAFT));
        assertEquals("PENDING_EDITOR 出度应为 2", 2,
                ArticleStateMachine.outDegree(ArticleStatus.PENDING_EDITOR));
        assertEquals("PENDING_CHIEF 出度应为 2", 2,
                ArticleStateMachine.outDegree(ArticleStatus.PENDING_CHIEF));
        assertEquals("APPROVED 出度应为 2", 2, ArticleStateMachine.outDegree(ArticleStatus.APPROVED));
        assertEquals("PUBLISHED 出度应为 2", 2, ArticleStateMachine.outDegree(ArticleStatus.PUBLISHED));
        assertEquals("SCHEDULED 出度应为 1", 1, ArticleStateMachine.outDegree(ArticleStatus.SCHEDULED));
        assertEquals("REJECTED 出度应为 1", 1, ArticleStateMachine.outDegree(ArticleStatus.REJECTED));
        assertEquals("WITHDRAWN 出度应为 2", 2, ArticleStateMachine.outDegree(ArticleStatus.WITHDRAWN));
        assertEquals("OFFLINE 出度应为 1", 1, ArticleStateMachine.outDegree(ArticleStatus.OFFLINE));

        int total = 0;
        for (ArticleStatus s : ArticleStatus.values()) {
            total += ArticleStateMachine.outDegree(s);
        }
        assertEquals("全部出度之和应为 14", 14, total);
    }

    /**
     * 入度校验：
     * <ul>
     *   <li>DRAFT 入度 = 0（唯一入口，只能新建出来）</li>
     *   <li>ARCHIVED 入度 = 2（从 WITHDRAWN 或 OFFLINE 来）</li>
     *   <li>PUBLISHED 入度 = 3（APPROVED 立即发布 / SCHEDULED 触发 / WITHDRAWN 重发）</li>
     * </ul>
     */
    @Test
    public void testInDegree() {
        Map<ArticleStatus, Integer> inDegree = new HashMap<>();
        for (ArticleStatus s : ArticleStatus.values()) {
            inDegree.put(s, 0);
        }

        Map<String, ArticleStatus> legal = expectedTransitions();
        legal.values().forEach(target ->
                inDegree.put(target, inDegree.get(target) + 1));

        assertEquals("DRAFT 入度应为 0（唯一入口）",
                Integer.valueOf(0), inDegree.get(ArticleStatus.DRAFT));
        assertEquals("PUBLISHED 入度应为 3",
                Integer.valueOf(3), inDegree.get(ArticleStatus.PUBLISHED));
        assertEquals("REJECTED 入度应为 2",
                Integer.valueOf(2), inDegree.get(ArticleStatus.REJECTED));
        assertEquals("ARCHIVED 入度应为 2",
                Integer.valueOf(2), inDegree.get(ArticleStatus.ARCHIVED));
        assertEquals("PENDING_EDITOR 入度应为 2",
                Integer.valueOf(2), inDegree.get(ArticleStatus.PENDING_EDITOR));

        // 校验：10 个状态全部可达
        inDegree.forEach((status, degree) -> {
            if (status != ArticleStatus.DRAFT) {
                assertTrue("状态 " + status + " 应可达（入度 > 0）", degree > 0);
            }
        });
    }

    /** null 入参的处理 */
    @Test
    public void testNullHandling() {
        assertFalse("null 状态应返回 false",
                ArticleStateMachine.canTransfer((ArticleStatus) null, ArticleAction.SUBMIT));
        assertFalse("null 动作应返回 false",
                ArticleStateMachine.canTransfer(ArticleStatus.DRAFT, (ArticleAction) null));

        try {
            ArticleStateMachine.transfer((ArticleStatus) null, ArticleAction.SUBMIT);
            fail("null 状态应抛 BusinessException");
        } catch (BusinessException e) {
            assertEquals("null 状态应使用 ARTICLE_NOT_FOUND 错误码",
                    ErrorCode.ARTICLE_NOT_FOUND.getCode(), e.getCode());
        }

        try {
            ArticleStateMachine.transfer(ArticleStatus.DRAFT, (ArticleAction) null);
            fail("null 动作应抛 BusinessException");
        } catch (BusinessException e) {
            assertEquals("null 动作应使用 PARAM_ERROR 错误码",
                    ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        }
    }

    /** 用 Integer 状态码的重载方法应与枚举版行为一致 */
    @Test
    public void testIntegerOverload() {
        assertTrue(ArticleStateMachine.canTransfer(0, ArticleAction.SUBMIT));
        assertEquals(ArticleStatus.PENDING_EDITOR,
                ArticleStateMachine.transfer(0, ArticleAction.SUBMIT));
        assertTrue(ArticleStateMachine.canTransfer(5, ArticleAction.WITHDRAW));
        assertFalse("已归档不可操作",
                ArticleStateMachine.canTransfer(9, ArticleAction.SUBMIT));
    }

    // ==================== 五、状态辅助方法 ====================

    @Test
    public void testStatusHelperMethods() {
        assertTrue(ArticleStatus.PENDING_EDITOR.isUnderAudit());
        assertTrue(ArticleStatus.PENDING_CHIEF.isUnderAudit());
        assertFalse(ArticleStatus.DRAFT.isUnderAudit());
        assertFalse(ArticleStatus.PUBLISHED.isUnderAudit());

        assertTrue(ArticleStatus.PUBLISHED.isEverPublished());
        assertTrue(ArticleStatus.WITHDRAWN.isEverPublished());
        assertTrue(ArticleStatus.OFFLINE.isEverPublished());
        assertTrue(ArticleStatus.ARCHIVED.isEverPublished());
        assertFalse(ArticleStatus.DRAFT.isEverPublished());

        assertTrue(ArticleStatus.DRAFT.isEditable());
        assertTrue(ArticleStatus.REJECTED.isEditable());
        assertFalse("审核中不可编辑", ArticleStatus.PENDING_EDITOR.isEditable());
        assertFalse("已发布不可直接编辑", ArticleStatus.PUBLISHED.isEditable());
    }

    /** 每个状态都必须有非空的中文描述与颜色（前端展示需要） */
    @Test
    public void testStatusDisplayFields() {
        for (ArticleStatus status : ArticleStatus.values()) {
            assertNotNull("状态 " + status + " 的中文描述不应为空", status.getDescription());
            assertFalse("状态 " + status + " 的中文描述不应为空串",
                    status.getDescription().trim().isEmpty());
            assertNotNull("状态 " + status + " 的颜色不应为空", status.getColor());
            assertFalse("状态 " + status + " 的颜色不应为空串",
                    status.getColor().trim().isEmpty());
        }
    }

    /** 每个动作都必须有非空的中文描述 */
    @Test
    public void testActionDescriptions() {
        for (ArticleAction action : ArticleAction.values()) {
            assertNotNull(action.getDescription());
            assertFalse(action.getDescription().trim().isEmpty());
        }
        assertTrue(ArticleAction.AUDIT_PASS.isAuditAction());
        assertTrue(ArticleAction.AUDIT_REJECT.isAuditAction());
        assertFalse(ArticleAction.SUBMIT.isAuditAction());
    }
}
