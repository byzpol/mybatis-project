package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签出参 VO。
 *
 * <p>字段取舍与 {@link CategoryVO} 同理：不出审计列、补中文语义。
 *
 * <h3>关于 {@code useCount}</h3>
 * <p>它是 {@code cms_tag} 上的冗余缓存列（DDL 注释：用于热度排序），
 * 由打标/取消打标时维护。本 VO 直接透出它，前端可以显示"热度 3"。
 * <p>⚠️ 但要注意它<b>不等于</b>"关联表里对得上的行数" ——
 * 种子数据里 {@code use_count} 与 {@code cms_article_tag} 的真实分布并不完全一致
 * （S24 已实测登记，见 {@code docs/S24-分类标签与日志接口实测记录.md}）。
 * 所以：
 * <ul>
 *   <li>展示热度 → 用本字段；</li>
 *   <li>决定"能不能删" → 用关联表实时 {@code COUNT(*)}（{@code TagServiceImpl#deleteTag} 就是这么做的）。</li>
 * </ul>
 * 把"展示用缓存、保护用真相"这条分清，是冗余列能安全存在的前提。
 */
@Data
public class TagVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签 ID。 */
    private Long id;

    /** 标签名称。 */
    private String tagName;

    /** 被引用次数（冗余缓存列）。 */
    private Integer useCount;

    /** 状态码：0 停用 / 1 启用。 */
    private Integer status;

    /** 状态中文（"停用" / "启用"）。 */
    private String statusDesc;

    /**
     * 该标签在 {@code cms_article_tag} 里的<b>真实</b>有效关联数。
     * <p>只有详情接口会填（要一次 {@code COUNT}），列表接口留 {@code null} ——
     * 列表页显示 {@link #useCount} 就够了，为每行再查一次计数是自找 N+1。
     */
    private Long realUseCount;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
