package com.lab.cms.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>全项目所有 Controller 的返回值都必须是 {@code Result} 或 {@code Result<PageResult<T>>}，
 * 不允许直接返回实体、List、Map —— 见《企业开发规范清单》1-8 条。
 *
 * <h3>为什么不直接返回业务对象</h3>
 * <p>若接口直接返回 {@code ArticleVO}，那么：
 * <ul>
 *   <li>"文章不存在"这种业务失败没有地方放错误信息（只能靠 HTTP 状态码，见契约文档 §3.3 的取舍）</li>
 *   <li>前端需要对每个接口分别处理"返回的是对象还是错误"</li>
 *   <li>将来加统一字段（如 traceId、服务器时间）需要改所有接口签名</li>
 * </ul>
 * 用统一包装体后，前端拦截器只需在一处判 {@code code}，业务代码只需关注 {@code data}。
 *
 * <h3>为什么带 timestamp</h3>
 * <p>前端排查"数据不一致"时，第一件事是确认"我看到的是哪个时刻的数据"。
 * 服务端时间戳能让"缓存/时序"类问题一眼看出，成本仅 8 字节。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码：200 表示成功，其余见 {@link com.lab.cms.common.exception.ErrorCode} */
    private Integer code;

    /** 提示信息：成功固定 "操作成功"，失败为可直接展示给用户的中文原因 */
    private String message;

    /** 业务数据：失败时为 null */
    private T data;

    /** 服务器时间戳（毫秒），便于前端排查时序问题 */
    private Long timestamp;

    /**
     * 私有构造：强制使用静态工厂方法创建。
     * <p>好处是调用方不可能"忘记设 code"或"忘设 message"，
     * 也不可能构造出 {@code code=null} 的非法响应体。
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 成功 ====================

    /** 成功，无数据。用于新增/修改/删除等只关心"成没成"的接口。 */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    /** 成功，带数据。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /** 成功，带自定义提示与数据（如"已提交审核"）。 */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    // ==================== 失败 ====================

    /**
     * 失败，用错误码枚举（绝大多数场景用这个）。
     * <p>错误码与提示信息一一对应，避免同一个错误在不同地方写出不同的文案。
     */
    public static <T> Result<T> fail(com.lab.cms.common.exception.ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败，用错误码枚举 + 自定义提示。
     *
     * <p>用于"同一个错误码但需要补上下文"的场景。例如：
     * <pre>
     *   // 错误码是 ARTICLE_STATUS_ILLEGAL，但提示要说明具体是什么状态
     *   Result.fail(ErrorCode.ARTICLE_STATUS_ILLEGAL,
     *               "文章当前状态为「已发布」，不允许提交审核")
     * </pre>
     */
    public static <T> Result<T> fail(com.lab.cms.common.exception.ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 失败，直接用 code + message。
     * <p>用于 HTTP 语义错误（400/404/405/415/500）这类没有业务枚举的场景。
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 判断是否成功。
     * <p>供 Service 内部或测试断言使用，避免在调用方硬编码 {@code == 200}。
     *
     * <p>⚠️ 必须加 {@code @JsonIgnore}：Lombok 的 {@code @Data} 会把
     * {@code isSuccess()} 当成一个名为 {@code success} 的布尔属性，
     * 序列化时输出多余的 {@code "success":true} 字段。
     * 虽然不影响功能，但它不在接口契约里（前端不该看到它），
     * 且会让响应体多出无用字段。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSuccess() {
        return this.code != null && this.code == 200;
    }
}
