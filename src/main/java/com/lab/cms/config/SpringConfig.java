package com.lab.cms.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Spring 根容器配置类。
 *
 * <p>职责：装配 Service、Mapper、数据源、事务、AOP 切面 —— 即"非 Web 层"的全部 Bean。
 * <p>Web 层（@Controller）由 {@link SpringMvcConfig} 所在的子容器负责，
 * 两者通过 {@link ServletContainersInitConfig} 建立父子关系。
 *
 * <h3>为什么要把 @Controller 排除掉</h3>
 * <p>若根容器也扫描 @Controller，同一个 Controller 会在父容器和子容器中各创建一份实例。
 * 子容器（DispatcherServlet）优先使用自己容器里的那份，父容器那份成为"死对象"，
 * 但它仍会参与 AOP 代理创建，导致：请求进入时生效的切面与注入的依赖可能来自两个不同实例。
 * 典型症状是 @Transactional 在 Controller 上"看起来没生效"、或注入的字段为 null。
 * 因此必须显式排除，让 Controller 只在 SpringMVC 子容器中注册。
 *
 * <h3>为什么需要 @EnableAspectJAutoProxy</h3>
 * <p>本项目的 {@code PermissionAspect}（垂直越权校验）与 {@code WebLogAspect}（操作日志）
 * 都是基于 AspectJ 注解的切面。虽然 spring-aop 提供了自动代理基础设施，但基于注解的切面
 * 需要一个显式的开关来注册 AnnotationAwareAspectJAutoProxyCreator。
 * 不写这行注解，切面就是一个"普通的、不会被织入的 Bean"，业务代码照常跑但切面从不执行
 * —— 属于静默失效，最容易被忽略的一类 bug。
 *
 * <p>注意：{@code proxyTargetClass} 保持默认（false），即优先用 JDK 动态代理（基于接口）。
 * 本项目 Service 全部是"接口 + 实现类"结构，天然满足 JDK 代理要求，
 * 且 JDK 代理语义更清晰（注入的类型就是接口，不会意外拿到实现类）。
 * 如果将来某个类没有接口却又需要被切面拦截，Spring 会自动退化为 CGLIB，无需手工干预。
 *
 * <h3>⚠️ 本类的 @EnableAspectJAutoProxy 管不到 Controller（重要，易漏）</h3>
 * <p>{@code @EnableAspectJAutoProxy} 注册的自动代理创建器是<b>容器级</b>的，
 * 只作用于<b>本容器内</b>的 Bean。而 {@code @Controller} 已被本类排除、注册在
 * {@link SpringMvcConfig} 子容器里 —— 因此加在 Controller 方法上的
 * {@code @OperLog} / {@code @RequirePermission} <b>在本类开启 AOP 是无效的</b>。
 *
 * <p>这就是为什么 {@link SpringMvcConfig} 上<b>也</b>写了 {@code @EnableAspectJAutoProxy}
 * 并 {@code @Import} 了两个切面。两处都开是刻意的，不是重复：
 * <ul>
 *   <li>根容器（本类）：服务于未来可能加在 Service 方法上的 {@code @OperLog}（如 S13 定时发布）。</li>
 *   <li>子容器（SpringMvcConfig）：服务于加在 Controller 方法上的两个注解 —— 当前的主力场景。</li>
 * </ul>
 *
 * <p>本类排除 {@code @Aspect} 标注的类，避免同一份无状态切面在两个容器里各注册一次
 * （虽无功能危害，但会让"哪个实例在生效"变得难以判断，徒增排查成本）。
 */
@Configuration
// 扫描本项目全部包，但排除 @Controller（交给 SpringMvcConfig）
@ComponentScan(
        basePackages = "com.lab.cms",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = Controller.class
                ),
                // @EnableWebMvc 标注的配置类也属于 Web 层，排除以免被根容器重复处理
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = EnableWebMvc.class
                ),
                // 切面统一由 SpringMvcConfig 导入到子容器（原因见类注释末段）
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = org.aspectj.lang.annotation.Aspect.class
                )
        }
)
@PropertySource("classpath:db.properties")
@EnableTransactionManagement
@EnableAspectJAutoProxy
public class SpringConfig {
}
