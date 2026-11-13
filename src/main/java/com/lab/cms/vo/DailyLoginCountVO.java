package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * "按天聚合的登录成功/失败数"行载体。
 *
 * <h3>⚠️ 只统计<b>有数据的天</b>，不补零</h3>
 * <p>SQL 是 {@code GROUP BY DATE(login_time)}，所以某天一条登录都没有时，
 * 结果里<b>不会出现那一天</b>。这是刻意的：
 * <ul>
 *   <li>在 SQL 侧补零需要一张日期序列表或递归 CTE —— 对一个课程项目是过度设计；</li>
 *   <li>在前端补零是自然的（图表库本来就要按 x 轴刻度对齐），
 *       把"业务上有几天没数据"这件事给前端看反而更直观。</li>
 * </ul>
 * <p>本类因此<b>不保证</b>返回 {@code recentDays} 个元素，可能少于它。
 * 消费方（前端图表 / 统计接口）不能假设长度。
 *
 * <h3>⚠️ 为什么 {@code successCnt} / {@code failCnt} 是 {@code Long} 而不是 {@code Integer}</h3>
 * <p>SQL 里写的是 {@code SUM(login_status = 1)}（借 MySQL 里布尔表达式等价于 0/1 的特性），
 * MySQL 对 {@code SUM} 的类型推断结果是 <b>{@code DECIMAL}</b>，
 * 不是 {@code BIGINT}。JDBC 的 {@code ResultSet#getLong} 对 DECIMAL 是安全的
 * （会做转换），但声明成 {@code Integer} 就可能在某些驱动/大数值下抛
 * {@code ArithmeticException}。用 {@code Long} 与 {@code COUNT()} 的返回类型保持一致。
 *
 * <p>⚠️ 别名 {@code successCnt} / {@code failCnt} 用的是驼峰，
 * 与字段名逐字一致 —— 不依赖 {@code mapUnderscoreToCamelCase}
 * （那个配置只影响"下划线 → 驼峰"的转换，对已经写成驼峰的别名是恒等映射）。
 */
@Data
public class DailyLoginCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日期，格式 {@code yyyy-MM-dd}（SQL 侧用 {@code DATE_FORMAT} 生成）。 */
    private String day;

    /** 当天登录成功次数。 */
    private Long successCnt;

    /** 当天登录失败次数。 */
    private Long failCnt;
}
