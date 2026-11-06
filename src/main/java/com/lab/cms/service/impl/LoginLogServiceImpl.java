package com.lab.cms.service.impl;

import com.lab.cms.entity.SysLoginLog;
import com.lab.cms.mapper.SysLoginLogMapper;
import com.lab.cms.service.LoginLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 登录日志写入实现（独立事务）。
 *
 * <p>核心就是方法上那个 {@code propagation = Propagation.REQUIRES_NEW}：
 * 调用方（{@code AuthServiceImpl#login}）即使因为密码错误把外层事务回滚了，
 * 本方法开的这一个事务已经在<b>抛异常之前</b>提交完毕，日志留得下来。
 *
 * <p>⚠️ 为什么调 {@code REQUIRES_NEW} 时不担心连接池被占满：
 * 这只在登录这一条路径上、且方法体只有一条 INSERT 就结束，
 * 不会出现"长事务嵌套长事务"的堆积。Druid 默认连接数足够。
 */
@Service
public class LoginLogServiceImpl implements LoginLogService {

    private static final Logger log = LoggerFactory.getLogger(LoginLogServiceImpl.class);

    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(String username, Long userId, String ip, String userAgent,
                       int loginStatus, String message) {
        try {
            SysLoginLog entity = new SysLoginLog();
            entity.setUsername(truncate(username, 50));
            entity.setUserId(userId);
            entity.setIp(truncate(ip, 50));
            entity.setUserAgent(truncate(userAgent, 500));
            entity.setLoginStatus(loginStatus);
            entity.setMessage(truncate(message, 255));
            entity.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(entity);
        } catch (Exception e) {
            // ⚠️ 写日志失败绝不影响登录流程本身：用户不该因为日志表的问题看到 500。
            // ⚠️ 传异常对象而不是只拼 e.getMessage()：Spring 的 DataAccessException
            //    翻译后 getMessage() 可能为空，真正原因在 cause 里（与 WebLogAspect 同处理）。
            log.warn("登录日志写库失败（不影响登录流程） | username={}", username, e);
        }
    }

    /**
     * 字符串截断，防字段超长导致日志写入失败。
     *
     * <p>超长在 {@code STRICT_TRANS_TABLES} 下会抛 {@code DataIntegrityViolationException(1406)}，
     * 一条 UA 过长的移动端请求就能让整条日志写不进去 —— 所以先截断再写。
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
