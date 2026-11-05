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
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
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
     * 【S19 新增 · 主干加方法，未改任何既有签名】注册 Web 层的
     * {@code String → LocalDateTime} 转换器。
     *
     * <h3>不加这个会怎样（S19 实测发现的真实缺陷）</h3>
     * <p>{@code GET /api/v1/articles?beginTime=2026-10-14&endTime=2026-10-20}
     * 会直接返回 <b>400</b>：
     * <pre>
     * 参数校验失败：beginTime Failed to convert property value of type 'java.lang.String'
     * to required type 'java.time.LocalDateTime' for value [2016-10-15 20:34:58]
     * </pre>
     *
     * <p>原因是项目里有<b>两条互不相干的"字符串 → 时间"转换链</b>：
     * <ul>
     *   <li>{@code @RequestBody} 的 JSON → {@link JacksonConfig} 的
     *       {@code LocalDateTimeDeserializer}，只认 {@code yyyy-MM-dd HH:mm:ss}；</li>
     *   <li>query string 绑定 DTO 属性 → Spring {@code DateTimeFormatterRegistrar} 的默认值，
     *       只认 ISO-8601 的 {@code T} 分隔写法。</li>
     * </ul>
     * <p>同一个前端组件在两处得到不同结果：{@code ArticleEdit.vue} 的排期时间走 JSON 能用，
     * 而 {@code ArticleList.vue} 的创建时间范围走 query string <b>一用就 400</b>。
     *
     * <p>⚠️ 这个缺陷此前没有任何自动化测试能发现：S17 的
     * {@code ArticlePageQueryServiceTest} 直接 {@code new CmsArticleQueryDTO()} 设值，
     * 绕过了 Web 绑定；{@code PageQueryTest} 更是纯单元测试。
     * <b>S19 之前全仓 MockMvc 出现 0 次</b>。
     *
     * <h3>为什么注册到 {@code addFormatters} 而不是给 DTO 字段加 {@code @DateTimeFormat}</h3>
     * <p>{@code @DateTimeFormat} 要逐个字段写，而且只解决"字段级"问题 ——
     * 将来任何人新增一个 query 上的时间参数，都会重新踩一遍同一个坑。
     * 注册到全局转换服务则一次性解决，且与"项目只有一种日期书写法"这条口径一致。
     *
     * <p>⚠️ 顺序说明：{@code addFormatters} 在 {@code mvcConversionService()} 初始化时被调用，
     * 此时 {@code GenericConversionService.addConverter} 会<b>覆盖</b>同
     * {@code ConvertiblePair} 的已注册转换器 —— 这正是我们要的效果（默认的 ISO 解析被换掉）。
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToLocalDateTimeConverter());
    }

    /**
     * 【F01 新增 · 主干加方法，未改任何既有签名】放行前端构建产物。
     *
     * <h3>为什么必须加这个方法</h3>
     * <p>三个已有的配置叠在一起，会导致"war 打包后前端页面打不开"：
     * <ol>
     *   <li>{@code ServletContainersInitConfig#getServletMappings()} 返回 {@code {"/"}} ——
     *       DispatcherServlet <b>顶替了 Tomcat 的 DefaultServlet</b>；</li>
     *   <li>本类上的 {@code @EnableWebMvc} 会<b>关闭</b>"未匹配请求转交默认 Servlet"的机制
     *       （Spring Boot 里靠自动配置补上，纯 SSM 必须自己写）；</li>
     *   <li>于是 {@code /cms-mis/index.html}、{@code /cms-mis/assets/*.js} 这些真实存在的文件
     *       在 DispatcherServlet 里找不到映射 → 被判为"无 handler"。</li>
     * </ol>
     * <p>又因为 S08 打开了 {@code throwExceptionIfNoHandlerFound}，
     * 最后落到 {@code GlobalExceptionHandler} 返回一个 <b>404 的 JSON</b> ——
     * 浏览器拿到的不是网页而是 {@code {"code":404,...}}，页面彻底白屏。
     *
     * <h3>为什么不用 configureDefaultServletHandling().enable()</h3>
     * <p>那是 XML 时代 {@code <mvc:default-servlet-handler/>} 的等价物，
     * 会把<b>所有</b>未匹配请求都转给默认 Servlet。而默认 Servlet 对不存在的路径
     * 返回的是 Tomcat 的 HTML 错误页 —— 正好把 S08 刚修好的"统一 404 结果"
     * 又打回原形（转发后 SpringMVC 认为"handler 找到了"，{@code throwExceptionIfNoHandlerFound} 不再触发）。
     * <p>所以这里<b>只放行确实存在的三类静态资源</b>，其余路径照旧走统一 404，
     * 两个目标不冲突。
     *
     * <h3>为什么 index.html 和 assets 的缓存策略不一样</h3>
     * <p>· {@code index.html} / {@code favicon.ico}：{@code setCachePeriod(0)} 不缓存。
     * Vite 每次构建都会换掉 assets 里的文件名，index.html 是"指路牌"，
     * 一旦被浏览器缓存，发版后用户会拿着旧壳子去请求已被 {@code emptyOutDir} 删掉的老 chunk。
     * <p>· {@code /assets/**}：文件名自带内容哈希（如 {@code index-D2gkF2JF.js}），
     * 内容变了名字必变，因此可以放心强缓存 30 天。
     *
     * <h3>⚠️ 为什么 location 必须写成 {@code "/assets/"} 而不是 {@code "/"}</h3>
     * <p>这是实测踩出来的：一开始两个 handler 都写 {@code addResourceLocations("/")}，
     * 结果 {@code /index.html} 能打开，<b>所有 /assets 下的 js/css 却一律 404</b>。
     *
     * <p>原因在 {@code ResourceHttpRequestHandler} 解析资源的算法：
     * 它先按 URL pattern 把请求路径"切掉"匹配到的部分，再把剩下的相对路径拼到 location 上。
     * <ul>
     *   <li>{@code /assets/index-x.js} 匹配 pattern {@code /assets/**} → 剩余 {@code index-x.js}；
     *       拼到 location {@code "/"} 上时，{@code StringUtils.applyRelativePath("/", "index-x.js")}
     *       的结果是 {@code "/index-x.js"} —— <b>前缀 assets/ 丢了</b>，
     *       于是去 web 根目录找 {@code index-x.js}，自然找不到 → 404。</li>
     *   <li>改成 location {@code "/assets/"} 后：{@code applyRelativePath("/assets/", "index-x.js")}
     *       = {@code "/assets/index-x.js"} ✅。</li>
     * </ul>
     * <p><b>结论：location 的目录层级必须与 URL 前缀对齐。</b>
     *
     * <p>至于 {@code /index.html}：它的 pattern 把整个路径都吃掉了，剩余路径是空串，
     * 拼出来是 web 根目录 {@code "/"}；Tomcat 对 web 根目录会按欢迎页规则返回 {@code index.html}
     * —— 能跑通，但属于"顺带成立"，所以这里把原因写清楚，避免后人误以为 location {@code "/"}
     * 是通用写法又去套用到子目录上。
     *
     * <p>另一条路是直接写 {@code addResourceHandler("/**").addResourceLocations("/")}，
     * 那样算法反而正确（剩余路径带了 {@code assets/}），代价是它会兜住<b>所有</b>未匹配路径，
     * 把 S08 修好的"未知路径返回统一 Result 404"冲掉（转发给默认 Servlet 后变成 Tomcat 的 HTML 错误页）。
     * 两害相权，选择"精确放行 + location 对齐"，保住接口契约。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 入口页：不缓存，否则发版后用户拿着旧壳子去要已被 emptyOutDir 删掉的老 chunk
        registry.addResourceHandler("/index.html")
                .addResourceLocations("/")
                .setCachePeriod(0);

        // 构建产物：文件名自带内容哈希（index-D2gkF2JF.js），内容变了名字必变，可放心长缓存。
        // ⚠️ location 必须写 "/assets/"，与 URL 前缀对齐 —— 见上方类注释。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("/assets/")
                .setCachePeriod(60 * 60 * 24 * 30);
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
                        // S08 主干探活端点：只放行 ping 这一个。
                        // 用精确路径而不是 /api/v1/health/** —— 因为 HealthController 里
                        // 还有能触发异常与写库的自测端点（/boom、/db-roundtrip 等），
                        // 那些不该对匿名请求开放。**放行范围要按最小必要来划**。
                        "/api/v1/health/ping",
                        // 错误页与静态资源（本项目基本不用，但保留以防将来加）
                        "/error",
                        "/favicon.ico"
                );
    }
}
