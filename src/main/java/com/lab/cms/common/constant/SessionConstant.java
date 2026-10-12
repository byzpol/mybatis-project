package com.lab.cms.common.constant;

/**
 * 全局常量：Session key、请求头名、固定角色码、分页默认值等。
 *
 * <p>对应《企业开发规范清单》2-2 条"禁止魔法值"。
 *
 * <h3>为什么用 final class + 私有构造而不是 interface</h3>
 * <p>用 interface 装常量是历史遗留的坏习惯（因为 interface 的字段隐式是 public static final，
 * 少写几个关键字），但它带来两个问题：
 * <ul>
 *   <li>任何人都能 {@code implements SessionConstant}，从而把常量"继承"进自己的命名空间，
 *       导致常量来源难追溯</li>
 *   <li>若 interface 被反序列化或代理，常量访问语义不清晰</li>
 * </ul>
 * 用 {@code final class} + 私有构造器是《Effective Java》推荐的做法，
 * 明确表达"这是一个工具类，只用来放常量，不应该被实例化或实现"。
 */
public final class SessionConstant {

    /** 私有构造：阻止实例化 */
    private SessionConstant() {
        throw new UnsupportedOperationException("常量类，禁止实例化");
    }

    // ==================== Session ====================

    /** Session 中登录用户的 key */
    public static final String LOGIN_USER = "LOGIN_USER";

    /** 登录超时（秒）：30 分钟。与 web 容器配置或 Session 的 maxInactiveInterval 配合 */
    public static final int SESSION_TIMEOUT_SECONDS = 30 * 60;

    // ==================== 数据库字段长度约束 ====================
    // 常量来源：sql/init.sql 中的 VARCHAR 定义。
    // 放在这里是为了让 DTO 的 @Size 校验与数据库定义**有同一个真相来源** ——
    // 若只写 @Size(max = 200) 而不写常量，数据库改成 300 时很容易漏改 Java 侧。

    /** 文章标题最大长度 */
    public static final int ARTICLE_TITLE_MAX_LENGTH = 200;
    /** 文章摘要最大长度 */
    public static final int ARTICLE_SUMMARY_MAX_LENGTH = 500;
    /** 驳回意见 / 审核意见最大长度 */
    public static final int AUDIT_OPINION_MAX_LENGTH = 500;
    /** 封面图 URL 最大长度 */
    public static final int COVER_IMAGE_MAX_LENGTH = 500;
    /** 分类名最大长度 */
    public static final int CATEGORY_NAME_MAX_LENGTH = 50;
    /** 标签名最大长度 */
    public static final int TAG_NAME_MAX_LENGTH = 30;

    // ==================== 固定角色码 ====================
    // 与 sql/init.sql 中 sys_role.role_code 的值严格一致。
    // 用常量而不是散落字符串：拼错 "ROLE_ADMIN " 这种带空格的，编译器不会报错。

    /** 超级管理员：拥有全部权限 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    /** 主编：拥有终审、发布、下架、归档权 */
    public static final String ROLE_CHIEF = "ROLE_CHIEF";
    /** 编辑：拥有初审权 */
    public static final String ROLE_EDITOR = "ROLE_EDITOR";
    /** 投稿人：只能创建、编辑、提交自己的文章 */
    public static final String ROLE_AUTHOR = "ROLE_AUTHOR";

    // ==================== 分页 ====================
    // 与 PageQuery 中的常量保持一致（此处冗余声明是为了让"非 Spring 组件"也能引用）

    /** 每页最大条数 */
    public static final int PAGE_MAX_SIZE = 100;
    /** 每页默认条数 */
    public static final int PAGE_DEFAULT_SIZE = 10;
}
