package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计报表概览出参（实时计算，不落库）。
 *
 * <h3>本 VO 与 {@code sys_report} 表的关系</h3>
 * <p>本 VO 是"<b>现在算一次给你看</b>"的实时结果；{@code sys_report} 是
 * "把某次结果<b>存下来</b>"的快照（见 {@code ReportServiceImpl#saveSnapshot}）。
 * 两者内容同构，所以快照就是把本 VO 序列化成 JSON 存进 {@code result_json}。
 *
 * <p>为什么同构还要分两步：报表的价值在读历史 ——
 * "上周的发布量是多少"必须查快照，实时算出来的是"此刻"。
 * {@code stat_period} 那一列就是给快照标注"这是哪个周期"的。
 *
 * <h3>⚠️ {@link #articleStatusDistribution} 里会包含"计数为 0 的状态"吗</h3>
 * <p><b>不会</b>。SQL 是 {@code GROUP BY status}，没有文章的状态不会出现。
 * 这是刻意的，且比"补齐 10 个状态"更有信息量：
 * 出现即代表有数据，10 个全 0 的行堆在一起反而看不出哪个是空的。
 * <p>前端画饼图时如果要固定扇区顺序，按 {@link StatusCountVO#status} 排序即可
 * （本接口已按 status 升序返回）。
 */
@Data
public class ReportOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章总数（已逻辑删除的计入吗？—— 不计，见 {@code ReportServiceImpl} 的说明）。 */
    private Long articleTotal;

    /** 文章按状态分布（按 status 升序；无数据的状态不出现）。 */
    private List<StatusCountVO> articleStatusDistribution;

    /** 分类总数。 */
    private Long categoryTotal;

    /** 文章按分类分布（按篇数倒序，只取前 N 个）。 */
    private List<LabelCountVO> categoryDistribution;

    /** 标签总数。 */
    private Long tagTotal;

    /** 已建立的文章-标签关联数（即"打标动作"的总和）。 */
    private Long articleTagTotal;

    /** 统计周期描述，如 {@code 2026-11}（快照落库时写进 {@code stat_period}）。 */
    private String statPeriod;

    /** 本次统计生成时刻。 */
    private LocalDateTime generatedAt;
}
