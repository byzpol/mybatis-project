package com.lab.cms.dto;

import com.lab.cms.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志查询入参（分页）。
 *
 * <h3>⚠️ 时间字段名沿用文章域的 {@code beginTime / endTime}</h3>
 * <p>这不是随手取的：S19 踩过一个真实的坑 —— 项目里有<b>两条互不相干的
 * "字符串 → 时间"转换链</b>（{@code @RequestBody} 走 Jackson 的自定义反序列化器，
 * 只认 {@code yyyy-MM-dd HH:mm:ss}；query string 走 Spring 默认的 ISO-8601，只认 {@code T} 分隔），
 * 结果是同一个前端组件在两处行为不同。S19 注册了全局
 * {@code StringToLocalDateTimeConverter} 统一到空格分隔格式。
 * <p>本 DTO 直接复用同一套字段名与格式，因此<b>不再需要任何额外配置</b>。
 * 若这里另取名字（比如 {@code startTime}），虽然也能跑，但会让
 * "项目里时间入参叫什么"多出一种答案。
 *
 * <h3>⚠️ 查的是 {@code oper_time} 不是 {@code create_time}</h3>
 * <p>{@code sys_oper_log} 有<b>两个</b>时间列：
 * <ul>
 *   <li>{@code create_time} —— 审计时间，"这一行是什么时候写进库的"；</li>
 *   <li>{@code oper_time} —— 业务时间，"这个操作是什么时候发生的"。</li>
 * </ul>
 * 正常入库时两者相差在毫秒级，但语义不同，且查日志的人是按"操作发生时间"找的。
 * 本 DTO 的时间区间过滤的是 {@code oper_time}（Service 里见真章）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务模块名关键字，模糊匹配（如"文章管理"）。 */
    private String module;

    /** 操作人用户名关键字，模糊匹配（冗余列 {@code oper_name}，不需要 JOIN 用户表）。 */
    private String operName;

    /** 操作类型枚举名精确匹配（如 {@code AUDIT}），取值见 {@code OperType}。 */
    private String operType;

    /**
     * 执行结果：{@code 1 成功 / 0 失败}。
     * <p>⚠️ <b>这与登录日志不是同一套口径</b>：{@code sys_login_log.login_status}
     * 是 {@code 1 成功 / 2 失败}。两列的 1 都表示成功，但"失败"的值不同，
     * 互相套用必然查错（本项目的两个实体类注释里都写明了这一点）。
     */
    private Integer status;

    /** 操作时间起（含），过滤 {@code oper_time}。 */
    private LocalDateTime beginTime;

    /** 操作时间止（含），过滤 {@code oper_time}。 */
    private LocalDateTime endTime;
}
