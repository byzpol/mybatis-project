package com.lab.cms.dto;

import com.lab.cms.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章列表查询入参（分页）。
 *
 * <h3>为什么继承 {@code PageQuery}</h3>
 * <p>这是标准的分页查询场景：文章列表必然分页（一个 CMS 可能有上万篇文章），
 * 且需要排序（按发布时间/浏览量）。{@code PageQuery} 已经提供
 * {@code page / size / sortField / sortOrder} 与配套的
 * {@link PageQuery#toMpPage(String...)}（含列名白名单防注入），
 * 直接用，不重复造。
 *
 * <h3>本类与 {@link CmsArticleSaveDTO} 的对比：同一实体两种入参，继承策略相反</h3>
 * <p>这两个类都描述"文章"，但一个继承 {@code PageQuery}、一个不继承。
 * 判据不是"是不是文章相关"，而是<b>这个操作需不需要分页</b>：
 * <ul>
 *   <li>列表查询 → 必须分页 → 继承</li>
 *   <li>保存 → 与分页无关 → 不继承</li>
 * </ul>
 * 这是"入参 DTO 不搞统一父类"的一个具体体现：
 * 强行让所有 DTO 都继承某个基类，会带来大量"用不上的字段"。
 *
 * <h3>⚠️ {@link #status} 为什么是 Integer 而不是 {@code ArticleStatus} 枚举</h3>
 * <p>Spring MVC 对枚举类型的查询参数绑定依赖 {@code Converter}，
 * 传 {@code "PUBLISHED"} 或 {@code "5"} 要分别处理；传 {@code 0~9} 之外的脏值
 * 还会让绑定直接失败（400）。用 {@code Integer} 接，
 * 再在 Service 里用 {@code ArticleStatus.ofNullable(status)} 宽松转换 ——
 * 查不到就当"条件不生效或查不到数据"，而不是"整个请求 400"。
 * <b>查询入参的原则：宽容，别让一个错参数毁掉整个列表页。</b>
 *
 * <h3>所有字段都是可选的（模糊条件）</h3>
 * <p>列表查询的语义是"按给定条件过滤"：字段为 null 表示该条件不参与过滤。
 * 所以本类<b>不加任何 {@code @NotNull} 之类</b>。
 * 唯一例外是时间区间 —— 若只传 {@code beginTime} 不传 {@code endTime}
 * （或反之），业务上是合法的（"此后所有"或"此前所有"），
 * 但若 {@code beginTime > endTime} 则应视为非法，这个检查放在 Service。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CmsArticleQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 标题关键字，模糊匹配（{@code LIKE %title%}）。
     * <p>⚠️ 不做长度限制，但 Service 拼 SQL 时必须用参数绑定
     * （{@code #{title}}）而不是字符串拼接。模糊匹配的
     * {@code %} 由 SQL 侧 {CONCAT} 生成，不要相信前端传来的通配符
     * ——若前端传 {@code "%"}，会变成"匹配所有"的全表扫描。
     */
    private String title;

    /** 分类 ID 精确过滤，可选。 */
    private Long categoryId;

    /**
     * 状态码过滤（0~9），可选。
     * <p>类型选择理由见类注释。
     */
    private Integer status;

    /**
     * 作者 ID 过滤，可选。
     * <p><b>本字段是普通筛选条件，<u>不是</u>数据安全边界</b>（S17 定、S18 订正本注释）。
     * <p>早期版本的注释写的是"Service 必须用当前登录用户 id 覆盖或不接受前端传的
     * {@code authorId}"，<b>与已实现行为不符</b>：{@code ArticleServiceImpl#pageArticles}
     * 的入参里<b>没有</b> {@code operatorId}，签名本身就说明这里不做归属判断 ——
     * 传别人的 {@code authorId} 确实能查到别人的文章（含草稿与驳回稿）。
     * <p>这是<b>刻意</b>的口径，不是漏做，理由与实测见
     * {@code docs/接口分类与鉴权表.md} §二.12：
     * <ul>
     *   <li>前端"我的投稿"页用它把自己筛出来，那是产品需求；</li>
     *   <li>编辑/主编必须能<b>枚举</b>任何人的文章才审得下去；</li>
     *   <li>本接口只校验功能级权限 {@code article:view}（四角色都有），
     *       所以"被限制的是「改」，不是「看」"（§二.1 / §二.10 管改）。</li>
     * </ul>
     * <p>⚠️ 若验收要求"作者只能看自己的"，改法是在 Service 里对<b>没有</b>
     * {@code article:manage} 的角色把 {@code authorId} 强制覆盖为当前登录用户 id
     * （而不是"拒绝接收前端传值"—— 那会让编辑/主编也筛不了别人）。
     * 该备选方案已登记在 {@code docs/重构记录.md} 的重构优先级表。
     */
    private Long authorId;

    /** 创建时间起（含），可选。 */
    private LocalDateTime beginTime;

    /** 创建时间止（含），可选。 */
    private LocalDateTime endTime;
}
