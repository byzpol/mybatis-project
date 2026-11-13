package com.lab.cms.controller;

import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.LoginLogQueryDTO;
import com.lab.cms.dto.OperLogQueryDTO;
import com.lab.cms.service.LogService;
import com.lab.cms.vo.LogStatsVO;
import com.lab.cms.vo.LoginLogVO;
import com.lab.cms.vo.OperLogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志查询与统计接口。<b>共 3 个端点，全部是读接口</b>。
 *
 * <h3>⚠️ 全类没有任何 {@code @OperLog}，而且这是必须的</h3>
 * <p>三个端点都是 {@code GET}。项目有一条被 {@code ApiPermissionCoverageTest}
 * 钉住的规则：<b>写接口必须有 {@code @OperLog}，读接口必须没有</b>。
 * 在日志查询接口上记操作日志会形成一个自我放大的循环 ——
 * 每查一次日志就写一条日志，而这些新日志又会被下一次查询读到。
 * 用户只要刷新几次日志页，{@code sys_oper_log} 就会以"每次查询 +1 条"的速度增长，
 * 且增长的内容全是"有人查了日志"，真需要追责的写操作被彻底淹没。
 *
 * <h3>⚠️ 权限码 {@code log:view} 在库表里只授予了管理员</h3>
 * <p>{@code sql/init.sql} 的角色-权限绑定：
 * <pre>
 *   ROLE_AUTHOR(1) : article:view/create/update/submit/withdraw/delete + category:view
 *   ROLE_EDITOR(2) : 上面 + article:audit + category:manage
 *   ROLE_CHIEF (3) : 上面 + article:manage + article:publish + report:view
 *   ROLE_ADMIN (4) : 全部 20 个（含 log:view）
 * </pre>
 * <p>所以用 {@code author / editor / chief} 调本类任一端点应得到
 * <b>403</b>（{@code PermissionAspect} 拦下），只有 {@code admin} 能查。
 * 这是刻意的：日志含 IP、请求参数、失败原因，是攻击者最想要的信息，
 * 不该对内容运营角色开放（对比：{@code report:view} 给了主编，因为报表只是聚合数字）。
 * <p>这条"谁能查日志"的边界会被真机联调实测覆盖，见实测记录。
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController extends BaseController {

    @Autowired
    private LogService logService;

    /**
     * 操作日志分页检索。{@code GET /api/v1/logs/oper}，权限 {@code log:view}。
     *
     * <p>参数：{@code page / size / sortField / sortOrder} +
     * {@code module / operName / operType / status / beginTime / endTime}。
     * <ul>
     *   <li>{@code status} 是 <b>1 成功 / 0 失败</b>（{@code sys_oper_log} 口径）；</li>
     *   <li>时间区间打在 {@code oper_time}（业务时间）上，格式
     *       {@code yyyy-MM-dd HH:mm:ss}（空格分隔，项目全局唯一格式）；</li>
     *   <li>{@code beginTime > endTime} → <b>400</b>，不静默返回空列表。</li>
     * </ul>
     *
     * <p>⚠️ 出参里 {@code requestParam} / {@code responseResult} 被截断到 200 字符
     * （库里最多 2000），{@code userAgent} 截到 120（库里 500）——
     * 保证分页接口的响应大小可控。完整内容可直接查库。
     */
    @GetMapping("/oper")
    @RequirePermission("log:view")
    public Result<PageResult<OperLogVO>> pageOperLogs(OperLogQueryDTO query) {
        return Result.success(logService.pageOperLogs(query));
    }

    /**
     * 登录日志分页检索。{@code GET /api/v1/logs/login}，权限 {@code log:view}。
     *
     * <p>参数：{@code page / size / sortField / sortOrder} +
     * {@code username / loginStatus / beginTime / endTime}。
     *
     * <h3>⚠️ {@code loginStatus} 是 <b>1 成功 / 2 失败</b>，与上面那个接口不同</h3>
     * <p>很多项目会在这里踩坑：把"失败"写成 0（沿用 {@code sys_oper_log} 的口径），
     * 结果是<b>永远查不到失败记录、失败率永远是 0%</b> ——
     * 接口 200、returns 正常，只是数据是错的，值班时最难发现。
     * 本项目在 {@code SysLoginLog} 实体、{@code LoginLogQueryDTO}、
     * {@code LogServiceImpl} 的常量区各写了一遍这个差异。
     *
     * <h3>为什么失败记录也要能查</h3>
     * <p>{@code sys_login_log} 对登录失败<b>也落库</b>（{@code login_status = 2}），
     * 且记录了当时输入的用户名。这是"连续登录失败 ⇒ 账号可能正被爆破"的
     * 唯一数据来源。按"用户名 + 时间区间"查一次失败记录，
     * 就能看出爆破是否在发生（命中的是 {@code idx_status_time} 索引）。
     */
    @GetMapping("/login")
    @RequirePermission("log:view")
    public Result<PageResult<LoginLogVO>> pageLoginLogs(LoginLogQueryDTO query) {
        return Result.success(logService.pageLoginLogs(query));
    }

    /**
     * 日志统计概览。{@code GET /api/v1/logs/stats}，权限 {@code log:view}。
     *
     * <p>参数：{@code recentDays}（走势窗口天数，可选，默认 7，区间 {@code [1, 90]}，
     * 越界会<b>归一到默认值</b>而不是报 400 —— 它是展示参数，不是业务条件）。
     *
     * <p>返回：操作/登录日志的成功失败分布、登录失败率（百分比两位小数）、
     * 操作量 Top 10 模块、最近 N 天的登录走势。
     *
     * <p>⚠️ 一次请求共 <b>4 条查询</b>，条数固定、不随数据量变化：
     * 2 条 {@code GROUP BY status} + 1 条模块 Top N + 1 条按天走势。
     * 没有用"各状态分别 {@code COUNT(*)}"的写法，因为那会让
     * "成功数 + 失败数"来自不同时刻的读，并发写入时可能大于总数。
     */
    @GetMapping("/stats")
    @RequirePermission("log:view")
    public Result<LogStatsVO> stats(@RequestParam(name = "recentDays", required = false) Integer recentDays) {
        return Result.success(logService.stats(recentDays));
    }
}
