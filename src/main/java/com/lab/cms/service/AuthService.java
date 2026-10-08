package com.lab.cms.service;

import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.dto.LoginDTO;
import com.lab.cms.vo.LoginVO;

import javax.servlet.http.HttpServletRequest;

/**
 * 认证服务接口。
 *
 * <h3>为什么方法签名里要带 HttpServletRequest</h3>
 * <p>登录需要记录 IP 与 User-Agent（写 {@code sys_login_log}）。
 * 这些信息只能在 Web 层获取，若在 Service 里直接调
 * {@code RequestContextHolder}，会让 Service 隐式依赖 Web 上下文
 * （单测时必须 mock 静态方法，很别扭）。
 *
 * <p>显式传参的好处：Service 的方法签名直接体现了"我需要请求上下文"，
 * 单测时构造一个 mock request 即可。这是"显式优于隐式"的取舍。
 *
 * <p>另一种做法是把这两个值抽成参数（{@code login(LoginDTO dto, String ip, String userAgent)}），
 * 更纯粹但参数变多。本项目选择传 request，在 Service 内部提取。
 */
public interface AuthService {

    /**
     * 登录。
     *
     * <p>流程：
     * <ol>
     *   <li>按用户名查用户（含角色与权限）</li>
     *   <li>用户不存在 → 写登录失败日志 + 抛 {@code PASSWORD_ERROR}</li>
     *   <li>账号被禁用 → 写登录失败日志 + 抛 {@code USER_DISABLED}</li>
     *   <li>BCrypt 比对密码 → 失败则写日志 + 抛 {@code PASSWORD_ERROR}</li>
     *   <li>成功 → 构建 {@code LoginUser} 存入 Session、更新 lastLoginTime、写成功日志</li>
     * </ol>
     *
     * <p>⚠️ 步骤 2 与步骤 4 抛的是**同一个错误码**（{@code PASSWORD_ERROR}）。
     * 这是刻意的：若"用户不存在"返回"用户不存在"，
     * 攻击者可以用它来枚举系统里有哪些用户名（用户名枚举漏洞）。
     * 统一返回"用户名或密码错误"，让两种情况无法区分。
     *
     * @param dto     登录入参
     * @param request 用于取 IP 与 User-Agent，并写入 Session
     * @return 登录结果（含权限集合，供前端做按钮级控制）
     */
    LoginVO login(LoginDTO dto, HttpServletRequest request);

    /**
     * 登出。
     *
     * <p>清空 Session 并写登出日志。
     *
     * @param request 用于取 Session 与写日志
     */
    void logout(HttpServletRequest request);

    /**
     * 查询当前登录用户信息（用于前端刷新页面后恢复用户态）。
     *
     * @param user 当前登录用户（由 {@code @CurrentUser} 注入）
     */
    LoginVO currentUser(LoginUser user);
}
