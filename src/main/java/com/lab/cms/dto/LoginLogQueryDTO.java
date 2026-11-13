package com.lab.cms.dto;

import com.lab.cms.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志查询入参（分页）。
 *
 * <h3>⚠️ {@code loginStatus} 是 {@code 1 成功 / 2 失败}，不是 {@code 1/0}</h3>
 * <p>取值以<b>库表列注释与种子数据</b>为准。历史代码曾传过 {@code 0}
 * 表示失败（与 {@code sys_oper_log.status} 同口径），S20 已统一到 {@code 1/2}。
 * <p>这个差异带来的现实后果：日志域若照操作日志的 {@code 0/1} 写统计，
 * "失败数"会永远查出 0 —— 不报错，只是数据是错的。
 * 本项目在 {@link OperLogQueryDTO#status} 与 {@link LoginLogQueryDTO#loginStatus}
 * 的注释里各写了一遍，就是为了让这个区别无法被忽略。
 *
 * <h3>为什么这里<b>没有</b> {@code ip} 过滤</h3>
 * <p>考虑过加"按 IP 查"（排查爆破来源时有用），但放弃：
 * <ul>
 *   <li>{@code oper_ip} / {@code ip} 上都<b>没有索引</b>，加了就是全表扫描；</li>
 *   <li>IP 字段可以带端口、多级代理链（{@code X-Forwarded-For} 是逗号分隔的一串），
 *       精确匹配查不准、模糊匹配更慢。</li>
 * </ul>
 * <p>需要按 IP 排查时，用"用户名 + 时间区间"先缩小范围，再看 IP 分布 ——
 * 这条路径上的 {@code idx_status_time} 索引能命中。留白比加一个扫全表的过滤条件好。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录用户名关键字，模糊匹配。失败记录里也存了用户名，所以能查到爆破尝试。 */
    private String username;

    /** 登录状态精确匹配：{@code 1 成功 / 2 失败}。 */
    private Integer loginStatus;

    /** 登录时间起（含），过滤 {@code login_time}。 */
    private LocalDateTime beginTime;

    /** 登录时间止（含），过滤 {@code login_time}。 */
    private LocalDateTime endTime;
}
