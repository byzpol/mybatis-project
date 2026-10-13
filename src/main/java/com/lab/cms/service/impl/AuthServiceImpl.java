package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.constant.SessionConstant;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.CurrentUserArgumentResolver;
import com.lab.cms.dto.LoginDTO;
import com.lab.cms.entity.SysLoginLog;
import com.lab.cms.entity.SysUser;
import com.lab.cms.mapper.SysLoginLogMapper;
import com.lab.cms.mapper.SysUserMapper;
import com.lab.cms.service.AuthService;
import com.lab.cms.vo.LoginVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 认证服务实现。
 *
 * <h3>⚠️ 三个安全要点</h3>
 *
 * <p><b>要点 1：用户不存在与密码错误返回同一个错误码。</b>
 * 若返回不同的提示，攻击者可以据此枚举系统里有哪些用户名。
 * 这是"用户名枚举漏洞"，属于 OWASP 收录的常见安全问题。
 *
 * <p><b>要点 2：登出时用 {@code invalidate()} 而不是 {@code removeAttribute()}。</b>
 * {@code removeAttribute} 只清掉一个属性，Session 本身还在，
 * JSESSIONID 仍有效。若攻击者已拿到该 JSESSIONID，
 * 他仍能创建一个新的登录态（会话固定攻击 Session Fixation）。
 * {@code invalidate()} 销毁整个 Session，服务端不再认这个 ID。
 *
 * <p><b>要点 3：不记录密码。</b>
 * 登录失败写日志时只记录"用户名或密码错误"，
 * <b>绝不</b>记录用户输入的密码，也不记录密码哈希。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /**
     * BCrypt 密码编码器。
     *
     * <p>⚠️ 用 {@code new BCryptPasswordEncoder()} 直接创建而不是从 Spring 容器注入：
     * 本项目没有引入完整的 Spring Security（只用 spring-security-crypto），
     * 因此没有自动配置的 PasswordEncoder Bean。
     * BCryptPasswordEncoder 本身是线程安全的（无状态），直接 new 成字段即可。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginDTO dto, HttpServletRequest request) {
        String username = dto.getUsername();
        String clientIp = CurrentUserArgumentResolver.getClientIp();
        String userAgent = request.getHeader("User-Agent");

        // ---- 1. 按用户名查用户 ----
        SysUser user = sysUserMapper.selectByUsername(username);

        // ---- 2. 用户不存在 ----
        // ⚠️ 与"密码错误"用同一个错误码（防用户名枚举），但日志里可以写清楚
        if (user == null) {
            log.warn("登录失败：用户不存在 | username={} | ip={}", username, clientIp);
            saveLoginLog(username, null, clientIp, userAgent, 0, "用户名或密码错误");
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        // ---- 3. 账号被禁用 ----
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("登录失败：账号被禁用 | username={} | userId={} | ip={}",
                    username, user.getId(), clientIp);
            saveLoginLog(username, user.getId(), clientIp, userAgent, 0, "账号已被禁用");
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        // ---- 4. 密码比对 ----
        // BCrypt 的 matches(明文, 哈希) —— 注意参数顺序：第一个是明文，第二个是哈希。
        // 顺序反了会永远返回 false（且不报错），是 BCrypt 最常见的误用。
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败：密码错误 | username={} | userId={} | ip={}",
                    username, user.getId(), clientIp);
            saveLoginLog(username, user.getId(), clientIp, userAgent, 0, "用户名或密码错误");
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        // ---- 5. 查询角色与权限 ----
        Set<String> roles = splitToSet(sysUserMapper.selectRoleCodesByUserId(user.getId()));
        Set<String> permissions = splitToSet(sysUserMapper.selectPermissionCodesByUserId(user.getId()));
        boolean superAdmin = roles.contains(SessionConstant.ROLE_ADMIN);

        // ---- 6. 构建会话用户 ----
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setRoles(roles);
        loginUser.setPermissions(permissions);
        loginUser.setSuperAdmin(superAdmin);

        // ---- 7. 写入 Session ----
        // ⚠️ 会话固定攻击防护：先 invalidate 旧 Session 再创建新的，
        //    确保登录前后 JSESSIONID 不同。
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionConstant.LOGIN_USER, loginUser);
        session.setMaxInactiveInterval(SessionConstant.SESSION_TIMEOUT_SECONDS);

        // ---- 8. 更新最近登录时间 ----
        // ⚠️ 用 UpdateWrapper 只更新 last_login_time 这一列，而不是 updateById(整个实体)。
        //    原因：updateById 会连带写入审计字段（update_by/update_time），
        //    而登录时刻用户**刚 invalidate 掉旧 Session、新 Session 尚未被后续请求读到**，
        //    审计填充器拿不到登录用户，早期实现因此写入了 null 并被数据库
        //    （update_time NOT NULL）拒绝，抛 Column 'update_time' cannot be null。
        //    用 UpdateWrapper 精确到列，既不依赖审计填充，也避免误改其它字段。
        //    ⚠️ 注意：这是"系统行为"而非"用户业务操作"，不写 update_by 是有意为之 ——
        //    让"最后登录时间"不被误读为"某人修改过该账号"。
        sysUserMapper.update(null,
                new UpdateWrapper<SysUser>()
                        .eq("id", user.getId())
                        .set("last_login_time", LocalDateTime.now()));

        // ---- 9. 写成功日志 ----
        saveLoginLog(username, user.getId(), clientIp, userAgent, 1, "登录成功");

        log.info("登录成功 | username={} | userId={} | roles={} | 权限数={} | ip={}",
                username, user.getId(), roles,
                permissions == null ? 0 : permissions.size(), clientIp);

        // ---- 10. 组装返回 ----
        return toLoginVO(loginUser, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Object attr = session.getAttribute(SessionConstant.LOGIN_USER);
        if (attr instanceof LoginUser) {
            LoginUser user = (LoginUser) attr;
            log.info("登出 | username={} | userId={}", user.getUsername(), user.getUserId());
            saveLoginLog(user.getUsername(), user.getUserId(),
                    CurrentUserArgumentResolver.getClientIp(),
                    request.getHeader("User-Agent"), 1, "登出成功");
        }
        // ⚠️ 必须 invalidate（见类注释要点 2）
        session.invalidate();
    }

    @Override
    public LoginVO currentUser(LoginUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return toLoginVO(user, null);
    }

    // ==================== 私有方法 ====================

    /**
     * 把逗号分隔的字符串切成 Set。
     *
     * <p>用 {@code LinkedHashSet} 保持数据库返回的顺序（GROUP_CONCAT 的顺序），
     * 便于前端调试时看到稳定的顺序。
     *
     * @return 永不为 null（查不到时返回空集合）
     */
    private Set<String> splitToSet(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(Arrays.asList(commaSeparated.split(",")));
    }

    /**
     * 写登录日志。
     *
     * <p>⚠️ 单独 try-catch：写日志失败绝不影响登录流程本身。
     * 用户在登录时因为日志表问题看到 500，是极差的体验。
     *
     * <p>⚠️ 本方法**不接收密码参数** —— 从类型上就杜绝了密码流入日志的可能。
     */
    private void saveLoginLog(String username, Long userId, String ip,
                              String userAgent, int status, String message) {
        try {
            SysLoginLog entity = new SysLoginLog();
            entity.setUsername(truncate(username, 50));
            entity.setUserId(userId);
            entity.setIp(truncate(ip, 50));
            entity.setUserAgent(truncate(userAgent, 500));
            entity.setLoginStatus(status);
            entity.setMessage(truncate(message, 255));
            entity.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(entity);
        } catch (Exception e) {
            // ⚠️ 传异常对象而不是只拼 e.getMessage()：Spring 的 DataAccessException
            //    翻译后 getMessage() 可能为空，只有 cause 里才有真正原因（见 WebLogAspect 同类处理）
            log.warn("登录日志写库失败（不影响登录流程） | username={}", username, e);
        }
    }

    /**
     * 组装 LoginVO。
     */
    private LoginVO toLoginVO(LoginUser user, LocalDateTime loginTime) {
        LoginVO vo = new LoginVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRoles(user.getRoles());
        vo.setPermissions(user.getPermissions());
        vo.setSuperAdmin(user.getSuperAdmin());
        vo.setLoginTime(loginTime);
        return vo;
    }

    /** 字符串截断，防字段超长导致日志写入失败 */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
