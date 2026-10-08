package com.lab.cms.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统信息 VO。
 *
 * <p>⚠️ 本类同时承担一个"隐形职责"：验证 Jackson 的
 * {@code LocalDateTime} 序列化配置是否生效。
 * 看响应里的 {@code serverTime} 字段 ——
 * 若输出 {@code "2026-10-08 17:30:00"} 则配置正确；
 * 若输出 {@code [2026,10,8,17,30,0]} 则 {@code JavaTimeModule} 没有注册成功。
 */
@Data
public class SystemInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应用名 */
    private String appName;

    /** 版本号 */
    private String version;

    /** 运行状态：UP */
    private String status;

    /**
     * 服务器当前时间。
     * <p>⚠️ 这是本 VO 最重要的字段：它的序列化格式直接反映 Jackson 配置是否生效。
     */
    private LocalDateTime serverTime;

    /** Java 版本 */
    private String javaVersion;

    /** 操作系统名 */
    private String osName;

    /** JVM 最大可用内存（MB） */
    private Long jvmMaxMemoryMb;

    /** 可用处理器核数 */
    private Integer availableProcessors;
}
