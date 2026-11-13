package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志出参 VO。
 *
 * <h3>⚠️ 永远不返回密码，连输入的内容也不返回</h3>
 * <p>{@code SysLoginLog} 实体里就没有 password 字段（登录失败时记的是
 * {@code message = "用户名或密码错误"}，不是用户输入的内容）——
 * 这是《企业开发规范清单》3-8 条的要求：<b>日志里不能出现凭据</b>，
 * 因为日志表往往被更宽的范围读取（运维、审计、甚至导出）。
 * <p>本 VO 沿用这个边界，只多不少。
 *
 * <h3>{@link #userAgent} 要截断</h3>
 * <p>库里是 {@code VARCHAR(500)}（写入时已截断），但列表页不显示 UA，
 * 出参只给前 120 字符 —— 一屏 10 条就是 1.2KB 的无效负载。
 */
@Data
public class LoginLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志 ID。 */
    private Long id;

    /** 登录时输入的用户名（失败也记 —— 这是排查"谁在爆破"的依据）。 */
    private String username;

    /** 用户 ID；登录失败或用户名不存在时为 {@code null}。 */
    private Long userId;

    /** 客户端 IP。 */
    private String ip;

    /** User-Agent（列表出参截断为 120 字符）。 */
    private String userAgent;

    /** 登录状态码：1 成功 / 2 失败。 */
    private Integer loginStatus;

    /** 登录状态中文（"成功" / "失败"）。 */
    private String loginStatusDesc;

    /** 结果说明，如"登录成功" / "用户名或密码错误" / "账号已被禁用"。 */
    private String message;

    /** 登录时间。 */
    private LocalDateTime loginTime;
}
