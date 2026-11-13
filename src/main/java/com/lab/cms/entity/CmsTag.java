package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标签实体，对应表 {@code cms_tag}。
 *
 * <h3>为什么到 S24 才建这个类</h3>
 * <p>在此之前全仓有 {@code cms_tag} / {@code cms_article_tag} 的建表语句与种子数据
 * （10 个标签、16 条关联），但<b>没有任何实体或 Mapper</b> —— 也就是说这两张表
 * "设计出来了但一行代码都没跑过"。S11 建 {@link CmsCategory} 时也只是为了
 * 按 id 批量取分类名（列表/详情要显示名字），标签连这个需求都没有，
 * 所以一直空着。S24 补标签管理接口时才把它补全。
 *
 * <h3>⚠️ 库表列名是 {@code tag_name} 不是 {@code name}</h3>
 * <p>这是本项目登记过的"想当然"陷阱：{@code cms_category} 用 {@code category_name}、
 * {@code sys_user} 用 {@code nickname}，顺手把标签写成 {@code name} 不会报编译错，
 * 只会在运行时报 {@code Unknown column 'name'}。见 {@code docs/数据库设计.md}。
 *
 * <h3>⚠️ 唯一键 {@code uk_tag_name(tag_name, deleted)} 的代价</h3>
 * <p>S18 给全库 9 个唯一键都把 {@code deleted} 追加成了<b>最后一列</b>，
 * 目的是让"逻辑删除后同名可以重建"。代价是：<b>同一个标签名只能被逻辑删除一次</b> ——
 * 第二次删除同名标签时 {@code (tag_name, 1)} 已存在，会撞 1062。
 *
 * <p>⚠️ <b>本类所在的标签域把这个代价真正撞上了</b>（S24 实测）：
 * 「新建 A → 删除 A → 再建 A → 再删 A」这条序列在第二删时会失败。
 * 处置方式写在 {@code TagServiceImpl#deleteTag} 与
 * {@code docs/S24-分类标签与日志接口实测记录.md}，不在这里假装没问题。
 *
 * <h3>{@code use_count} 是冗余列，不是真相</h3>
 * <p>它是 {@code cms_article_tag} 里该标签出现次数的<b>缓存</b>，DDL 注释写明用途是"热度排序"。
 * 真相在关联表的 {@code COUNT(*)}。两者可能不一致（种子数据就不一定对得上，
 * S24 已实测登记），因此本项目的口径是：
 * <ul>
 *   <li><b>展示热度</b>用 {@code use_count}（一次查询拿到，不用 JOIN）；</li>
 *   <li><b>删除保护</b>用关联表的实时 {@code COUNT(*)} —— 保护必须是硬的，
 *       不能依赖一个可能漂移的冗余列。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_tag")
public class CmsTag extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 标签名称（库表列名 {@code tag_name}）。 */
    @TableField("tag_name")
    private String tagName;

    /** 被引用次数（冗余缓存列，真相在 {@code cms_article_tag}）。 */
    @TableField("use_count")
    private Integer useCount;

    /** 状态：0 停用 / 1 启用。停用的标签不可再打到文章上。 */
    @TableField("status")
    private Integer status;
}
