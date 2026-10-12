package com.lab.cms.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.constant.SessionConstant;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 登录拦截器。
 *
 * <p>职责：校验请求是否携带有效登录态（Session 中的 LoginUser），未登录则直接返回 401。
 *
 * <h3>为什么用 Session 而不是 JWT</h3>
 * <p>本项目为单机部署的课程项目，不涉及分布式会话。Session 的优势在答辩场景下很明显：
 * 打开浏览器开发者工具就能看到 JSESSIONID，能直观演示"登录后 Cookie 变化"、
 * "关闭浏览器 Session 失效"。JWT 的无状态优势在这里用不上，反而要多写一份
 * 签名/解析/过期处理代码，且没法直观演示。
 *
 * <h3>⚠️ 本拦截器只解决"是否登录"（认证）</h3>
 * <p>它**不解决"是否有权限"（授权）**。授权由 {@code PermissionAspect} 通过
 * {@code @RequirePermission} 注解在方法级别校验。两者职责分离：
 * <ul>
 *   <li>拦截器：粗粒度，路径级，"没登录就别进来"</li>
 *   <li>切面：细粒度，方法级，"登录了但没这个权限也不行"</li>
 * </ul>
 *
 * <h3>⚠️ 不拦截 OPTIONS 预检请求</h3>
 * <p>跨域场景下浏览器会先发一个 OPTIONS 请求做预检。预检请求**不携带 Cookie**，
 * 若被拦截器拦下返回 401，浏览器会认为跨域失败，真正的请求根本不会发出。
 * 因此必须放行 OPTIONS。这是前后端分离项目联调时"明明后端配了 CORS 但还是跨域失败"
 * 的最常见原因。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 在 Handler 执行前调用。
     *
     * @return true 放行；false 中断（此时必须自己写出响应）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // ---- 1. 放行 CORS 预检请求 ----
        // 见类注释：OPTIONS 预检不带 Cookie，拦下会导致跨域失败。
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // ---- 2. 取 Session 与登录用户 ----
        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("拦截未登录请求：{} {}（无 Session）", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, ErrorCode.UNAUTHORIZED);
            return false;
        }

        Object attr = session.getAttribute(SessionConstant.LOGIN_USER);
        if (!(attr instanceof LoginUser)) {
            log.warn("拦截未登录请求：{} {}（Session 中无登录用户）",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, ErrorCode.UNAUTHORIZED);
            return false;
        }

        // ---- 3. 后台管理系统要求账号处于启用状态 ----
        // 这里不做校验（登录时已校验），因为账号可能在使用过程中被管理员禁用。
        // 严格做法是每次请求都查一次库确认账号状态，但那样每个请求多一次查询，
        // 成本过高。折中方案：把"账号被禁用时的强制下线"放在管理员禁用操作的 Service 里
        // —— 禁用时主动清掉该用户的所有 Session（需要 SessionRegistry，超出课程范围）。
        // 本项目采取的做法：登录时校验 + 依赖管理员禁用后用户下次登录失败。
        return true;
    }

    /**
     * 写出 401 响应。
     *
     * <p>⚠️ 三个容易漏的点：
     * <ol>
     *   <li>必须设 {@code Content-Type} 且带 {@code charset=UTF-8}，
     *       否则中文 message 在浏览器里显示为乱码。</li>
     *   <li>必须设 HTTP 状态码为 401（不是 200）：
     *       前端拦截器靠 HTTP 401 触发"跳转登录页"行为，用 200 + 业务码
     *       需要前端在每个请求里判 code，且跳转逻辑会分散。</li>
     *   <li>必须用 {@code response.getWriter()} 而不是 {@code getOutputStream()}
     *       （写字符流），且不可两者混用。</li>
     * </ol>
     */
    private void writeUnauthorized(HttpServletResponse response, ErrorCode errorCode) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> result = Result.fail(errorCode);
        String json = objectMapper.writeValueAsString(result);

        try (PrintWriter writer = response.getWriter()) {
            writer.write(json);
            writer.flush();
        }
    }
}
