package com.lab.cms.annotation;

import com.lab.cms.common.enums.OperType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解。
 *
 * <p>加在 Controller 方法上，由 {@code WebLogAspect} 通过 AOP 拦截，
 * 把操作人、模块、类型、描述、入参、耗时、结果写入 {@code sys_oper_log} 表。
 *
 * <h3>为什么用注解 + AOP 而不是在 Service 里手动插日志</h3>
 * <p>手动写法：
 * <pre>
 *   // ✗ 每个写方法都要写这一堆
 *   OperLog log = new OperLog();
 *   log.setOperId(user.getUserId());
 *   log.setModule("文章管理");
 *   ...
 *   operLogMapper.insert(log);
 * </pre>
 * 问题：① 重复代码多 ② 容易漏（新同事不知道要写）③ 日志代码与业务代码混杂
 * ④ 耗时统计要自己加时间戳。
 *
 * <p>用注解后，写方法只需加一行 {@code @OperLog(...)}，其余全部由切面完成。
 * 这是 AOP 最经典、收益最明确的应用场景。
 *
 * <h3>⚠️ 日志写入失败不能影响业务</h3>
 * <p>切面里写日志的操作必须用 try-catch 包住，任何异常只记为 WARN 并吞掉。
 * 理由：日志是"辅助功能"，它失败不应该导致用户的业务操作回滚
 * （用户成功发布了文章，却因为日志表满了而报错，体验极差）。
 * 这个取舍在 {@code WebLogAspect} 中有明确注释。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {

    /**
     * 业务模块名，如 {@code "文章管理"}。
     * <p>与 {@code sys_oper_log.module} 字段对应。
     * <p>⚠️ 模块名要**收敛**：不要一个功能一个模块名，
     * 否则后续统计"各模块操作量"时会得到几十个碎块。建议按业务域划分
     * （文章管理 / 分类管理 / 用户管理 / 日志管理）。
     */
    String module();

    /**
     * 操作类型。与 {@code sys_oper_log.oper_type} 对应。
     * <p>用于统计"这个月删了多少东西"这类审计需求。
     */
    OperType type();

    /**
     * 操作描述，如 {@code "审核文章"}。
     * <p>展示在日志列表的"操作内容"列，让人一眼知道做了什么。
     */
    String desc() default "";

    /**
     * 是否记录请求参数。
     *
     * <p>默认 {@code true}。但以下场景必须设为 {@code false}：
     * <ul>
     *   <li>登录接口（参数里有明文密码）</li>
     *   <li>修改密码接口</li>
     *   <li>参数包含大文本（如文章正文 LONGTEXT，会把日志表撑爆）</li>
     * </ul>
     *
     * <p>⚠️ 这是一个**安全相关**的开关。默认 true 是为了"排查问题时有据可查"，
     * 但涉及凭据的接口一定要显式关掉 —— 日志里的密码可能被运维、
     * 被日志采集系统看到，属于典型的凭据泄漏渠道。
     */
    boolean saveParams() default true;

    /**
     * 是否记录响应结果。
     *
     * <p>默认 {@code false} —— 因为：
     * <ul>
     *   <li>响应体通常很大（列表接口可能返回几十条记录）</li>
     *   <li>写操作的成功响应一般是 {@code {code:200, data:null}}，本身没信息量</li>
     *   <li>若需排查"为什么返回了这个"，看 Service 日志更直接</li>
     * </ul>
     * 只有少数场景需要打开（如"导出接口要记录导出了多少条"）。
     */
    boolean saveResult() default false;
}
