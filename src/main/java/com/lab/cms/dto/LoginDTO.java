package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 登录入参。
 *
 * <h3>⚠️ 本类的 {@code @Size} 上限是"防爆破"的一部分</h3>
 * <p>若不加长度限制，攻击者可以传一个 10MB 的密码字符串，
 * 服务端会花大量 CPU 在 BCrypt 哈希计算上 —— 这是一种 DoS 攻击。
 * 限制长度后，超长输入在参数校验阶段就被拒绝，不进入密码比对逻辑。
 *
 * <h3>⚠️ 本类绝不能出现在日志里</h3>
 * <p>调用登录接口时必须用 {@code @OperLog(saveParams = false)}，
 * 否则明文密码会写进 {@code sys_oper_log.request_param} 字段。
 * 这是《企业开发规范清单》3-8 条与 {@code @OperLog.saveParams} 注释中
 * 明确列出的必须关闭的场景。
 */
@Data
public class LoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过 50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度应在 6~64 之间")
    private String password;
}
