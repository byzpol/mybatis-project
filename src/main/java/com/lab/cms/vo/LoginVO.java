package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 登录结果 VO。
 *
 * <h3>⚠️ 本类绝不包含 password</h3>
 * <p>（Entity 上虽已加 {@code @JsonIgnore}，但那是纵深防御；
 * 真正的保证是"出参只用 VO"这条分层纪律。）
 *
 * <h3>为什么把 permissions 也返回给前端</h3>
 * <p>前端需要它做**按钮级权限控制**（没有 {@code article:publish} 权限就不显示发布按钮）。
 * <p>⚠️ 但这只是**体验优化**，不是安全措施 ——
 * 真正的权限校验在后端 {@code @RequirePermission} 与 Service 的 {@code checkOwnership}。
 * 前端把按钮藏起来，用户改一下请求照样能发出去，后端必须能拦住。
 */
@Data
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;

    /** 角色码集合 */
    private Set<String> roles;

    /** 权限码集合（前端按钮级控制用） */
    private Set<String> permissions;

    /** 是否超级管理员 */
    private Boolean superAdmin;

    /** 本次登录时间 */
    private LocalDateTime loginTime;
}
