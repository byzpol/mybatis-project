package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.SysReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统计报表 Mapper。
 *
 * <p>本表在 S24 之前没有任何 Java 侧的访问入口（只有建表语句与 1 条种子数据），
 * 这是它的第一个 Mapper。
 *
 * <p>只用 {@code BaseMapper} 的通用能力（{@code insert} / {@code selectPage}），
 * 不写自定义 SQL、不写 XML —— 报表的"统计分组"逻辑不在本表上做：
 * 那些聚合查询打在 {@code cms_article} / {@code sys_login_log} 等业务表上，
 * 由各自的 Mapper 提供（见 {@code LogMapper} 段落里的说明）。
 * 本表只负责"把算好的结果 JSON 存下来"这一件事。
 */
@Mapper
public interface SysReportMapper extends BaseMapper<SysReport> {
}
