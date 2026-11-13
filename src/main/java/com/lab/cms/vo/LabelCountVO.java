package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * "分组名 + 计数"行载体。
 *
 * <h3>为什么叫"行载体"而不是业务 VO</h3>
 * <p>本类不直接出现在接口契约的字段说明里当主角，它的职责是
 * <b>承接 {@code GROUP BY} 查询的结果行</b>：
 * {@code SELECT module AS label, COUNT(*) AS cnt ... GROUP BY module}。
 *
 * <p>⚠️ 为什么需要一个具名类，而不是用 {@code Map<String,Object>}：
 * <ul>
 *   <li><b>字段名即契约</b>。用 Map 时，SQL 的列别名改了、Java 侧的
 *       {@code map.get("cnt")} 不会编译报错，只会在运行时拿到 {@code null}
 *       —— 然后被 JSON 序列化成 {@code "cnt": null}，前端显示空白，一路静默。</li>
 *   <li><b>类型明确</b>。{@code COUNT(*)} 在不同驱动/方言下可能映射成
 *       {@code Long} / {@code BigInteger} / {@code BigDecimal}。
 *       声明成 {@code Long} 后，转换失败会立刻抛异常，而不是让下游去做
 *       {@code (Number)} 强制转换（那才是真正的类型隐患）。</li>
 * </ul>
 *
 * <h3>⚠️ 字段名为什么是 {@code label} 而不是 {@code name}/{@code key}</h3>
 * <p>{@code key} 是 MySQL 的<b>保留字</b>，直接写 {@code AS key} 会语法错误，
 * 得加反引号 —— 一个不起眼的坑。{@code label} 无歧义、无需转义，
 * 且语义上正好是"分组的标签"。
 *
 * <p>⚠️ 本类同时是 {@code @Select} 的返回类型，因此字段名<b>必须与列别名逐字一致</b>：
 * MyBatis 的自动映射按名字匹配，写错一个字母就得到全 {@code null} 的结果
 * （不报错）。改动本类字段名时，必须同步改 Mapper 里的 SQL 别名。
 */
@Data
public class LabelCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分组标签（如模块名"文章管理"、分类名"技术资讯"）。 */
    private String label;

    /** 该分组的记录数。 */
    private Long cnt;
}
