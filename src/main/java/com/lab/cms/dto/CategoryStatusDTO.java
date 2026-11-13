package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 分类启停用入参。
 *
 * <h3>为什么启停用要单独一个 DTO，而不是复用 {@code CategorySaveDTO}</h3>
 * <p>语义不同：编辑是"整体替换名称/父级/排序"，
 * 启停用是"翻转一个开关"。合成一个 DTO 的后果是
 * "改个状态还得把名称一起传上来"，一旦前端漏传就会把名称清空
 * （这正是 {@code updateById} 跳过 null 字段那类静默失效的高发场景）。
 *
 * <h3>⚠️ 为什么校验写成 {@code @Min(0) @Max(1)} 而不是 {@code @Pattern("0|1")}</h3>
 * <p>这是 {@code Integer} 类型，用数值范围校验更自然，报错信息也更人能读懂
 * （{@code @Pattern} 要求字段是 String，得先把类型改成 String，
 * 再在 Service 里 parse —— 多一次转换就多一处可能抛 {@code NumberFormatException}）。
 *
 * <p>超出范围时返回 <b>400</b>，不是静默降级：状态值写错属于调用方错误，
 * 静默当成"不变"会让前端以为设置成功了。
 */
@Data
public class CategoryStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 启用状态：0 停用 / 1 启用。
     * <p>{@code @NotNull} 是必须的 —— 不传时 {@code null} 会让下游
     * {@code setStatus(null)} 走进 {@code NOT_NULL} 策略而被静默跳过，
     * 接口返回 200 但什么都没改。
     */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0（停用）或 1（启用）")
    @Max(value = 1, message = "状态只能是 0（停用）或 1（启用）")
    private Integer status;
}
