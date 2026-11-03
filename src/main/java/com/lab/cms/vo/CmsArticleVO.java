package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章出参 VO。
 *
 * <h2>⚠️ 为什么不能直接返回 Entity（本类存在的根本理由）</h2>
 *
 * <p>直接把 {@code CmsArticle} 转 JSON 返回，会有四个问题，每个都是真问题：
 *
 * <ol>
 *   <li><b>泄露内部字段</b>：
 *     <ul>
 *       <li>{@code deleted}：虽然基类标了 {@code @JsonIgnore} 挡住了，
 *           但那是"幸好标了"，不是"本来该"——依赖一个注解兜底不如字段根本不出现</li>
 *       <li>{@code version}：乐观锁版本号是并发控制内部字段。
 *           暴露出去的好处是"前端可以带 version 回来做乐观锁校验"，
 *           但本项目没打算让前端参与乐观锁，暴露只会增加被恶意构造的风险</li>
 *       <li>{@code createBy / updateBy}：审计字段是内部运营信息，
 *           前端不需要也不该看到</li>
 *     </ul>
 *   </li>
 *   <li><b>与库表强耦合</b>：Entity 字段随库表变动（加列、改名、拆表）。
 *       若前端直接消费 Entity，<b>改一次表结构就要改前端</b>。
 *       用 VO 隔一层，表结构变化只影响"Entity → VO 的转换代码"这一处。
 *       这是典型的<b>防腐层（Anti-Corruption Layer）</b>作用。</li>
 *   <li><b>拿不到展示语义</b>：Entity 里 {@code status} 是 {@code 0~9} 的数字。
 *       前端拿到 {@code 3} 要知道该显示"审核通过"还是"已通过待发布"？
 *       让它自己维护映射表，就是"后端加一个状态前端要改代码"。
 *       所以 VO 里直接给 {@link #statusDesc}（中文）+ {@link #statusColor}
 *       （Element Plus 的 tag type），前端零逻辑渲染。</li>
 *   <li><b>需要跨域补数据</b>：{@link #authorName}、{@link #categoryName}
 *       在 {@code cms_article} 表里<b>不存在</b>——表里只有
 *       {@code author_id / category_id}。要显示人名和分类名，
 *       必须 JOIN 或二次查询。Entity 一个字段对应一列，
 *       装不下这种"计算出来的字段"，VO 才行。</li>
 * </ol>
 *
 * <h3>VO 的字段取舍原则：这个字段前端用来干什么</h3>
 * <p>本 VO 里 <b>没有 {@code content}</b>（正文，LONGTEXT）。
 * 理由是列表接口与详情接口共用这个 VO 时，正文可能几百 KB，
 * 列表页一条都显示不了正文却要把整个正文传过来 —— 浪费带宽、拖慢响应。
 * 所以约定：<b>列表用本 VO（不含正文），详情单独出
 * 一个含正文的 VO</b>（S09 再建 {@code CmsArticleDetailVO}）。
 *
 * <h3>⚠️ 关于 id 的返回类型</h3>
 * <p>{@code id} 是 {@code Long}。若将来改用雪花 ID（19 位 long），
 * 超出 JS {@code Number} 的安全整数范围（2^53-1，约 16 位），
 * 前端精度会丢。本项目主键用数据库自增（{@code IdType.AUTO}），
 * 不会达到这个量级，所以 {@code Long} 直接返回即可。
 * 这也正是 {@code BaseEntity#id} 注释里强调"不要用雪花算法"的原因之一。
 */
@Data
public class CmsArticleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章 ID */
    private Long id;

    /** 文章标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面图 URL */
    private String coverUrl;

    /** 分类 ID（保留，前端做分类级联筛选时需要） */
    private Long categoryId;

    /**
     * 分类名称（跨表补数据）。
     * <p>来源：{@code cms_category.category_name}（**不是 {@code name}**，
     * 库表列名见 {@code sql/init.sql} 的 {@code cms_category} 建表段），
     * 由 Service 查一次分类表填充。
     * <p>⚠️ 批量列表时不要每行查一次库（N+1 问题）——
     * 应先收集本页所有 {@code categoryId}，一次 {@code IN} 查询建 Map，再回填。
     * S11 详情接口与 S17 分页检索都按这个写法实现（实测：本页 10 条与 20 条
     * 的 SQL 条数完全相同）。
     */
    private String categoryName;

    /** 文章状态码（0~9），前端若需按码做逻辑判断时用 */
    private Integer status;

    /**
     * 文章状态中文描述。
     * <p>来源：{@code ArticleStatus.getDesc(status)}。
     * <p>⚠️ 库里若有 0~9 之外的脏值，这里给"未知状态"兜底，
     * 不让整个列表因一条脏数据 500。
     */
    private String statusDesc;

    /**
     * 状态对应的标签颜色（{@code info/warning/success/danger/primary}）。
     * <p>直接对应 Element Plus {@code <el-tag type="...">} 的取值，
     * 前端不用维护映射表。
     */
    private String statusColor;

    /** 作者 ID */
    private Long authorId;

    /**
     * 作者昵称（跨表补数据）。
     * <p>来源：{@code sys_user.nickname}，同样注意 N+1 问题。
     */
    private String authorName;

    /** 发布人 ID，未发布时为空 */
    private Long publisherId;

    /** 实际发布时间 */
    private LocalDateTime publishTime;

    /** 排期发布时间 */
    private LocalDateTime scheduleTime;

    /** 是否置顶：0 否 / 1 是 */
    private Integer isTop;

    /** 浏览量 */
    private Integer viewCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间 */
    private LocalDateTime updateTime;
}
