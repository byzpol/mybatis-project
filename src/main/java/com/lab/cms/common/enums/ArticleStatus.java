package com.lab.cms.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 文章状态枚举（10 态）。
 *
 * <h3>⚠️ 数值必须与数据库严格一致</h3>
 * <p>{@code code} 的值必须与 {@code sql/init.sql} 中 {@code cms_article.status}
 * 的字段注释完全对齐，也要与 {@code docs/状态机图.md} 的编号一致。
 * 这是《企业开发规范清单》4-8 条的强制要求，并且有 JUnit 测试断言
 * （见 {@code ArticleStatusTest}）—— 不允许"数据库从 1 开始、代码从 0 开始"。
 *
 * <h3>为什么带 color 字段</h3>
 * <p>前端要显示状态标签（"已发布"绿色、"已驳回"红色），如果让前端维护
 * {@code status → 文本/颜色} 的映射，那么后端加一个状态前端就得改代码，且两边易不一致。
 * 把展示语义放在后端（本枚举的 {@link #description} 与 {@link #color}），
 * 前端只管渲染。{@code color} 的取值直接对应 Element Plus 的 {@code <el-tag type="...">}。
 *
 * <h3>为什么不用 ordinal()</h3>
 * <p>虽然本枚举的 ordinal 恰好等于 code（0~9），但**绝不能在业务代码中使用 ordinal()**：
 * 一旦有人在中间插入一个新状态，后面所有状态的 ordinal 都会平移，
 * 而数据库里的历史数据不会跟着变 —— 数据静默错乱。
 * 一律用 {@link #getCode()}。
 */
@Getter
public enum ArticleStatus {

    /** 草稿：刚创建，投稿人还在编辑 */
    DRAFT(0, "草稿", "info"),

    /** 待编辑初审：投稿人已提交，等编辑处理 */
    PENDING_EDITOR(1, "待编辑初审", "warning"),

    /** 待主编终审：编辑已初审通过，等主编处理 */
    PENDING_CHIEF(2, "待主编终审", "warning"),

    /** 审核通过：主编已终审通过，等待发布 */
    APPROVED(3, "审核通过", "success"),

    /** 已驳回：初审或终审被驳回，等投稿人修改后重新提交 */
    REJECTED(4, "已驳回", "danger"),

    /** 已发布：内容对外可见 */
    PUBLISHED(5, "已发布", "success"),

    /** 定时发布中：已排期，等触发时间到达 */
    SCHEDULED(6, "定时发布中", "primary"),

    /** 已撤回：曾经发布过，被作者主动撤回 */
    WITHDRAWN(7, "已撤回", "info"),

    /** 已下架：被管理员强制下架 */
    OFFLINE(8, "已下架", "info"),

    /** 已归档：**终态**，不可再执行任何动作 */
    ARCHIVED(9, "已归档", "info");

    /** 状态码，与数据库 cms_article.status 严格一致 */
    private final Integer code;

    /** 中文描述，直接展示给用户 */
    private final String description;

    /** 前端 Element Plus el-tag 的 type 值：info/warning/success/danger/primary */
    private final String color;

    ArticleStatus(Integer code, String description, String color) {
        this.code = code;
        this.description = description;
        this.color = color;
    }

    /**
     * 按 code 反查枚举。
     *
     * <p>⚠️ 查不到时抛 {@code IllegalArgumentException} 而不是返回 null：
     * 返回 null 会让调用方在下一行 NPE，且堆栈指向调用点而非根因。
     * 抛异常能立刻指出"哪个值不合法"。
     *
     * <p>若确实需要"查不到返回 null"的语义，用 {@link #ofNullable(Integer)}。
     */
    public static ArticleStatus of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("文章状态码不能为 null");
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知的文章状态码：" + code + "（合法值 0~9）"));
    }

    /**
     * 按 code 反查枚举，查不到返回 null。
     * <p>用于"从数据库读出的历史脏数据"场景，避免整个查询因一条脏数据失败。
     */
    public static ArticleStatus ofNullable(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 是否为终态。
     *
     * <p>终态 = 不能再执行任何动作。本项目只有 {@link #ARCHIVED} 是终态。
     * <p>{@code ArticleStateMachine} 在计算迁移前会先检查这个，
     * 提前给出"已归档不可修改"的明确提示，而不是笼统的"状态不允许操作"。
     */
    public boolean isFinal() {
        return this == ARCHIVED;
    }

    /**
     * 是否为"审核中"状态（待初审 或 待终审）。
     * <p>用于：① 前端"我的待办"列表 ② 判断文章是否被锁定（审核中不可编辑）。
     */
    public boolean isUnderAudit() {
        return this == PENDING_EDITOR || this == PENDING_CHIEF;
    }

    /**
     * 是否为"已对外可见"状态（已发布 或 已撤回/下架但曾发布过）。
     * <p>用于：判断删除时的保护策略（发布过的文章不允许物理删除，只能归档）。
     */
    public boolean isEverPublished() {
        return this == PUBLISHED || this == WITHDRAWN || this == OFFLINE || this == ARCHIVED;
    }

    /**
     * 是否允许作者编辑正文。
     * <p>审核中的文章不允许编辑（否则审核人看到的与最终发布的不一致）。
     */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }
}
