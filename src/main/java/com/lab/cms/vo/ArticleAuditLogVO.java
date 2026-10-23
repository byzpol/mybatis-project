package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审核流水出参 VO。
 *
 * <h2>⚠️ 字段名以"前端已经消费的形状"为准（与契约 §5.3 有一处差异）</h2>
 * <p>契约 §5.3 把这个 VO 命名为 {@code AuditLogVO}，字段用的是
 * {@code auditAction / auditActionDesc / auditOpinion}，另外多一个 {@code auditorRole}。
 * 但 F05 已经写完的 {@code frontend/src/components/AuditTimeline.vue}
 * 在文件头部的"数据形状"注释里明确写的是：
 * <pre>
 *   auditStage:   1 | 2,
 *   auditorName:  '李编辑',
 *   result:       1 | 2,
 *   opinion:      '内容结构清晰',
 *   auditTime:    '2026-10-01 09:30:00',
 *   articleVersion: 3        // 模板里渲染成 v3
 * </pre>
 * 模板里实际读的也是 {@code item.result}、{@code item.opinion}、{@code item.auditorName}、
 * {@code item.auditStage}、{@code item.auditTime}、{@code item.articleVersion}。
 *
 * <p><b>所以字段名跟 F05 的组件走</b>（与 S10 的 {@code auditAction} 入参命名是两回事：
 * 入参表达"动作"、出参表达"结论"，值域相同）。若照契约改成
 * {@code auditAction / auditOpinion}，已跑通的审核时间线会立刻全部显示空白 ——
 * 这正是 S09/S10 反复遇到的同一类问题：<b>已实现的前端就是最硬的契约</b>。
 *
 * <p>契约里的 {@code auditorRole} <b>本步没有提供</b>：前端不读它，
 * 取值需要再关联 {@code sys_role}（多一次查询）。没有消费者的字段不该为它付查询成本。
 * 若将来审核时间线要显示"编辑/主编"角标，再加。
 *
 * <h2>为什么同时给"码"和"中文"</h2>
 * <p>{@code auditStage}/{@code result} 是给前端做逻辑用的
 * （比如 {@code isReject(result)} 决定意见框变红）；
 * {@code auditStageDesc}/{@code resultDesc} 是给人看的。
 * 只给码，前端就得自己维护一份映射表（后端改文案前端要跟着改）；
 * 只给中文，前端就没法做判断（"通过"和"退回"比字符串很脆弱）。
 * 这与 {@code CmsArticleVO} 同时给 {@code status} + {@code statusDesc} 是同一套思路。
 */
@Data
public class ArticleAuditLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 流水 ID。 */
    private Long id;

    /**
     * 审核环节：1 编辑初审 / 2 主编终审。
     * <p>取值对应 {@link com.lab.cms.common.enums.AuditStageEnum}。
     */
    private Integer auditStage;

    /** 环节中文（"编辑初审"/"主编终审"），由 Service 填充，前端直接显示。 */
    private String auditStageDesc;

    /** 审核人 ID。 */
    private Long auditorId;

    /**
     * 审核人昵称（跨表补数据）。
     * <p>来源 {@code sys_user.nickname}。⚠️ 列表场景必须<b>批量</b>查（{@code selectBatchIds}）
     * 后内存回填，不能在循环里逐条查 —— 这是本步要证明的"没有 N+1"。
     * <p>取不到时给 {@code null}，前端模板用 {@code item.auditorName || '—'} 兜底，
     * 所以这里不编造"未知用户"之类的假名字（那会掩盖数据问题）。
     */
    private String auditorName;

    /** 审核结论：1 通过 / 2 驳回。 */
    private Integer result;

    /** 结论中文（"通过"/"驳回"）。 */
    private String resultDesc;

    /** 审核意见，驳回时必填、通过时可空。 */
    private String opinion;

    /** 审核时间（业务时间，非入库时间）。 */
    private LocalDateTime auditTime;

    /**
     * 审核时的文章业务版本号（对应 {@code cms_article_version.version_no}）。
     * <p>前端渲染成 {@code v3} 这样的小标签，用来回答"这一版审的是哪一版内容"。
     */
    private Integer articleVersion;
}
