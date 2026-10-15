package com.lab.cms.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 审核结论枚举（2 态），对应 {@code cms_article_audit_log.result}。
 *
 * <h3>取值与数据库严格一致（已核对 sql/init.sql 第 299 行）</h3>
 * <pre>
 *   result TINYINT NOT NULL COMMENT '审核结论：1=通过 2=驳回'
 * </pre>
 *
 * <h3>验证一下这个 code 分组确实是需要枚举的</h3>
 * <p>判据：<b>如果两个值在业务上会导致截然不同的后续动作，就该有枚举。</b>
 * <ul>
 *   <li>{@link #PASS}：文章进入下一环节（初审通过 → 待终审；终审通过 → 待发布），
 *       不写 {@code opinion} 也合法</li>
 *   <li>{@link #REJECT}：文章回到 {@code REJECTED} 状态，
 *       <b>{@code opinion} 必填</b>（否则投稿人不知道该改什么）——
 *       违反时抛 {@code ARTICLE_AUDIT_OPINION_REQUIRED(11014)}</li>
 * </ul>
 * 这个"驳回必填意见"的分支如果写成裸数字 {@code if (result == 2)}，
 * 三个月后没人记得 2 是驳回还是通过。用枚举则 {@code result == REJECT} 自解释。
 *
 * <h3>与 {@code AuditStageEnum} 的分工</h3>
 * <p>同一条流水记录里两个字段职责不同：
 * {@code audit_stage} 回答"在哪一道关卡审的"，
 * {@code result} 回答"审的结果如何"。
 * 组合起来才是完整信息："主编终审 + 驳回"。
 */
@Getter
public enum AuditResultEnum {

    /** 通过：进入下一环节（编辑初审通过 → 待主编终审；主编终审通过 → 审核通过待发布） */
    PASS(1, "通过"),

    /** 驳回：回到"已驳回"状态，投稿人修改后可重新提交；**必须填写审核意见** */
    REJECT(2, "驳回");

    /** 结论码，与数据库 result 严格一致 */
    private final Integer code;

    /** 中文描述 */
    private final String description;

    AuditResultEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 按 code 反查枚举，查不到返回 {@code null}。
     * <p>理由同 {@link AuditStageEnum#ofNullable(Integer)}：
     * 主要用于读历史流水并生成展示文案，返回 null 比抛异常更适合。
     */
    public static AuditResultEnum ofNullable(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(r -> r.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 code 取中文描述，查不到返回 {@code "未知结论"}。
     */
    public static String getDesc(Integer code) {
        AuditResultEnum result = ofNullable(code);
        return result == null ? "未知结论" : result.description;
    }

    /** 判断 code 是否为合法结论码。 */
    public static boolean isValid(Integer code) {
        return ofNullable(code) != null;
    }

    /**
     * 是否为"驳回"结论。
     *
     * <p>供 Service 层判断"是否强制校验审核意见非空"。
     * 封装成方法而不是让调用方写 {@code code == REJECT.getCode()}，
     * 是为了让"驳回必填意见"这条规则的表达集中在语义层。
     */
    public static boolean isReject(Integer code) {
        return REJECT.code.equals(code);
    }
}
