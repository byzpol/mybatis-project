package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.SysLoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志 Mapper。
 *
 * <p>与 {@link SysOperLogMapper} 同理：继承 {@code BaseMapper} 以获得通用查询能力，
 * "只增不改"的约束靠 Code Review 保证。
 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
