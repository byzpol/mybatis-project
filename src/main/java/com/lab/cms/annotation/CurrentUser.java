package com.lab.cms.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当前登录用户注入注解。
 *
 * <p>标在 Controller 方法参数上，由 {@code CurrentUserArgumentResolver} 从 Session
 * 中取出 {@code LoginUser} 并注入。
 *
 * <h3>解决的问题</h3>
 * <p>不用本注解时，每个需要当前用户的方法都要写：
 * <pre>
 *   // ✗ 重复、易错
 *   public Result&lt;Void&gt; submit(@PathVariable Long id, HttpSession session) {
 *       LoginUser user = (LoginUser) session.getAttribute(SessionConstant.LOGIN_USER);
 *       // 忘判 null 就是 NPE；忘转类型就是 ClassCastException
 *       ...
 *   }
 * </pre>
 * 用注解后：
 * <pre>
 *   // ✓
 *   public Result&lt;Void&gt; submit(@PathVariable Long id, {@code @CurrentUser} LoginUser user) {
 *       ...
 *   }
 * </pre>
 *
 * <h3>为什么参数类型必须是 LoginUser</h3>
 * <p>解析器只认 {@code LoginUser} 类型。若写成别的类型会抛
 * {@code IllegalStateException}（见解析器实现）—— 明确报错比注入 null 好。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    /**
     * 是否必须登录。
     *
     * <p>默认 {@code true}：未登录时抛 401 异常。
     *
     * <p>设为 {@code false} 的场景：接口对"已登录"和"未登录"有不同行为，
     * 但都不算错误。例如"点赞"接口未登录时提示"请先登录"。
     * 本项目所有需要用户的接口都在 {@code LoginInterceptor} 之后，
     * 因此实际都是已登录状态，本属性保留是为将来扩展。
     */
    boolean required() default true;
}
