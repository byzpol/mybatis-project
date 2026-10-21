package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 文章版本快照 Mapper。
 *
 * <p>本 Mapper 只需 MP 的通用能力，不额外声明方法。
 *
 * <h3>⚠️ 关于"取下一个版本号"</h3>
 * <p>典型写法是 {@code SELECT MAX(version_no) FROM cms_article_version WHERE article_id = ?}，
 * 然后 {@code +1} 再插入。本步（S07）不实现，但把要点先记在这里：
 * <ul>
 *   <li>不能"先查后插"就完事 —— 并发下两个请求可能拿到同一个号。
 *       库表有唯一索引 {@code uk_article_version(article_id, version_no)} 兜底，
 *       第二个插入会失败，业务层应<b>捕获唯一键冲突后重试</b>，而不是把异常抛给用户。</li>
 *   <li>取号与插入应在同一个事务里，避免中途被别的写操作插入。</li>
 * </ul>
 *
 * <h3>⚠️ 版本快照不做逻辑删除查询过滤的例外</h3>
 * <p>{@code CmsArticleVersion} 也标了 {@code @TableLogic}，
 * MP 查询会过滤 {@code deleted = 0}。业务上不删版本快照，
 * 所以默认行为符合预期，无需特殊处理 —— 与
 * {@link CmsArticleAuditLogMapper} 的注意事项同理。
 */
@Mapper
public interface CmsArticleVersionMapper extends BaseMapper<CmsArticleVersion> {

    /**
     * 查某篇文章当前的最大版本号；一条快照都没有时返回 {@code 0}。
     *
     * <p>为什么返回 {@code 0} 而不是 {@code null}：调用方拿到的号要 {@code +1}，
     * 返回 {@code null} 会逼每个调用点写一次判空（漏一处就是 NPE）。
     * 用 {@code IFNULL(...,0)} 把"没有快照"与"最大号是 0"统一成同一个语义 ——
     * 反正 {@code version_no} 是从 1 开始的正整数，0 不可能是一条真实快照的号。
     *
     * <p>⚠️ 为什么这个方法必须手写 SQL 而不是用 MP 的 {@code selectObjs} +
     * {@code QueryWrapper.select("IFNULL(MAX(version_no),0)")}：
     * 后者写出来是"把 SQL 片段塞进 Java 字符串"，聚合列的语义完全靠字符串表达，
     * 改一次就容易拼错且编译期无任何保护。本项目对"带聚合/函数的 SQL"
     * 一律走 XML，让 SQL 保持 SQL 的样子。
     *
     * <p>⚠️ XML 里**必须自己写 {@code deleted = 0}**（见实现）。同
     * {@link CmsArticleMapper#selectIdTitleByKeyword} 的理由。
     *
     * <p>⚠️ 本方法**不是**并发安全的取号方案，它只是取号的第一步。
     * "先查 MAX 再 +1 插入"在并发下两个请求会拿到同一个号 ——
     * 真正的兜底是库表唯一索引 {@code uk_article_version(article_id, version_no)}：
     * 第二个插入必然失败，业务层捕获唯一键冲突后重试即可。
     * 选择"取号 + 唯一索引重试"而不是"SELECT ... FOR UPDATE 行锁"的理由见
     * {@code ArticleServiceImpl#insertVersionSnapshot} 的注释。
     *
     * @param articleId 文章 ID
     * @return 当前最大版本号；无快照时为 0
     */
    Integer selectMaxVersionNo(@Param("articleId") Long articleId);
}
