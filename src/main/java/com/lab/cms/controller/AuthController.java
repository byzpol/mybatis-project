package com.lab.cms.controller;

import com.lab.cms.annotation.CurrentUser;
import com.lab.cms.annotation.OperLog;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.LoginDTO;
import com.lab.cms.service.AuthService;
import com.lab.cms.vo.LoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 认证接口。
 *
 * <p>本类**不加载 {@code @RequirePermission}** —— 登录/登出的语义是
 * "我正在获取身份"，此时还没有身份，谈不上权限校验。
 * 方法级权限注解只能加在"已登录后"的接口上。
 *
 * <h3>⚠️ 登录接口的 {@code @OperLog} 必须设 {@code saveParams = false}</h3>
 * <p>否则 {@code LoginDTO} 里的明文密码会被序列化写进
 * {@code sys_oper_log.request_param} 字段。这是凭据泄漏的典型渠道。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 登录。
     *
     * <p>⚠️ {@code saveParams = false}：参数含明文密码，绝不能进日志。
     */
    @PostMapping("/login")
    @OperLog(module = "认证管理", type = OperType.LOGIN, desc = "用户登录", saveParams = false)
    public Result<LoginVO> login(@RequestBody @Valid LoginDTO dto, HttpServletRequest request) {
        LoginVO vo = authService.login(dto, request);
        return Result.success("登录成功", vo);
    }

    /**
     * 登出。
     */
    @PostMapping("/logout")
    @OperLog(module = "认证管理", type = OperType.LOGOUT, desc = "用户登出")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return Result.success();
    }

    /**
     * 查询当前登录用户。
     *
     * <p>前端刷新页面后用它恢复用户态（权限集合、昵称、头像）。
     */
    @GetMapping("/me")
    public Result<LoginVO> me(@CurrentUser LoginUser user) {
        return Result.success(authService.currentUser(user));
    }
}
