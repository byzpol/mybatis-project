package com.lab.cms.dto;

import com.lab.cms.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 分类列表查询入参（分页）。
 *
 * <h3>为什么继承 {@code PageQuery}</h3>
 * <p>分类数量有限（本项目种子 9 条），本可以不假分页。
 * 但仍然分页，理由是<b>结构与文章域保持一致</b>：前端只需要一套
 * {@code PageTable} 组件就能渲染两个列表页，且 {@code size ≤ 100} 的保护
 * 由 {@link PageQuery#normalize()} 统一提供，不用为分类域再想一遍。
 *
 * <h3>所有字段可选</h3>
 * <p>与 {@link CmsArticleQueryDTO} 同口径：为 {@code null} 表示该条件不参与过滤。
 * 特别地 {@code parentId = 0} 是一个<b>有意义的筛选值</b>（"只看顶级分类"），
 * 所以判断必须用 {@code != null} 而不是 {@code > 0} —— 这个区别很容易写错。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CategoryQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类名称关键字，模糊匹配。 */
    private String categoryName;

    /** 父分类 ID 精确过滤。{@code 0} 是合法值（查顶级分类），别用 {@code > 0} 过滤。 */
    private Long parentId;

    /** 启用状态：0 停用 / 1 启用。 */
    private Integer status;

    /** 审批状态：0 待审批 / 1 已生效 / 2 已驳回。 */
    private Integer auditStatus;
}
