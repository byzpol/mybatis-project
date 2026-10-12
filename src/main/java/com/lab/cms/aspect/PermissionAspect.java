package com.lab.cms.aspect;

import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.CurrentUserArgumentResolver;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 权限校验切面（**垂直越权**防护）。
 *
 * <p>拦截标注了 {@link RequirePermission} 的 Controller 方法，
 * 校验当前登录用户是否拥有所需权限码。未通过则抛
 * {@code BusinessException(FORBIDDEN)}。
 *
 * <h2>⚠️ 本切面不解决水平越权</h2>
 * <p>垂直越权 = "普通用户用了管理员的接口" → 本切面解决。
 * <p>水平越权 = "投稿人 A 操作了投稿人 B 的文章" → **必须在 Service 层
 * 用 {@code checkOwnership} 解决**。
 *
 * <p>很多人以为加了权限注解就安全了，其实不是：投稿人 A **确实有**"提交文章"这个权限，
 * 所以 {@code @RequirePermission("article:submit")} 会放行。
 * 真正该拦的是"文章 2 不属于 A"，而这需要先把文章查出来才能判断 ——
 * 切面在方法执行**之前**运行，拿不到数据。
 *
 * <h3>为什么用 @Before 而不是 @Around</h3>
 * <p>权限校验只需要"进入方法前判断一次"，不需要修改返回值或捕获异常。
 * 用 {@code @Before} 语义更准确，性能也略好（少一层包装）。
 * 需要统计耗时/记录返回值时才用 {@code @Around}（如 {@link WebLogAspect}）。
 *
 * <h3>切点为什么写成注解匹配而不是包路径匹配</h3>
 * <p>{@code execution(* com.lab.cms.controller..*(..))} 这种包路径切点的问题：
 * 一旦有人把 Controller 放到别的包（或写测试时用 mock），切面就静默失效。
 * 用 {@code @annotation(...)} 精确匹配注解，则"只要标了注解就一定被拦" ——
 * 这个不变式不受包结构调整影响，更可靠。
 */
@Aspect
@Component
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    /**
     * 切点：标注了 {@code @RequirePermission} 的方法。
     *
     * <p>{@code @annotation(...)} 匹配**方法上**的注解。
     * 另一个常见写法 {@code @within(...)} 匹配**类上**的注解。
     * 本项目两种都支持（见 {@link #checkPermission} 里的查找逻辑），
     * 所以切点这里只用方法级 —— 类级注解会通过"向方法上找注解"的逻辑覆盖到。
     */
    @Pointcut("@annotation(com.lab.cms.annotation.RequirePermission)")
    public void permissionPointcut() {
        // 切点声明方法，无需实现
    }

    /**
     * 前置校验。
     *
     * <p>流程：取注解 → 取当前用户 → 取用户权限集合 → 逐码校验。
     */
    @Before("permissionPointcut()")
    public void checkPermission(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // ---- 1. 取注解：先找方法上，再找类上 ----
        // AnnotationUtils.findAnnotation 会自动沿继承链与类级查找，
        // 是比 method.getAnnotation() 更可靠的写法（后者不考虑类级与继承）。
        RequirePermission annotation = AnnotationUtils.findAnnotation(method, RequirePermission.class);
        if (annotation == null) {
            // 理论上不会发生（切点已限定），但保留防御
            return;
        }

        String[] requiredPermissions = annotation.value();
        if (requiredPermissions.length == 0) {
            // ⚠️ 空权限数组是配置错误：本意可能是"需要权限"但忘了写，
            // 这时**应该拒绝**而不是放行 —— fail-safe 原则。
            log.error("权限注解配置错误：{}#{} 的 @RequirePermission 未指定权限码",
                    signature.getDeclaringType().getSimpleName(), method.getName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "接口权限配置错误，请联系管理员");
        }

        // ---- 2. 取当前登录用户 ----
        LoginUser user = CurrentUserArgumentResolver.getCurrentUser();
        if (user == null) {
            log.warn("权限校验失败：未登录 | {}#{}",
                    signature.getDeclaringType().getSimpleName(), method.getName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // ---- 3. 校验 ----
        boolean allowed = checkPermission(user, requiredPermissions, annotation.logical());

        if (!allowed) {
            // ⚠️ 用 WARN 而不是 ERROR：越权尝试中大部分是"用户点了不该点的按钮"
            // （正常误操作），只有少量是恶意的。用 ERROR 会污染错误日志。
            // 但如果将来要做安全告警，这些 WARN 日志就是数据源。
            log.warn("权限校验失败（垂直越权拦截） | user={}(id={}) | 需要权限={} | 逻辑={} | 接口={}#{}",
                    user.getUsername(), user.getUserId(),
                    Arrays.toString(requiredPermissions), annotation.logical(),
                    signature.getDeclaringType().getSimpleName(), method.getName());

            throw new BusinessException(ErrorCode.FORBIDDEN,
                    String.format("没有该操作权限，需要：%s", String.join("、", requiredPermissions)));
        }

        log.debug("权限校验通过 | user={} | 权限={}",
                user.getUsername(), Arrays.toString(requiredPermissions));
    }

    /**
     * 按逻辑关系校验权限。
     *
     * @param user                当前用户（其 hasPermission 方法内部已处理超管短路）
     * @param requiredPermissions 所需权限码
     * @param logical             AND / OR
     */
    private boolean checkPermission(LoginUser user, String[] requiredPermissions,
                                    RequirePermission.Logical logical) {
        if (logical == RequirePermission.Logical.OR) {
            return user.hasAnyPermission(requiredPermissions);
        }
        // 默认 AND
        return user.hasAllPermissions(requiredPermissions);
    }
}
