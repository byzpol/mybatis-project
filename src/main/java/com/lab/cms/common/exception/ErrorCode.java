package com.lab.cms.common.exception;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <h3>分段规则（各负责人只能在自己段位内新增码）</h3>
 * <table border="1">
 *   <tr><th>段位</th><th>归属</th><th>使用人</th></tr>
 *   <tr><td>200</td><td>成功</td><td>—</td></tr>
 *   <tr><td>400/401/403/404/405/409/415/500</td><td>HTTP 语义错误</td><td>主干统一抛</td></tr>
 *   <tr><td>10000~10999</td><td>通用业务码</td><td>主干（成员A）</td></tr>
 *   <tr><td>11000~11999</td><td>文章域</td><td>成员A</td></tr>
 *   <tr><td>12000~12999</td><td>分类与标签域</td><td>成员B</td></tr>
 *   <tr><td>13000~13999</td><td>用户权限与授权审批域</td><td>成员C</td></tr>
 *   <tr><td>14000~14999</td><td>日志与统计域</td><td>成员D</td></tr>
 * </table>
 *
 * <p>新增码后必须同步到 {@code docs/接口契约.md}，否则前端无从得知这个码的含义。
 *
 * <h3>message 的写法要求</h3>
 * <p>每个 message 必须是**可直接展示给用户看的中文**，不要写 "ARTICLE_NOT_FOUND" 这类英文，
 * 也不要写 "状态错误" 这种没信息量的。前端会直接把 message 弹成 toast，
 * 好的 message 能让用户知道"我该做什么"。
 */
@Getter
public enum ErrorCode {

    // ==================== HTTP 语义错误（与状态码一致）====================

    /** 操作成功 */
    SUCCESS(200, "操作成功"),
    /** 参数不合法（@Valid 校验失败） */
    PARAM_ERROR(400, "请求参数不合法"),
    /** 未登录或登录已过期（会触发前端跳转登录页） */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 没有该操作权限（垂直越权） */
    FORBIDDEN(403, "没有该操作权限"),
    /** 请求的资源不存在 */
    NOT_FOUND(404, "请求的资源不存在"),
    /** 请求方法不支持（如对只支持 POST 的接口发了 GET） */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    /** 数据状态冲突（并发修改、重复操作等） */
    CONFLICT(409, "数据状态冲突，请刷新后重试"),
    /** 不支持的请求格式（如该传 JSON 却传了表单） */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的请求格式"),
    /** 系统内部错误（兜底） */
    SYSTEM_ERROR(500, "系统内部错误，请联系管理员"),

    // ==================== 10xxx 通用业务码 ====================

    /** 登录已过期 */
    TOKEN_EXPIRED(10001, "登录已过期，请重新登录"),
    /** 重复提交 */
    REPEAT_SUBMIT(10002, "请勿重复提交"),
    /** 数据不存在 */
    DATA_NOT_EXIST(10003, "数据不存在或已被删除"),
    /** 数据已存在 */
    DATA_ALREADY_EXIST(10004, "数据已存在"),
    /** 操作过于频繁 */
    OPERATION_TOO_FREQUENT(10005, "操作过于频繁，请稍后再试"),
    /** 文件上传失败 */
    FILE_UPLOAD_FAILED(10006, "文件上传失败"),
    /** 水平越权：无权访问非本人所有的数据 */
    NO_PERMISSION_RESOURCE(10007, "无权访问该数据"),

    // ==================== 11xxx 文章域 ====================

    /** 文章不存在 */
    ARTICLE_NOT_FOUND(11001, "文章不存在"),
    /** 文章当前状态不允许该操作（状态机拦截） */
    ARTICLE_STATUS_ILLEGAL(11002, "文章当前状态不允许该操作"),
    /** 水平越权：只能操作自己的文章 */
    ARTICLE_NOT_OWNER(11003, "只能操作自己创建的文章"),
    /** 不能审核自己创建的文章 */
    ARTICLE_CANNOT_AUDIT_SELF(11004, "不能审核自己创建的文章"),
    /** 该阶段已审核过 */
    ARTICLE_ALREADY_AUDITED(11005, "该阶段已审核过，请勿重复审核"),
    /** 不可发布 */
    ARTICLE_NOT_PUBLISHABLE(11006, "只有审核通过的文章才能发布"),
    /** 排期时间非法 */
    ARTICLE_SCHEDULE_TIME_INVALID(11007, "排期时间必须晚于当前时间"),
    /** 不在排期状态 */
    ARTICLE_NOT_SCHEDULED(11008, "该文章不在排期发布状态"),
    /** 版本不存在 */
    ARTICLE_VERSION_NOT_FOUND(11009, "指定的版本不存在"),
    /** 乐观锁冲突 */
    ARTICLE_UPDATE_CONFLICT(11010, "文章已被他人修改，请刷新后重试"),
    /** 已归档只读 */
    ARTICLE_ARCHIVED_READONLY(11011, "已归档的文章不可修改"),
    /** 标题重复 */
    ARTICLE_TITLE_DUPLICATE(11012, "已存在同名文章"),
    /** 只有草稿或驳回态可删除 */
    ARTICLE_DRAFT_ONLY(11013, "仅草稿或已驳回状态的文章可删除"),
    /** 驳回必填意见 */
    ARTICLE_AUDIT_OPINION_REQUIRED(11014, "驳回时必须填写审核意见"),

    // ==================== 12xxx 分类与标签域（成员B 扩充）====================

    /** 分类不存在 */
    CATEGORY_NOT_FOUND(12001, "分类不存在"),
    /** 有子分类不可删 */
    CATEGORY_HAS_CHILDREN(12002, "该分类下存在子分类，不可删除"),
    /** 有文章不可删 */
    CATEGORY_HAS_ARTICLES(12003, "该分类下存在文章，不可删除"),
    /** 同级重名 */
    CATEGORY_NAME_DUPLICATE(12004, "同级下已存在同名分类"),
    /** 分类已停用 */
    CATEGORY_DISABLED(12005, "该分类已停用，不可选用"),
    /** 分类已归档 */
    CATEGORY_ARCHIVED_READONLY(12006, "已归档的分类不可修改"),
    /** 标签重名 */
    TAG_NAME_DUPLICATE(12007, "已存在同名标签"),
    /** 标签不存在 */
    TAG_NOT_FOUND(12008, "标签不存在"),

    // ==================== 13xxx 用户权限与授权审批域（成员C 扩充）====================

    /** 用户不存在 */
    USER_NOT_FOUND(13001, "用户不存在"),
    /** 用户名被占用 */
    USERNAME_ALREADY_EXIST(13002, "用户名已被占用"),
    /** 用户名或密码错误 */
    PASSWORD_ERROR(13003, "用户名或密码错误"),
    /** 账号被禁用 */
    USER_DISABLED(13004, "账号已被禁用，请联系管理员"),
    /** 角色不存在 */
    ROLE_NOT_FOUND(13005, "角色不存在"),
    /** 角色被使用中不可删 */
    ROLE_CODE_IN_USE(13006, "该角色已被用户使用，不可删除"),
    /** 权限不存在 */
    PERMISSION_NOT_FOUND(13007, "权限不存在"),
    /** 申请已被处理 */
    APPLY_APPROVAL_ALREADY_HANDLED(13008, "该申请已被处理"),
    /** 不能给自己授权 */
    CANNOT_GRANT_SELF(13009, "不能给自己授权"),
    /** 不可变更超管权限 */
    CANNOT_GRANT_SUPER_ADMIN(13010, "不可变更超级管理员的权限"),

    // ==================== 14xxx 日志与统计域（成员D 扩充）====================

    /** 不支持的报表类型 */
    REPORT_TYPE_UNSUPPORTED(14001, "不支持的报表类型"),
    /** 报表生成失败 */
    REPORT_GENERATE_FAILED(14002, "报表生成失败"),
    /** 日志导出失败 */
    LOG_EXPORT_FAILED(14003, "日志导出失败");

    /** 错误码 */
    private final Integer code;

    /** 可直接展示给用户的中文提示 */
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
