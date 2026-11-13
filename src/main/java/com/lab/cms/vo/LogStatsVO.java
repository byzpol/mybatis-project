package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日志统计概览出参。
 *
 * <h3>为什么把操作日志与登录日志放在同一个 VO 里统计</h3>
 * <p>它们回答的是同一个问题："系统最近健康吗"。分开两个接口的话，
 * 前端"数据统计"页要发两次请求，而两次请求之间数据可能已经变了
 * （统计口径要求"同一时刻的快照"）。一个接口一次算完，天然一致。
 *
 * <h3>⚠️ 两个 {@code status} 口径不同，是本 VO 最需要小心的地方</h3>
 * <table border="1">
 *   <tr><th>表</th><th>列</th><th>成功</th><th>失败</th></tr>
 *   <tr><td>{@code sys_oper_log}</td><td>{@code status}</td><td>1</td><td>0</td></tr>
 *   <tr><td>{@code sys_login_log}</td><td>{@code login_status}</td><td>1</td><td>2</td></tr>
 * </table>
 * <p>两列的"成功"都是 1，但"失败"一个是 0、一个是 2。
 * 若照抄 {@code status != 1} 去算登录失败数，会把失败记成"另一个值"从而漏掉一半。
 * S24 的实现里两处分别写死了各自的常量并配注释，并且有单测钉住。
 *
 * <h3>{@link #loginFailRate} 是百分比，不是小数</h3>
 * <p>返回 {@code 33.33} 表示 33.33%，而不是 0.3333。理由：前端直接
 * {@code `${loginFailRate}%`} 就能显示，不用再乘 100 —— 少一次前端换算
 * 就少一处"两处各乘一次变成 100 倍"的错。
 * <p>⚠️ 分母为 0 时返回 {@code 0.0} 而不是 {@code null} 或 {@code NaN}：
 * {@code null} 会让前端图表断点，{@code NaN} 序列化成 JSON 是<b>非法</b>的
 * （Jackson 默认会写成 {@code "NaN"} 字符串，JS 的 {@code parseFloat} 拿到 NaN，
 * 图表直接空白）。"没有登录记录 ⇒ 失败率 0%"是合理语义。
 */
@Data
public class LogStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 操作日志总条数。 */
    private Long operTotal;

    /** 操作日志成功条数。 */
    private Long operSuccess;

    /** 操作日志失败条数。 */
    private Long operFail;

    /** 登录日志总条数。 */
    private Long loginTotal;

    /** 登录成功条数。 */
    private Long loginSuccess;

    /** 登录失败条数。 */
    private Long loginFail;

    /** 登录失败率（百分比，两位小数，如 {@code 33.33}）。 */
    private Double loginFailRate;

    /** 操作量最高的模块（含计数），按次数倒序。 */
    private List<LabelCountVO> topModules;

    /** 最近 {@link #recentDays} 天的登录成功/失败走势（只含有数据的天）。 */
    private List<DailyLoginCountVO> recentLoginTrend;

    /** 走势统计的天数窗口（默认 7 天）。 */
    private Integer recentDays;

    /** 本次统计的生成时刻 —— 让消费方知道"这是哪一刻的数字"。 */
    private LocalDateTime generatedAt;
}
