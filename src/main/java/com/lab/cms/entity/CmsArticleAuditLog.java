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
 *   <li><b>防重复审核</b>：查 {@code (article_id, audit_stage)} 是否已有记录
 *       （库表有 {@code idx_article_stage} 联合索引直接支撑），有则拒绝 —— 对应错误码
 *       {@code ARTICLE_ALREADY_AUDITED}</li>
 *   <li><b>审核轨迹展示</b>：前端"审核历史"时间线按 {@code audit_time} 正序渲染</li>
 *   <li><b>责任追溯</b>：谁在什么时候驳回了哪一版，是答辩时的证据链</li>
 * </ol>
 *
 * <p>⚠️ <b>本表不做逻辑删除的常规查询</b>：审核流水是审计记录，即使关联的文章被删，
 * 流水也应保留（否则单据对不上）。所以查询本表时通常**显式忽略 deleted**，
 * 需要的时候用 {@code @TableField(exist = false)} 之外的裸 SQL 或 XML 查询。
 * 这是一个刻意的例外，不要一刀切认为"所有表查询都自动带 deleted = 0"。
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
