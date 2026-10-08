package com.lab.cms.controller;

import com.lab.cms.annotation.CurrentUser;
import com.lab.cms.annotation.OperLog;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.Result;
import com.lab.cms.vo.SystemInfoVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 系统健康检查与自测接口。
 *
 * <p>本接口是 S05 的"端到端验证工具"：它不依赖任何业务表，
 * 可用来验证以下链路是否全部打通：
 * <ol>
 *   <li>Web 容器初始化（双容器父子关系是否正确）</li>
 *   <li>Jackson 时间序列化（{@code LocalDateTime} 是否输出为
 *       {@code yyyy-MM-dd HH:mm:ss} 而**不是**数组）</li>
 *   <li>统一响应体包装（是否返回 {@code {code, message, data, timestamp}}）</li>
 *   <li>{@code @OperLog} 切面（写 sys_oper_log 表是否成功）</li>
 *   <li>{@code @RequirePermission} 切面（权限拦截是否生效）</li>
 *   <li>全局异常处理器（非法请求是否返回统一错误结构）</li>
 *   <li>CORS 配置（跨域请求是否被放行）</li>
 * </ol>
 *
 * <p>⚠️ 生产环境应移除或以更严格的方式暴露本接口（会泄漏环境信息）。
 * 课程项目保留它作为"答辩演示 + 排查工具"。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    /**
     * 健康检查（无需登录）。
     *
     * <p>⚠️ 本接口**故意**跳过权限与登录校验（返回环境信息而已），
     * 且不加 {@code @OperLog}（避免每次探活都写一条日志污染日志表）。
     *
     * <p>需要在 {@code SpringMvcConfig} 的拦截器白名单里放行 —— 见该配置类。
     * 当前白名单只放行了 {@code /api/v1/auth/**} 与静态资源，
     * 因此本接口**仍会被登录拦截器拦住**。这是刻意的：
     * 项目不需要公开的健康检查端点，保留在需要登录后可访问即可。
     * 若要公开，把 {@code /api/v1/system/ping} 加进白名单。
     */
    @GetMapping("/health")
    public Result<SystemInfoVO> health() {
        SystemInfoVO vo = new SystemInfoVO();
        vo.setAppName("cms-mis");
        vo.setVersion("1.0.0-SNAPSHOT");
        vo.setStatus("UP");
        vo.setServerTime(LocalDateTime.now());
        vo.setJavaVersion(System.getProperty("java.version"));
        vo.setOsName(System.getProperty("os.name"));
        vo.setJvmMaxMemoryMb(Runtime.getRuntime().maxMemory() / 1024 / 1024);
        vo.setAvailableProcessors(Runtime.getRuntime().availableProcessors());
        return Result.success("服务运行正常", vo);
    }

    /**
     * 触发一次写操作，用于验证 {@code @OperLog} 切面写表。
     *
     * <p>调用后查 {@code SELECT * FROM sys_oper_log ORDER BY id DESC LIMIT 1}
     * 应有对应记录，且 {@code module='系统自测'}、{@code oper_type='OTHER'}、
     * {@code status=1}、{@code oper_id} 为当前登录用户。
     */
    @PostMapping("/selftest/operlog")
    @OperLog(module = "系统自测", type = OperType.OTHER, desc = "验证操作日志切面写表")
    public Result<String> selfTestOperLog(@CurrentUser LoginUser user) {
        log.info("系统自测：验证操作日志切面 | 当前用户={}", user.getUsername());
        return Result.success("已触发一次带 @OperLog 的写操作，请查 sys_oper_log 表最后一条记录");
    }

    /**
     * 验证 {@code @RequirePermission} 切面（垂直越权拦截）。
     *
     * <p>本接口要求 {@code role:manage} 权限（角色管理）。
     * 实测权限分配：ROLE_ADMIN 有 20 个权限（含 role:manage），
     * ROLE_CHIEF 有 12 个、ROLE_EDITOR 有 9 个、ROLE_AUTHOR 有 7 个 —— **都不含 role:manage**。
     *
     * <p>所以用 {@code admin} 调用应成功，用
     * {@code chief} / {@code editor} / {@code author} 调用应返回
     * {@code {"code":403,"message":"没有该操作权限，需要：role:manage"}}。
     *
     * <p>这条用例是答辩演示"垂直越权防护"的标准素材。
     */
    @PostMapping("/selftest/permission")
    @com.lab.cms.annotation.RequirePermission("role:manage")
    @OperLog(module = "系统自测", type = OperType.OTHER, desc = "验证权限切面（需 role:manage）")
    public Result<String> selfTestPermission(@CurrentUser LoginUser user) {
        log.info("系统自测：权限校验通过 | 用户={}", user.getUsername());
        return Result.success("权限校验通过：你有 role:manage 权限");
    }

    /**
     * 触发一次业务异常，用于验证全局异常处理器。
     *
     * <p>期望响应：HTTP 200 + {@code {"code":11001,"message":"文章不存在",...}}。
     * <p>⚠️ 不是在业务代码里写死抛异常，而是通过 {@code mode} 参数控制，
     * 便于在 ApiFox 里作为一条测试用例保留。
     */
    @PostMapping("/selftest/exception")
    @OperLog(module = "系统自测", type = OperType.OTHER, desc = "验证全局异常处理器")
    public Result<String> selfTestException(Integer mode) {
        if (mode == null) {
            mode = 0;
        }
        switch (mode) {
            case 1:
                // 业务异常：应返回 code=11001
                throw new com.lab.cms.common.exception.BusinessException(
                        com.lab.cms.common.exception.ErrorCode.ARTICLE_NOT_FOUND);
            case 2:
                // 业务异常带自定义提示：应返回 code=11002 + 具体状态说明
                throw new com.lab.cms.common.exception.BusinessException(
                        com.lab.cms.common.exception.ErrorCode.ARTICLE_STATUS_ILLEGAL,
                        "文章当前状态为「已发布」，不允许提交审核");
            case 3:
                // 系统异常：应返回 code=500，且**不泄漏**堆栈
                throw new IllegalStateException("这是故意抛出的系统异常，用于验证兜底处理器不泄漏堆栈");
            default:
                return Result.success("未触发异常（mode=0）");
        }
    }
}
