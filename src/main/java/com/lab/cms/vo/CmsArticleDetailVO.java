package com.lab.cms.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 文章详情出参 VO（含正文与审核流水）。
 *
 * <h2>为什么继承 {@link CmsArticleVO} 而不是重写一遍字段</h2>
 * <p>详情 = 列表项的<b>全部字段</b> + 正文 + 驳回意见 + 审核流水。
 * 若另起一个类把 17 个字段抄一遍，就会出现两个"文章展示字段"的定义，
 * 以后加一个字段要改两处、漏一处就静默不一致。
 * <p>这与契约 §5.3 的设计一致（那里也是 {@code ArticleDetailVO extends ArticleVO}）。
 * <p>⚠️ 继承时必须写 {@code @EqualsAndHashCode(callSuper = true)} ——
 * 否则 Lombok 生成的 {@code equals} 会忽略父类字段，两个 id 相同但标题不同的 VO
 * 会被判"相等"。Lombok 对此有编译期警告，本项目在 {@code CmsArticleAuditLog}
 * 等类上也是同样处理。
 *
 * <h2>⚠️ 为什么<b>没有</b> {@code versions} 字段（契约 §5.3 里有）</h2>
 * <p>契约的详情 VO 里带 {@code versions}（版本列表）。本步（S11）<b>不提供</b>，原因：
 * <ol>
 *   <li>版本功能要到 <b>S15</b> 才做（{@code listVersions} / {@code rollbackVersion}），
 *       现在没有数据来源；</li>
 *   <li>更重要的是<b>已经有了独立接口</b>：{@code ArticleDetail.vue} 实测是调
 *       {@code listVersions(id)} 单独取版本的（那个页面自己注释了"为什么单独取一次"），
 *       并不读 {@code detail.versions}。<b>没有消费者的字段不填</b> ——
 *       为它多查一张表是把响应时间花在没人看的地方。</li>
 * </ol>
 * <p>S15 做完后如果确实需要"详情里一并带版本"，再加字段不迟；届时本注释要同步更新。
 *
 * <h2>关于 {@code auditLogs} 的顺序</h2>
 * <p>按 {@code auditTime} <b>正序</b>（最早的在前）—— 与契约 §5.3 的字段注释
 * （"审核流水（按时间正序）"）以及 {@code frontend/src/api/modules/article.js}
 * 的 {@code listAuditLogs} 注释（"按时间正序"）一致。
 * <p>⚠️ 手册 S11 写的是"按 {@code auditTime} 倒序"——<b>不采纳</b>，因为：
 * ① 流水（log）的天然顺序是发生顺序；② 展示用的倒序<b>前端已经自己做了</b>：
 * {@code AuditTimeline.vue} 里 {@code sortedRecords} 明确写着"按时间倒序排（最新在上）"
 * 并自己 {@code sort} 了一遍，所以后端返正序时界面仍然是"最新在上"。
 * 也就是说手册那个核查项（"顺序与时间倒序正确"）在<b>界面层面</b>是满足的，
 * 而接口层面按契约返正序。这个差异已登记在 S11 实测记录里。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CmsArticleDetailVO extends CmsArticleVO {

    private static final long serialVersionUID = 1L;

    /**
     * 正文（富文本 HTML）。
     * <p>⚠️ 这是详情 VO 相对列表 VO 唯一的"重"字段，也正是"列表与详情分两个 VO"
     * 的根本理由：正文是 LONGTEXT，可能几百 KB，列表页一条都显示不了它。
     */
    private String content;

    /**
     * 最近一次驳回意见。
     * <p>取值规则：在审核流水里找<b>最后一条</b> {@code result = 2（驳回）} 的 {@code opinion}。
     * <p>为什么不是"最后一次审核的意见"：终审通过之后 {@code opinion} 是"同意发布"之类，
     * 把它当驳回意见展示是错的。也不做"是否已被后续提交覆盖"的判断 ——
     * 那个判断在 S11 用不到（前端只在 {@code status = REJECTED} 时展示它，
     * 而已驳回状态下最后一条驳回意见一定就是当前需要修改的原因）。
     * <p>没有驳回过时为 {@code null}，前端用 {@code v-if="detail?.rejectReason"} 控制显隐。
     */
    private String rejectReason;

    /**
     * 审核流水，按 {@code auditTime} <b>正序</b>（见类注释）。
     * <p>空列表而非 null：没有审核记录时返 {@code []}，前端不用写 {@code || []} 兜底。
     */
    private List<ArticleAuditLogVO> auditLogs;
}
