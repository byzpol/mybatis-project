package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticleVersion;
import org.apache.ibatis.annotations.Mapper;

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
}
