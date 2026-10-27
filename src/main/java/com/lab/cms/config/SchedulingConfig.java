package com.lab.cms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关配置（S13）。
 *
 * <h3>⚠️ 为什么 {@code @EnableScheduling} <b>不</b>直接写在 {@link SpringConfig} 上</h3>
 * <p>这是本步踩到的一个真实问题，不是"为了多建一个类"。
 *
 * <p>{@code @EnableScheduling} 会注册 {@code ScheduledAnnotationBeanPostProcessor}，
 * 它让容器里的 {@code @Scheduled} 方法<b>真的开始跑</b>。而单元测试用的根容器
 * （{@code @ContextConfiguration(classes = SpringConfig.class)}）
 * 与生产用的是<b>同一个</b> {@code SpringConfig} —— 于是：
 * <ul>
 *   <li>{@code mvn test} 期间后台线程每分钟扫一次排期文章，<b>真的会改数据库</b>：
 *       本机种子里那篇"定时待发"的文章（{@code id=14}，排期时间是初始化脚本执行时的
 *       {@code NOW() + 5 分钟}，早就是过去时间）会被自动化测试顺手发布掉；</li>
 *   <li>更糟的是<b>偶发性</b>：测试自己造的"到点文章"如果刚好跨过整分钟被后台发掉，
 *       正在跑的断言就会以"状态怎么变成已发布了"的形式随机失败 ——
 *       这类失败复现不了，是最难查的一种。</li>
 * </ul>
 * <p>也就是说，把开关放在 {@code SpringConfig} 上等于<b>让测试环境与生产环境行为不一致，
 * 而测试还以为自己在测生产的那一套</b>。
 *
 * <h3>本类的做法</h3>
 * <p>把"是否开启调度"这件事从"业务配置"里拆出来单独放一个配置类，
 * 并由 {@link ServletContainersInitConfig}（<b>只有真实 Web 环境才会被容器加载</b>）
 * 显式注册：
 * <pre>
 *   getRootConfigClasses() → { SpringConfig.class, SchedulingConfig.class }
 * </pre>
 * <p>于是：
 * <ul>
 *   <li><b>生产</b>：两个配置类都加载 → 调度生效 → 排期文章到点自动发布；</li>
 *   <li><b>测试</b>：只加载 {@code SpringConfig} → 没有调度处理器 →
 *       {@code ArticlePublishScheduler} 只是一个没人调用的普通 Bean，
 *       后台不会自己动数据。</li>
 * </ul>
 *
 * <h3>⚠️ 因此本类必须在 {@code SpringConfig} 的组件扫描里被排除</h3>
 * <p>{@link SpringConfig} 扫的是整个 {@code com.lab.cms} 包，
 * 而 {@code @Configuration} 本身也是 {@code @Component} ——
 * 若不做排除，测试加载 {@code SpringConfig} 时会把本类<b>一起扫进来</b>，
 * 调度又被打开了，前面的拆分就白做了。
 * <p>排除方式与该项目已有的做法一致（{@code SpringConfig} 已经把
 * {@code @EnableWebMvc} 标注的类排除掉，理由同样是"它属于另一层的配置"）。
 *
 * <h3>为什么不需要 {@code @EnableAsync}</h3>
 * <p>本项目的定时任务是"每分钟扫一次、每次几百毫秒"的短任务，
 * 用 Spring 自带的单线程调度器即可；不需要异步执行，也就不需要开
 * {@code @EnableAsync}（开了反而会因为线程池切换导致
 * {@code AuditMetaObjectHandler} 依赖的 ThreadLocal 取不到登录用户 ——
 * 项目里已记录这个前提）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
