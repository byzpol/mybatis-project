package com.lab.cms.service;

import java.util.Map;

/**
 * 主干自测服务（S08 专用）。
 *
 * <h3>为什么单独开一个 Service 而不是在 Controller 里直接操作 Mapper</h3>
 * <p>{@code HealthController#dbRoundtrip} 需要"插入一条记录 → 执行无 WHERE 更新 → 按 id 逻辑删除"
 * 这一串数据库动作。若把它写在 Controller 里，就必须在 Controller 注入 Mapper，
 * 直接违反《企业开发规范清单》里"Controller 禁直连 Mapper、分层调用单向向下"的纪律。
 *
 * <p><b>自测代码同样要遵守规范</b> —— 否则这份"主干样板"本身就在示范错误写法，
 * 后面 B/C/D 拉入户线时照着抄就全错了。
 *
 * <h3>本接口的定位</h3>
 * <p>它是<b>基础设施能力的自测入口</b>，不是业务服务。
 * 业务开工后（S09 起）它仍然有用：主干改动后跑一次就能确认
 * "审计填充/逻辑删除/防全表更新"这三项没有被改坏 —— 相当于一个可以远程触发的冒烟测试。
 *
 * <p>⚠️ 它不写任何业务表，只写 {@code sys_oper_log}（日志表天然就是追加型的）。
 */
public interface TrunkSelfTestService {

    /**
     * 数据层往返自测：插入 → 验证审计填充 → 无 WHERE 更新 → 按 id 逻辑删除 → 验证查不到。
     *
     * <p>返回结构化报告，每一项都带"实测值"而不是"是否通过"的布尔 ——
     * 布尔值只说明结论，实测值才能让人判断结论是否可信。
     * 例如"createBy=0" 与 "createBy 非空" 是两回事：前者能看出填的是系统哨兵值。
     *
     * @return 报告键依次为 {@code insert} / {@code updateNoWhere} / {@code logicDelete}
     */
    Map<String, Object> dbRoundTrip();
}
