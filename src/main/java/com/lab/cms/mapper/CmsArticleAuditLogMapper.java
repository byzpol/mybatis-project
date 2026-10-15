package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticleAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章审核流水 Mapper。
 *
 * <p>本 Mapper 只需要 MP 的通用能力（插入流水、按文章 id 查询流水列表、
 * 按 {@code (article_id, audit_stage)} 判断是否已审），
 * 不额外声明自定义方法 —— <b>能用 BaseMapper + Wrapper 表达的，就不写 XML</b>，
 * 少写一处 XML 就少一处拼写错误与 SQL 注入的可能。
 *
 * <h3>本表查询的注意事项</h3>
 * <p>审核流水是审计记录，即使文章被逻辑删除，流水一般也应保留。
 * 但 {@code CmsArticleAuditLog} 继承 {@code BaseEntity} 且 {@code deleted}
 * 标了 {@code @TableLogic}，所以 MP 的查询<b>默认会过滤 {@code deleted = 0}</b>。
 * 如果确实需要"包括已软删的流水"，需要绕开 MP（手写 XML 或用
 * {@code @InterceptorIgnore(tenantLine = "...")} 之类），
 * 但本项目的业务约束是"不软删流水"，所以默认行为即符合预期，
 * 这里只把差异记在注释里，避免后来人误以为是 bug。
 */
@Mapper
public interface CmsArticleAuditLogMapper extends BaseMapper<CmsArticleAuditLog> {
}
