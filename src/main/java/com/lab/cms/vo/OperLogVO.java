package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志出参 VO。
 *
 * <h3>⚠️ 与 {@code BaseController#toPageResult} 的关系</h3>
 * <p>{@code BaseController} 的 Javadoc 里提过：那个浅转换方法
 * "保留给确实不需要 VO 的场景（如日志查询，字段本身就是给运营看的）"。
 * 但本项目最终仍然给日志建了 VO，理由是：
 * <ul>
 *   <li>{@code SysOperLog} 继承 {@code BaseEntity}，带 {@code deleted} 以及
 *       {@code create_by/update_by} 审计列 —— 虽然 {@code deleted} 上有
 *       {@code @JsonIgnore} 兜底，但"靠注解兜住"不是"字段本来就不该出现"；</li>
 *   <li>{@link #statusDesc} 需要补中文（{@code 1 成功 / 0 失败}）；</li>
 *   <li>{@link #requestParam} / {@link #responseResult} 需要在出参时<b>截断</b>
 *       （见下），这已经不是"原样透传"，Entity 直接出参做不到。</li>
 * </ul>
 * 结论：{@code BaseController.toPageResult} 那条注释描述的是"可以那样做"，
 * 而不是"本项目那样做了" —— S24 收敛到 VO 后，全项目查询接口口径一致。
 *
 * <h3>⚠️ 请求参数与响应结果要截断</h3>
 * <p>{@code request_param} 最长可到 2000 字符、{@code response_result} 同理
 * （{@code WebLogAspect} 写入时已截断）。列表页一屏 10 条 × 2 字段 × 2000 字符
 * 最坏可达 40KB 响应体，而这些内容列表上根本不显示 ——
 * 所以列表出参只给前 {@code 200} 字符 + 省略号，点进详情才给全文。
 */
@Data
public class OperLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志 ID。 */
    private Long id;

    /** 业务模块名，如"文章管理"。 */
    private String module;

    /** 操作类型枚举名，如 {@code AUDIT}。 */
    private String operType;

    /** 操作类型中文（由 {@code OperType} 反查；枚举名入库、中文出参）。 */
    private String operTypeDesc;

    /** 操作描述，如"审核文章"。 */
    private String operDesc;

    /** 请求方法：GET / POST / PUT / DELETE。 */
    private String requestMethod;

    /** 请求 URL。 */
    private String requestUrl;

    /** 请求参数（JSON，列表出参已截断为 200 字符）。 */
    private String requestParam;

    /** 响应结果（JSON，列表出参已截断；多数接口本就没记）。 */
    private String responseResult;

    /** 操作人 ID；系统操作时为 {@code null}。 */
    private Long operId;

    /** 操作人用户名（冗余列，避免查日志时再 JOIN 用户表）。 */
    private String operName;

    /** 客户端 IP。 */
    private String operIp;

    /** 耗时（毫秒）。 */
    private Long costTime;

    /** 执行结果码：1 成功 / 0 失败。 */
    private Integer status;

    /** 执行结果中文（"成功" / "失败"）。 */
    private String statusDesc;

    /** 失败时的错误信息。 */
    private String errorMsg;

    /** 操作时间（业务时间；过滤条件也是打在这一列上）。 */
    private LocalDateTime operTime;
}
