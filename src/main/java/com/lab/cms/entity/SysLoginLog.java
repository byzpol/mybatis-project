package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 登录日志实体，对应表 {@code sys_login_log}。
 *
 * <p>登录成功与失败都记录（失败时 {@code loginStatus = 0}）。
 *
 * <h3>为什么失败也要记</h3>
 * <p>"连续登录失败"是账号被盗的第一信号。若只记成功，
 * 攻击者爆破密码的过程在日志里完全看不到 —— 这正是安全审计最需要的数据。
 * {@code idx_status_time} 索引就是为"查某时间段内的连续失败"设计的。
 *
 * <h3>⚠️ 绝不记录密码</h3>
 * <p>本类**没有** password 字段。登录失败时记录的是
 * {@code message = "用户名或密码错误"}，而不是用户输入的内容。
 * 这是《企业开发规范清单》3-8 条的要求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 登录时输入的用户名（失败时也记，便于排查"谁在爆破"） */
    @TableField("username")
    private String username;

    /** 用户 ID。登录失败或用户名不存在时为 null */
    @TableField("user_id")
    private Long userId;

    /** 客户端 IP */
    @TableField("ip")
    private String ip;

    /** User-Agent（截断到 500 字符）。用于识别异常客户端 */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 登录状态：{@code 1 成功 / 0 失败}。
     * <p>与 {@code sys_oper_log.status} 的约定一致。
     */
    @TableField("login_status")
    private Integer loginStatus;

    /** 结果说明，如 "登录成功" / "用户名或密码错误" / "账号已被禁用" */
    @TableField("message")
    private String message;

    /** 登录时间 */
    @TableField("login_time")
    private LocalDateTime loginTime;
}
