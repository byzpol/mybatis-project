package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章-标签关联实体，对应表 {@code cms_article_tag}。
 *
 * <h3>⚠️ 这是一张"关联表"，但本项目仍然给它逻辑删除</h3>
 * <p>纯关联表在教科书里通常直接物理删除（插一行、删一行，没有历史价值）。
 * 本项目不这样做，原因有二：
 * <ol>
 *   <li>17 张表统一带 {@code deleted} + 审计四字段是 S18 定下的硬约束，
 *       开一个例外会让"全表口径一致"这句话立不住；</li>
 *   <li>审计四字段在这里<b>是有用的</b>：{@code create_by} 是"谁给它打的标"、
 *       {@code update_by} 是"谁取消的" —— 物理删除会把这些一起抹掉。</li>
 * </ol>
 *
 * <h3>⚠️ 唯一键 {@code uk_article_tag(article_id, tag_id, deleted)} ⇒ 必须"复活"而不是"重插"</h3>
 * <p>这是本表最需要小心的地方，也是 S24 相对 S18 的<b>实质性推进</b>：
 *
 * <p>S18 建索引时的注释已经预告了这个坑（原文：<i>"取消标签后重新打同一标签会撞 1062"</i>），
 * 但那句话当时只是<b>注释里的预判</b> —— 因为标签功能没做，这段代码路径<b>根本没人走到</b>。
 * S24 把功能做出来了，于是这条预判变成必须处理的事实。
 *
 * <p>设某文章 A 与标签 T 的交互序列：<pre>
 *   ① 打标        → INSERT (A, T, deleted=0)         库中: [(A,T,0)]
 *   ② 取消        → UPDATE set deleted=1             库中: [(A,T,1)]
 *   ③ 再打标      → INSERT (A, T, 0)  ← 不与 (A,T,1) 冲突，能插进去
 *                  库中: [(A,T,1), (A,T,0)]
 *   ④ 再取消      → UPDATE set deleted=1  ← ⚠️ 要产生第二行 (A,T,1) ⇒ 撞 1062
 * </pre>
 *
 * <p><b>解法：第 ③ 步不做 INSERT，而是把第 ② 步那行"复活"</b>
 * （{@code UPDATE ... SET deleted = 0 WHERE id = ? AND deleted = 1}）。
 * 这样库里对任意 {@code (article_id, tag_id)} <b>永远只有一行</b>，
 * 唯一的那个 {@code (A, T, 1)} 也只可能出现一次 —— ④ 再也不会冲突，
 * 而且序列可以无限循环下去。
 *
 * <p>复活与删除都不能走 MP 的 {@code deleteById} / {@code updateById}：
 * 前者生成的 SQL 是 {@code WHERE deleted = 0}，<b>查不到已删除的那一行</b>；
 * 后者受 {@code FieldStrategy.NOT_NULL} 影响会跳过 null 字段。
 * 因此走 {@link com.lab.cms.mapper.CmsArticleTagMapper} 里的两条显式 SQL。
 *
 * <p>⚠️ 这段推理的每一句都在 S24 用真机 SQL 验过（含"只留一行"的断言），
 * 不是纸面推演 —— 见 {@code docs/S24-分类标签与日志接口实测记录.md}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_article_tag")
public class CmsArticleTag extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 文章 ID（逻辑外键 {@code cms_article.id}）。 */
    @TableField("article_id")
    private Long articleId;

    /** 标签 ID（逻辑外键 {@code cms_tag.id}）。 */
    @TableField("tag_id")
    private Long tagId;
}
