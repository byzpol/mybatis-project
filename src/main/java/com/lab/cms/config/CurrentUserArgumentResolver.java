package com.lab.cms.config;

import com.lab.cms.annotation.CurrentUser;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.constant.SessionConstant;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * {@code @CurrentUser} 参数解析器。
 *
 * <p>把 Session 中的 {@link LoginUser} 注入到 Controller 方法参数。
 *
 * <h3>为什么不直接从 RequestContextHolder 拿</h3>
 * <p>SpringMVC 的 {@code @CurrentUser} 参数解析发生在 HandlerAdapter 调用 Controller 之前，
 * 此时 {@code RequestContextHolder} 已经由 DispatcherServlet 设置好了，
 * 所以可以从那里拿。但更规范的方式是通过 {@link NativeWebRequest} 参数
 * （SpringMVC 传进来的，一定是当前的请求）—— 这样不依赖"ThreadLocal 已被设置"这个隐含前提，
 * 也更利于单测（可以直接构造 NativeWebRequest 的 mock 来测）。
 *
 * <h3>注册位置</h3>
 * <p>在 {@code SpringMvcConfig#addArgumentResolvers} 中用 {@code add(0, ...)} 注册，
 * 必须放在索引 0（原因见该方法的注释）。
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 判断是否需要本解析器处理：只有当参数类型是 {@link LoginUser}
     * <b>且</b>带 {@link CurrentUser} 注解时才处理。
     *
     * <p>⚠️ 两个条件都要判。只判类型的话，任何 {@code LoginUser} 类型的参数
     * 都会被强行注入 —— 若将来某个方法想接收前端传来的 LoginUser（不合理但可能），
     * 就会拿到 Session 里的对象，产生难以理解的 bug。
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && LoginUser.class.isAssignableFrom(parameter.getParameterType());
    }

    /**
     * 解析参数值。
     *
     * <p>流程：取 Session → 取 LOGIN_USER 属性 → 判类型 → 返回。
     *
     * <p>⚠️ 关于 {@code required = false} 的处理：
     * 若注解声明非必须，则取不到时返回 {@code null} 而不是抛异常。
     * 但**类型不对**（Session 里存的不是 LoginUser）时仍然抛异常 ——
     * 那是代码 bug，不是"未登录"，静默返回 null 会掩盖问题。
     */
    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        boolean required = annotation == null || annotation.required();

        // ---- 取 Session ----
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            if (required) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "@CurrentUser 无法获取当前请求");
            }
            return null;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            if (required) {
                // 走到这里说明 LoginInterceptor 没拦住（路径没配进拦截器），
                // 属于配置问题，值得用明确的 message 提示
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录，请先登录");
            }
            return null;
        }

        Object attr = session.getAttribute(SessionConstant.LOGIN_USER);
        if (attr == null) {
            if (required) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
            }
            return null;
        }

        // ---- 类型校验 ----
        // 类型不对说明 Session 里被塞了别的东西（可能是命名冲突或历史遗留），
        // 这是明确的代码 bug，无论 required 与否都要暴露出来。
        if (!(attr instanceof LoginUser)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    String.format("Session 中的登录用户类型不正确：期望 %s，实际 %s",
                            LoginUser.class.getName(), attr.getClass().getName()));
        }

        return attr;
    }

    /**
     * 静态工具方法：从当前线程的请求上下文中取登录用户。
     *
     * <p>⚠️ <b>仅用于 AOP 切面</b>（切面方法签名里拿不到 {@code NativeWebRequest}）。
     * Controller 与 Service **一律用 {@code @CurrentUser} 参数注入**，不要调这个方法。
     *
     * <p>理由：本方法依赖 {@code RequestContextHolder} 的 ThreadLocal，
     * 在异步/线程池场景下会拿到错误的值（或 null）。
     * 只在切面这一处使用（同步执行、必然在请求线程内），风险可控。
     *
     * @return 登录用户；未登录返回 {@code null}
     */
    public static LoginUser getCurrentUser() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object attr = session.getAttribute(SessionConstant.LOGIN_USER);
        return attr instanceof LoginUser ? (LoginUser) attr : null;
    }

    /**
     * 静态工具方法：取当前请求的 IP 地址（写登录日志、操作日志需要）。
     *
     * <p>⚠️ 关于 {@code X-Forwarded-For}：本项目无反向代理，理论上直接
     * {@code request.getRemoteAddr()} 即可。但为了将来部署到 Nginx 后不用改代码，
     * 这里做了 XFF 解析。
     *
     * <p><b>安全提示</b>：XFF 是客户端可伪造的头。若无反向代理，
     * 攻击者可以随便伪造 IP 写进日志。本项目在文档中明确记录这个局限
     * （见 {@code docs/设计决策记录}），不假装它是可信的。
     */
    public static String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();

        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // XFF 可能是 "client, proxy1, proxy2"，取第一个（最接近客户端的）
                int commaIndex = ip.indexOf(',');
                return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
