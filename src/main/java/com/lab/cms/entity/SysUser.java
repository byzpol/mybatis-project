package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code sys_user}。
 *
 * <h3>⚠️ 字段名的实际值（已实测核对，勿凭记忆写）</h3>
 * <p>显示名是 {@code nickname} 而**不是** {@code real_name}。
 * <p>{@code status} 默认值是 {@code 1}（正常），
 * 与 {@code cms_article.status} 默认 {@code 0}（草稿）方向相反 —— 这是刻意的：
 * 账号默认可用，文章默认未提交。写代码时不要复用同一个"默认值约定"。
 *
 * <h3>⚠️ 关于 password 字段与 JSON 序列化</h3>
 * <p>本类**没有**加 {@code @JsonIgnore} 到 password 上，因为：
 * <ul>
 *   <li>本项目约定 Controller 只用 VO 出参，Entity 不出层（规范 1-6）</li>
 *   <li>若加了 {@code @JsonIgnore}，登录时的"用用户名查用户（含密码哈希）→ 比对"
 *       流程中密码仍能正常从数据库读出（{@code @JsonIgnore} 只影响序列化）</li>
 * </ul>
 * 但作为**纵深防御**，仍然建议加 —— 万一某处直接返回了 Entity，
 * 至少响应里不会有密码哈希。故本类加上该注解。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 登录用户名，唯一索引 uk_username */
    @TableField("username")
    private String username;

    /**
     * 密码（BCrypt 哈希）。
     * <p>⚠️ 永不存明文；永不返回给前端。
     */
    @TableField("password")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String password;

    /** 显示昵称 */
    @TableField("nickname")
    private String nickname;

    /** 手机号 */
    @TableField("phone")
    private String phone;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 头像 URL */
    @TableField("avatar")
    private String avatar;

    /**
     * 账号状态：{@code 0 禁用 / 1 正常}。默认 1。
     * <p>⚠️ 与 {@code cms_article.status} 的默认值方向相反（见类注释）。
     */
    @TableField("status")
    private Integer status;

    /** 最近登录时间 */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;
}
