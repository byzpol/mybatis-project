package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章版本快照实体，对应表 {@code cms_article_version}。
 *
 * <h2>⚠️ 为什么不继承 {@code BaseEntity}</h2>
 *
 * <p>判据是<b>库表的列</b>，不是"这张表重不重要"。对照 sql/init.sql 第 319~332 行：
 *
 * <pre>
 * cms_article_version 的列：
 *   id, article_id, version_no, title_snapshot, content_snapshot,
 *   modifier_id, modify_remark, create_time, deleted
 *                              ^^^^^^^^^^^ 无 update_by / update_time
 *
 * BaseEntity 提供的列：
 *   id, create_by, create_time, update_by, update_time, deleted
 *            ^^^^^^^^^               ^^^^^^^^^^  ^^^^^^^^^^^
 * </pre>
 *
 * <p>{@code BaseEntity} 里有 4 个业务列它没有：{@code create_by}、
 * {@code update_by}、{@code update_time}，以及可复用的 {@code create_time}
 * （本表叫一样的名字，倒是能对上）。
 * 若强行继承，会带来三个真实问题：
 * <ol>
 *   <li><b>INSERT 失败</b>：{@code AuditMetaObjectHandler} 会给
 *       {@code updateBy / updateTime} 填值（{@code FieldFill.INSERT_UPDATE} 在插入时也填），
 *       MP 于是把这些列写进 INSERT 语句 → 数据库报
 *       {@code Unknown column 'update_by' in 'field list'}。</li>
 *   <li><b>SELECT 报错或静默丢字段</b>：MP 按实体字段拼 SELECT 列表，
 *       同样会拼出不存在的列。</li>
 *   <li><b>语义错位</b>：就算上面两条靠 {@code @TableField(exist = false)}
 *       苟住，继承关系也在传达"这是一张可更新的业务表"的错误信号。
 *       而本表的业务约束恰恰是**只增不改、不可变更**——
 *       版本快照被改了，回滚功能就失去意义了。</li>
 * </ol>
 *
 * <p>所以本类**独立实现 {@code Serializable}**，手写自己需要的
 * {@code id / create_time / deleted} 三列，把"不可更新"这个语义
 * 在类型层面表达出来（没有 {@code updateBy/updateTime} 字段，
 * 别人想更新也无从下手）。
 *
 * <h3>与 {@code CmsArticleAuditLog} 的对照</h3>
 * <p>那张表五列俱全 → 继承；这张表缺三列 → 不继承。
 * <b>同一个"文章域"里两种选择并存，正是判据在起作用的证明</b>。
 * 反过来，如果无脑让所有实体都继承 {@code BaseEntity}，
 * 第 1 条问题会在"第一次插入版本快照"时立刻爆出来。
 *
 * <h3>deleted 为什么留着</h3>
 * <p>库表有 {@code deleted} 列（保持 17 张表的统一形状），
 * 本类也保留该字段并标 {@code @TableLogic}。但**正常业务流程永远不该删版本快照**：
 * 回滚要靠它，删了历史就断了。保留逻辑删除能力只是"对齐表结构、
 * 以备将来清库时按条件软删"，不代表业务上允许删。
 */
@Data
@TableName("cms_article_version")
public class CmsArticleVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键，自增。
     * <p>参数说明同 {@code BaseEntity#id}：用 {@code IdType.AUTO} 而不是雪花 ID，
     * 避免 19 位 long 超出 JS Number 安全范围导致前端精度丢失。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文章 ID（逻辑外键 cms_article.id），{@code BIGINT UNSIGNED NOT NULL}。 */
    @TableField("article_id")
    private Long articleId;

    /**
     * 版本号，从 1 递增，{@code INT NOT NULL}。
     * <p>与 {@code article_id} 组成唯一索引 {@code uk_article_version}，
     * 所以"同一篇文章同一版本号只允许一行"由数据库兜底，不靠应用层保证。
     * <p>⚠️ 取号时必须用 {@code SELECT MAX(version_no) + 1} 之类的原子写法并配合
     * 唯一索引重试，不能"先查再插"（并发下两人拿到同一个号，第二个因唯一索引失败）。
     */
    @TableField("version_no")
    private Integer versionNo;

    /** 标题快照，{@code VARCHAR(200) NOT NULL}。 */
    @TableField("title_snapshot")
    private String titleSnapshot;

    /**
     * 正文快照，{@code LONGTEXT}，可为空。
     * <p>⚠️ 用 {@code NULL DEFAULT NULL} 而不是空串，可以让"确实没正文"
     * 与"正文是空串"两种情况区分开。
     */
    @TableField("content_snapshot")
    private String contentSnapshot;

    /** 修改人 ID（逻辑外键 sys_user.id），可为空。 */
    @TableField("modifier_id")
    private Long modifierId;

    /** 修改说明，{@code VARCHAR(255)}，可为空。例如"调整了标题措辞"。 */
    @TableField("modify_remark")
    private String modifyRemark;

    /**
     * 快照创建时间，{@code DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP}。
     * <p>这是本表唯一的时间列（没有 {@code update_time}）—— 快照一旦生成就冻结。
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 逻辑删除标记：0 未删除 / 1 已删除。
     * <p>显式写 value/delval 的理由见 {@code BaseEntity} 的类注释（默认值语义易理解反）。
     * <p>业务上不删版本快照，保留此字段仅为对齐表结构与将来清库之用。
     */
    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    @JsonIgnore
    private Integer deleted;
}
