package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文章主表实体，对应表 {@code cms_article}。
 *
 * <h3>字段与库表的对照关系（已逐字段核对 sql/init.sql 第 262~289 行）</h3>
 * <p>本类共 14 个业务字段 + 基类 6 个公共字段。
 * <p>⚠️ 手册的字段清单里**没有列 {@link #version}**，但库表确实有这一列
 * （{@code version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'}）——
 * 参照物是库表，不是手册。漏掉它的后果是"乐观锁静默失效"，
 * 即并发编辑时后提交的静默覆盖先提交的，不报错、不留痕。
 *
 * <h3>为什么 {@code isTop} 必须用 {@code @TableField("is_top")} 显式映射</h3>
 * <p>Java 的 POJO 字段命名遵循 lowerCamelCase（{@code isTop}），
 * 由 getter {@code getIsTop()} / setter {@code setIsTop()} 暴露。
 * 但 Lombok 对 {@code boolean isXxx} 与 {@code Integer isXxx} 的处理**不同**：
 * <ul>
 *   <li>{@code boolean isTop} → getter 生成 {@code isTop()}，字段名会被 MP 推导成 {@code top}</li>
 *   <li>{@code Integer isTop} → getter 生成 {@code getIsTop()}，MP 推导出的列名可能仍是 {@code is_top}，
 *       也可能因 {@code is} 前缀被当作布尔语义而错推</li>
 * </ul>
 * 这里类型是 {@code Integer}（库表是 {@code TINYINT}），
 * 类型推导的不确定性叠加 {@code is} 前缀，是一类典型"编译通过、运行报 Unknown column"故障。
 *
 * <p>根本原因：<b>MP 的列名推导依赖 getter/setter 名，而非字段名本身</b>，
 * 而 Lombok 生成的方法名对 {@code is} 前缀有特殊规则。
 * 显式写 {@code @TableField("is_top")} 直接绕开推导，是唯一稳妥做法。
 * 本类其余字段也都显式标注，理由相同（可读性 + 抗改名）。
 *
 * <h3>为什么 content 用 String 而不是 byte[] 或单独的从表</h3>
 * <p>{@code content} 对应库表 {@code LONGTEXT}（最大 4GB）。
 * 用 {@code String} 而非 {@code byte[]}：正文是文本，JDBC 驱动会按连接字符集编码，
 * 用二进制类型反而要自己做字符集转换。MySQL 的 {@code max_allowed_packet}
 * 默认 64MB，单篇正文受此限制 —— 这是"超大正文要放文件服务器"的边界，
 * 本项目语料量级远小于此，不引入复杂度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_article")
public class CmsArticle extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 文章标题，库表 {@code VARCHAR(200) NOT NULL}。
     * <p>长度上限 200 在 DTO 上用 {@code @Size(max = 200)} 校验，
     * 实体层不再重复校验（实体是持久化模型，校验归入参）。
     */
    @TableField("title")
    private String title;

    /** 摘要，{@code VARCHAR(500)}，可为空。 */
    @TableField("summary")
    private String summary;

    /** 封面图 URL，{@code VARCHAR(255)}，可为空。 */
    @TableField("cover_url")
    private String coverUrl;

    /**
     * 正文内容，{@code LONGTEXT}，可为空。
     * <p>允许为空是为支持"先存标题的草稿"——草稿阶段不强制写正文。
     */
    @TableField("content")
    private String content;

    /** 分类 ID（逻辑外键 cms_category.id），{@code BIGINT UNSIGNED}，可为空。 */
    @TableField("category_id")
    private Long categoryId;

    /**
     * 文章状态码 0~9，{@code TINYINT NOT NULL DEFAULT 0}。
     *
     * <p>⚠️ 存的是 {@code Integer} 而不是 {@link com.lab.cms.common.enums.ArticleStatus} 枚举。
     * 原因：MP 要把这个值写进/读出数据库列，用枚举需要在枚举上实现
     * {@code IEnum<Integer>} 或注册 {@code TypeHandler}。本项目状态值会被
     * <b>状态机</b>大量读写（{@code ArticleStateMachine}），
     * 而状态机以 {@code Integer} 为入参更利于"脏数据不炸"（枚举反查会抛异常）。
     * 展示用的中文描述由 VO 在出参时用 {@code ArticleStatus.ofNullable(code)} 补上。
     *
     * <p>切勿直接 {@code setStatus(5)} 这种魔法数字 —— 一律走状态机
     * {@code ArticleStateMachine.transfer(...)} 拿到目标状态再写。
     */
    @TableField("status")
    private Integer status;

    /** 作者 ID（逻辑外键 sys_user.id），{@code BIGINT UNSIGNED NOT NULL}。 */
    @TableField("author_id")
    private Long authorId;

    /** 发布人 ID（逻辑外键 sys_user.id），可为空（未发布时为空）。 */
    @TableField("publisher_id")
    private Long publisherId;

    /** 实际发布时间，可为空。 */
    @TableField("publish_time")
    private LocalDateTime publishTime;

    /**
     * 排期发布时间，可为空。
     * <p>仅当 {@code status = SCHEDULED(6)} 时有意义。与 {@link #publishTime} 的区别：
     * 前者是"计划什么时候发"，后者是"实际什么时候发的"。
     * 定时任务扫的是这一列（库表有 {@code idx_status_schedule_time} 联合索引支撑）。
     */
    @TableField("schedule_time")
    private LocalDateTime scheduleTime;

    /** 是否置顶：{@code 0 否 / 1 是}，默认 0。 */
    @TableField("is_top")
    private Integer isTop;

    /** 浏览量，{@code INT NOT NULL DEFAULT 0}。 */
    @TableField("view_count")
    private Integer viewCount;

    /**
     * 乐观锁版本号，{@code INT NOT NULL DEFAULT 0}。
     *
     * <p>⚠️ 手册的字段清单漏了这一列，但库表有。语义与陷阱：
     * <ul>
     *   <li>标了 {@code @Version} 后，MP 的 {@code updateById} 会自动改写 SQL 为
     *       {@code UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?}，
     *       影响行数为 0 即代表"版本冲突"（别人先改了）</li>
     *   <li><b>但它只对 {@code updateById} / {@code update(entity, wrapper)}
     *       生效</b>，对手写的 Mapper XML 里的 UPDATE 完全无效 ——
     *       同理，手写 XML 更新时必须自己带上 {@code AND version = #{version}} 并自增</li>
     *   <li>本次 S07 只建骨架不写更新逻辑，但字段必须先在，否则 S09 起实现更新时
     *       会"以为有乐观锁其实没有"</li>
     * </ul>
     *
     * <p>与基类 {@code BaseEntity} 的 {@code deleted} 一样，
     * 这一列属于**并发控制内部字段**，不出现在 {@code CmsArticleVO} 里。
     */
    @Version
    @TableField("version")
    private Integer version;
}
