package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章版本快照的列表项 VO（S15）。
 *
 * <h2>⚠️ 为什么<b>不</b>返回 {@code contentSnapshot} 正文</h2>
 * <p>库表 {@code cms_article_version.content_snapshot} 是 {@code LONGTEXT}（可为空），
 * 一篇正文几 KB 到几十 KB 都正常。版本列表是"一次拿 N 条"的接口，
 * 一篇有 20 个版本的常规文章就要多传几百 KB，而列表页上<b>一个字都不显示</b>
 * （{@code ArticleDetail.vue} 的版本表格只渲染版本号 / 标题快照 / 修改人 / 说明 / 时间）。
 * 传了不用 = 白花带宽 + 白占内存。
 *
 * <p>判据不是"字段大不大"，而是<b>这一列在这张列表上有没有人用</b>。
 * 手册 S15 的核查项要求"用 ApiFox 看返回体大小验证"，
 * 正是这个意思：不返回正文之后，列表响应体应与"版本条数 × 每条百余字节"同量级。
 *
 * <p>需要看正文的场景是"回滚前确认要变成什么样"，那属于<b>单条</b>查询，
 * 不该塞进列表接口。当前契约里没有这个单条接口（见实现记录里的挂起项）。
 *
 * <h2>⚠️ 一处与手册不一致：{@code titleSnapshot} 返回<b>完整</b>标题，不截成 50 字</h2>
 * <p>手册写"只返回标题快照摘要（前 50 字）"。本项目<b>不采纳截断</b>，理由：
 * <ul>
 *   <li>真正撑大响应体的是 {@code LONGTEXT} 正文，不是 {@code VARCHAR(200)} 的标题。
 *       把 200 字的标题截到 50 字，一行只省 150 字节，却让"标题快照"这一列
 *       在很多中文标题上直接被砍掉后半截 —— 而这一列正是用户区分版本的主要依据。</li>
 *   <li>前端这一列本来就配了 {@code show-overflow-tooltip}，长标题由 UI 层折叠，
 *       不需要后端提前截断。</li>
 *   <li>截断是<b>不可逆的信息损失</b>：后端截了，前端就算想显示全文也拿不到。
 *       而"不截断 + 前端折叠"两者都保留了。</li>
 * </ul>
 *
 * <h2>{@code isCurrent} 的语义</h2>
 * <p>{@code true} 表示这条快照就是文章<b>当前</b>内容对应的那一版，
 * 即 {@code version_no == SELECT MAX(version_no)}。
 * <p>⚠️ 注意它和"文章状态"没有任何关系 —— 一篇文章的 {@code isCurrent} 行
 * 与它是不是已发布、是不是已归档无关，只表示"版本号最大"。
 * <p>⚠️ 字段名用 {@code isCurrent}（而不是 {@code current}）是为了让前端模板里
 * {@code row.isCurrent} 读起来是一句判断而不是一个名词。
 * Lombok 对包装类型 {@code Boolean} 生成 {@code getIsCurrent()}，
 * Jackson 序列化出来的 JSON 键就是 {@code isCurrent}。
 */
@Data
public class CmsArticleVersionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 快照行主键。
     * <p>⚠️ 它**不是** {@code versionNo}。前端做列表 key 时两者都能用，
     * 但回滚接口收的是 {@code versionNo}（业务号），不是这个 id ——
     * 因为这个 id 是自增的，"换个库就变了"，不适合写进接口契约。
     */
    private Long id;

    /**
     * 版本号，从 1 开始递增，同一篇文章内唯一（库表唯一索引
     * {@code uk_article_version(article_id, version_no)}）。
     * <p>回滚接口的目标就是这个号。
     */
    private Integer versionNo;

    /** 标题快照（完整，不截断；上限 200 字，见库表 {@code VARCHAR(200)}）。 */
    private String titleSnapshot;

    /** 修改人 ID（逻辑外键 {@code sys_user.id}），可为空（老数据 / 系统写入）。 */
    private Long modifierId;

    /**
     * 修改人昵称，由 Service 批量查 {@code sys_user} 拼装。
     * <p>⚠️ 刻意<b>不是</b>在循环里 {@code selectById} —— 那是 N+1，
     * 一次列表查询会变成"1 + N 条 SQL"。
     * <p>用户已被逻辑删除或 {@code modifier_id} 为空时这里是 {@code null}，
     * <b>不编造</b>"已注销用户"之类的文案，让数据问题显式暴露
     * （前端模板已有 {@code row.modifierName || row.modifierId || '-'} 兜底）。
     */
    private String modifierName;

    /** 修改说明，例如"创建草稿" / "更新草稿" / "提交初审" / "回滚至版本 1"。 */
    private String modifyRemark;

    /** 快照生成时间（快照一旦生成就冻结，本表没有 update_time）。 */
    private LocalDateTime createTime;

    /**
     * 是否当前版本：{@code versionNo == 该文章的最大 versionNo}。
     * <p>见类注释。一篇文章<b>最多一条</b>为 {@code true}。
     */
    private Boolean isCurrent;
}
