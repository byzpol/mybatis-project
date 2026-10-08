package com.lab.cms.base;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.config.CurrentUserArgumentResolver;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充器。
 *
 * <p>实现 MyBatis-Plus 的 {@link MetaObjectHandler}，在 INSERT / UPDATE 时
 * 自动填充 {@code createBy / createTime / updateBy / updateTime}。
 *
 * <h3>为什么不靠数据库的 DEFAULT CURRENT_TIMESTAMP</h3>
 * <p>数据库的自动时间戳只能填"时间"，填不了"操作人 ID"。
 * 而审计的核心恰恰是**谁**改的。用 Java 侧填充可以同时拿到用户与时间，
 * 且逻辑集中在本地，不依赖数据库方言。
 *
 * <p>数据库的 {@code DEFAULT CURRENT_TIMESTAMP} 保留作为兜底：
 * 用于"绕过 ORM 直接执行 INSERT"的场景（如演示数据脚本）。
 *
 * <h3>⚠️ 为什么 INSERT 用 strictXXX、UPDATE 用 setFieldValByName（本类最重要的一条经验）</h3>
 *
 * <p>MP 提供两类填充 API：
 * <ul>
 *   <li>{@code strictInsertFill / strictUpdateFill}：只在字段当前为 null 时填充。</li>
 *   <li>{@code setFieldValByName / setFieldValByName}：无条件覆盖。</li>
 * </ul>
 *
 * <p><b>INSERT 用 strict 版</b>：数据初始化/测试时可能显式指定 createTime
 * （如灌入"过去 3 天创建的文章"用于演示时间排序），strict 版能保留这个能力。
 * 正常业务代码不会显式设置这些字段，所以效果与覆盖版一致。
 *
 * <p><b>UPDATE 必须用覆盖版 —— 这是实测踩出来的坑。</b>
 * 场景：{@code updateById(new SysUser(){{ setId(4L); setLastLoginTime(now); }})}。
 * 此时实体里 updateTime 为 null，看上去 {@code strictUpdateFill} 会填上，
 * 但实测（MP 3.5.5）生成的 SQL 是：
 * <pre>UPDATE sys_user SET last_login_time=?, update_by=?, update_time=? WHERE id=? AND deleted=0</pre>
 * 参数绑定为：{@code 2026-10-08T17:41:53(non-null), null, null, 4} ——
 * <b>updateTime 与 updateBy 都是 null，strict 填充没生效</b>，直接被数据库拒绝：
 * <pre>Column 'update_time' cannot be null</pre>
 * 与「generate_java_code」无关，是 MP 在
 * {@code updateById} 组装 SET 子句时把带 {@code INSERT_UPDATE} 策略的列
 * 提前纳入更新列表，导致 strictFill 的判定条件被绕过。
 *
 * <p>因此在 UPDATE 分支改用 {@code setFieldValByName} 无条件写入 ——
 * 更新时间本就应该是"这一次操作的时间"，覆盖是正确语义，不存在"尊重调用方"的诉求。
 *
 * <h3>⚠️ 拿不到当前用户时，updateBy 填什么（第二个实测坑）</h3>
 * <p>登录接口在执行时**用户尚未登录**（Session 刚 invaldate 还没写入），
 * 因此 {@code getCurrentUserId()} 返回 null。原实现选择"跳过填充保持 null"，
 * 但 {@code update_by} 在 17 张表里都是 {@code NULL} 可空，保持 null 是合法的 ——
 * 真正炸的是 {@code update_time}。
 *
 * <p>本类的策略：
 * <ul>
 *   <li>{@code update_time}：**永远填**（操作系统时间，与是否有登录用户无关）。</li>
 *   <li>{@code update_by}：拿得到用户就填用户 ID；拿不到就填 {@link #SYSTEM_USER_ID}
 *       （0），语义为"系统操作"。填 0 而不是 null 的好处是查询/统计时
 *       不用额外处理 null 分支，且 {@code create_by = 0} 能被明确识别为系统行为。</li>
 * </ul>
 *
 * <p>⚠️ 这里改用 0 而非 null，与本类早期注释中"null 表示系统操作"的说法相反 ——
 * 以实测结论为准：既避免 NOT NULL 冲突，也让审计语义更显式。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditMetaObjectHandler.class);

    /** 字段名常量，避免字符串拼错 */
    private static final String FIELD_CREATE_BY = "createBy";
    private static final String FIELD_CREATE_TIME = "createTime";
    private static final String FIELD_UPDATE_BY = "updateBy";
    private static final String FIELD_UPDATE_TIME = "updateTime";

    /**
     * 系统操作用户 ID。
     *
     * <p>没有真实"系统"账号（sys_user 的 id 从 1 开始），用 0 作为哨兵值。
     * 查询审计轨迹时，{@code create_by = 0} 即表示"由系统/定时任务写入"，
     * 常见于：登录日志、数据初始化、后续的定时发布任务（S13）。
     */
    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * INSERT 时填充。
     *
     * <p>填充 createBy / createTime / updateBy / updateTime 四个字段 ——
     * 新建的记录，它的"更新人/更新时间"就等于"创建人/创建时间"，
     * 这样查询"最近更新的记录"时不会漏掉刚创建的那些。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long currentUserId = getCurrentUserId();

        // createTime：strict 版，保留"调用方显式指定 createTime"的能力
        strictInsertFill(metaObject, FIELD_CREATE_TIME, LocalDateTime.class, now);
        // updateTime：与 createTime 同值
        strictInsertFill(metaObject, FIELD_UPDATE_TIME, LocalDateTime.class, now);

        // createBy / updateBy：拿不到登录用户时填 SYSTEM_USER_ID(0)，
        // 而不是留 null —— 0 让"系统写入"在审计里显式可见（见类注释）。
        Long auditUserId = currentUserId != null ? currentUserId : SYSTEM_USER_ID;
        strictInsertFill(metaObject, FIELD_CREATE_BY, Long.class, auditUserId);
        strictInsertFill(metaObject, FIELD_UPDATE_BY, Long.class, auditUserId);

        if (currentUserId == null) {
            log.debug("审计填充：无登录用户，createBy/updateBy 记为 SYSTEM_USER_ID=0（{}）",
                    metaObject.getOriginalObject().getClass().getSimpleName());
        }
    }

    /**
     * UPDATE 时填充。
     *
     * <p>只填 updateBy / updateTime。
     * <p>⚠️ 绝不填 createBy / createTime（那会把创建信息改掉，是数据损坏）。
     *
     * <p>⚠️ 这里刻意使用 {@code setFieldValByName}（无条件覆盖）而非
     * {@code strictUpdateFill} —— 原因见类注释「UPDATE 必须用覆盖版」一节，
     * 这是 MP 3.5.5 实测踩出来的坑，改用 strict 版会导致
     * {@code Column 'update_time' cannot be null}。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        Long currentUserId = getCurrentUserId();
        Long auditUserId = currentUserId != null ? currentUserId : SYSTEM_USER_ID;

        // 无条件写入：更新时间就是"本次操作的时间"，覆盖是正确语义
        setFieldValByName(FIELD_UPDATE_TIME, LocalDateTime.now(), metaObject);
        setFieldValByName(FIELD_UPDATE_BY, auditUserId, metaObject);
    }

    /**
     * 获取当前登录用户 ID。
     *
     * <p>复用 {@code CurrentUserArgumentResolver.getCurrentUser()}（内部走
     * RequestContextHolder，依赖 ThreadLocal）。这里能安全使用的前提是：
     * MyBatis 的 SQL 执行与请求处理在**同一个线程**内（本项目无异步、无线程池切换）。
     *
     * <p>⚠️ 如果将来引入异步（{@code @Async}、{@code CompletableFuture}），
     * 这里的 ThreadLocal 会取不到值（拿不到当前用户），审计就断了。
     * 到那时需要改造为"显式传递用户上下文"。本项目在文档中记录这个前提。
     */
    private Long getCurrentUserId() {
        try {
            LoginUser user = CurrentUserArgumentResolver.getCurrentUser();
            return user == null ? null : user.getUserId();
        } catch (Exception e) {
            // 填充失败绝不能影响业务：它只是审计信息
            log.warn("获取当前登录用户失败，跳过审计人字段填充", e);
            return null;
        }
    }
}
