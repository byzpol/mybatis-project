package com.lab.cms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lab.cms.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 操作日志实体，对应表 {@code sys_oper_log}。
 *
 * <p>由 {@code WebLogAspect} 拦截 {@code @OperLog} 注解的方法后写入。
 *
 * <h3>字段命名说明</h3>
 * <p>本表的"操作人"字段是 {@code oper_id} / {@code oper_name}（不是 create_by / create_time）——
 * 语义不同：{@code oper_id} 是"这条日志记录的动作是谁做的"，
 * 而 {@code create_by} 是"这条日志记录本身是谁写入的"。
 * 正常情况下两者相同（切面写入时都用当前登录用户），
 * 但若是系统定时任务产生的操作日志，{@code oper_id} 会是 null（系统操作），
 * 而 {@code create_by} 也应为 null。本项目保持两者都填同一个值，
 * 但字段分开保留是为了将来"代操作/代理场景"的扩展。
 *
 * <h3>⚠️ 本表不参与业务更新</h3>
 * <p>日志是**只增不改**的（append-only）。虽然基类提供了 updateBy/updateTime，
 * 但业务代码**不应**对日志记录执行 update。这条约束由 Code Review 保证
 * （日志表没有更新入口 —— Mapper 只暴露 insert 与查询方法，不继承 BaseMapper）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_oper_log")
public class SysOperLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 业务模块名，如 "文章管理" */
    @TableField("module")
    private String module;

    /** 操作类型枚举名，如 "AUDIT"（与 OperType.name() 一致，不是中文描述） */
    @TableField("oper_type")
    private String operType;

    /** 操作描述，如 "审核文章" */
    @TableField("oper_desc")
    private String operDesc;

    /** 请求方法：GET / POST / PUT / DELETE */
    @TableField("request_method")
    private String requestMethod;

    /** 请求 URL，如 /api/v1/articles/1/audit */
    @TableField("request_url")
    private String requestUrl;

    /** 请求参数（JSON，已按 @OperLog.saveParams 决定是否记录；超长会截断） */
    @TableField("request_param")
    private String requestParam;

    /** 响应结果（JSON，默认不记录，按 @OperLog.saveResult 决定） */
    @TableField("response_result")
    private String responseResult;

    /** 操作人 ID。系统操作时为 null */
    @TableField("oper_id")
    private Long operId;

    /** 操作人用户名（冗余存储，避免查日志时还要关联用户表） */
    @TableField("oper_name")
    private String operName;

    /** 客户端 IP（含 X-Forwarded-For 解析，注意该头可被伪造） */
    @TableField("oper_ip")
    private String operIp;

    /** 耗时（毫秒） */
    @TableField("cost_time")
    private Long costTime;

    /**
     * 执行状态：{@code 1 成功 / 0 失败}。
     * <p>注意这里 **1=成功**，与 {@code cms_article.status} 的 0=草稿 语义方向一致
     * （都是小值代表"初始/未完成"）。但与 {@code sys_user.status} 的 1=正常 也一致。
     * 本项目三个 status 字段的语义约定见 {@code docs/数据库设计.md}。
     */
    @TableField("status")
    private Integer status;

    /** 失败时的错误信息（截断到 1000 字符以内） */
    @TableField("error_msg")
    private String errorMsg;

    /** 操作时间（业务时间，与 create_time 通常相同） */
    @TableField("oper_time")
    private LocalDateTime operTime;
}
