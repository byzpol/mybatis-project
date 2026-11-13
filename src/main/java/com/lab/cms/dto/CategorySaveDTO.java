package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 分类新增 / 编辑入参。
 *
 * <h3>为什么新增与编辑共用一个 DTO</h3>
 * <p>两者要填的字段完全相同（名称、父级、排序、描述），差别只在"有没有 id"——
 * 而那是<b>路径变量</b>，不是请求体字段（见下面"为什么没有 id"）。
 * 拆成两个类会产生两份几乎一样的校验注解，改一处忘一处就出现
 * "新增校验了长度、编辑没校验"的漏洞。
 *
 * <h3>⚠️ 请求体里刻意<b>没有</b> {@code id}</h3>
 * <p>与 {@code CmsArticleSaveDTO} 同口径：id 走 URL 路径
 * （{@code PUT /categories/{id}}）。若两处都能传 id，就必须定义
 * "以哪个为准"，而这条规则本身是 bug 的温床 —— 前端把 id 拼进 body 而 URL 里写错时，
 * 到底是改 A 还是改 B？不给它这个机会。
 *
 * <h3>⚠️ 刻意<b>没有</b> {@code level} 与 {@code audit_status} 字段</h3>
 * <p>这两个是"派生字段"，由 Service 计算，不接受入参 —— 防批量赋值（Mass Assignment）：
 * <ul>
 *   <li>{@code level}：由 {@code parentId} 决定（父级为 0 则 1，否则父级层 +1）。</li>
 *   <li>{@code audit_status}：由业务规则决定，不由调用方指定。</li>
 * </ul>
 * 与 {@code CmsArticleSaveDTO} 不让前端传 {@code status} / {@code authorId} / {@code viewCount}
 * 是同一条原则。
 *
 * <h3>⚠️ {@code status} 也不在新增入参里</h3>
 * <p>新建的分类一律为<b>启用</b>（{@code status = 1}）：刚建出来就是停用状态
 * 会让人以为"建失败了"。要停用走 {@code PUT /categories/{id}/status} ——
 * 那是一个显式的、会被记进操作日志的动作。
 */
@Data
public class CategorySaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分类名称。
     * <p>校验：非空 + 去空白后非空（{@code @NotBlank}）+ 长度 ≤ 50（与库表
     * {@code category_name VARCHAR(50)} 对齐）。
     * <p>⚠️ 长度必须在这里卡住：库表是 {@code VARCHAR(50)}，
     * 传 51 个字符在严格模式下会报 {@code Data too long for column}，
     * 那是 <b>500</b> 而不是 400 —— 属于"把用户的输入错误报成服务端故障"。
     */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过 50 个字符")
    private String categoryName;

    /**
     * 父分类 ID。不传或传 {@code null} 视为 {@code 0}（顶级分类）。
     * <p>⚠️ 不支持"顶级分类再挂子级"以外的层级：本项目是<b>两级分类</b>
     * （库表 {@code level} 的注释写的是 {@code 1=一级 2=二级}，种子数据也只到二级）。
     * 给二级分类再挂子级会返回 400。
     */
    private Long parentId;

    /** 排序号，越小越靠前。不传视为 0。 */
    private Integer sort;

    /** 分类描述，长度 ≤ 255（与库表 {@code description VARCHAR(255)} 对齐）。 */
    @Size(max = 255, message = "分类描述不能超过 255 个字符")
    private String description;
}
