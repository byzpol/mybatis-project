package com.lab.cms.service;

import com.lab.cms.common.result.PageResult;
import com.lab.cms.dto.LoginLogQueryDTO;
import com.lab.cms.dto.OperLogQueryDTO;
import com.lab.cms.vo.LogStatsVO;
import com.lab.cms.vo.LoginLogVO;
import com.lab.cms.vo.OperLogVO;

/**
 * 日志查询与统计服务。
 *
 * <h2>⚠️ 本接口<b>不</b>继承 {@code BaseService}</h2>
 * <p>项目约定"业务 Service 一律继承 {@code BaseService&lt;T&gt;}"，
 * 那条约定针对的是<b>单表 CRUD 型</b>服务（{@code BaseService} 继承自 MP 的
 * {@code IService<T>}，需要绑定一个实体）。本服务<b>跨三张表</b>
 * （{@code sys_oper_log} / {@code sys_login_log} / {@code sys_report} 的聚合），
 * 没有任何一个 {@code T} 能代表它，硬套一个会得到一个语义错误的泛型。
 *
 * <p>所以这里<b>只做查询与统计，不提供任何写入方法</b>：
 * 日志表是"只增不改"的（{@code SysOperLog} 的类注释已写明），
 * 唯一的写入方是 {@code WebLogAspect} 与 {@code LoginLogServiceImpl}。
 * 不在本接口暴露写入，是"用接口形状表达约束"——
 * 比在文档里写一句"请不要修改日志"可靠得多。
 *
 * <h2>⚠️ 两个 {@code status} 口径不同，是本域最容易查错的地方</h2>
 * <table border="1">
 *   <tr><th>表</th><th>列</th><th>成功</th><th>失败</th></tr>
 *   <tr><td>{@code sys_oper_log}</td><td>{@code status}</td><td>1</td><td>0</td></tr>
 *   <tr><td>{@code sys_login_log}</td><td>{@code login_status}</td><td>1</td><td>2</td></tr>
 * </table>
 * <p>两列的"成功"都是 1（所以只写 {@code = 1} 的那一半永远是对的，
 * 也永远发现不了错），但"失败"一个是 0 一个是 2。
 * 把 {@code status != 1} 当成两处通用的"失败"判断，
 * 登录失败数会被算成 0 —— <b>不报错，只是数据错</b>。
 *
 * <h2>⚠️ 权限码</h2>
 * <p>查询日志要求 {@code log:view}，报表要求 {@code report:view}
 * （库表 {@code sys_permission} 里的 {@code path} 分别写着 {@code /api/v1/logs}
 * 与 {@code /api/v1/reports}）。本服务的三个方法都属于 {@code log:view} ——
 * 报表的快照接口在 {@link ReportService} 上。
 */
public interface LogService {

    /** 操作日志分页检索（按模块 / 操作人 / 类型 / 结果 / 时间区间过滤）。 */
    PageResult<OperLogVO> pageOperLogs(OperLogQueryDTO query);

    /** 登录日志分页检索（按用户名 / 结果 / 时间区间过滤）。 */
    PageResult<LoginLogVO> pageLoginLogs(LoginLogQueryDTO query);

    /**
     * 日志统计概览。
     *
     * @param recentDays 登录走势统计的天数窗口；{@code null} 或超出 {@code [1, 90]}
     *                   时归一到默认值 7
     */
    LogStatsVO stats(Integer recentDays);
}
