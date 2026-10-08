package com.lab.cms.common;

import com.lab.cms.common.enums.ArticleAction;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 文章状态机。
 *
 * <h2>这是全项目最重要的一个类</h2>
 *
 * <p>14 条合法迁移集中登记在此，是唯一的真相来源（Single Source of Truth）。
 *
 * <h3>⚠️ 强制约束</h3>
 * <p><b>禁止在业务代码里写 {@code if (status == DRAFT)} / {@code if (status == 1)} 这类
 * 散落的状态判断来表达"能否执行某动作"。</b>一律调用 {@link #transfer} 或 {@link #canTransfer}。
 *
 * <p>违反这条约束的症状：某天要在"草稿"和"已驳回"之外支持"从定时发布中撤回"，
 * 需要改 8 个 if-else 分支，且很可能漏掉一两处 —— 漏掉的地方就是一个能绕过业务规则的后门。
 * 集中成映射表后，加一条迁移只需在 static 块里加一行。
 *
 * <h3>14 条合法迁移（与 docs/状态机图.md 完全一致）</h3>
 * <pre>
 *   DRAFT            + SUBMIT                   → PENDING_EDITOR
 *   REJECTED         + SUBMIT                   → PENDING_EDITOR
 *   PENDING_EDITOR   + AUDIT_PASS               → PENDING_CHIEF
 *   PENDING_EDITOR   + AUDIT_REJECT             → REJECTED
 *   PENDING_CHIEF    + AUDIT_PASS               → APPROVED
 *   PENDING_CHIEF    + AUDIT_REJECT             → REJECTED
 *   APPROVED         + PUBLISH_NOW              → PUBLISHED
 *   APPROVED         + PUBLISH_SCHEDULE         → SCHEDULED
 *   SCHEDULED        + PUBLISH_SCHEDULED_TRIGGER→ PUBLISHED
 *   PUBLISHED        + WITHDRAW                 → WITHDRAWN
 *   PUBLISHED        + OFFLINE                  → OFFLINE
 *   WITHDRAWN        + PUBLISH_NOW              → PUBLISHED
 *   WITHDRAWN        + ARCHIVE                  → ARCHIVED
 *   OFFLINE          + ARCHIVE                  → ARCHIVED
 * </pre>
 *
 * <p><b>ARCHIVED 是终态</b>，任何动作都非法（迁移表中没有任何以 ARCHIVED 为源状态的行）。
 *
 * <h3>为什么用 "状态:动作" 字符串作 key 而不是二维数组</h3>
 * <p>用 {@code Map<String, ArticleStatus>} 的 key = {@code "DRAFT:SUBMIT"}：
 * <ul>
 *   <li>调试时一眼看懂（二维数组要看两个下标）</li>
 *   <li>用枚举名而不是 code 数字，避免"数字看错"（DRAFT 是 0 还是 1？）</li>
 *   <li>添加新迁移时只加一行，不需要维护数组大小</li>
 * </ul>
 * 代价是每次查询要做字符串拼接 —— 但 {@link #transfer} 的调用频率是"用户点一次按钮"级别，
 * 完全不是性能瓶颈，可读性优先。
 */
public final class ArticleStateMachine {

    /** 私有构造：纯静态工具类 */
    private ArticleStateMachine() {
        throw new UnsupportedOperationException("状态机工具类，禁止实例化");
    }

    /**
     * 迁移表：key = "当前状态枚举名:动作枚举名"，value = 目标状态。
     *
     * <p>用 {@code LinkedHashMap} 保持插入顺序，让 {@link #allTransitions()} 的输出
     * 与上面的注释、与文档中的列表顺序一致 —— 便于人工比对。
     * 用 {@code unmodifiableMap} 包一层，防止外部代码意外修改（虽然字段是 private，
     * 但 {@link #allTransitions()} 会暴露 keySet）。
     */
    private static final Map<String, ArticleStatus> TRANSITIONS;

    static {
        Map<String, ArticleStatus> map = new LinkedHashMap<>(32);

        // ---------- 1~2. 提交审核 ----------
        // 草稿可以提交
        map.put(key(ArticleStatus.DRAFT, ArticleAction.SUBMIT),
                ArticleStatus.PENDING_EDITOR);
        // 已驳回也可以提交（投稿人改完再交）—— 这是很容易漏掉的一条
        map.put(key(ArticleStatus.REJECTED, ArticleAction.SUBMIT),
                ArticleStatus.PENDING_EDITOR);

        // ---------- 3~4. 编辑初审 ----------
        map.put(key(ArticleStatus.PENDING_EDITOR, ArticleAction.AUDIT_PASS),
                ArticleStatus.PENDING_CHIEF);
        map.put(key(ArticleStatus.PENDING_EDITOR, ArticleAction.AUDIT_REJECT),
                ArticleStatus.REJECTED);

        // ---------- 5~6. 主编终审 ----------
        map.put(key(ArticleStatus.PENDING_CHIEF, ArticleAction.AUDIT_PASS),
                ArticleStatus.APPROVED);
        map.put(key(ArticleStatus.PENDING_CHIEF, ArticleAction.AUDIT_REJECT),
                ArticleStatus.REJECTED);

        // ---------- 7~9. 发布 ----------
        map.put(key(ArticleStatus.APPROVED, ArticleAction.PUBLISH_NOW),
                ArticleStatus.PUBLISHED);
        map.put(key(ArticleStatus.APPROVED, ArticleAction.PUBLISH_SCHEDULE),
                ArticleStatus.SCHEDULED);
        // 定时任务触发：注意这是唯一由系统发起的动作
        map.put(key(ArticleStatus.SCHEDULED, ArticleAction.PUBLISH_SCHEDULED_TRIGGER),
                ArticleStatus.PUBLISHED);

        // ---------- 10~11. 撤回与下架 ----------
        map.put(key(ArticleStatus.PUBLISHED, ArticleAction.WITHDRAW),
                ArticleStatus.WITHDRAWN);
        map.put(key(ArticleStatus.PUBLISHED, ArticleAction.OFFLINE),
                ArticleStatus.OFFLINE);

        // ---------- 12. 已撤回可以重新发布 ----------
        map.put(key(ArticleStatus.WITHDRAWN, ArticleAction.PUBLISH_NOW),
                ArticleStatus.PUBLISHED);

        // ---------- 13~14. 归档（两条路径，都到终态）----------
        map.put(key(ArticleStatus.WITHDRAWN, ArticleAction.ARCHIVE),
                ArticleStatus.ARCHIVED);
        map.put(key(ArticleStatus.OFFLINE, ArticleAction.ARCHIVE),
                ArticleStatus.ARCHIVED);

        TRANSITIONS = Collections.unmodifiableMap(map);

        // 静态初始化时自检：合法迁移必须是 14 条。
        // 若将来有人手滑加了一条或删了一条，容器启动就会失败（而不是带病运行）。
        // 这叫"让错误尽早暴露"——Fail Fast 原则。
        assert TRANSITIONS.size() == 14
                : "文章状态机迁移表条目数应为 14，实际为 " + TRANSITIONS.size();
    }

    /**
     * 生成迁移表的 key。
     * <p>用枚举 {@code name()} 而不是 {@code getCode()}，理由见类注释。
     */
    private static String key(ArticleStatus status, ArticleAction action) {
        return status.name() + ":" + action.name();
    }

    // ==================== 查询能力 ====================

    /**
     * 判断某状态下能否执行某动作。
     *
     * <p>⚠️ 用于<b>前端按钮控制</b>（"能不能显示撤回按钮"）与<b>测试断言</b>。
     * 真正执行动作时**不要**用它 + if 判断，而应直接调 {@link #transfer} 让它抛异常 ——
     * 否则一旦漏写 else 分支，就变成"静默什么都不做"，比抛异常难排查得多。
     *
     * @param status 当前状态（枚举）
     * @param action 待执行动作
     */
    public static boolean canTransfer(ArticleStatus status, ArticleAction action) {
        if (status == null || action == null) {
            return false;
        }
        // 终态直接返回 false，不必查表（虽然表里也确实没有）
        if (status.isFinal()) {
            return false;
        }
        return TRANSITIONS.containsKey(key(status, action));
    }

    /**
     * 判断某状态下能否执行某动作（用状态码）。
     */
    public static boolean canTransfer(Integer statusCode, ArticleAction action) {
        ArticleStatus status = ArticleStatus.ofNullable(statusCode);
        return canTransfer(status, action);
    }

    /**
     * 计算目标状态：合法则返回目标，非法直接抛 {@link BusinessException}。
     *
     * <p><b>这是业务代码唯一应该调用的方法。</b>典型用法：
     * <pre>
     *   // "我要提交这篇文章" —— 不必自己判断当前是什么状态
     *   ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), ArticleAction.SUBMIT);
     *   article.setStatus(target.getCode());
     * </pre>
     *
     * <p>异常区分三种情况，给出不同的提示：
     * <ol>
     *   <li>状态为 null → {@code ARTICLE_NOT_FOUND}（数据异常）</li>
     *   <li>状态是终态 → {@code ARTICLE_ARCHIVED_READONLY}（"已归档不可修改"，语义明确）</li>
     *   <li>状态合法但该动作不允许 → {@code ARTICLE_STATUS_ILLEGAL} + 具体原因
     *       （"文章当前状态为「已发布」，不允许提交审核"）</li>
     * </ol>
     * 第 3 点的提示信息把"当前状态"和"想做的动作"都拼进去，
     * 前端直接 toast 出来就是一句人话，不需要前端再翻译。
     */
    public static ArticleStatus transfer(ArticleStatus status, ArticleAction action) {
        if (status == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (action == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未指定要执行的操作");
        }

        // 终态单独判断：给出比"状态不允许操作"更明确的提示
        if (status.isFinal()) {
            throw new BusinessException(ErrorCode.ARTICLE_ARCHIVED_READONLY,
                    "文章已归档，不可再执行任何操作");
        }

        ArticleStatus target = TRANSITIONS.get(key(status, action));
        if (target == null) {
            throw new BusinessException(ErrorCode.ARTICLE_STATUS_ILLEGAL,
                    String.format("文章当前状态为「%s」，不允许执行「%s」",
                            status.getDescription(), action.getDescription()));
        }
        return target;
    }

    /**
     * 计算目标状态（用状态码）。
     */
    public static ArticleStatus transfer(Integer statusCode, ArticleAction action) {
        return transfer(ArticleStatus.ofNullable(statusCode), action);
    }

    // ==================== 供文档与测试使用 ====================

    /**
     * 返回全部合法迁移的可读描述（"当前状态 + 动作 → 目标状态"），按插入顺序。
     *
     * <p>用途：① 单元测试断言（迁移表恰好 14 条且与文档一致）
     * ② 生成文档 ③ 答辩时打印出来对照
     *
     * <p>用 {@code TreeSet} 还是 {@code List}？这里用 {@code List}（保持顺序）：
     * 插入顺序与文档中的编号一一对应，比对起来最直观。
     */
    public static Set<String> allTransitions() {
        Set<String> result = new TreeSet<>();
        TRANSITIONS.forEach((k, target) -> {
            String[] parts = k.split(":");
            result.add(String.format("%s + %s → %s", parts[0], parts[1], target.name()));
        });
        return result;
    }

    /**
     * 某状态的合法出边数量（用于矩阵校验）。
     * <p>已归档应为 0，草稿应为 1，已发布应为 2。
     */
    public static int outDegree(ArticleStatus status) {
        if (status == null) {
            return 0;
        }
        int count = 0;
        for (String k : TRANSITIONS.keySet()) {
            if (k.startsWith(status.name() + ":")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 迁移表条目总数。应为 14。
     * <p>供测试断言与文档核对使用。
     */
    public static int transitionCount() {
        return TRANSITIONS.size();
    }
}
