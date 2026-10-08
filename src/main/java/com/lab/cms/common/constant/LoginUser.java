package com.lab.cms.common.constant;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 登录用户会话对象。
 *
 * <p>由 {@code AuthServiceImpl} 登录成功后放入 {@code HttpSession}
 * （key = {@link SessionConstant#LOGIN_USER}）。
 *
 * <h3>⚠️ 全项目只认这一个用户模型</h3>
 * <p>不允许各业务域自行创建 {@code UserContext} / {@code CurrentUserHolder} /
 * {@code ThreadLocal<User>} 之类的"当前用户"载体。原因：
 * <ul>
 *   <li>四个域各有一份，则"当前用户是谁"有四种查法，Code Review 时无法统一检查</li>
 *   <li>ThreadLocal 在异步/线程池场景下会串数据（A 用户的请求拿到 B 用户的身份），
 *       是非常危险的一类 bug</li>
 *   <li>Session 方案在答辩时可以直接演示，优势明显</li>
 * </ul>
 *
 * <h3>为什么实现 Serializable</h3>
 * <p>Session 可能被容器序列化（如 Tomcat 重启时持久化 Session 到磁盘、
 * 或将来做会话共享）。不实现 Serializable 会导致这类场景抛
 * {@code NotSerializableException}，且问题在重启时才暴露，很难定位。
 *
 * <h3>为什么不放密码</h3>
 * <p>Session 中的对象可能被日志打印、被调试工具 dump、被序列化到磁盘。
 * 放密码（即使已哈希）没有意义 —— 后续鉴权只需要权限码集合，不需要再验密码。
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。水平越权校验的核心依据，与 cms_article.author_id 比对 */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 显示昵称。用于前端右上角展示、审核人姓名展示 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /**
     * 角色码集合，如 {@code ["ROLE_AUTHOR"]}。
     * <p>用 Set 而不是 List：
     * <ul>
     *   <li>角色天然不重复，Set 在结构上就杜绝了"同一角色出现两次"</li>
     *   <li>{@code contains} 是 O(1)，权限判断会被高频调用</li>
     * </ul>
     */
    private Set<String> roles;

    /**
     * 权限码集合，如 {@code ["article:create", "article:submit"]}。
     * <p>登录时一次性从数据库查出来放进 Session，
     * 避免每个请求都去查权限表（那样每个请求多 2~3 次数据库查询）。
     *
     * <p>⚠️ 代价：管理员在线给用户加权限后，该用户需要重新登录才生效。
     * 这是本项目明确接受的取舍（见 {@code docs/设计决策记录}）。
     * 若要求"即时生效"，需要引入权限缓存 + 失效通知机制，超出课程范围。
     */
    private Set<String> permissions;

    /**
     * 是否超级管理员。
     * <p>超管绕过所有权限判断。用独立的布尔字段而不是"判断 roles 里有没有 ROLE_ADMIN"，
     * 是为了让这个"特权"显式化 —— 在 Code Review 时能一眼看到哪些地方做了特权短路。
     */
    private Boolean superAdmin;

    /** 空集合常量，避免每次判空 */
    private static final Set<String> EMPTY_SET = Collections.emptySet();

    /**
     * 判断是否拥有某权限。
     *
     * <p>超管恒为 true —— 这是"特权短路"的唯一实现点，
     * 其他所有权限判断都应走本方法（而不是自己写 {@code permissions.contains(...)}），
     * 这样"超管绕过"的规则只需维护一处。
     *
     * @param code 权限码，格式 "资源:动作"
     */
    public boolean hasPermission(String code) {
        if (Boolean.TRUE.equals(superAdmin)) {
            return true;
        }
        if (code == null) {
            return false;
        }
        Set<String> perms = permissions == null ? EMPTY_SET : permissions;
        return perms.contains(code);
    }

    /**
     * 判断是否拥有任一权限（OR 关系）。
     * <p>用于"部门主管或编辑都能通过审批"这类场景。
     */
    public boolean hasAnyPermission(String... codes) {
        if (Boolean.TRUE.equals(superAdmin)) {
            return true;
        }
        if (codes == null || codes.length == 0) {
            return false;
        }
        Set<String> perms = permissions == null ? EMPTY_SET : permissions;
        for (String code : codes) {
            if (perms.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否拥有全部权限（AND 关系）。
     */
    public boolean hasAllPermissions(String... codes) {
        if (Boolean.TRUE.equals(superAdmin)) {
            return true;
        }
        if (codes == null || codes.length == 0) {
            return false;
        }
        Set<String> perms = permissions == null ? EMPTY_SET : permissions;
        for (String code : codes) {
            if (!perms.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否拥有某角色。
     * <p>⚠️ 注意：角色判断通常应**避免**用于业务逻辑分支。
     * 更好的方式是用权限码（"能不能发布"看 {@code article:publish} 权限，
     * 而不是看"是不是主编角色"）。原因：角色是权限的集合，
     * 若代码里到处判断角色，将来"给某个编辑单独加发布权限"就得改代码。
     * 角色判断仅用于：① 展示需要（如显示"主编"标签）② 超管特判。
     */
    public boolean hasRole(String roleCode) {
        if (roleCode == null) {
            return false;
        }
        Set<String> roleSet = roles == null ? EMPTY_SET : roles;
        return roleSet.contains(roleCode);
    }

    /**
     * 只读视图的权限集合，避免外部拿到后修改 Session 中的对象。
     */
    public Set<String> getPermissions() {
        return permissions == null ? EMPTY_SET : Collections.unmodifiableSet(permissions);
    }

    /**
     * 只读视图的角色集合。
     */
    public Set<String> getRoles() {
        return roles == null ? EMPTY_SET : Collections.unmodifiableSet(roles);
    }
}
