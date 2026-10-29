package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 版本回滚入参（S15）。
 *
 * <h2>⚠️ 为什么 {@code versionNo} 在<b>请求体</b>里，而不是放在 URL 路径上</h2>
 * <p>手册 S15 给的 URL 是 {@code POST /articles/{id}/versions/{versionNo}/rollback}。
 * 本项目<b>不采用</b>，改用契约 {@code docs/接口契约.md} §1.3 第 16 行的
 * {@code POST /api/v1/articles/{id}/rollback}，把版本号放进请求体。三个理由：
 *
 * <ol>
 *   <li><b>契约是冻结的</b>（tag {@code trunk-v1.0}，2026-10-16 评审通过）。
 *       手册是任务书，契约是交付物；两者冲突时以契约为准。</li>
 *   <li><b>前端已经按契约写好了</b>：{@code frontend/src/api/modules/article.js}
 *       的 {@code rollbackVersion(id, versionNo)} 调的就是
 *       {@code post(\`/articles/${id}/rollback\`, { versionNo })}，
 *       注释里还明确写着"手册写的 {@code /versions/{n}/rollback} 已废弃"。
 *       后端若按手册实现，已跑通的前端会立刻 404。</li>
 *   <li><b>URL 层级</b>：项目约定资源层级 ≤3。
 *       {@code /articles/{id}/versions/{versionNo}/rollback} 在
 *       {@code /api/v1} 之后有 4 段，超出约定。</li>
 * </ol>
 *
 * <h2>为什么校验注解是 {@code @NotNull} + {@code @Min(1)} 两个</h2>
 * <p>它们挡的不是同一件事：
 * <ul>
 *   <li>{@code @NotNull} 挡"字段缺失"和显式的 {@code null}。</li>
 *   <li>{@code @Min(1)} 挡 {@code 0} 和负数 —— 版本号从 1 开始，
 *       传 0 或 -1 一定是调用方算错了，应该在校验层就被 400 拦下，
 *       而不是进到 Service 里再查一次库才说"版本不存在"。
 *       提前拦下来还有个好处：错误信息能说"版本号必须是正整数"，
 *       比"指定的版本不存在"更能指出问题在哪。</li>
 * </ul>
 * <p>⚠️ 但 {@code @Min(1)} <b>不能</b>替代"版本是否存在"的检查：
 * 传一个合法但库里没有的版本号（比如 9999）会正常通过注解校验，
 * 必须由 Service 查库后抛 {@code ARTICLE_VERSION_NOT_FOUND(11009)}。
 * 两层各管一段，缺一不可。
 */
@Data
public class CmsArticleRollbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 要回滚到的目标版本号（从 1 开始递增，对应 {@code cms_article_version.version_no}）。
     *
     * <p>⚠️ 是 <b>versionNo</b> 而不是快照行的主键 {@code id}：
     * {@code id} 是自增列，换个数据库就变了，不适合作为对外契约的一部分；
     * {@code version_no} 是"这篇文章的第几版"，是业务含义上的版本标识。
     */
    @NotNull(message = "请指定要回滚到的版本号")
    @Min(value = 1, message = "版本号必须是正整数")
    private Integer versionNo;
}
