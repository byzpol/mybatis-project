package com.lab.cms.config;

import com.lab.cms.aspect.PermissionAspect;
import com.lab.cms.aspect.WebLogAspect;
import com.lab.cms.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * SpringMVC 子容器配置类。
 *
 * <p>职责：装配 @Controller、消息转换器、拦截器、参数解析器 —— 即"Web 层"的全部 Bean。
 *
 * <h3>@EnableWebMvc 做了什么</h3>
 * <p>它等价于 XML 时代的 {@code <mvc:annotation-driven/>}，会导入 DelegatingWebMvcConfiguration，
 * 注册：RequestMappingHandlerMapping、RequestMappingHandlerAdapter、
 * ExceptionHandlerExceptionResolver、以及一整套默认 HttpMessageConverter。
 *
 * <p>⚠️ 副作用：@EnableWebMvc 会**关闭** Spring Boot 式的自动配置（本项目无 Spring Boot，
 * 所以这个副作用对我们是中性的）。但它同时意味着：一旦写了这个注解，
 * 任何 MessageConverter 的定制都必须显式写在这里 —— 包括我们需要的 Jackson 时间格式配置。
 *
 * <h3>两类"增强"接口的选择</h3>
 * <p>{@code WebMvcConfigurer} 是增量式接口（只覆写需要的方法），
 * 而 {@code WebMvcConfigurationSupport} 是替换式（会屏蔽默认配置）。
 * 本项目一律用 {@code WebMvcConfigurer}，避免"为了改一件事导致其他默认行为全没了"。
 *
 * <h3>⚠️⚠️ 本类最容易被忽略、也最容易踩的一个坑：双容器下切面为何失效</h3>
 *
 * <p><b>现象</b>：{@code @OperLog} 加了、注解也写对了、编译打包都没问题，
 * 但接口调用后 {@code sys_oper_log} 表里就是没有新记录，日志里连切面的一行输出都没有。
 * {@code @RequirePermission} 同理 —— 明明加了注解，却没有生效。
 *
 * <p><b>根因</b>：本项目是<b>双容器</b>结构（web.xml 时代的父子容器）：
 * <ul>
 *   <li><b>根容器</b>（{@code SpringConfig}）：扫描 {@code com.lab.cms} 但<b>排除</b> {@code @Controller}，
 *       存放 Service / Mapper / 各类基础设施 Bean。</li>
 *   <li><b>子容器</b>（本类）：只扫 {@code @Controller}，存放所有 Controller。</li>
 * </ul>
 *
 * <p>{@code @EnableAspectJAutoProxy} 写在 {@code SpringConfig}（根容器）里，
 * 它注册的 {@code AnnotationAwareAspectJAutoProxyCreator} 是<b>容器级</b>的后置处理器 ——
 * <b>只会给"自己所在容器"里的 Bean 创建 AOP 代理</b>。
 * 而 {@code SystemController} 是子容器的 Bean，根容器的后置处理器根本管不到它，
 * 于是加在 Controller 方法上的 {@code @OperLog} / {@code @RequirePermission} 全部形同虚设。
 *
 * <p>更隐蔽的是：<b>这个 bug 不会报错</b>。没有异常、没有警告，
 * 只是"功能静默失效"。如果只测 admin（他有全部权限），连 {@code @RequirePermission}
 * 失效都看不出来 —— 直到用 author 测越权才发现拦不住。
 *
 * <p><b>修复</b>：本类上加 {@code @EnableAspectJAutoProxy}，
 * 并用 {@code @Import} 把两个切面显式引入子容器。
 * 这样 AOP 后置处理器与切面 Bean 都在子容器，能正常代理 Controller。
 *
 * <p>⚠️ 为什么不干脆"所有 Bean 都扫进一个容器"：那会破坏 Web 层的父子隔离习惯，
 * 且本项目的手册已按双容器约定写完，改动成本大于收益。保留双容器 +
 * 在本类开启 AOP 是代价最小的正确解。
 *
 * <p>⚠️ 注意：切面类在<b>两个容器里各有一份实例</b>（根容器扫到一份、子容器 Import 一份）。
 * 这不是问题 —— 切面本身无状态（只依赖注入的 ObjectMapper 与 Mapper），
 * 且实际被调用的是子容器里那一份（因为它才代理了 Controller）。
 * 根容器那份只服务于根容器内 Bean 的注解（本项目没有，等于是空转，无副作用）。
 */
@Configuration
// 只扫描 @Controller 与本配置类所在的 Web 层
@ComponentScan(
        basePackages = "com.lab.cms",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class)
        },
        useDefaultFilters = false
)
@EnableWebMvc
// ⚠️ 见类注释「双容器下切面为何失效」：必须在子容器开启 AOP，并导入切面
@EnableAspectJAutoProxy
@Import({WebLogAspect.class, PermissionAspect.class})
public class SpringMvcConfig implements WebMvcConfigurer {

    /**
     * 登录拦截器。
     * <p>用 @Autowired 注入而不是 new：让拦截器也能享受依赖注入
     * （例如将来拦截器里需要查用户表判断账号是否被禁用）。
     */
    @Autowired
    private LoginInterceptor loginInterceptor;

    /**
     * 注入 Jackson 消息转换器（由 {@link JacksonConfig} 提供）。
     * <p>拿到它之后覆写 {@link #extendMessageConverters} 的第一项，
     * 这样既保留 SpringMVC 的其他默认转换器（如 StringHttpMessageConverter、
     * ByteArrayHttpMessageConverter），又用我们的 Jackson 配置替换掉默认的 JSON 处理器。
     */
    @Autowired
    private MappingJackson2HttpMessageConverter jacksonMessageConverter;

    /**
     * 【关键】在默认转换器列表前插入自定义的 Jackson 转换器。
     *
     * <p>为什么不直接 {@code converters.add(...)} 而是 {@code add(0, ...)}：
     * SpringMVC 按顺序遍历 converters，第一个 "canWrite" 返回 true 的胜出。
     * 默认列表里已经有 MappingJackson2HttpMessageConverter，
     * 如果只是 add 到末尾，永远轮不到我们的自定义配置生效 —— 时间还是会被序列化成数组。
     * 放在索引 0 才能确保它优先被选中。
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, jacksonMessageConverter);
    }

    /**
     * 注册参数解析器：让 {@code @CurrentUser} 注解能把 Session 中的登录用户注入到方法参数。
     *
     * <p>⚠️ 注意解析顺序：必须 add(0, ...)，让自定义解析器优先。
     * 放在末尾时，如果将来引入了别的通用参数解析器（或 SpringMVC 自身扩展了默认解析器），
     * 它们可能先于我们的解析器返回非 null，导致 @CurrentUser 参数拿到的是默认值（null）。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(0, new CurrentUserArgumentResolver());
    }

    /**
     * 注册拦截器。
     *
     * <p>拦截路径设计原则：**白名单手写，黑名单排除**。
     * <p>用 {@code addPathPatterns("/api/**")} 只拦 API（不拦静态资源），
     * 再用 {@code excludePathPatterns} 放过登录、登出等无需鉴权的接口。
     *
     * <p>这样做的安全性优势：新加的接口默认**被拦截**（fail-safe），
     * 而不是默认放过。如果反着写（只拦已知需要鉴权的路径），
     * 新接口一旦忘记加进名单，就是一个未受保护的公开接口 —— 这是安全审计中最常见的漏洞来源。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 认证相关全部放行：
                        //   /login  必须放行，否则无法登录
                        //   /logout 必须放行 —— 登出时 Session 可能已过期，
                        //           若被拦截器拦住返回 401，前端就无法完成"清理本地状态"的流程
                        //   /me     放行 —— 前端刷新页面时用它探活，
                        //           未登录时应返回 code=401 的业务响应（由 Service 抛），
                        //           而不是被拦截器拦成 HTTP 401 + 一层额外判断
                        "/api/v1/auth/**",
                        // 健康检查：探活工具需要在无登录态时也能访问
                        "/api/v1/system/health",
                        // 错误页与静态资源（本项目基本不用，但保留以防将来加）
                        "/error",
                        "/favicon.ico"
                );
    }
}
