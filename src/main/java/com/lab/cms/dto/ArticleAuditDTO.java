package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 审核入参（编辑初审 / 主编终审<b>共用</b>）。
 *
 * <h2>⚠️ 为什么这里<b>没有</b> auditStage 字段（与手册不一致的第一处）</h2>
 * <p>手册 S10 要求 DTO 带 {@code auditStage}（1 编辑初审 / 2 主编终审）+ {@code result}
 * + {@code opinion}，并在 Service 里校验 {@code auditStage} 与实际状态是否匹配。
 * <b>本项目不采纳这条</b>，理由有两个，第二个是决定性的：
 *
 * <ol>
 *   <li><b>它是冗余入参，冗余入参必然会造成前后端打架。</b>
 *       "这次该谁审"由文章<b>当前状态</b>唯一决定
 *       （{@code PENDING_EDITOR} 就是初审、{@code PENDING_CHIEF} 就是终审）。
 *       让客户端再传一遍，就等于允许客户端声明"我认为现在是初审"——
 *       而这个声明和数据库里的状态完全可能不一致。
 *       此时程序只有两种处理：信客户端（错）或不信客户端（那这个参数白传）。
 *       这正是 {@code docs/接口契约.md} 第 72 行明确否掉"拆成 /audit/first 与 /audit/final
 *       两个 URL"的同一个理由，只是把问题从 URL 搬到了请求体。</li>
 *   <li><b>⚠️ 前端的审核页面已经按"不带 stage"实现了。</b>
 *       {@code frontend/src/api/modules/article.js} 的 {@code auditArticle()} 注释里写着
 *       "⚠️ 不传 stage！审核阶段由文章当前状态决定"，
 *       F06 的 {@code ArticleAudit.vue} 也是照这个口径调用的。
 *       <b>如果后端改成必须传 auditStage，已写好的前端会立刻全部 400。</b>
 *       改一处 DTO 去破坏一个已经跑通的前端契约，代价远大于收益。</li>
 * </ol>
 *
 * <p>所以本 DTO 与 {@code docs/接口契约.md} §5.2 一致：
 * <b>{@code {auditAction, auditOpinion}}</b>，环节在 Service 里由状态反推。
 *
 * <h2>字段命名：为什么是 auditAction 而不是 result</h2>
 * <p>{@code cms_article_audit_log} 表里的列确实叫 {@code result}，
 * 但那是<b>落库字段名</b>；入参叫 {@code auditAction} 更准确 ——
 * 它表达的是"审核人做了什么动作"（通过 / 驳回），
 * 而 {@code result} 在入库后是"这次审核的结论"。两者值域相同，语义角度不同。
 * 前端、契约、本 DTO 统一用 {@code auditAction}，落库时再映射到 {@code result} 列。
 *
 * <h2>⚠️ 为什么不把"驳回必填意见"写在校验注解上</h2>
 * <p>{@code @NotBlank} 表达不了"<b>当 auditAction = 2 时</b> opinion 必填"这种<b>条件</b>校验。
 * 可以用类上加一个 {@code @AssertTrue} 的 {@code isOpinionValid()} 方法硬凑，
 * 但那样错误信息会变成"opinionValid 校验未通过"这种没有业务含义的文案，
 * 与项目"错误信息必须是可直接展示给用户看的中文"的约定冲突。
 * <p>所以这条规则放在 Service 里显式校验（抛
 * {@code ARTICLE_AUDIT_OPINION_REQUIRED(11014)}），
 * 与 {@code docs/接口契约.md} §5.2 下方那段说明完全一致。
 *
 * <p>⚠️ 另有一处手册与代码的错位登记在此：手册 S10 的核查项写"驳回不填意见 → {@code 11005}"，
 * 但 {@code ErrorCode} 里 {@code 11005} 是 {@link com.lab.cms.common.exception.ErrorCode#ARTICLE_ALREADY_AUDITED}
 * （该阶段已审核过）。驳回不填意见的正确码是
 * {@code ARTICLE_AUDIT_OPINION_REQUIRED(11014)}，手册提到的
 * {@code ARTICLE_AUDIT_REJECT_REASON_EMPTY} 这个码<b>在 ErrorCode 里根本不存在</b>。
 */
@Data
public class ArticleAuditDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审核动作：{@code 1 通过 / 2 驳回}。
     *
     * <p>取值对应 {@link com.lab.cms.common.enums.AuditResultEnum}。
     * <p>{@code @NotNull} 只挡住"字段缺失"，挡不住"传了 3 或 999"——
     * 后者在 Service 里用 {@code AuditResultEnum.isValid()} 兜住（抛 400）。
     * 两层都要有：注解管"格式"，Service 管"值域"，这跟
     * {@code CmsArticleSaveDTO#isTop} 的处理口径一致。
     */
    @NotNull(message = "请选择审核结果")
    private Integer auditAction;

    /**
     * 审核意见。
     * <p>通过时可为空；<b>驳回时必填</b> —— 该规则依赖 {@code auditAction} 的值，
     * 因此不在本类上用注解校验，而在 Service 层显式判断（见类注释）。
     * <p>库表 {@code opinion VARCHAR(500)}，超长会由 MySQL 严格模式拦下（错误 1406）。
     */
    private String auditOpinion;
}
