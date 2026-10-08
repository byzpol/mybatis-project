package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.SysOperLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper。
 *
 * <h3>⚠️ 为什么直接继承 BaseMapper 而不是像接口契约里说的"只暴露 insert"</h3>
 * <p>接口契约的设计意图是"日志只增不改"。但为了：
 * <ul>
 *   <li>让 S14/S19 的日志查询需求（成员D 的域，但本项目也要展示）能直接用
 *       {@code selectPage} / {@code selectCount}</li>
 *   <li>避免重复实现通用的查询方法</li>
 * </ul>
 * 这里仍然继承 {@code BaseMapper}。**"只增不改"的约束靠 Code Review 保证**，
 * 而不是靠类型系统（Java 的接口无法"隐藏继承来的方法"）。
 *
 * <p>在 {@code docs/企业开发规范清单.md} 中记录这条"约定优于限制"的取舍。
 *
 * <h3>关于 @Mapper 注解</h3>
 * <p>本项目已在 {@code MybatisConfig} 中用 {@code MapperScannerConfigurer}
 * 扫描 {@code com.lab.cms.mapper} 包，因此 {@code @Mapper} 注解**不是必需的**。
 * 但显式加上有好处：
 * <ul>
 *   <li>IDEA 能识别这是 Mapper 接口，在 XML 里写 SQL 时有自动补全与"未绑定语句"告警</li>
 *   <li>若将来有人移除扫描配置改用 {@code @MapperScan} 或注解方式，不会漏掉</li>
 *   <li>Code Review 时一眼能看出哪些是 Mapper</li>
 * </ul>
 */
@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {
}
