package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 排期发布入参。
 *
 * <h2>为什么单独建一个 DTO，而不用 {@code @RequestParam}</h2>
 * <p>排期发布只有一个入参 {@code scheduleTime}，用
 * {@code POST /{id}/schedule?scheduleTime=...} 也能跑。但仍建 DTO，理由有三：
 * <ol>
 *   <li><b>时间格式。</b>URL 上的时间要靠 {@code @DateTimeFormat} 解释，
 *       而请求体里走的是 Jackson 的 {@code LocalDateTime} 反序列化 —— 两套解析器，
 *       两套失败时的报错文案。本项目其余接口全部走 JSON 体，保持一致。</li>
 *   <li><b>将来要加字段。</b>比如"排期说明"、"是否覆盖已有排期"。
 *       加在 DTO 里是加一列；加在 {@code @RequestParam} 上就要再加一个参数，
 *       而且方法签名会越来越长。</li>
 *   <li><b>校验写在一处。</b>见下。</li>
 * </ol>
 *
 * <h2>⚠️ 这里只校验 {@code @NotNull}，"必须晚于当前时间"<b>不</b>放这里</h2>
 * <p>"必须晚于现在"依赖<b>当前时间</b>，而当前时间在"参数校验"与"真正执行"之间是会走的。
 * 放在注解里（比如 {@code @Future}）有两个问题：
 * <ul>
 *   <li>{@code @Future} 报的是框架的 {@code PARAM_ERROR(400)}，
 *       而契约与 {@code ErrorCode} 里为这件事专门留了 <b>11007</b>
 *       「排期时间必须晚于当前时间」—— 用 400 会把这个业务码作废；</li>
 *   <li>校验发生的时间点比真正写库<b>更早</b>，边界上会出现
 *       "校验时还差 1 秒、写库时已经到了"这种极窄窗口。</li>
 * </ul>
 * <p>所以时间合法性放在 Service 里，在<b>即将写库的那个事务内</b>用同一个
 * {@code now} 判断，并抛 11007。见
 * {@code ArticleServiceImpl#publishSchedule}。
 *
 * <p>另外"至少晚 1 分钟"这个阈值也属于业务规则（不是格式规则），同样在 Service 里。
 */
@Data
public class CmsArticlePublishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 计划发布时间。
     *
     * <p>格式：ISO 8601，例如 {@code "2026-10-27T09:00:00"}。
     * <p>⚠️ 只允许 {@code @NotNull}：格式不合法由 Jackson 报 400，
     * "太早"由 Service 报 11007，两者分工不同。
     */
    @NotNull(message = "排期时间不能为空")
    private LocalDateTime scheduleTime;
}
