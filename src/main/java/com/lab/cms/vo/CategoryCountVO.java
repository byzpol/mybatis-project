package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * "分类 ID + 文章数"行载体（分类列表页补计数用）。
 *
 * <h3>⚠️ 为什么这里带的是 {@code categoryId} 而不是 {@code label}（名字）</h3>
 * <p>与 {@link LabelCountVO} 是"同一件事的两种键"，区别很关键：
 * <ul>
 *   <li>{@link LabelCountVO} 用<b>名字</b>作键 —— 给报表看，人读得懂；</li>
 *   <li>本类用 <b>id</b> 作键 —— 给列表页回填用。
 *       分类名<b>只在同一父级下唯一</b>（库表上是 {@code idx_category_name}
 *       普通索引，不是唯一键！），所以"技术资讯"这个名字在树里可能出现在不同父级下。
 *       用名字当键回填，会给错误的分类填上别人的计数。</li>
 * </ul>
 * <p>这条差异是 S24 写代码时才注意到的：一开始两个场景共用了一个
 * "按名字分组"的查询，直到意识到分类名并非全局唯一才拆开。
 * 记在这里，避免以后有人图省事又合回去。
 *
 * <p>⚠️ 字段名与 SQL 列别名逐字一致（{@code categoryId} / {@code cnt}），
 * 否则自动映射失败会返回全 {@code null} 且不报错。
 */
@Data
public class CategoryCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类 ID。 */
    private Long categoryId;

    /** 该分类下的文章数（已逻辑删除的不计）。 */
    private Long cnt;
}
