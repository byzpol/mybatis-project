package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper。
 *
 * <h3>三个自定义方法的用途</h3>
 * <p>都服务于登录流程。用 JOIN + GROUP_CONCAT 一次查出角色码/权限码字符串，
 * 避免"先查角色、再逐个查权限"的 N+1 查询。
 *
 * <p>SQL 写在 {@code resources/com/lab/cms/mapper/SysUserMapper.xml}。
 *
 * <p>⚠️ 三个方法都在 XML 里显式写了 {@code deleted = 0} ——
 * MP 的 {@code @TableLogic} **只对自动生成的 SQL 生效**，
 * 手写 XML 必须自己加逻辑删除条件（本项目最容易踩的坑之一）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按用户名查用户（含密码哈希，供登录比对）。
     *
     * @param username 登录名
     * @return 用户；不存在返回 null
     */
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 查用户的角色码集合，返回以逗号分隔的字符串。
     *
     * @param userId 用户 ID
     * @return 如 {@code "ROLE_AUTHOR"}；无角色返回 null
     */
    String selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 查用户的权限码集合，返回以逗号分隔的字符串。
     *
     * @param userId 用户 ID
     * @return 如 {@code "article:create,article:submit"}；无权限返回 null
     */
    String selectPermissionCodesByUserId(@Param("userId") Long userId);
}
