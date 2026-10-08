package com.lab.cms.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.cms.annotation.OperLog;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.config.CurrentUserArgumentResolver;
import com.lab.cms.entity.SysOperLog;
import com.lab.cms.mapper.SysOperLogMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作日志切面。
 *
 * <p>拦截标注了 {@link OperLog} 的方法，记录：操作人、模块、类型、描述、
 * 请求方法、请求 URL、IP、入参、耗时、执行结果。
 *
 * <h3>为什么用 @Around 而不是 @Before / @AfterReturning</h3>
 * <p>需要记录**耗时**与**执行结果（成功/失败）**，这必须在方法执行前后各取一次时间，
 * 且要能捕获异常。只有 {@code @Around} 能同时做到这三件事。
 *
 * <h2>⚠️ 三条铁律（写错了会导致业务受影响或日志不可信）</h2>
 *
 * <p><b>铁律 1：日志写入失败绝不能影响业务。</b>
 * 用户在演示时成功发布了文章，却因为日志表写满而看到 500 —— 体验极差，
 * 且这个错误与用户操作无关。因此整个日志记录过程必须被 try-catch 包住，
 * 异常只记 WARN 并吞掉。
 *
 * <p><b>铁律 2：业务异常必须继续抛出。</b>
 * 用 {@code @Around} 时很容易犯的错误是"catch 了异常但忘了 rethrow"，
 * 导致业务异常被吞掉、接口返回 200。所以结构必须是：
 * <pre>
 *   try {
 *       result = pjp.proceed();
 *   } catch (Throwable e) {
 *       // 记录失败日志
 *       throw e;      ← 必须重新抛出
 *   } finally {
 *       // 记录日志（不抛异常）
 *   }
 * </pre>
 *
 * <p><b>铁律 3：日志的"持久化"必须用独立事务。</b>
 * ⚠️ 本项目**不做独立事务**（见下方说明），而是把日志写入放在
 * 业务事务**之外**（切面在 Service 外层，Controller 层事务已提交完）。
 * 这带来一个后果：若 Controller 方法自身有事务（本项目没有），
 * 业务回滚时日志也会被回滚 —— 那样就丢了"失败操作"的日志，恰恰是最需要的那条。
 *
 * <h3>关于"独立事务"的实现说明</h3>
 * <p>严格的企业做法是给日志写入方法加
 * {@code @Transactional(propagation = Propagation.REQUIRES_NEW)}，
 * 让它在独立事务中提交，不受外层事务回滚影响。
 * 但这需要把日志写入抽成一个独立的 Spring Bean（自调用会导致 AOP 失效），
 * 本项目为简化结构，采用"切面在 Controller 层执行、Controller 无事务"的方式，
 * 达到同样效果：业务事务在 Service 层已完成提交/回滚，日志写入发生在之后。
 *
 * <h3>关于入参序列化</h3>
 * <p>把方法参数用 Jackson 序列化成 JSON 存入日志。
 * ⚠️ 序列化可能失败（如参数里有不可序列化的对象、循环引用），
 * 因此单独 try-catch，失败时存 {@code "[参数序列化失败]"} 而不是让整个日志丢失。
 */
@Aspect
@Component
public class WebLogAspect {

    private static final Logger log = LoggerFactory.getLogger(WebLogAspect.class);

    /** 入参序列化后的最大长度，超出截断（避免 LONGTEXT 正文把日志撑爆） */
    private static final int MAX_PARAM_LENGTH = 2000;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 操作日志 Mapper。
     *
     * <p>⚠️ 注意：这里直接注入 Mapper 而不是走 Service —— 
     * 这是本项目**唯一**一处切面直连 Mapper 的地方（违反分层纪律的例外）。
     *
     * <p>理由：若日志写入走 Service，那么 Service 层可能又有事务，
     * 会导致"日志写入被业务事务包裹"的问题（业务回滚时日志也没了）。
     * 切面不参与业务事务，直连 Mapper 写入反而能保证日志独立落库。
     * 这个例外已在 {@code docs/工程骨架与配置说明.md} 中记录。
     */
    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    /**
     * 切点：标注了 {@code @OperLog} 的方法。
     */
    @Pointcut("@annotation(com.lab.cms.annotation.OperLog)")
    public void operLogPointcut() {
    }

    /**
     * 环绕通知：记录操作日志。
     */
    @Around("operLogPointcut()")
    public Object recordOperLog(ProceedingJoinPoint joinPoint) throws Throwable {
        // ---- 1. 收集日志所需信息（在方法执行前）----
        long startTime = System.currentTimeMillis();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        OperLog annotation = AnnotationUtils.findAnnotation(method, OperLog.class);

        String className = signature.getDeclaringType().getSimpleName();
        String methodName = method.getName();

        // 注解理论上不为 null（切点已限定），但保留防御
        if (annotation == null) {
            return joinPoint.proceed();
        }

        // ---- 2. 执行目标方法 ----
        Object result = null;
        Throwable businessException = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            businessException = e;
            // ⚠️ 铁律 2：必须重新抛出。这里显式记录后 rethrow，
            // 不能只 catch 不 throw（那样业务异常会被吞掉，接口返回 200）。
            throw e;
        } finally {
            // ---- 3. 记录日志（放在 finally 保证成功/失败都记录）----
            // ⚠️ 铁律 1：整段包 try-catch，日志失败绝不影响业务（此时业务已结束，但异常会影响响应）
            try {
                long costMs = System.currentTimeMillis() - startTime;
                doRecord(joinPoint, annotation, className, methodName,
                        costMs, businessException);
            } catch (Exception logEx) {
                log.warn("操作日志记录失败（不影响业务） | {}#{}", className, methodName, logEx);
            }
        }
    }

    /**
     * 实际记录日志。
     *
     * <p>做两件事：① 写 logback 文件（便于即时查看）② 写 {@code sys_oper_log} 表（结构化留痕）。
     *
     * <p>⚠️ 本方法内部的任何异常都会被 {@link #recordOperLog} 的 finally 块捕获，
     * 因此这里不必再套一层 try-catch（但写表那步仍单独包了一层，原因见下）。
     */
    private void doRecord(ProceedingJoinPoint joinPoint, OperLog annotation,
                          String className, String methodName,
                          long costMs, Throwable businessException) {

        // ---- 操作人 ----
        LoginUser user = CurrentUserArgumentResolver.getCurrentUser();
        Long operatorId = user == null ? null : user.getUserId();
        String operatorName = user == null ? "anonymous" : user.getUsername();

        // ---- 请求信息 ----
        String requestMethod = "-";
        String requestUrl = "-";
        String clientIp = "-";
        String userAgent = "-";
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestMethod = request.getMethod();
            // ⚠️ 用 getRequestURI() 而不是 getRequestURL()：
            //    URL 含协议与主机（http://localhost:8080/api/...），
            //    URI 只含路径（/api/...），入库更短更稳定（换域名不影响日志）
            requestUrl = request.getRequestURI();
            clientIp = CurrentUserArgumentResolver.getClientIp();
            String ua = request.getHeader("User-Agent");
            userAgent = ua == null ? "-" : (ua.length() > 500 ? ua.substring(0, 500) : ua);
        }

        // ---- 结果状态 ----
        int status = businessException == null ? 1 : 0;
        String resultDesc = businessException == null ? "SUCCESS" : "FAIL";
        String errorMsg = businessException == null ? null : truncate(businessException.getMessage(), 1000);

        // ---- 入参（按注解开关决定是否记录）----
        String params = annotation.saveParams()
                ? serializeParams(joinPoint.getArgs())
                : "[已按配置忽略]";

        // ---- ① 写 logback 文件 ----
        // 成功用 INFO（业务动作），失败用 WARN（预期内的业务失败，不用 ERROR 污染错误日志）
        if (businessException == null) {
            log.info("操作日志 | 操作人={}({}) | 模块={} | 类型={} | 描述={} | {} {} | IP={} | 耗时={}ms | 结果={} | 参数={}",
                    operatorName, operatorId, annotation.module(), annotation.type().name(),
                    annotation.desc(), requestMethod, requestUrl, clientIp, costMs, resultDesc, params);
        } else {
            log.warn("操作日志 | 操作人={}({}) | 模块={} | 类型={} | 描述={} | {} {} | IP={} | 耗时={}ms | 结果={} | 错误={} | 参数={}",
                    operatorName, operatorId, annotation.module(), annotation.type().name(),
                    annotation.desc(), requestMethod, requestUrl, clientIp, costMs,
                    resultDesc, errorMsg, params);
        }

        // ---- ② 写 sys_oper_log 表 ----
        // ⚠️ 单独包 try-catch：写表失败（如表结构变更、字段超长）不应影响已有文件日志，
        //    也不应冒泡到外层（虽然外层也吞了，但这里更细粒度地标明"是写表失败"）
        try {
            SysOperLog entity = new SysOperLog();
            entity.setModule(truncate(annotation.module(), 50));
            entity.setOperType(annotation.type().name());
            entity.setOperDesc(truncate(annotation.desc(), 200));
            entity.setRequestMethod(truncate(requestMethod, 10));
            entity.setRequestUrl(truncate(requestUrl, 255));
            entity.setRequestParam(params);
            entity.setOperId(operatorId);
            entity.setOperName(truncate(operatorName, 50));
            entity.setOperIp(truncate(clientIp, 50));
            entity.setCostTime(costMs);
            entity.setStatus(status);
            entity.setErrorMsg(errorMsg);
            entity.setOperTime(LocalDateTime.now());
            // createBy / createTime / updateBy / updateTime 由 AuditMetaObjectHandler 自动填充

            sysOperLogMapper.insert(entity);
        } catch (Exception e) {
            // 写表失败只记 WARN，不影响任何东西。
            // ⚠️ 必须打完整堆栈（log.warn(msg, e) 而不是只拼 e.getMessage()）：
            //    Spring 的 DataAccessException 经翻译后 getMessage() 常常是空串
            //    （如 DataIntegrityViolationException：真正的原因藏在 cause 里），
            //    只打 message 会得到 "原因=" 这样的空信息，等于没打日志。
            log.warn("操作日志写库失败（已保留文件日志） | {}#{}", className, methodName, e);
        }
    }

    /**
     * 字符串截断，防止超出数据库字段长度导致写入失败。
     *
     * <p>⚠️ 这里**必须**做截断：因为 {@code WebLogAspect} 是在方法执行**之后**运行的，
     * 若此时抛"Data too long for column"异常，虽然被 catch 吞了，
     * 但那条日志就丢了 —— 而且是"最需要记录的那条"（往往伴随异常的长参数）。
     * 主动截断能保证日志一定写得进去。
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 序列化方法参数。
     *
     * <p>⚠️ 单独 try-catch：参数可能包含不可序列化的对象（如
     * {@code HttpServletRequest}、{@code LoginUser} 里若有循环引用），
     * 序列化失败时返回占位符，而不是让整个日志丢失。
     *
     * <p>另外会过滤掉 {@code LoginUser} 类型的参数 —— 它的 {@code permissions}
     * 集合可能有几十个元素，全打进日志很冗余，且信息在"操作人"字段里已经有了。
     */
    private String serializeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "-";
        }
        try {
            StringBuilder sb = new StringBuilder("[");
            for (Object arg : args) {
                if (arg instanceof LoginUser) {
                    // 已在"操作人"字段记录，跳过冗余
                    sb.append("LoginUser(已省略),");
                    continue;
                }
                if (arg instanceof HttpServletRequest) {
                    sb.append("HttpServletRequest(已省略),");
                    continue;
                }
                sb.append(objectMapper.writeValueAsString(arg)).append(",");
            }
            sb.append("]");

            String result = sb.toString();
            // 超长截断，避免大文本（如文章正文）撑爆日志
            if (result.length() > MAX_PARAM_LENGTH) {
                result = result.substring(0, MAX_PARAM_LENGTH) + "...[已截断]";
            }
            return result;
        } catch (Exception e) {
            log.debug("操作日志参数序列化失败", e);
            return "[参数序列化失败]";
        }
    }
}
