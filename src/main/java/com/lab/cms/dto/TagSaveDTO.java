package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 标签新增 / 编辑入参。
 *
 * <h3>⚠️ {@code status} 为什么不在这里</h3>
 * <p>与 {@link CategorySaveDTO} 同一取舍：新建一律启用，停用走独立动作。
 * 这样"建一个停用的标签"这件事在 API 上不可表达，少一种状态组合要测。
 *
 * <h3>⚠️ {@code useCount} 也刻意不在这里</h3>
 * <p>它是冗余缓存列，由系统在打标/取消打标时维护。
 * 若允许前端传，就会出现"把引用数改成 0，然后该标签被删掉，而文章上的关联还在"
 * 这类数据不一致 —— 冗余列一旦能被外部写，它就不再是缓存了。
 *
 * <h3>取名规则</h3>
 * <p>只做长度与非空校验（≤ 50，与 {@code tag_name VARCHAR(50)} 对齐），
 * <b>不做</b>"只允许字母数字"之类的正则：中文标签是刚需（种子数据里就有
 * "性能优化""踩坑记录"）。重名判定由 Service 查库完成（{@code uk_tag_name}）。
 */
@Data
public class TagSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签名称：非空 + 长度 ≤ 50。 */
    @NotBlank(message = "标签名称不能为空")
    @Size(max = 50, message = "标签名称不能超过 50 个字符")
    private String tagName;
}
