package com.lab.cms.common.exception;

import com.lab.cms.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>职责：捕获所有从 Controller 抛出的异常，统一转换为 {@link Result} 返回，
 * 并且**保证异常不会泄漏到响应体里**（避免把堆栈信息、SQL 语句暴露给客户端）。
 *
 * <h3>@RestControllerAdvice 的两个组成部分</h3>
 * <p>{@code @RestControllerAdvice} = {@code @ControllerAdvice} + {@code @ResponseBody}：
 * 前者让本类对所有 Controller 生效，后者让返回值自动序列化成 JSON。
 * 若只用 {@code @ControllerAdvice}，返回的 Result 对象会被当成"视图名"，
 * 导致 SpringMVC 去找一个叫 "Result" 的 JSP 页面 —— 症状是 404 或奇怪的视图解析错误。
 *
 * <h3>⚠️ 本类必须同时在 SpringMVC 子容器可见</h3>
 * <p>本类在 {@code com.lab.cms.common.exception} 包下，被 {@code SpringConfig} 的
 * {@code @ComponentScan} 扫进**根容器**。而 {@code @ControllerAdvice} 生效需要
 * SpringMVC 子容器里有这个 Bean。
 *
 * <p>由于子容器能访问父容器的 Bean，且 ExceptionHandlerExceptionResolver
 * 会从根容器与子容器共同查找 advice，因此**本类放在根容器是可用的**。
 * 但更稳妥的做法是把 Web 层相关组件（本类、拦截器）扫进子容器 ——
 * 本项目的 {@code SpringMvcConfig} 只扫 {@code @Controller}，
 * 所以本类实际注册在根容器，由子容器通过父容器查找。这是可行且常见的方式，
 * 但需知悉：若将来出现"异常处理器不生效"，第一件事是确认它被注册到了哪个容器。
 *
 * <h3>异常处理的粒度顺序</h3>
 * <p>SpringMVC 会选择"最具体"的处理器（继承关系最近的）。
 * 因此本类中：
 * <ul>
 *   <li>具体异常（BusinessException、MethodArgumentNotValidException…）各自一个方法</li>
 *   <li>最后用一个 {@code Exception} 兜底 —— 它永远不会被前面的具体处理器抢走</li>
 * </ul>
 *
 * <h3>⚠️ HTTP 状态码必须与业务码一致（本类最重要的一条设计）</h3>
 *
 * <p><b>反模式</b>：所有异常都返回 HTTP 200，把错误只写在响应体的 {@code code} 字段里。
 * 这是很多项目的常见做法（"HTTP 恒 200 前端只看 code"），但它有三个硬伤：
 * <ol>
 *   <li><b>前端拦截器无法工作</b>：axios 的 {@code validateStatus} 默认只把 2xx 当成功，
 *       业务错误若走 200，就既要写成功分支又要判 {@code code}，两套逻辑混杂。</li>
 *   <li><b>网关/监控失明</b>：Nginx、SLB、APM 的健康检查与错误率统计都基于 HTTP 状态码。
 *       一个 401 横飞的系统在监控里显示"100% 成功率"，故障无法被及时发现。</li>
 *   <li><b>缓存与重试语义错乱</b>：中间层无法区分"请求成功但业务失败"与"请求本身失败"。</li>
 * </ol>
 *
 * <p><b>本项目的做法</b>：HTTP 状态码与业务码<strong>同时</strong>设置，两者语义一致。
 * 例如未登录 → HTTP 401 + {@code {"code":401,...}}；
 * 越权 → HTTP 403 + {@code {"code":13001,...}}。
 * 前端既可基于 HTTP 状态做统一拦截（推荐），也可读 {@code code} 做精细提示，两条路都通。
 *
 * <p><b>唯一的例外</b>：参数校验类错误（{@code PARAM_ERROR}）返回 HTTP 400 而非 200 ——
 * 它确实是"客户端请求有问题"，用 400 是标准语义。
 *
 * <p>⚠️ 实现细节：{@code @ResponseStatus} 注解只能写死常量，无法按异常动态取值，
 * 因此统一通过 {@link #writeStatus(HttpServletResponse, int)} 手动设置，
 * 并在每个 handler 里显式调用。这是本类所有方法都多一个 {@code HttpServletResponse} 参数的原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 把业务码同步写到 HTTP 状态码上。
     *
     * <p>⚠️ 为什么要夹一层方法而不是直接 {@code response.setStatus(code)}：
     * <ol>
     *   <li>集中处理"业务码不在合法 HTTP 范围"的情况 —— 本项目的业务码有 10xxx~14xxx 段，
     *       这些数字直接当 HTTP 状态码是非法的。此方法会把超出 100~599 的值回退为 200
     *       （业务细节由响应体的 code 字段承载）。</li>
     *   <li>让"哪些异常需要同步状态码"这件事在代码里一眼可见（每个 handler 都显式调用）。</li>
     * </ol>
     */
    private void writeStatus(HttpServletResponse response, int code) {
        if (code >= 100 && code <= 599) {
            response.setStatus(code);
        }
        // 业务码（如 11001 状态机非法流转）不映射到 HTTP 状态码：
        // 那类错误本质是"请求合法但业务被拒"，HTTP 200 + 业务码是合适的表达。
    }

    /**
     * 业务异常。
     *
     * <p>⚠️ 日志级别用 <b>WARN</b> 而不是 ERROR：
     * 业务异常是"预期内的失败"（用户点了不该点的按钮、状态不允许操作），
     * 不是系统故障。若用 ERROR，会导致 cms-error.log 里塞满这类正常业务拦截，
     * 真正需要人工介入的系统故障就被淹没了。
     *
     * <p>不打印堆栈：业务异常没有排查价值（我们知道它从哪抛的），
     * 打印堆栈只会让日志膨胀。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) {
        log.warn("业务异常 | {} {} | code={} | msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        writeStatus(response, e.getCode());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * {@code @RequestBody} 上的 {@code @Valid} 校验失败。
     *
     * <p>这是最常用的参数校验异常。会收集**所有**字段的错误信息，
     * 拼接成一条可读的 message，而不是只报第一个错误 ——
     * 用户填错 3 个字段，一次告知比让他改 3 次体验好得多。
     *
     * <p>格式：{@code "参数校验失败：title 不能为空；categoryId 必须为正整数"}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), message);
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, "参数校验失败：" + message);
    }

    /**
     * 表单/Bean 参数绑定校验失败（非 @RequestBody 场景，如 GET 的查询 DTO）。
     *
     * <p>与 {@code MethodArgumentNotValidException} 的区别：
     * 前者是"JSON 反序列化后校验"，本异常是"参数绑定到对象后校验"。
     * 两者都需要处理，否则 GET 请求的校验失败会走到兜底分支，
     * 用户看到的是"系统内部错误"而不是具体的字段提示。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), message);
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, "参数校验失败：" + message);
    }

    /**
     * 方法参数上的约束校验失败（如 {@code @PathVariable @Positive Long id}）。
     *
     * <p>需要在 Controller 类上标 {@code @Validated} 才会触发这类校验。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数约束校验失败 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), message);
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, "参数校验失败：" + message);
    }

    /**
     * 缺少必需的请求参数。
     * <p>如 {@code @RequestParam("id") Long id} 但请求里没带 id。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        String message = "缺少必需参数：" + e.getParameterName();
        log.warn("缺少请求参数 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), message);
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 参数类型不匹配。
     * <p>如 {@code @PathVariable Long id} 但前端传了 {@code id=abc}，
     * 会导致 Spring 转换失败。不处理的话会暴露 "Failed to convert value of type..." 这类英文报错。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        String requiredType = e.getRequiredType() == null ? "正确类型" : e.getRequiredType().getSimpleName();
        String message = String.format("参数 %s 类型不正确，应为 %s", e.getName(), requiredType);
        log.warn("参数类型不匹配 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), message);
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 请求体不可读（JSON 格式错误、JSON 里的字段类型对不上等）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        log.warn("请求体解析失败 | {} {} | {}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        writeStatus(response, ErrorCode.PARAM_ERROR.getCode());
        return Result.fail(ErrorCode.PARAM_ERROR, "请求体格式不正确，请检查 JSON 格式");
    }

    /**
     * 请求方法不支持。
     * <p>⚠️ 用 HTTP 405 状态码而不是 200 + 业务码：
     * 这是**协议级**错误（对 POST 接口发 GET），不是业务结果，
     * 用标准 HTTP 语义更利于排查与工具（如 ApiFox）识别。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        log.warn("请求方法不支持 | {} {} | 支持的方法：{}",
                request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
        writeStatus(response, ErrorCode.METHOD_NOT_ALLOWED.getCode());
        return Result.fail(ErrorCode.METHOD_NOT_ALLOWED.getCode(),
                String.format("该接口不支持 %s 方法", e.getMethod()));
    }

    /**
     * 请求媒体类型不支持。
     * <p>典型场景：接口要求 {@code application/json}，但前端用了表单提交。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        log.warn("请求格式不支持 | {} {} | Content-Type={}",
                request.getMethod(), request.getRequestURI(), e.getContentType());
        writeStatus(response, ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode());
        return Result.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 找不到处理器（404）。
     *
     * <p>⚠️ 需要开启 {@code throwExceptionIfNoHandlerFound} 才会走到这里，
     * 否则 DispatcherServlet 直接返回容器的 404 页面。
     * 本项目不额外开启（SpringMVC 5.3 默认 false），所以本处理器作为"尽力而为"的兜底，
     * 主要价值是当配置改变时能给出统一的 JSON 404 响应。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        log.warn("接口不存在 | {} {}", request.getMethod(), request.getRequestURI());
        writeStatus(response, ErrorCode.NOT_FOUND.getCode());
        return Result.fail(ErrorCode.NOT_FOUND,
                String.format("接口不存在：%s %s", e.getHttpMethod(), e.getRequestURL()));
    }

    /**
     * 兜底：所有未被上面处理的异常。
     *
     * <p>⚠️ 三个关键点：
     * <ol>
     *   <li><b>日志级别必须 ERROR</b>：走到这里说明出了我们没有预期的错，需要人工介入。</li>
     *   <li><b>必须传异常对象给 log</b>（{@code log.error(msg, e)} 而不是只传 message），
     *       否则 cms-error.log 里只有一行文字，没有堆栈，等于没法排查。</li>
     *   <li><b>绝不能把 e.getMessage() 返回给客户端</b>：
     *       系统异常的 message 可能包含 SQL 语句、表名、文件路径等敏感信息。
     *       统一返回"系统内部错误"，真实原因去日志里看。</li>
     * </ol>
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        log.error("系统异常 | {} {}", request.getMethod(), request.getRequestURI(), e);
        writeStatus(response, ErrorCode.SYSTEM_ERROR.getCode());
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /**
     * 格式化单条字段错误。
     *
     * <p>输出格式：{@code "字段名 错误提示"}，例如 {@code "title 不能为空"}。
     * <p>不用 {@code "title: 不能为空"} 是因为中文字段提示里加冒号
     * 在多个错误用分号拼接后可读性反而下降。
     */
    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
