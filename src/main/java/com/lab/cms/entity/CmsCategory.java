package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分类实体，对应表 {@code cms_category}。
 *
 * <h3>⚠️ 本类在 S11 是"最小可用"，不是分类域的完整实现</h3>
 * <p>文章详情/列表需要显示 {@code categoryName}，而 {@code cms_article} 表里
 * 只有 {@code category_id} —— 要显示分类名就必须查这张表。
 * 但在此之前项目里<b>没有任何 {@code cms_category} 的实体或 Mapper</b>，
 * 所以本步（S11）建了这一个最小实体，<b>只用于按 id 批量取分类名</b>。
 *
 * <p>⚠️ <b>分类的增删改查、分类审核（{@code audit_status}）等完整功能不在这里</b> ——
 * 那属于分类管理模块，是另一位成员的主责范围（本项目 4 人分工）。
 * 届时会补上分类自己的 Service/Controller，本实体可能被扩充字段，
 * 但<b>已有的字段名与库表列一一对应，扩充不会破坏本步的用法</b>。
 * 这也是为什么本类字段严格照库表来写，而不是"只写需要的两列" ——
 * 只写两列的话，后面那个人要么改本类、要么再建一个类，两种都很别扭。
 *
 * <h3>为什么继承 BaseEntity</h3>
 * <p>判据仍然是"库表有没有那几列"：{@code cms_category} 带齐了
 * {@code create_by / create_time / update_by / update_time / deleted} 五列
 * （已核对 {@code information_schema}），所以直接继承。
 * 这与 {@link CmsArticleVersion} 不继承形成对照。
 *
 * <h3>⚠️ 库表列名是 {@code category_name} 不是 {@code name}</h3>
 * <p>这是本项目记录过的一处"想当然"陷阱：{@code sys_user} 用 {@code nickname}、
 * {@code cms_tag} 用 {@code tag_name}，很容易顺手把分类写成 {@code name}。
 * 写错不会报编译错，只会在运行时报 {@code Unknown column 'name'}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_category")
public class CmsCategory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 父分类 ID，一级分类为 0。 */
    @TableField("parent_id")
    private Long parentId;

    /** 分类名称（库表列名 {@code category_name}）。 */
    @TableField("category_name")
    private String categoryName;

    /** 层级：1 一级 / 2 二级。 */
    @TableField("level")
    private Integer level;

    /** 排序号，越小越靠前。 */
    @TableField("sort")
    private Integer sort;

    /** 分类描述。 */
    @TableField("description")
    private String description;

    /** 启用状态：0 禁用 / 1 启用。 */
    @TableField("status")
    private Integer status;

    /**
     * 审核状态：0 待审批 / 1 已生效 / 2 已驳回。
     * <p>本项目里分类也需要审核（新分类要审批后才可被文章选用）。
     */
    @TableField("audit_status")
    private Integer auditStatus;
}
