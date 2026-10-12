package com.lab.cms.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 操作类型枚举。
 *
 * <p>对应 {@code sys_oper_log.oper_type} 字段（VARCHAR(20)，存枚举名）。
 *
 * <h3>为什么存枚举名而不是 code 数字</h3>
 * <p>数字 code 需要"看代码才知道 3 是什么"，而日志表的主要消费者是**人**
 * （排查问题时直接 {@code SELECT * FROM sys_oper_log} 看）。
 * 存枚举名（如 {@code AUDIT}）让日志自解释，代价是几个字节的存储。
 * <p>⚠️ 但要注意：枚举名一旦入库就是"持久化契约"，**不允许改枚举名**。
 * 若确实需要改，必须同时写数据迁移脚本更新历史记录。
 */
@Getter
public enum OperType {

    /** 新增 */
    CREATE("新增"),

    /** 修改 */
    UPDATE("修改"),

    /** 删除 */
    DELETE("删除"),

    /** 查询（一般不入日志，除非审计要求） */
    QUERY("查询"),

    /** 审核（初审/终审共用） */
    AUDIT("审核"),

    /** 发布（立即发布/排期发布/定时触发） */
    PUBLISH("发布"),

    /** 撤回 */
    WITHDRAW("撤回"),

    /** 下架 */
    OFFLINE("下架"),

    /** 归档 */
    ARCHIVE("归档"),

    /** 导出 */
    EXPORT("导出"),

    /** 导入 */
    IMPORT("导入"),

    /** 登录 */
    LOGIN("登录"),

    /** 登出 */
    LOGOUT("登出"),

    /** 其他 */
    OTHER("其他");

    /** 中文描述 */
    private final String description;

    OperType(String description) {
        this.description = description;
    }

    public static OperType ofNullable(String name) {
        if (name == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
