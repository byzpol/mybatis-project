package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * "状态码 + 计数（+ 中文描述）"行载体。
 *
 * <h3>⚠️ 中文描述由 Service 填，不由 SQL 填</h3>
 * <p>状态码 {@code 0~9} 的中文（"草稿""待编辑初审"…）是<b>Java 枚举
 * {@code ArticleStatus} 的知识</b>，不是数据库的知识。若在 SQL 里用
 * {@code CASE status WHEN 0 THEN '草稿' ...} 硬编码一遍，就出现了<b>第二份真相</b>：
 * 哪天改了枚举的中文，SQL 这份不会跟着变，而两边不一致时以谁为准没有说法。
 *
 * <p>所以分工是：Mapper 只 {@code GROUP BY status} 返回码与计数，
 * Service 用 {@code ArticleStatus.getDesc(status)} 补中文。
 * <p>⚠️ 库里若存在 {@code 0~9} 之外的脏值，{@code getDesc} 需要能兜底
 * （给"未知状态"而不是抛异常）—— 否则一条脏数据会让整个统计接口 500。
 */
@Data
public class StatusCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码（文章域为 0~9）。 */
    private Integer status;

    /** 该状态的记录数。 */
    private Long cnt;

    /** 状态中文描述（Service 填）。 */
    private String statusDesc;
}
