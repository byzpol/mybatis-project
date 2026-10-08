package com.lab.cms.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能级权限校验注解。
 *
 * <p>加在 Controller 方法或类上，由 {@code PermissionAspect} 通过 AOP 校验
 * 当前登录用户的权限码集合。未通过则抛 {@code BusinessException(FORBIDDEN)}。
 *
 * <h2>⚠️ 本注解只解决"垂直越权"，不解决"水平越权"</h2>
 *
 * <table border="1">
 *   <tr><th></th><th>垂直越权（功能级）</th><th>水平越权（数据级）</th></tr>
 *   <tr><td>含义</td><td>普通用户调用了管理员才能用的接口</td>
 *       <td>投稿人 A 操作了投稿人 B 的文章</td></tr>
 *   <tr><td>例子</td><td>投稿人调 {@code POST /articles/{id}/publish}</td>
 *       <td>投稿人 A 调 {@code POST /articles/2/submit}（文章 2 属于 B）</td></tr>
 *   <tr><td>防护手段</td><td><b>本注解</b>（AOP 方法级校验）</td>
 *       <td><b>Service 层 {@code checkOwnership}</b>（必须手写）</td></tr>
 * </table>
 *
 * <p>水平越权**不能**用注解解决，因为注解不知道"这条数据属不属于当前用户" ——
 * 那需要先把数据查出来。因此必须在 Service 层显式校验
 * （见 {@code ArticleServiceImpl#checkOwnership}）。
 *
 * <p>这是本项目最容易失分的点：前端把"提交"按钮藏起来，用户改一下 URL 里的 id
 * 就能提交别人的文章。**前端隐藏不是安全措施。**
 *
 * <h3>用法</h3>
 * <pre>
 *   // 单一权限
 *   {@code @RequirePermission("article:audit")}
 *
 *   // 多个权限，AND（默认）
 *   {@code @RequirePermission({"article:audit", "article:publish"})}
 *
 *   // 多个权限，OR
 *   {@code @RequirePermission(value = {"article:edit", "article:submit"},
 *                            logical = RequirePermission.Logical.OR)}
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 权限码，格式 {@code "资源:动作"}，如 {@code "article:audit"}。
     *
     * <p>⚠️ 权限码必须与 {@code sys_permission.permission_code} 中的值完全一致。
     * 拼错不会报错，但会导致"应该是能访问的被拒绝了"或更糟的"应该被拒绝的放过了"
     * （若写成空字符串且校验逻辑不严）。因此建议：权限码从 {@code sys_permission} 表导出，
     * 不要凭记忆手写。
     */
    String[] value();

    /**
     * 多个权限码之间的关系。
     * <p>默认 {@link Logical#AND}：必须同时拥有全部权限。
     */
    Logical logical() default Logical.AND;

    /**
     * 权限码之间的关系枚举。
     */
    enum Logical {
        /** 与：必须拥有全部权限 */
        AND,
        /** 或：拥有任一权限即可 */
        OR
    }
}
