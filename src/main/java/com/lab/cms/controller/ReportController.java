package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.Result;
import com.lab.cms.service.ReportService;
import com.lab.cms.vo.ReportOverviewVO;
import com.lab.cms.vo.ReportTypeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计报表接口。<b>共 3 个端点</b>（2 个读 + 1 个写）。
 *
 * <h3>⚠️ 为什么与 {@code LogController} 分开</h3>
 * <p>两者都提供"统计"，但：
 * <ul>
 *   <li><b>数据源不同</b>：日志统计读 {@code sys_oper_log} / {@code sys_login_log}；
 *       报表读 {@code cms_article} / {@code cms_category} / {@code cms_tag} /
 *       {@code cms_article_tag}。合并会让"管日志的类依赖文章表"。</li>
 *   <li><b>权限码不同</b>：{@code log:view}（只管理员）vs {@code report:view}
 *       （主编 + 管理员）。合在一个类里就会出现"同一个类里两个方法要求不同权限"，
 *       而权限边界是需要在代码里一眼看清的东西。</li>
 *   <li><b>库表 {@code sys_permission} 把它们登记为两条权限</b>
 *       （{@code path} 分别是 {@code /api/v1/logs} 与 {@code /api/v1/reports}），
 *       接口路径与权限登记的 path 对齐，评审时能一一对上。</li>
 * </ul>
 *
 * <h3>权限码 {@code report:view}</h3>
 * <p>库表里授予 {@code ROLE_CHIEF(3)} 与 {@code ROLE_ADMIN(4)}。
 * 所以 {@code author / editor} 调本类应得到 403。
 * <p>⚠️ 库里还有 {@code report:export}（导出报表，只授管理员），
 * 本类<b>没有</b>实现导出端点 —— 导出要返回文件流，而项目有一条
 * "所有 Controller 必须返回 {@code Result} 包装体"的硬约束
 * （《企业开发规范清单》1-8 条），两者需要专门设计（如返回带签名的下载 URL）。
 * 这是本期明确留下的缺口，不假装做了。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController extends BaseController {

    @Autowired
    private ReportService reportService;

    /**
     * 支持的报表类型清单。{@code GET /api/v1/reports/types}，权限 {@code report:view}。
     *
     * <p>返回值里每个类型都有 {@code implemented} 字段，如实标注哪些有计算逻辑
     * （当前只有 1 文章状态分布、2 分类分布）。前端据此把未实现的置灰，
     * 而不是让用户选完再收到 14001。
     */
    @GetMapping("/types")
    @RequirePermission("report:view")
    public Result<List<ReportTypeVO>> listTypes() {
        return Result.success(reportService.listTypes());
    }

    /**
     * 实时统计概览。{@code GET /api/v1/reports/overview}，权限 {@code report:view}。
     *
     * <p>返回：文章总数 + 按状态分布（带中文）、分类总数 + 按分类分布、
     * 标签总数、文章标签关联总数、统计周期、生成时刻。
     *
     * <p>⚠️ 所有数字都是"<b>未被逻辑删除</b>的数据"口径，与列表接口一致。
     * <p>⚠️ 不落库（每次调用实时算）。要留存请调 {@code POST /reports/snapshot}。
     */
    @GetMapping("/overview")
    @RequirePermission("report:view")
    public Result<ReportOverviewVO> overview() {
        return Result.success(reportService.overview());
    }

    /**
     * 生成并保存一份报表快照。{@code POST /api/v1/reports/snapshot}，权限 {@code report:view}。
     *
     * <p>参数：{@code type}（报表类型，可选，默认 1；支持 1 文章状态分布 / 2 分类分布）。
     * 其它值 → {@code REPORT_TYPE_UNSUPPORTED(14001)}。
     *
     * <h3>⚠️ 为什么是 {@code POST} 而不是 {@code PUT}</h3>
     * <p>快照是<b>追加</b>语义：每调用一次就多一条 {@code sys_report} 记录
     * （"历史报表"这条需求正是靠多份快照实现的）。
     * 用 {@code PUT} 会暗示"幂等、覆盖同一条"，与事实相反。
     *
     * <h3>为什么参数走 query 而不是请求体</h3>
     * <p>只有一个标量参数 {@code type}，包一层 JSON 体反而让调用方多写
     * {@code -H 'Content-Type: application/json' -d '{"type":1}'}。
     * 项目里 {@code POST /system/selftest/exception?mode=1} 也是同一个做法。
     *
     * <h3>⚠️ 本端点是写操作，必须记操作日志</h3>
     * <p>{@code @OperLog} 保留了默认的参数记录 —— {@code type} 很短，
     * 且"谁在什么时候生成了哪类报表"是需要留档的（生成过快说明有人在刷接口）。
     */
    @PostMapping("/snapshot")
    @RequirePermission("report:view")
    @OperLog(module = "报表管理", type = OperType.CREATE, desc = "生成报表快照")
    public Result<Long> saveSnapshot(@RequestParam(name = "type", required = false) Integer type) {
        Long id = reportService.saveSnapshot(type, requireCurrentUserId());
        return Result.success("报表快照已生成", id);
    }
}
