package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分类出参 VO。
 *
 * <h3>为什么不让 Entity 直接出参</h3>
 * <p>与 {@code CmsArticleVO} 同口径（那里列了四条理由，此处只重复与本域相关的两条）：
 * <ul>
 *   <li><b>不外泄审计列</b>：{@code create_by / update_by} 是内部运营信息。</li>
 *   <li><b>补展示语义</b>：库里 {@code status} 是 {@code 0/1}、
 *       {@code audit_status} 是 {@code 0/1/2}，前端拿到数字还要自己维护中文映射表。
 *       这里直接给 {@code statusDesc} / {@code auditStatusDesc}，前端零逻辑渲染
 *       （与 {@code CmsArticleVO.statusDesc} 是同一个做法）。</li>
 * </ul>
 *
 * <h3>{@code articleCount} 是跨表算出来的，Entity 装不下</h3>
 * <p>"这个分类下有多少篇文章"在 {@code cms_category} 里没有对应列，
 * 必须去 {@code cms_article} 数。列表接口用<b>一次 {@code GROUP BY} 查询</b>
 * 拿到本页所有分类的计数（避免每行一条 SQL）；
 * 树接口则一次拿全部（分类总量小，见 {@code CategoryServiceImpl#listTree}）。
 */
@Data
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类 ID。 */
    private Long id;

    /** 父分类 ID，0 表示顶级。 */
    private Long parentId;

    /** 分类名称（库表列名为 {@code category_name}）。 */
    private String categoryName;

    /** 层级：1 一级 / 2 二级。 */
    private Integer level;

    /** 排序号，越小越靠前。 */
    private Integer sort;

    /** 分类描述。 */
    private String description;

    /** 启用状态码：0 停用 / 1 启用。 */
    private Integer status;

    /** 启用状态中文（"停用" / "启用"）。 */
    private String statusDesc;

    /** 审批状态码：0 待审批 / 1 已生效 / 2 已驳回。 */
    private Integer auditStatus;

    /** 审批状态中文。 */
    private String auditStatusDesc;

    /** 该分类下的文章数（已删除的不计）。 */
    private Long articleCount;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
