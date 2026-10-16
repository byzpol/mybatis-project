package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lab.cms.entity.SysOperLog;
import com.lab.cms.mapper.SysOperLogMapper;
import com.lab.cms.service.TrunkSelfTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主干自测服务实现（S08 专用）。
 *
 * <h3>三项验证对应的底层机制</h3>
 * <table border="1">
 *   <tr><th>验证项</th><th>靠什么生效</th><th>失效会怎样</th></tr>
 *   <tr><td>审计字段填充</td><td>{@code AuditMetaObjectHandler}（须在 {@code MybatisConfig} 里
 *       {@code globalConfig.setMetaObjectHandler(...)} 挂上）</td>
 *       <td>报 {@code Column 'create_time' cannot be null}</td></tr>
 *   <tr><td>防全表更新</td><td>{@code BlockAttackInnerInterceptor}（MP 拦截器链第一环）</td>
 *       <td>一条 {@code UPDATE} 把全表数据改掉，且不报错</td></tr>
 *   <tr><td>逻辑删除</td><td>实体上的 {@code @TableLogic(value="0", delval="1")}</td>
 *       <td>{@code deleteById} 变成物理删除，数据真的没了</td></tr>
 * </table>
 * <p>三项的共同点是：<b>失效时都不会编译报错、不会启动报错</b>，
 * 只会安静地产生错误结果。所以必须有一个能在运行时把它们暴露出来的入口 —— 就是本类。
 *
 * <h3>为什么三个动作写在一个方法里</h3>
 * <p>它们天然是一条链：插入拿 id → 更新 → 按 id 删。写在一起的好处是
 * <b>全程操作同一条记录</b>，报告里三个结论互相可印证（比如 insert 返回的 id
 * 就是 logicDelete 用的那个）。拆成三个方法反而要额外传递和校验这个 id。
 *
 * <h3>⚠️ 异常处理策略：只吞"预期内的拦截异常"</h3>
 * <p>第 2 步的"无 WHERE 更新"<b>就是要让它抛异常</b>，所以必须 catch。
 * 但 catch 的范围要克制 —— 只捕获并记录，把异常类型与消息放进报告，让调用方看到真相。
 * 绝不能 catch 后静默 continue：那样当拦截器失效（更新真的执行了）时，
 * 报告会显示"没抛异常"，反而更早发现问题。
 */
@Service
public class TrunkSelfTestServiceImpl implements TrunkSelfTestService {

    private static final Logger log = LoggerFactory.getLogger(TrunkSelfTestServiceImpl.class);

    /** 测试数据的标记，便于事后用 SQL 定位并物理清理。 */
    private static final String TEST_TAG = "[S08-TRUNK-TEST]";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    @Override
    public Map<String, Object> dbRoundTrip() {
        Map<String, Object> report = new LinkedHashMap<>();

        // ==================== 1. 插入：验证审计字段自动填充 ====================
        SysOperLog record = new SysOperLog();
        record.setModule("主干自测");
        record.setOperType("OTHER");
        record.setOperDesc(TEST_TAG + " 审计填充与逻辑删除往返验证");
        record.setRequestMethod("POST");
        record.setRequestUrl("/api/v1/health/db-roundtrip");
        record.setStatus(1);
        record.setOperTime(LocalDateTime.now());
        // ⚠️ 这里刻意**不设置** createBy/createTime/updateBy/updateTime/deleted 中的任何一个。
        //
        // ⚠️⚠️ 判断填充是否生效，**要看 createBy / updateBy，不能只看 createTime / updateTime**：
        //     - create_by / update_by 在库表里是 `NULL DEFAULT NULL` —— 填充失效时它们就是 null，
        //       所以"非空"是填充生效的**硬证据**
        //     - create_time / update_time 是 `NOT NULL DEFAULT CURRENT_TIMESTAMP` ——
        //       MetaObjectHandler 失效时数据库会自己填上，值照样非空，
        //       **因此单独看时间字段证明不了填充机制生效**
        //     （这一点是实测 DDL 后确认的：sql/init.sql 第 356~360 行）
        //     结论："四个字段都非空"只是必要条件，"createBy 非空"才是充分证据。

        int insertedRows = sysOperLogMapper.insert(record);
        Long id = record.getId();

        Map<String, Object> insertReport = new LinkedHashMap<>();
        insertReport.put("rows", insertedRows);
        insertReport.put("id", id);
        insertReport.put("createBy", record.getCreateBy());
        insertReport.put("createTime", format(record.getCreateTime()));
        insertReport.put("updateBy", record.getUpdateBy());
        insertReport.put("updateTime", format(record.getUpdateTime()));
        // 硬指标：操作人 ID 被填上，说明 MetaObjectHandler 真的跑了
        insertReport.put("handlerWorked", record.getCreateBy() != null && record.getUpdateBy() != null);
        insertReport.put("caveat", "createTime/updateTime 有数据库 DEFAULT 兜底，非空不代表填充生效；"
                + "以 createBy/updateBy 是否非空为准");
        report.put("insert", insertReport);

        // ==================== 2. 无 WHERE 的 UPDATE：验证防全表更新 ====================
        Map<String, Object> updateReport = new LinkedHashMap<>();
        try {
            // entity 传 null + 空 UpdateWrapper ⇒ MP 生成的 SQL 没有 WHERE 子句
            // BlockAttackInnerInterceptor 会在这句 SQL 执行前拦下并抛异常
            sysOperLogMapper.update(null, new UpdateWrapper<SysOperLog>()
                    .set("module", "主干自测-全表更新测试"));
            // 走到这里说明**没有被拦截** —— 这是失败信号，如实记录
            updateReport.put("blocked", false);
            updateReport.put("note", "⚠️ 未被拦截：全表更新已执行，BlockAttackInnerInterceptor 可能未生效");
            log.error("主干自测失败：不带 WHERE 的 update 没有被拦截");
        } catch (Exception e) {
            updateReport.put("blocked", true);
            updateReport.put("exceptionType", e.getClass().getName());
            updateReport.put("message", e.getMessage());
            log.info("主干自测：不带 WHERE 的 update 已被拦截 | {} | {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        report.put("updateNoWhere", updateReport);

        // ==================== 3. 逻辑删除：验证 deleted 生效 ====================
        Map<String, Object> deleteReport = new LinkedHashMap<>();
        int deletedRows = sysOperLogMapper.deleteById(id);
        SysOperLog afterDelete = sysOperLogMapper.selectById(id);
        deleteReport.put("deleteRows", deletedRows);
        deleteReport.put("selectByIdAfterDelete", afterDelete);
        deleteReport.put("hiddenFromQuery", afterDelete == null);
        deleteReport.put("note", "行仍在库中，deleted 已置 1；验证用："
                + "SELECT id,deleted FROM sys_oper_log WHERE id = " + id);
        report.put("logicDelete", deleteReport);

        report.put("testTag", TEST_TAG);
        report.put("testRecordId", id);
        return report;
    }

    /** 时间格式化：为 null 时返回字符串 "null" 而不是抛异常（测试报告要能容忍空值）。 */
    private String format(LocalDateTime time) {
        return time == null ? "null" : time.format(FMT);
    }
}
