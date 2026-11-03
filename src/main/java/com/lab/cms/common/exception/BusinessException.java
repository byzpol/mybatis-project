package com.lab.cms.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>业务规则不满足时抛本异常（而不是裸抛 {@code RuntimeException} 或 {@code Exception}），
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@code Result}。
 *
 * <h3>为什么继承 RuntimeException 而不是 Exception</h3>
 * <p>若继承受检异常 {@code Exception}，每个调用方都必须 {@code try-catch} 或
 * 在方法签名上 {@code throws}。而业务规则的失败（"状态不允许提交"）
 * 在每一层都是同样无法就地处理的 —— 只能一路往上抛给全局处理器。
 * 强制声明会污染整条调用链的签名，且让"本方法可能因业务规则失败"这个信息
 * 淹没在大量 {@code throws} 声明里。用非受检异常更贴合实际语义。
 *
 * <h3>为什么不直接用 Result.fail 返回而用异常</h3>
 * <p>如果用"返回 Result.fail"，那么每一层都要写：
 * <pre>
 *   Result&lt;Void&gt; r = service.submit(id);
 *   if (!r.isSuccess()) { return r; }   // 每层都要判一次，漏一处就是 bug
 * </pre>
 * 更严重的是：**写方法中间的失败无法用返回值表达**（比如事务中改了 3 张表，
 * 第 2 张表发现状态非法，此时只能抛异常）。
 * 因此约定：**写操作失败用异常，读操作的"查不到"用返回值（null / 空集合）**。
 *
 * <h3>异常信息与堆栈的取舍</h3>
 * <p>业务异常是**预期内**的失败（用户点了不该点的按钮），不是系统故障。
 * 因此：
 * <ul>
 *   <li>日志级别用 WARN 而不是 ERROR（见 GlobalExceptionHandler）</li>
 *   <li>不需要"填满堆栈" —— Java 的 {@code fillInStackTrace} 开销不小，
 *       高频业务校验场景可以覆写它返回 {@code this} 来省掉堆栈收集。
 *       本项目调用量低，**不做这个优化**（保留堆栈更利于排查），但要知道这个取舍点。</li>
 * </ul>
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码。全局处理器直接用它构造 Result。 */
    private final Integer code;

    /**
     * 用错误码枚举构造（最常用）。
     * <p>提示信息取枚举的默认 message。
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 用错误码枚举 + 自定义提示构造。
     * <p>用于"错误码相同但需要补充上下文"的场景，例如：
     * <pre>
     *   throw new BusinessException(ErrorCode.ARTICLE_STATUS_ILLEGAL,
     *           "文章当前状态为「已发布」，不允许提交初审");
     * </pre>
     * 这样前端 toast 能显示具体原因，比笼统的"文章当前状态不允许该操作"有用得多。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 用 code + message 直接构造。
     * <p>用于枚举未覆盖的临时场景。⚠️ 使用前先考虑是否该在 {@link ErrorCode} 中新增一枚枚举 —— 
     * 直接写数字会让"错误码含义"散落在代码各处，破坏集中管理。
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 带原因异常的构造。
     * <p>用于"捕获了底层异常（如 SQLException），转换为业务异常往上抛"的场景。
     * ⚠️ 转换时**必须把原异常作为 cause 传入**，否则堆栈断裂，
     * 排查时只能看到"业务异常"而看不到真正的根因（如具体的 SQL 错误）。
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }
}
