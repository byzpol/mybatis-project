package com.lab.cms.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 审核环节枚举（2 态），对应 {@code cms_article_audit_log.audit_stage}。
 *
 * <h3>取值与数据库严格一致（已核对 sql/init.sql 第 297 行）</h3>
 * <pre>
 *   audit_stage TINYINT NOT NULL COMMENT '审核环节：1=编辑初审 2=主编终审'
 * </pre>
 *
 * <h3>为什么单独建一个枚举，而不是在 {@code ArticleStatus} 里再放两个常量</h3>
 * <p>这是<b>两个不同的维度</b>，混在一起会立刻产生矛盾：
 * <ul>
 *   <li>{@code ArticleStatus} 描述的是"文章现在处于什么阶段"（10 个值，含草稿/已发布/已归档）</li>
 *   <li>{@code AuditStageEnum} 描述的是"这一次审核发生在哪个环节"（只有 2 个值）</li>
 * </ul>
 * 一篇文章的 {@code status} 只有一个，但它的 {@code audit_log} 会有多条记录，
 * 每条各有自己的 {@code audit_stage}。把两者塞进同一个枚举，
 * 会出现"已发布"这种根本不可能作为审核环节的值，代码里到处 {@code if} 过滤，
 * 迟早漏掉一处。
 *
 * <h3>为什么不实现 {@code IEnum<Integer>}</h3>
 * <p>{@code IEnum} 的作用是让 MP 在读写时自动把枚举转成 code。
 * 但本项目的实体字段统一声明为 {@code Integer}（见 {@code CmsArticleAuditLog#auditStage}
 * 的注释），压根不存枚举类型，所以 {@code IEnum} 没有用武之地。
 * 实现接口反而多一层"看起来会自动转换、其实不会"的误导 ——
 * <b>只在需要 MP 自动转换时才实现 {@code IEnum}，否则老老实实写 {@code of()} 反查</b>。
 */
@Getter
public enum AuditStageEnum {

    /** 编辑初审：投稿人提交后的第一道审核 */
    EDITOR_FIRST(1, "编辑初审"),

    /** 主编终审：初审通过后的第二道审核，通过即进入"待发布" */
    CHIEF_FINAL(2, "主编终审");

    /** 环节码，与数据库 audit_stage 严格一致 */
    private final Integer code;

    /** 中文描述，用于审核时间线展示与提示信息 */
    private final String description;

    AuditStageEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 按 code 反查枚举，查不到返回 {@code null}。
     *
     * <p>⚠️ 本枚举**刻意不提供抛异常版本的 {@code of()}**，
     * 因为它的主要调用场景是"从数据库读出的历史流水反查中文描述"——
     * 历史数据受库表约束（TINYINT NOT NULL）但不受枚举约束（有人手工改过库）。
     * 这种场景下抛异常会导致"一条脏数据让整个审核历史列表 500"，
     * 返回 null 由调用方决定展示"未知环节"更稳妥。
     *
     * <p>若确需入参校验（如前端提交初审时必须给合法值），
     * 用 {@link #isValid(Integer)} 判断。
     */
    public static AuditStageEnum ofNullable(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 code 取中文描述，查不到返回 {@code "未知环节"}。
     *
     * <p>返回兜底文案而非 null，是为了让调用方可以直接拼进 VO
     * （{@code vo.setAuditStageDesc(AuditStageEnum.getDesc(stage))}），
     * 不用在每处都写 null 判断。
     */
    public static String getDesc(Integer code) {
        AuditStageEnum stage = ofNullable(code);
        return stage == null ? "未知环节" : stage.description;
    }

    /** 判断 code 是否为合法环节码。 */
    public static boolean isValid(Integer code) {
        return ofNullable(code) != null;
    }
}
