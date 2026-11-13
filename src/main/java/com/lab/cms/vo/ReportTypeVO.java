package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 报表类型元数据 VO。
 *
 * <h3>这个接口存在的意义：把"魔法数字"变成可枚举的数据</h3>
 * <p>{@code sys_report.report_type} 是 {@code TINYINT}，取值
 * {@code 1/2/3/4/9}。前端要做一个下拉框让用户选报表类型 ——
 * 如果这个映射只存在于后端代码里，前端就得自己再写一份
 * （然后后端加了一种类型，前端下拉框里没有，用户永远用不到）。
 *
 * <p>所以把类型清单做成一个只读接口，前端渲染下拉框：
 * <b>后端支持几种报表，前端就显示几种</b>，不需要发版对齐。
 *
 * <h3>⚠️ 为什么"支持的类型"与"已实现的类型"要分开说</h3>
 * <p>DDL 的列注释里定义了 5 种（1 文章状态分布 / 2 分类分布 / 3 审核效率 /
 * 4 用户活跃 / 9 自定义），但 S24 只实现了<b>前两种</b>的可计算逻辑。
 * 本 VO 的 {@link #implemented} 字段就是用来表达这个差异的 ——
 * 前端可以据此把未实现的类型置灰，而不是让用户选完再收到
 * {@code REPORT_TYPE_UNSUPPORTED(14001)}。
 *
 * <p>把"定义了但没实现"显式暴露出来，而不是在接口清单里假装都有，
 * 是本项目一贯的口径（同类例子见 {@code docs/验收自测表.md} §三）。
 */
@Data
public class ReportTypeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 报表类型码（对应 {@code sys_report.report_type}）。 */
    private Integer type;

    /** 报表类型名称。 */
    private String name;

    /** 该类型的统计口径说明（给运营看，不是给程序看）。 */
    private String description;

    /** 后端当前是否已实现该类型的计算逻辑。 */
    private Boolean implemented;
}
