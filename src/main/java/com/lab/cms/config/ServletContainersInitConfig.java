package com.lab.cms.config;

import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

import javax.servlet.Filter;

/**
 * Web 容器初始化类（替代 web.xml）。
 *
 * <p>继承 {@code AbstractAnnotationConfigDispatcherServletInitializer}，
 * Servlet 3.0+ 容器（Tomcat 9）启动时会通过 SPI 机制自动发现它并执行，
 * 从而创建"根容器 + SpringMVC 子容器"的双容器结构。
 *
 * <h3>双容器的父子关系</h3>
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │  根容器（ContextLoaderListener）          │
 *   │  SpringConfig：Service / Mapper / 事务   │
 *   │                 AOP 切面 / 数据源         │
 *   └────────────────┬────────────────────────┘
 *                    │ parent
 *   ┌────────────────▼────────────────────────┐
 *   │  子容器（DispatcherServlet）              │
 *   │  SpringMvcConfig：@Controller            │
 *   │                   消息转换器 / 拦截器      │
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>为什么必须分两个容器</b>：子容器能看到父容器的 Bean（Controller 能注入 Service），
 * 但父容器看不到子容器（Service 不能注入 Controller）。这样天然保证了
 * "Web 层可以依赖业务层，业务层不能反向依赖 Web 层"的分层纪律 —— 这是本项目
 * 《企业开发规范清单》1-3 条（分层调用方向单向）在框架层面就落地的保障。
 *
 * <h3>⚠️ 若把两个配置类放进同一个 getRootConfigClasses</h3>
 * 双容器会退化成单容器，SpringMVC 的 HandlerMapping、拦截器、
 * @ControllerAdvice 都不会正确工作 —— 症状是"接口 404"或"拦截器不生效"。
 * 这是纯 Java 配置（无 web.xml）时最常见的错误。
 */
public class ServletContainersInitConfig extends AbstractAnnotationConfigDispatcherServletInitializer {

    /**
     * 根容器配置类。
     * <p>只放"非 Web 层"的配置：{@link SpringConfig}
     */
    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class[]{SpringConfig.class};
    }

    /**
     * SpringMVC 子容器配置类。
     * <p>只放 Web 层配置：{@link SpringMvcConfig}
     */
    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class[]{SpringMvcConfig.class};
    }

    /**
     * DispatcherServlet 的映射路径。
     *
     * <p>用 {@code "/"} 而不是 {@code "*.do"} 或 {@code "/api/*"}：
     * <ul>
     *   <li>{@code "/"}：默认 Servlet 映射，接管所有未被其他 Servlet 处理的请求，
     *       是 REST 风格前后端分离项目的标准做法。</li>
     *   <li>{@code "*.do"}：传统 Struts 风格，URL 里带后缀不美观，且与 REST 语义不符。</li>
     *   <li>{@code "/api/*"}：会导致 DispatcherServlet 只处理 /api 开头的请求，
     *       虽然本项目 URL 确实都是 /api/v1 开头，但一旦将来加静态资源或错误页就受限。</li>
     * </ul>
     */
    @Override
    protected String[] getServletMappings() {
        return new String[]{"/"};
    }

    /**
     * 注册字符编码过滤器。
     *
     * <p>⚠️ 中文乱码的第一道防线。虽然：
     * <ul>
     *   <li>数据库连接串已带 {@code characterEncoding=utf8}</li>
     *   <li>Tomcat 9 默认 URI 编码是 UTF-8</li>
     *   <li>响应头由 Jackson 配置里的 charset=UTF-8 保证</li>
     * </ul>
     * 但**请求体**（POST 的 JSON）的解码是由容器按请求头或默认编码决定的，
     * 不加这个过滤器，在没有明确 charset 的请求上可能用 ISO-8859-1 解码，中文变问号。
     *
     * <p>{@code setForceEncoding(true)} 是关键：它同时强制请求和响应的编码，
     * 避免只设了 request 编码而 response 仍用平台默认编码的情况。
     */
    @Override
    protected Filter[] getServletFilters() {
        CharacterEncodingFilter encodingFilter = new CharacterEncodingFilter();
        encodingFilter.setEncoding("UTF-8");
        encodingFilter.setForceEncoding(true);
        return new Filter[]{encodingFilter};
    }
}
