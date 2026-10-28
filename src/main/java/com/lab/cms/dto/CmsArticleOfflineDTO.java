package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 下架请求入参（S14）。
 *
 * <h3>为什么只有 {@code reason} 一个字段</h3>
 * <p>下架是"运营动作"（见 {@code docs/接口分类与鉴权表.md} S12 定下的口径），
 * 与"撤回（作者收回自己的稿）"的区别在于：<b>下架需要一个说得出口的理由</b>。
 * 文章从"已发布"变"已下架"，读者端立刻不可见，事后必须能回答"为什么下的"。
 * <p>⚠️ 注意 {@code id} 走路径变量、<b>不在</b>请求体里 —— 与
 * {@link CmsArticleSaveDTO} 同一条规则：两个地方都能传 id 就要定义"以哪个为准"，
 * 而"以哪个为准"的规则本身就是 bug 温床。
 *
 * <h3>⚠️ 校验注解为什么写 {@code @NotBlank} 而不是 {@code @NotNull}</h3>
 * <p>下架原因如果只判非 null，传 {@code {"reason":"   "}}（三个空格）就能过 ——
 * 库里存一条看不出内容的"理由"，等于没填。
 * {@code @NotBlank} 同时挡掉 {@code null} / 空串 / 纯空白，是这三者里唯一正确的选择。
 * 这与 {@code requireSubmittable} 里用 {@code StringUtils.hasText} 是同一个判据。
 *
 * <h3>为什么长度上限是 200 而不是 500</h3>
 * <p>{@code cms_article_audit_log.opinion} 是 500（那是审核意见，要写结论）。
 * 下架原因是一句运营备注，200 足够。
 * <p>⚠️ 更要紧的是：本字段会被 {@code @OperLog} 写进
 * {@code sys_oper_log.request_param}（该列 {@code VARCHAR(2000)}，见建表脚本）——
 * 上限留在 200 可以确保"日志里能看到完整原因"，
 * 而不是被 {@code WebLogAspect} 的 {@code truncate()} 截掉半句。
 */
@Data
public class CmsArticleOfflineDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 下架原因，必填，最长 200 字。
     *
     * <p>⚠️ 这个字段<b>不会</b>写进 {@code cms_article_audit_log}（审核流水表只记录
     * "审核通过/驳回"两种结论，见 {@code ArticleService#offline} 的注释），
     * 它的持久化通道是 {@code @OperLog} → {@code sys_oper_log.request_param}。
     */
    @NotBlank(message = "下架原因不能为空")
    @Size(max = 200, message = "下架原因不能超过 200 个字符")
    private String reason;
}
