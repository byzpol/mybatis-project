package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

/**
 * 批量提交审核入参。
 *
 * <h3>为什么单独建一个 DTO，而不是直接收 {@code List<Long>}</h3>
 * <p>让 Controller 可以写成 {@code @RequestBody @Valid BatchSubmitDTO dto}：
 * <ul>
 *   <li><b>本类是"有字段名的对象"，将来加字段不用改签名。</b>
 *       比如以后要支持"批量提交并指定统一的修改说明"，
 *       加一个 {@code remark} 字段即可；若当初直接收 {@code List<Long>}，
 *       就得把入参换成新对象 —— 前端的请求体形状也随之变化。</li>
 *   <li><b>能在参数上挂校验注解。</b>直接收 {@code List<Long>} 时，
 *       对集合本身的 {@code @NotEmpty} / {@code @Size} 校验无处可写
 *       （写在 {@code @RequestBody} 参数上需要类级 {@code @Validated} 与方法级
 *       {@code @Valid} 混用，比 DTO 绕）。</li>
 * </ul>
 *
 * <h3>⚠️ 校验放在 DTO 上，与 Service 里的上限检查是"双重保险"，不是重复</h3>
 * <p>两者拦的不是同一类调用方：
 * <ul>
 *   <li><b>DTO 上的注解</b>拦的是 HTTP 入口 —— 让非法请求在参数绑定阶段就得到
 *       一个明确的 400，不必进 Service 再抛。</li>
 *   <li><b>Service 里的检查</b>拦的是所有调用方 ——
 *       定时任务、批处理脚本、将来的其他 Service 调这个方法时**不经过 DTO 校验**。
 *       只写 DTO 校验等于"把安全边界设在最外层"，内层一旦被别的入口调用就失守。</li>
 * </ul>
 * 这正是 {@code ArticleServiceImpl#MAX_BATCH_SIZE} 即使与 DTO 上的
 * {@code @Size} 数值相同也必须单独存在的原因。
 */
@Data
public class BatchSubmitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待提交的文章 ID 列表。
     *
     * <p>⚠️ {@code @NotEmpty} 而不是 {@code @NotNull}：
     * {@code @NotNull} 只拦 {@code null}，放得过空数组 {@code []}，
     * 而"传了空数组"与"没传"在语义上都是"没有要提交的东西"，
     * 都应当被拒。前端清空了勾选却仍点了"批量提交"就是这种情况 ——
     * 放过去的话会返回"成功 0 条"，用户不知道自己是漏选了还是系统没反应。
     *
     * <p>⚠️ 上限 100 与 {@code ArticleServiceImpl.MAX_BATCH_SIZE} 保持一致。
     * 不限制的话，有人传 10 万个 id 就是一次不需要任何技术的 DoS：
     * 每个 id 一次事务、一次更新、一条快照。
     *
     * <p>注意这里**不校验元素非 null**（{@code List<@NotNull Long>} 需要
     * Hibernate Validator 的容器元素校验，本项目未启用）——
     * 元素为 null 的情况在 Service 里按"这一条失败"处理并计入失败清单，
     * 而不是抛异常，因为批量接口的语义就是"逐条容错"。
     */
    @NotEmpty(message = "请至少选择一篇文章")
    @Size(max = 100, message = "单次最多批量提交 100 篇")
    private List<Long> ids;
}
