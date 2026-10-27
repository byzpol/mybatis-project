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
 *   │  SchedulingConfig：定时任务开关（S13）     │
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
     * <p>只放"非 Web 层"的配置：{@link SpringConfig}（业务/数据/事务/切面）
     * 与 {@link SchedulingConfig}（定时任务开关，S13）。
     *
     * <h3>⚠️ 为什么 {@code SchedulingConfig} 要写在这里，而不是让 {@code SpringConfig} 扫描到</h3>
     * <p>本方法<b>只有真实 Web 容器（Tomcat）启动时才会被执行</b>，
     * 而单元测试是通过 {@code @ContextConfiguration(classes = SpringConfig.class)}
     * 直接建根容器的 —— 不会经过本类。
     * <p>把"开启定时调度"放在这里，就得到了一条清晰的分界：
     * <b>生产环境开启调度，测试环境不开启</b>，于是测试不会在后台改数据库。
     * 详细原因见 {@link SchedulingConfig} 的类注释。
     */
    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class[]{SpringConfig.class, SchedulingConfig.class};
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

    /**
     * 覆写 DispatcherServlet 的创建过程 —— 本项目只用来开一个开关：{@code throwExceptionIfNoHandlerFound}。
     *
     * <h3>⚠️ 为什么必须开这个开关（S08 主干自测第 2 项发现的真实问题）</h3>
     *
     * <p><b>现象</b>：访问一个不存在的路径（如 {@code /api/v1/nonexistent}），
     * 返回的是 <b>Tomcat 的 HTML 错误页</b>（那个"HTTP状态 404 - 未找到"的白底页面），
     * 而不是项目统一的 {@code {"code":404,"message":"请求的资源不存在",...}} 结构。
     * 前端拦截器按 JSON 解析时会直接抛异常。
     *
     * <p><b>根因</b>：SpringMVC 的 {@code DispatcherServlet} 有一个属性
     * {@code throwExceptionIfNoHandlerFound}，**默认值是 {@code false}**。
     * 关闭时，找不到 Handler 的情况不会抛 {@code NoHandlerFoundException}，
     * 而是直接让容器（Tomcat）返回默认 404 页面 —— 于是
     * {@code GlobalExceptionHandler} 里那个 {@code handleNoHandlerFound} 方法
     * <b>写了也永远不执行</b>（一个典型的"代码看起来对、实际是死代码"陷阱）。
     *
     * <p><b>修复</b>：设为 {@code true}，让"找不到 Handler"走异常通道，
     * 被全局异常处理器接管，返回统一 JSON。
     *
     * <h3>⚠️⚠️ 这里有一个必须记下的 API 事实（实测纠正过一个幻觉）</h3>
     * <p>最初我打算通过 {@code ServletRegistration.setInitParameter(...)} 传参给 DispatcherServlet，
     * 并引用了常量 {@code DispatcherServlet.THROW_EXCEPTION_IF_NO_HANDLER_FOUND}。
     * <b>这个常量在 Spring 5.3 里根本不存在</b>（{@code javap} 实测确认）：
     * <pre>
     *   javap -cp spring-webmvc-5.3.31.jar org.springframework.web.servlet.DispatcherServlet
     *   → 常量清单里没有 THROW_EXCEPTION_IF_NO_HANDLER_FOUND
     *   → 但有方法：public void setThrowExceptionIfNoHandlerFound(boolean)
     * </pre>
     * 更关键的是：<b>这个属性并不会从 Servlet init-param 里读取</b>
     * （Spring Boot 是在 {@code DispatcherServletRegistrationBean} 里主动调 setter 设的）。
     * 所以在纯 SpringMVC + Java Config 下，唯一正确的做法是
     * <b>在创建 DispatcherServlet 时直接调 setter</b> —— 也就是覆写本方法。
     *
     * <p>如果把名字猜成 init-param 字符串（如 {@code registration.setInitParameter("throwExceptionIfNoHandlerFound","true")}），
     * 编译能过、启动也不报错，但开关<b>根本不会生效</b> —— 又一个静默失效。
     *
     * <p>⚠️ 副作用评估：开启后，静态资源 404 也会走异常通道（本项目没有静态资源，
     * DispatcherServlet 映射到 "/"，所有请求都进 SpringMVC，所以影响是中性的）。
     */
    @Override
    protected org.springframework.web.servlet.FrameworkServlet createDispatcherServlet(
            org.springframework.web.context.WebApplicationContext servletAppContext) {
        org.springframework.web.servlet.DispatcherServlet dispatcherServlet =
                new org.springframework.web.servlet.DispatcherServlet(servletAppContext);
        // 让"找不到 Handler"抛 NoHandlerFoundException，交给 GlobalExceptionHandler 统一包装成 JSON
        dispatcherServlet.setThrowExceptionIfNoHandlerFound(true);
        return dispatcherServlet;
    }
}
