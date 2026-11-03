package com.lab.cms.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 文章领域动作枚举（9 个）。
 *
 * <p>动作是"用户想做什么"，状态是"文章现在处于什么阶段"。
 * 两者组合决定"能不能做"以及"做完之后变成什么状态"，这个映射表由
 * {@code ArticleStateMachine} 集中维护。
 *
 * <h3>为什么动作要单独枚举而不直接用字符串</h3>
 * <p>若用 {@code "SUBMIT"} 这样的字符串：
 * <ul>
 *   <li>拼错 {@code "SUBMIT "}（末尾空格）编译器不会报错，只在运行时"状态不允许操作"</li>
 *   <li>IDE 无法给出补全与"查找引用"，重构时容易漏改</li>
 *   <li>无法在枚举上挂 {@link #description} 用于生成友好提示</li>
 * </ul>
 *
 * <h3>{@link #PUBLISH_SCHEDULED_TRIGGER} 是唯一"非人工"动作</h3>
 * <p>其他 8 个动作都由用户点击触发（走 Controller → Service）。
 * 只有"定时发布触发"是由 {@code @Scheduled} 定时任务在排期时间到达时自动发起的。
 * 把系统动作也纳入状态机，好处是"状态迁移的唯一入口"这个约束不被破坏 ——
 * 定时任务也必须走 {@code ArticleStateMachine.transfer(...)}，
 * 而不是自己直接 {@code setStatus(PUBLISHED)}。
 */
@Getter
public enum ArticleAction {

    /**
     * 提交初审：草稿/已驳回 → 待编辑初审。
     * <p>⚠️ 描述文案在 S18 由「提交审核」订正为「提交初审」，见 {@code docs/重构记录.md} 第二节：
     * 系统里其余 12+ 处（前端按钮与提示、{@code @OperLog} 描述、版本快照的 {@code modify_remark}、
     * 接口成功消息、种子数据、接口分类与鉴权表、ApiFox 用例）用的都是「提交初审」，
     * 而本枚举描述会经 {@code ArticleStateMachine.transfer} 拼进**用户可见的异常消息**
     * （"文章当前状态为「已发布」，不允许执行「xxx」"）—— 于是同一个动作，用户在成功与失败时
     * 会看到两个名字。以多数口径 + 更具体的语义为准，把这一处改成「提交初审」。
     * <p>本改动只动描述常量，<b>不改签名、不改任何行为分支</b>；
     * 已实测 {@code getDescription()} 全仓仅用于拼提示文案，无逻辑依赖。
     */
    SUBMIT("提交初审"),

    /** 审核通过：待初审 → 待终审；待终审 → 审核通过 */
    AUDIT_PASS("审核通过"),

    /** 审核驳回：待初审 → 已驳回；待终审 → 已驳回 */
    AUDIT_REJECT("审核驳回"),

    /** 立即发布：审核通过 → 已发布；已撤回 → 已发布 */
    PUBLISH_NOW("立即发布"),

    /** 排期发布：审核通过 → 定时发布中 */
    PUBLISH_SCHEDULE("排期发布"),

    /**
     * 定时发布触发：定时发布中 → 已发布。
     * <p>⚠️ 由定时任务发起，不是用户动作。
     */
    PUBLISH_SCHEDULED_TRIGGER("定时发布触发"),

    /** 撤回：已发布 → 已撤回 */
    WITHDRAW("撤回"),

    /** 下架：已发布 → 已下架 */
    OFFLINE("下架"),

    /** 归档：已撤回/已下架 → 已归档（终态） */
    ARCHIVE("归档"),

    /** 版本回滚：不改变状态，只回滚内容（由 Service 单独处理，不走状态机迁移表） */
    ROLLBACK("版本回滚");

    /** 中文描述，用于生成"不允许执行「撤回」"这类提示 */
    private final String description;

    ArticleAction(String description) {
        this.description = description;
    }

    /**
     * 按枚举名反查。
     *
     * <p>⚠️ 用 {@code name()} 而不是 {@code toString()} 做匹配：
     * {@code name()} 是编译期常量且不可覆写，{@code toString()} 可以被改
     * （若有人覆写了 toString 返回中文，这里就会全查不到）。
     */
    public static ArticleAction of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("文章动作名不能为 null");
        }
        return Arrays.stream(values())
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的文章动作：" + name));
    }

    /** 按枚举名反查，查不到返回 null。 */
    public static ArticleAction ofNullable(String name) {
        if (name == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 是否为审核类动作。
     * <p>用于 {@code ArticleServiceImpl} 里判断"要不要写审核流水表"。
     */
    public boolean isAuditAction() {
        return this == AUDIT_PASS || this == AUDIT_REJECT;
    }
}
