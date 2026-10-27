package com.lab.cms.task;

import com.lab.cms.service.ArticleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排期发布定时任务（S13）。
 *
 * <h3>为什么用定时任务（方案 A），而不是"访问时惰性触发"（方案 B）</h3>
 * <p>手册给了两个方案，本项目选 <b>A（{@code @Scheduled} 定时扫描）</b>。两者的差别不在难度，
 * 而在<b>"到点"这件事由谁保证</b>：
 * <ul>
 *   <li><b>到点是否真发布</b>：A 与有没有人访问无关；B 没人访问就永远不发 ——
 *       一篇"排到明早 9 点"的稿子可能到中午才被第一个访客"顺手"发出去。</li>
 *   <li><b>发布时间是否可信</b>：A 的 {@code publish_time} ≈ 排期时间（差 ≤ 1 分钟）；
 *       B 取决于访问时间，"实际发布时间"这个字段失去意义。</li>
 *   <li><b>演示效果</b>：A 可以当场排到 2 分钟后，让评审看着它自己变成"已发布"；
 *       B 必须先排期、再手动点一次列表页才可能触发。</li>
 *   <li><b>代价</b>：A 多一个调度线程 + 每分钟一次空查询；B 零额外资源，
 *       但把逻辑藏在读路径里。</li>
 * </ul>
 * <p>对"课程项目 / 单机 / 演示为主"这个场景，B 的省资源优势基本无意义
 * （每分钟一条走索引的简单查询，代价可以忽略），
 * 而 A 换来的是<b>语义正确</b>：排期发布之所以叫"排期"，
 * 就是因为它必须独立于用户访问发生。
 * <p>⚠️ B 还有个隐蔽的问题：把它塞进"查列表"的路径里，
 * 会让一次读操作产生写副作用，与项目"查询接口不写数据、不记操作日志"的约定直接冲突
 * —— 一个 GET 请求可能在事务里改掉别人的文章状态，排查时分不清是"谁"发的。
 *
 * <h3>⚠️ 为什么这个类能被 {@code @Scheduled} 驱动</h3>
 * <p>{@code @EnableScheduling} 加在 {@code SpringConfig}（根容器）上，
 * 本类是不带 {@code @Controller} / {@code @EnableWebMvc} / {@code @Aspect} 的普通
 * {@code @Component}，正好被根容器扫描到 —— 两者在同一个容器里，注解才会被处理。
 * <p>若把 {@code @EnableScheduling} 加到 {@code SpringMvcConfig}（子容器）上，
 * 则只有子容器里的 Bean 会被注册；本类在根容器里，任务<b>永远不会跑</b>，
 * 而且<b>没有任何报错</b>（静默失效 —— 项目里已为这类问题踩过多次，
 * 唯一判据是启动日志里有没有本类的 {@code 排期扫描} 输出）。
 *
 * <h3>为什么要分成"扫描"和"逐篇发布"两段</h3>
 * <p>扫描（{@link ArticleService#findDueScheduledArticleIds}）只做一次查询，<b>不开事务</b>；
 * 发布逐篇调用 {@link ArticleService#publishScheduled(Long)}，<b>每篇一个独立事务</b>。
 * <p>⚠️ 如果图省事写成"一个事务里循环发布 200 篇"，
 * 那么第 199 篇失败会把前 198 篇已经成功的发布<b>全部回滚</b> ——
 * 用户看到的是"排期时间过了却一篇都没发"。
 * <p>这里能拿到独立事务，靠的是 {@code articleService} 是<b>接口的代理对象</b>
 * （Spring 注入接口时会自动给实现类套上 AOP 代理），
 * 所以每次调用都真正进入 {@code @Transactional} 的代理链。
 */
@Component
public class ArticlePublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArticlePublishScheduler.class);

    /** ⚠️ 注入的是接口，Spring 会自动给实现类套事务代理；不要改成注入 Impl 类再 new。 */
    @Autowired
    private ArticleService articleService;

    /**
     * 每分钟第 0 秒扫一次，把到点的排期文章发出去。
     *
     * <h3>为什么是每分钟一次（cron {@code 0 * * * * ?}）</h3>
     * <p>与"排期时间至少晚于当前时间 1 分钟"这条校验配套：
     * 分钟级扫描 + 分钟级最小提前量 = 排期后最多等 1 分钟就会发布，
     * 这个延迟在演示与验收中是可接受的，且不产生持续的后台负载。
     *
     * <h3>⚠️ 不会出现"两次扫描重叠"</h3>
     * <p>Spring 默认的调度线程池大小为 <b>1</b>。本任务跑得慢时（比如数据库卡住），
     * 下一次触发会<b>排队等待</b>而不是并发执行 —— 所以本类
     * <b>不需要</b>额外的 {@code AtomicBoolean} 重入保护。
     * <p>若将来把线程池调大（或加 {@code @Async}），这个前提就没了，
     * 那时必须补"上一轮还没跑完就跳过"的判断。
     *
     * <h3>⚠️ 异常必须在本方法内消化</h3>
     * <p>{@code @Scheduled} 方法抛出的异常不会传到任何 HTTP 响应里，
     * 只会被 Spring 记一条 ERROR，然后<b>静默地不再影响</b>下一次调度
     * （不会像定时器那样直接停掉），但错误日志会丢现场细节。
     * 因此这里对"扫描失败"与"单篇失败"分别 catch，各自带上可定位的上下文
     * （扫描时刻 / articleId），并且<b>绝不</b>让异常穿出本方法。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void publishScheduledArticles() {
        LocalDateTime scanTime = LocalDateTime.now();

        List<Long> dueIds;
        try {
            dueIds = articleService.findDueScheduledArticleIds(scanTime);
        } catch (Exception e) {
            // 扫描本身就失败了（如数据库不可用）：本轮放弃，下一分钟再试
            log.error("排期扫描失败，本轮跳过 | 扫描时刻={}", scanTime, e);
            return;
        }

        if (dueIds.isEmpty()) {
            // 空扫描也打日志：这是"定时任务真的在跑"的唯一证据。
            // ⚠️ 若怀疑任务没生效，第一件事就是看这行有没有按分钟出现 ——
            // 而不是去读代码（切面/调度这类静默失效，代码永远看着是对的）。
            log.info("排期扫描：无到点文章 | 扫描时刻={}", scanTime);
            return;
        }

        log.info("排期扫描开始 | 扫描时刻={} | 到点文章数={} | articleIds={}",
                scanTime, dueIds.size(), dueIds);

        int published = 0;
        int skipped = 0;
        int failed = 0;
        for (Long id : dueIds) {
            try {
                if (articleService.publishScheduled(id)) {
                    published++;
                } else {
                    // 返回 false = "本轮无需/无法发布"（状态已变、未到点、文章没了、
                    // 乐观锁冲突），原因已经由 Service 打了 WARN，这里只计数。
                    skipped++;
                }
            } catch (Exception e) {
                // 真正的意外（不是"跳过"）：记下来，继续处理下一篇。
                // ⚠️ 注意这里不能把异常继续往外抛 —— 那会中断整轮，
                // 让后面本来能发布的文章被"前面一篇的故障"连累。
                failed++;
                log.error("排期发布异常，跳过本篇继续 | articleId={}", id, e);
            }
        }

        log.info("排期扫描结束 | 扫描时刻={} | 到点={} 已发布={} 跳过={} 失败={}",
                scanTime, dueIds.size(), published, skipped, failed);
    }
}
