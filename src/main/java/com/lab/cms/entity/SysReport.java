package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 统计报表实体，对应表 {@code sys_report}。
 *
 * <h3>这张表在 S24 之前是什么状态</h3>
 * <p>建表语句 + <b>1 条种子数据</b>（"文章状态分布统计"，{@code report_type=1}，
 * 2026-10-05 由 admin 生成），但全仓<b>没有实体、没有 Mapper、没有接口</b>。
 * 与标签两张表同类：设计了、填了种子、没跑起来。
 * S24 补报表快照接口时才有第一个写入方。
 *
 * <h3>⚠️ {@code result_json} 是 MySQL 的 {@code JSON} 列</h3>
 * <p>两条实际影响：
 * <ul>
 *   <li><b>写入必须是合法 JSON</b>。随便塞一个字符串（比如 {@code "abc"}）会报
 *       {@code Invalid JSON text}。所以 Java 侧必须用 {@code ObjectMapper}
 *       序列化，而不是手拼字符串 —— 手拼漏一个引号就是运行期错误。</li>
 *   <li><b>JDBC 读出来是 String</b>（驱动把 JSON 当字符类型返回），
 *       需要回读成对象时由 Service 再反序列化。</li>
 * </ul>
 *
 * <p>{@code stat_period} 是自由文本（{@code VARCHAR(50)}），
 * 既能放 {@code "2026-10"} 也能放 {@code "2026-10-05~2026-10-11"}，
 * 这是刻意的 —— 硬拆成起止两列会把"月度"这种周期表达不进去。
 *
 * <h3>为什么继承 {@link BaseEntity}</h3>
 * <p>判据仍然是"库表有没有那五列"：{@code sys_report} 带齐了
 * {@code create_by / create_time / update_by / update_time / deleted}，所以继承。
 * <p>⚠️ 注意它<b>另有</b>一组业务时间字段 {@code generator_id} / {@code generate_time}，
 * 与审计四字段语义不同：前者是"这份报表是谁在哪一刻生成的"（业务事实），
 * 后者是"这一行是谁写进库的"（数据治理）。正常情况下两者相同，但分开保留
 * 才能表达"系统定时任务代生成"这类场景（那时 {@code generator_id} 会是哨兵值 0）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_report")
public class SysReport extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 报表名称。 */
    @TableField("report_name")
    private String reportName;

    /**
     * 报表类型：1 文章状态分布 / 2 分类分布 / 3 审核效率 / 4 用户活跃 / 9 自定义。
     * <p>取值以库表列注释为准（种子数据只有 1）。
     */
    @TableField("report_type")
    private Integer reportType;

    /** 统计周期，如 {@code 2026-10} 或 {@code 2026-10-05~2026-10-11}。 */
    @TableField("stat_period")
    private String statPeriod;

    /** 统计结果 JSON 快照（必须合法 JSON，写入前由 ObjectMapper 序列化）。 */
    @TableField("result_json")
    private String resultJson;

    /** 生成人 ID（逻辑外键 {@code sys_user.id}）。 */
    @TableField("generator_id")
    private Long generatorId;

    /** 生成时间（业务时间，非审计时间）。 */
    @TableField("generate_time")
    private LocalDateTime generateTime;
}
