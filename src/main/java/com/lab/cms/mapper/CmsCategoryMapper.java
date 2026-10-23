package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper。
 *
 * <p>本步（S11）只需要 MP 的通用能力，其中用到的是
 * {@code BaseMapper#selectBatchIds(Collection)} —— 详情/列表补 {@code categoryName} 时
 * <b>一次 {@code IN} 查询</b>取回一页涉及的全部分类，避免在循环里逐条查（N+1）。
 *
 * <p>⚠️ 与 {@link CmsArticleAuditLogMapper} 一样，这里<b>不写自定义 XML</b>：
 * 能用 BaseMapper + Wrapper 表达的就不写 XML，少写一处 XML 就少一处拼写错误
 * 与 SQL 注入的可能。分类的完整 CRUD 由分类管理模块负责，届时若出现
 * "按父级查树""按名称模糊查"这类需求，再按需加方法。
 */
@Mapper
public interface CmsCategoryMapper extends BaseMapper<CmsCategory> {
}
