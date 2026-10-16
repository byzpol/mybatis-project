package com.lab.cms.controller;

import com.lab.cms.common.result.PageQuery;
import com.lab.cms.common.result.Result;
import com.lab.cms.service.TrunkSelfTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主干健康检查与自测接口。
 *
 * <h3>⚠️ 本接口属于「主干自测」用途</h3>
 * <p>它不是业务接口，存在的理由是：在业务功能尚未开工（S09 起）时，
 * 能有一个<b>不依赖任何业务表</b>的入口，把主干七项能力逐条验一遍。
 *
 * <p>放行路径已在 {@code SpringMvcConfig} 里配置：只有 {@code /api/v1/health/ping}
 * 在白名单里（探活工具需要在无登录态时访问）。其余自测端点<b>仍需登录</b>——
 * 这是刻意的：它们能触发数据库写操作，不该对公网匿名开放。
 *
 * <h3>为什么拆成 {@code /ping} 与其它自测端点</h3>
 * <p>Ping 是"探活"，语义是"服务活着吗"，必须极轻（不查库、不鉴权）。
 * 其余端点会触发异常或写库，属于"体检"，语义不同。
 * 同理，手册只要求 {@code /ping} 放行，其余端点保持登录态要求。
 *
 * <h3>⚠️ 这里为什么不直接用 Mapper</h3>
 * <p>数据层的三个自测项（审计填充 / 逻辑删除 / 防全表更新）需要写库，
 * 而 {@code Controller} <b>禁止直连 Mapper</b>（《企业开发规范清单》1-x 条：单向向下调用）。
 * 因此这三个动作封装在 {@link TrunkSelfTestService} 里，由本类调用 ——
 * 自测代码也遵守分层纪律，否则"自测"本身就示范了错误写法。
 *
 * <h3>生产环境注意</h3>
 * <p>本类所有端点都应在生产部署时移除或加访问限制（会泄漏环境信息、可触发写操作）。
 * 课程项目保留它们作为"答辩演示 + 联调排查工具"。
 */
@RestController
@RequestMapping("/api/v1/health")
@Validated
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @Autowired
    private TrunkSelfTestService trunkSelfTestService;

    /**
     * 探活：验证 DispatcherServlet 链路、Jackson 序列化、统一返回体、编码过滤器是否都通了。
     *
     * <p>这是全项目最"廉价"的一个接口 —— 不查库、不鉴权、不写日志，
     * 因此可以放心给探活工具高频调用。
     *
     * <p>⚠️ 手册写的是 {@code Result.ok("pong")}，期望响应
     * {@code {"code":200,"message":"success","data":"pong"}}。
     * <b>实际项目里没有 {@code Result.ok()} 这个方法，成功方法是
     * {@link Result#success(Object)}，且固定 message 为「操作成功」</b>（见 {@code Result} 类注释：
     * message 用中文是为了"前端可直出"，不用英文 "success"）。
     * 所以实际响应是 {@code {"code":200,"message":"操作成功","data":"pong","timestamp":...}}。
     * 这是"手册描述了另一套命名"的情况，以代码实际约定为准。
     *
     * <p>放行配置：{@code SpringMvcConfig#addInterceptors} 的白名单里有
     * {@code /api/v1/health/ping}。
     */
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }

    /**
     * 分页参数归一化自测：把"传进来的原始值"与"归一化后的值"一并返回，便于对比。
     *
     * <p>用于验证两项：
     * <ul>
     *   <li>{@code size=9999} 是否被截断为 {@link PageQuery#MAX_SIZE}（100）</li>
     *   <li>{@code sortField="create_time; DROP TABLE cms_article"} 这类注入样本
     *       是否被拒绝（抛参数错误）或回落默认</li>
     * </ul>
     *
     * <p>⚠️ 本方法<b>不碰数据库</b>，只调 {@link PageQuery} 上的纯逻辑方法，
     * 所以放在 Controller 里不违反分层纪律（PageQuery 是 common 层的参数对象）。
     *
     * <p>注入样本被拒时，`{@code BusinessException}` 会被全局异常处理器转成
     * {@code {"code":400,...}} —— 也就是说本端点<b>成功时返回对比结果、
     * 失败时返回 400 业务码</b>，两种响应都是"预期内"的。
     */
    @GetMapping("/page-echo")
    public Result<Map<String, Object>> pageEcho(PageQuery query) {
        Map<String, Object> report = new LinkedHashMap<>();
        // 归一化前的原始值（记录下来才能证明是"归一化"起了作用）
        report.put("rawPage", query.getPage());
        report.put("rawSize", query.getSize());
        report.put("rawSortField", query.getSortField());
        report.put("rawSortOrder", query.getSortOrder());

        // normalize() 修非法 page/size/sortOrder（不碰 sortField，见其 Javadoc）
        query.normalize();
        report.put("normalizedPage", query.getPage());
        report.put("normalizedSize", query.getSize());
        report.put("normalizedSortOrder", query.getSortOrder());

        // checkSortField() 用正则白名单校验排序字段；非法则抛 BusinessException(400)
        query.checkSortField();
        report.put("acceptedSortField", query.getSortField());
        report.put("maxSize", PageQuery.MAX_SIZE);

        return Result.success("分页参数归一化完成", report);
    }

    /**
     * 必填请求参数自测：不传 {@code name} 时应由全局异常处理器返回 400 且指明缺了哪个参数。
     *
     * <p>期望响应：{@code {"code":400,"message":"缺少必需参数：name"}}。
     * <p>这条验证的是 {@code MissingServletRequestParameterException} 分支 ——
     * 若不处理，用户看到的会是 Spring 的英文报错页。
     */
    @GetMapping("/require-param")
    public Result<String> requireParam(@RequestParam("name") String name) {
        return Result.success("收到参数：" + name);
    }

    /**
     * 空指针自测：故意抛 NPE，验证兜底处理器。
     *
     * <p>期望响应：HTTP 500 + {@code {"code":500,"message":"系统内部错误，请联系管理员"}}，
     * 且响应体里<b>看不到任何类名、堆栈、文件路径</b>。
     *
     * <p>⚠️ 这条用例的价值在于"证明不泄漏"：真实原因只进 {@code logs/cms-error.log}，
     * 客户端拿到的是一句人话。若哪天有人为了"方便排查"把 {@code e.getMessage()}
     * 拼进返回体，这条自测会立刻暴露（响应里会多出 "java.lang.NullPointerException"）。
     */
    @GetMapping("/boom")
    public Result<String> boom() {
        // 刻意用一条"看起来无害"的链式调用制造 NPE，而不是 throw new NullPointerException()。
        // 理由：手动 throw 的 NPE 在 JDK 15+ 会被 JIT 优化掉堆栈信息（省掉构造开销），
        // 真 NPE 才有完整信息 —— 这样测出来的日志才是真实故障时的样子。
        String deliberatelyNull = null;
        return Result.success(String.valueOf(deliberatelyNull.length()));
    }

    /**
     * 数据层往返自测：一次调用依次验证三项数据能力，返回结构化报告。
     *
     * <p>三项分别是：
     * <ol>
     *   <li><b>审计字段自动填充</b>：插入一条记录后，{@code create_by/create_time/update_by/update_time}
     *       四个字段应全部非空（由 {@code AuditMetaObjectHandler} 填充）</li>
     *   <li><b>防全表更新</b>：执行一个不带 WHERE 的 update，
     *       应被 {@code BlockAttackInnerInterceptor} 拦截抛异常，
     *       而不是真的把全表更新掉</li>
     *   <li><b>逻辑删除</b>：{@code deleteById} 后 {@code selectById} 应返回 {@code null}
     *       （行还在库里，只是 {@code deleted} 变成了 1）</li>
     * </ol>
     *
     * <p>为什么合成一个端点而不是三个：这三项天然是一条链（插入 → 拿 id → 更新 → 按 id 删），
     * 合成一次调用能给出<b>同一时刻、同一条数据</b>的完整证据，也避免三次落库。
     *
     * <p>写的是 {@code sys_oper_log}（操作日志表）而不是业务表 —— 日志表本身就是"可追加记录"的，
     * 拿它做自测不会污染业务数据。
     */
    @PostMapping("/db-roundtrip")
    public Result<Map<String, Object>> dbRoundtrip() {
        log.info("主干自测：开始数据层往返验证");
        return Result.success("数据层往返验证完成", trunkSelfTestService.dbRoundTrip());
    }
}
