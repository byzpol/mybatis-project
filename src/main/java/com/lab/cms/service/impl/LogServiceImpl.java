package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.common.util.SqlLikeUtil;
import com.lab.cms.dto.LoginLogQueryDTO;
import com.lab.cms.dto.OperLogQueryDTO;
import com.lab.cms.entity.SysLoginLog;
import com.lab.cms.entity.SysOperLog;
import com.lab.cms.mapper.LogStatsMapper;
import com.lab.cms.mapper.SysLoginLogMapper;
import com.lab.cms.mapper.SysOperLogMapper;
import com.lab.cms.service.LogService;
import com.lab.cms.vo.DailyLoginCountVO;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.LogStatsVO;
import com.lab.cms.vo.LoginLogVO;
import com.lab.cms.vo.OperLogVO;
import com.lab.cms.vo.StatusCountVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询与统计服务实现。
 *
 * <h3>本类只读，因此没有任何 {@code @Transactional}</h3>
 * <p>方法体里只有 {@code SELECT}，不需要事务。给只读方法加事务的害处是
 * 占用一个数据库连接直到方法结束、并在某些配置下开启不必要的写事务 ——
 * 本类明确不加。
 *
 * <h3>⚠️ 三处刻意的"截断"</h3>
 * <p>{@code request_param}（≤2000 字符）与 {@code response_result} 在列表出参里
 * 截到 200 字符、{@code user_agent}（≤500）截到 120 字符。
 * 这是<b>出参层</b>的行为，不影响库里的数据：完整内容仍然可查（直接查库），
 * 只是不会被列表接口一次性吐给前端。
 * <p>为什么不做成"可选参数控制是否返回全文"：那会让列表接口多一个
 * "返回体大小不可预测"的分支，而分页接口的响应大小可控是一条重要性质
 * （否则有人拉 {@code size=100} 就能让响应涨到几 MB）。
 */
@Service
public class LogServiceImpl implements LogService {

    private static final Logger log = LoggerFactory.getLogger(LogServiceImpl.class);

    /** 操作日志列表出参里 {@code request_param} / {@code response_result} 的截断长度。 */
    private static final int PARAM_TRUNCATE = 200;

    /** 登录日志列表出参里 {@code user_agent} 的截断长度。 */
    private static final int UA_TRUNCATE = 120;

    /** 操作量 Top N 的默认 N。 */
    private static final int TOP_MODULE_LIMIT = 10;

    /** 登录走势窗口的默认值与上下界。 */
    private static final int DEFAULT_RECENT_DAYS = 7;
    private static final int MIN_RECENT_DAYS = 1;
    private static final int MAX_RECENT_DAYS = 90;

    // ==================== ⚠️ 两个 status 口径的常量（本类最重要的一处定义） ====================

    /** {@code sys_oper_log.status}：1 = 成功。 */
    private static final int OPER_STATUS_SUCCESS = 1;
    /** {@code sys_oper_log.status}：0 = 失败。 */
    private static final int OPER_STATUS_FAIL = 0;

    /** {@code sys_login_log.login_status}：1 = 成功。 */
    private static final int LOGIN_STATUS_SUCCESS = 1;
    /**
     * {@code sys_login_log.login_status}：<b>2</b> = 失败。
     * <p>⚠️ 不是 0。与 {@code OPER_STATUS_FAIL} 不同 —— 两者刻意分成两组常量，
     * 就是为了让"把操作日志的失败值直接用在登录日志上"在代码里显得很扎眼。
     */
    private static final int LOGIN_STATUS_FAIL = 2;

    /** 操作日志可排序列（库表列名）。 */
    private static final String[] OPER_SORTABLE_COLUMNS = {"oper_time", "cost_time", "id"};

    /** 登录日志可排序列（库表列名）。 */
    private static final String[] LOGIN_SORTABLE_COLUMNS = {"login_time", "id"};

    @Autowired
    private SysOperLogMapper operLogMapper;

    @Autowired
    private SysLoginLogMapper loginLogMapper;

    @Autowired
    private LogStatsMapper logStatsMapper;

    // ==================== 一、操作日志 ====================

    @Override
    public PageResult<OperLogVO> pageOperLogs(OperLogQueryDTO query) {
        OperLogQueryDTO q = (query == null) ? new OperLogQueryDTO() : query;
        q.normalize();
        requireTimeRange(q.getBeginTime(), q.getEndTime());

        LambdaQueryWrapper<SysOperLog> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(q.getModule())) {
            wrapper.apply("module LIKE {0}", SqlLikeUtil.containsPattern(q.getModule()));
        }
        if (StringUtils.hasText(q.getOperName())) {
            // oper_name 是冗余列（WebLogAspect 写入时就存了用户名），
            // 所以这里不需要 JOIN sys_user —— 查日志时不该再去用户表。
            wrapper.apply("oper_name LIKE {0}", SqlLikeUtil.containsPattern(q.getOperName()));
        }
        if (StringUtils.hasText(q.getOperType())) {
            wrapper.eq(SysOperLog::getOperType, q.getOperType());
        }
        if (q.getStatus() != null) {
            wrapper.eq(SysOperLog::getStatus, q.getStatus());
        }
        // ⚠️ 时间过滤打在 oper_time（业务时间）而不是 create_time（审计时间）——
        //    见 OperLogQueryDTO 的类注释。
        if (q.getBeginTime() != null) {
            wrapper.ge(SysOperLog::getOperTime, q.getBeginTime());
        }
        if (q.getEndTime() != null) {
            wrapper.le(SysOperLog::getOperTime, q.getEndTime());
        }

        Page<SysOperLog> mpPage = q.toMpPage(OPER_SORTABLE_COLUMNS);
        if (!q.hasSortField()) {
            // 补 id 得到确定全序：oper_time 是 DATETIME 秒精度，同一秒内多条是常态
            // （一次批量提交会写出好几条），只按 oper_time 排序翻页会重复/漏行。
            mpPage.addOrder(OrderItem.desc("oper_time"), OrderItem.desc("id"));
        }

        IPage<SysOperLog> page = operLogMapper.selectPage(mpPage, wrapper);
        List<OperLogVO> list = new ArrayList<>(page.getRecords().size());
        for (SysOperLog row : page.getRecords()) {
            list.add(toOperVO(row));
        }
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    // ==================== 二、登录日志 ====================

    @Override
    public PageResult<LoginLogVO> pageLoginLogs(LoginLogQueryDTO query) {
        LoginLogQueryDTO q = (query == null) ? new LoginLogQueryDTO() : query;
        q.normalize();
        requireTimeRange(q.getBeginTime(), q.getEndTime());

        LambdaQueryWrapper<SysLoginLog> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(q.getUsername())) {
            wrapper.apply("username LIKE {0}", SqlLikeUtil.containsPattern(q.getUsername()));
        }
        if (q.getLoginStatus() != null) {
            wrapper.eq(SysLoginLog::getLoginStatus, q.getLoginStatus());
        }
        if (q.getBeginTime() != null) {
            wrapper.ge(SysLoginLog::getLoginTime, q.getBeginTime());
        }
        if (q.getEndTime() != null) {
            wrapper.le(SysLoginLog::getLoginTime, q.getEndTime());
        }

        Page<SysLoginLog> mpPage = q.toMpPage(LOGIN_SORTABLE_COLUMNS);
        if (!q.hasSortField()) {
            mpPage.addOrder(OrderItem.desc("login_time"), OrderItem.desc("id"));
        }

        IPage<SysLoginLog> page = loginLogMapper.selectPage(mpPage, wrapper);
        List<LoginLogVO> list = new ArrayList<>(page.getRecords().size());
        for (SysLoginLog row : page.getRecords()) {
            list.add(toLoginVO(row));
        }
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    // ==================== 三、统计 ====================

    /**
     * 统计概览。
     *
     * <h3>为什么用 4 条 {@code GROUP BY} 而不是 6 条 {@code COUNT(*)}</h3>
     * <p>"操作日志成功数 + 失败数 + 登录成功数 + 登录失败数"看起来是 4 个独立计数，
     * 但如果用 4 条 {@code SELECT COUNT(*) ... WHERE status = ?}，
     * 这 4 条语句<b>不在同一个快照时刻</b>（默认隔离级别下每条都是独立读）。
     * 并发写入时会出现"成功数 + 失败数 > 总数"这种自相矛盾的统计。
     *
     * <p>改成"每张表一条 {@code GROUP BY}"，就得到 2 组各自内部一致的数字；
     * 再加上取"模块 Top N"与"按天走势"各 1 条固定查询，
     * 整个接口一共 <b>4 条查询</b>，且条数不随数据量变化。
     */
    @Override
    public LogStatsVO stats(Integer recentDays) {
        int days = normalizeRecentDays(recentDays);

        LogStatsVO vo = new LogStatsVO();
        vo.setRecentDays(days);

        // ---- 操作日志：1/0 ----
        Map<Integer, Long> operByStatus = toCountMap(logStatsMapper.countOperByStatus());
        long operSuccess = operByStatus.getOrDefault(OPER_STATUS_SUCCESS, 0L);
        long operFail = operByStatus.getOrDefault(OPER_STATUS_FAIL, 0L);
        vo.setOperSuccess(operSuccess);
        vo.setOperFail(operFail);
        vo.setOperTotal(sum(operByStatus));

        // ---- 登录日志：1/2（⚠️ 失败是 2，不是 0） ----
        Map<Integer, Long> loginByStatus = toCountMap(logStatsMapper.countLoginByStatus());
        long loginSuccess = loginByStatus.getOrDefault(LOGIN_STATUS_SUCCESS, 0L);
        long loginFail = loginByStatus.getOrDefault(LOGIN_STATUS_FAIL, 0L);
        vo.setLoginSuccess(loginSuccess);
        vo.setLoginFail(loginFail);
        long loginTotal = sum(loginByStatus);
        vo.setLoginTotal(loginTotal);

        vo.setLoginFailRate(failRate(loginSuccess, loginFail, loginTotal));

        // ---- 模块 Top N ----
        List<LabelCountVO> modules = logStatsMapper.countOperLogByModule(TOP_MODULE_LIMIT);
        vo.setTopModules(modules == null ? new ArrayList<>() : modules);

        // ---- 最近 N 天登录走势 ----
        LocalDateTime since = LocalDateTime.now().toLocalDate().minusDays(days - 1L).atStartOfDay();
        List<DailyLoginCountVO> trend = logStatsMapper.countLoginByDay(since);
        vo.setRecentLoginTrend(trend == null ? new ArrayList<>() : trend);

        vo.setGeneratedAt(LocalDateTime.now());
        log.debug("日志统计 | 操作={}条(成功{} 失败{}) | 登录={}条(成功{} 失败{}) | 窗口={}天",
                vo.getOperTotal(), operSuccess, operFail,
                loginTotal, loginSuccess, loginFail, days);
        return vo;
    }

    // ==================== 私有工具 ====================

    /**
     * 时间区间自洽性检查。
     *
     * <p>与 {@code ArticleServiceImpl#pageArticles} 同一口径：
     * <ul>
     *   <li>只传一端 → 合法（"此后所有" / "此前所有"）；</li>
     *   <li>两端都传且 {@code begin > end} → <b>400</b>，不静默返回空列表。</li>
     * </ul>
     * <p>为什么不能静默：用户填错区间时看到空结果，会得出"这段时间没有日志"的
     * 错误结论，进而去怀疑"日志没记上"，排查方向完全跑偏。
     */
    private void requireTimeRange(LocalDateTime begin, LocalDateTime end) {
        if (begin != null && end != null && begin.isAfter(end)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始时间不能晚于结束时间");
        }
    }

    /** 把 {@code GROUP BY status} 的结果行转成 {@code status → count}。 */
    private Map<Integer, Long> toCountMap(List<StatusCountVO> rows) {
        Map<Integer, Long> map = new HashMap<>(4);
        if (rows == null) {
            return map;
        }
        for (StatusCountVO row : rows) {
            long cnt = (row.getCnt() == null) ? 0L : row.getCnt();
            map.merge(row.getStatus(), cnt, Long::sum);
        }
        return map;
    }

    /**
     * 各组计数求和 = 总数。
     *
     * <p>⚠️ 不写成"成功 + 失败"：库里若存在 {@code 0/1} 或 {@code 1/2} 之外的
     * 脏状态值，那样算出来的"总数"会小于真实行数，
     * 而"失败率"的分母同时偏小 —— 两个错叠在一起很难被发现。
     * 直接对全部分组求和，脏值也会被算进总数。
     */
    private long sum(Map<Integer, Long> byStatus) {
        long total = 0L;
        for (Long cnt : byStatus.values()) {
            total += (cnt == null ? 0L : cnt);
        }
        return total;
    }

    /**
     * 登录失败率（百分比，两位小数）。
     *
     * <p>⚠️ 分母为 0 时返回 {@code 0.0}，不返回 {@code null} / {@code NaN}：
     * {@code NaN} 经 Jackson 序列化会变成字符串 {@code "NaN"}（非法 JSON 数值），
     * 前端 {@code parseFloat("NaN")} 得到 NaN，图表直接空白。
     * "没有任何登录记录 ⇒ 失败率 0%"本来就是合理语义。
     *
     * <p>用 {@link BigDecimal} 而不是 {@code (long)(x*100)/100.0}：
     * 后者在 {@code fail/total} 接近 1/3 这类除不尽的值上会有
     * {@code 33.329999999999998} 这样的双精度尾数，直接吐给前端很丑。
     */
    private Double failRate(long success, long fail, long total) {
        if (total <= 0) {
            return 0.0D;
        }
        return BigDecimal.valueOf(fail * 100.0D / total)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 归一化走势窗口天数。 */
    private int normalizeRecentDays(Integer recentDays) {
        if (recentDays == null || recentDays < MIN_RECENT_DAYS) {
            return DEFAULT_RECENT_DAYS;
        }
        return Math.min(recentDays, MAX_RECENT_DAYS);
    }

    /**
     * 按需截断字符串。
     * <p>被截断时补上省略号与原始长度，让人知道"这里不是全部内容"——
     * 只截断不加提示，使用者会以为日志内容本来就这么短。
     */
    private String truncate(String s, int limit) {
        if (s == null || s.length() <= limit) {
            return s;
        }
        return s.substring(0, limit) + "…(共 " + s.length() + " 字符)";
    }

    /**
     * 操作日志 Entity → VO。
     *
     * <p>{@code operTypeDesc} 用 {@code OperType.ofNullable(枚举名)} 反查中文；
     * 库里是<b>枚举名</b>（{@code WebLogAspect} 写的是 {@code annotation.type().name()}），
     * 反查不到（历史脏值/枚举改过名）时给 {@code null} 而不是报错 ——
     * 类型展示不出来不该让整页日志打不开。
     */
    private OperLogVO toOperVO(SysOperLog src) {
        OperLogVO vo = new OperLogVO();
        vo.setId(src.getId());
        vo.setModule(src.getModule());
        vo.setOperType(src.getOperType());
        OperType type = OperType.ofNullable(src.getOperType());
        vo.setOperTypeDesc(type == null ? null : type.getDescription());
        vo.setOperDesc(src.getOperDesc());
        vo.setRequestMethod(src.getRequestMethod());
        vo.setRequestUrl(src.getRequestUrl());
        vo.setRequestParam(truncate(src.getRequestParam(), PARAM_TRUNCATE));
        vo.setResponseResult(truncate(src.getResponseResult(), PARAM_TRUNCATE));
        vo.setOperId(src.getOperId());
        vo.setOperName(src.getOperName());
        vo.setOperIp(src.getOperIp());
        vo.setCostTime(src.getCostTime());
        vo.setStatus(src.getStatus());
        vo.setStatusDesc(operStatusDesc(src.getStatus()));
        vo.setErrorMsg(src.getErrorMsg());
        vo.setOperTime(src.getOperTime());
        return vo;
    }

    private LoginLogVO toLoginVO(SysLoginLog src) {
        LoginLogVO vo = new LoginLogVO();
        vo.setId(src.getId());
        vo.setUsername(src.getUsername());
        vo.setUserId(src.getUserId());
        vo.setIp(src.getIp());
        vo.setUserAgent(truncate(src.getUserAgent(), UA_TRUNCATE));
        vo.setLoginStatus(src.getLoginStatus());
        vo.setLoginStatusDesc(loginStatusDesc(src.getLoginStatus()));
        vo.setMessage(src.getMessage());
        vo.setLoginTime(src.getLoginTime());
        return vo;
    }

    /** {@code sys_oper_log.status} 的中文：1 成功 / 0 失败 / 其它 未知。 */
    private static String operStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        if (status == OPER_STATUS_SUCCESS) {
            return "成功";
        }
        return status == OPER_STATUS_FAIL ? "失败" : "未知";
    }

    /** {@code sys_login_log.login_status} 的中文：1 成功 / 2 失败 / 其它 未知。 */
    private static String loginStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        if (status == LOGIN_STATUS_SUCCESS) {
            return "成功";
        }
        return status == LOGIN_STATUS_FAIL ? "失败" : "未知";
    }
}
