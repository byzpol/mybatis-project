package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文章审核流水实体，对应表 {@code cms_article_audit_log}。
 *
 * <h3>为什么继承 BaseEntity</h3>
 * <p>库表（sql/init.sql 第 294~312 行）带齐了 {@code create_by / create_time /
 * update_by / update_time / deleted} 五列，与基类字段一一对应，直接继承即可。
 *
 * <p>与 {@link CmsArticleVersion} 的对比是关键：那张表是"只增不改的快照"，
 * 缺 {@code update_by / update_time}，所以不继承。**判据是库表有没有这几列，
 * 不是"这张表重不重要"。**
 *
 * <h3>这张表是审核的审计凭证</h3>
 * <p>每完成一次审核（初审或终审，通过或驳回）写入一行。
 * 它带来三个业务能力：
 * <ol>
 *   <li><b>审核轨迹展示</b>：前端"审核历史"时间线按 {@code audit_time} 正序渲染
 *       （排序键是 {@code audit_time ASC, id ASC}，第二键不能省 ——
 *       {@code audit_time} 只有秒精度，"提交→初审→终审"常落在同一秒）</li>
 *   <li><b>责任追溯</b>：谁在什么时候驳回了哪一版，是答辩时的证据链</li>
 *   <li><b>驳回意见回传</b>：{@code rejectReason} 取最近一次驳回的 {@code opinion}</li>
 * </ol>
 *
 * <p>⚠️ <b>"防重复审核"不靠本表，靠状态机</b>（S18 订正，原注释写法有误）：
 * 早期的注释写的是"查 {@code (article_id, audit_stage)} 是否已有记录，有则拒绝 ——
 * 对应错误码 {@code ARTICLE_ALREADY_AUDITED}"。实际实现<b>不是</b>这样，两点都错：
 * <ul>
 *   <li>{@code ArticleServiceImpl#audit} 只判"当前状态是不是 {@code PENDING_EDITOR} /
 *       {@code PENDING_CHIEF}"（否则抛 {@code ARTICLE_STATUS_ILLEGAL 11002}，
 *       终态抛 {@code ARTICLE_ARCHIVED_READONLY 11011}），
 *       <b>不查本表</b>。重复审核被"状态已经不在待审环节"自然挡住。</li>
 *   <li>{@code ARTICLE_ALREADY_AUDITED(11005)} 虽然是 {@code ErrorCode} 里的合法枚举，
 *       但全仓<b>零处引用、从未被抛出</b>（S18 实测：{@code grep -rn "ErrorCode.ARTICLE_ALREADY_AUDITED"}
 *       在 java 源码里 0 命中）。属"已定义未使用"的常量，见 {@code docs/重构记录.md}。</li>
 *   <li>原注释提到的 {@code idx_article_stage(article_id, audit_stage)} 联合索引
 *       已在 S17 被<b>替换</b>为 {@code idx_article_time(article_id, audit_time)}
 *       —— {@code audit_stage} 在本项目里从不出现在 WHERE 中，是死列。
 *       理由与实测见 {@code docs/SQL优化记录.md} §四。</li>
 * </ul>
 *
 * <p>⚠️ <b>关于逻辑删除："本表是例外"这个说法是错的</b>（S18 订正）。
 * 原注释写"查询本表时通常显式忽略 deleted"、"不要一刀切认为所有表查询都自动带
 * deleted = 0"，<b>与实际代码相反</b>：本实体继承 {@link BaseEntity}，
 * 于是 {@code auditLogMapper.selectList(...)} 由 MP 自动生成 SQL，
 * {@code @TableLogic} 生效。真实日志（{@code logs/cms.log}，S18 取）：
 * <pre>
 * SELECT id,article_id,audit_stage,auditor_id,result,opinion,audit_time,article_version,
 *        create_by,create_time,update_by,update_time,deleted
 *   FROM cms_article_audit_log
 *  WHERE deleted=0 AND (article_id = ?)
 *  ORDER BY audit_time ASC,id ASC
 * </pre>
 * <p>也就是说 <b>本表和其他 16 张表一样，自动带 {@code deleted = 0}</b>，不是例外。
 * <p>那"流水要保留"这个设计意图靠什么实现？靠<b>删除时不级联</b>：
 * {@code deleteById} 是 {@code UPDATE ... SET deleted = 1}（MP 原生语义，
 * 只动自己一行）。所以即使文章被逻辑删除，流水行自己的 {@code deleted} 仍是 0，
 * 查得出来。<b>判据是"删文章不会去删流水"，不是"查流水时不带 deleted"</b>。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_article_audit_log")
public class CmsArticleAuditLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 文章 ID（逻辑外键 cms_article.id），{@code BIGINT UNSIGNED NOT NULL}。 */
    @TableField("article_id")
    private Long articleId;

    /**
     * 审核环节：{@code 1 编辑初审 / 2 主编终审}，{@code TINYINT NOT NULL}。
     * <p>取值对应 {@link com.lab.cms.common.enums.AuditStageEnum}。
     * <p>存 {@code Integer} 而非枚举的理由同 {@link CmsArticle#getStatus()}：
     * 与状态机/校验代码交互时不用反复做枚举转换，且脏数据不炸。
     */
    @TableField("audit_stage")
    private Integer auditStage;

    /** 审核人 ID（逻辑外键 sys_user.id），可为空。 */
    @TableField("auditor_id")
    private Long auditorId;

    /**
     * 审核结论：{@code 1 通过 / 2 驳回}，{@code TINYINT NOT NULL}。
     * <p>取值对应 {@link com.lab.cms.common.enums.AuditResultEnum}。
     * <p>⚠️ 规则：{@code result = 2（驳回）} 时 {@link #opinion} 必填，
     * 否则前端只看到"被驳回"却不知道为什么。
     */
    @TableField("result")
    private Integer result;

    /** 审核意见，{@code VARCHAR(500)}，驳回时必填、通过时可选。 */
    @TableField("opinion")
    private String opinion;

    /**
     * 审核时间，{@code DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP}。
     *
     * <p>⚠️ 注意与基类 {@code createTime} 的区别：两者在"审核刚发生"时值几乎相同，
     * 但语义不同 —— {@code auditTime} 是**业务时间**（用户点"驳回"的那一刻），
     * {@code createTime} 是**入库时间**。极端情况（补录历史数据）下两者会不一致。
     * 前端时间线展示用 {@code auditTime}。
     */
    @TableField("audit_time")
    private LocalDateTime auditTime;

    /**
     * 审核时的文章版本号，{@code INT NULL}，可为空。
     * <p>对应 {@code cms_article_version.version_no}。
     * <p>作用：把"这次审核通过的是哪一版内容"钉死。
     * 若审核后作者又改了（虽然审核中不允许改，但驳回后可以改再提交），
     * 靠这个号能对上是"改之前的版本过了审"还是"改之后的版本过了审"。
     */
    @TableField("article_version")
    private Integer articleVersion;
}
