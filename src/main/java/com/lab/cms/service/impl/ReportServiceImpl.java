package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsArticleTag;
import com.lab.cms.entity.CmsCategory;
import com.lab.cms.entity.CmsTag;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsArticleTagMapper;
import com.lab.cms.mapper.CmsCategoryMapper;
import com.lab.cms.mapper.CmsTagMapper;
import com.lab.cms.mapper.SysReportMapper;
import com.lab.cms.entity.SysReport;
import com.lab.cms.service.ReportService;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.ReportOverviewVO;
import com.lab.cms.vo.ReportTypeVO;
import com.lab.cms.vo.StatusCountVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计报表服务实现。
 *
 * <h3>{@code sys_report} 在这之前是什么状态</h3>
 * <p>建表语句 + 1 条种子数据（{@code id=1}，2026-10-05 由 admin 生成），
 * 全仓<b>没有实体、没有 Mapper、没有任何写入方</b>。
 * 本类是它的第一个写入者 —— 这一点会写进实测记录，
 * 因为它正是"表结构有、接口没有"那类问题的（被解决的）样本。
 *
 * <h3>⚠️ 本类与 {@code LogServiceImpl} 的分工</h3>
 * <p>日志统计（{@code /logs/stats}）回答"系统最近健康吗"，
 * 报表（{@code /reports/overview}）回答"内容库长什么样"。
 * 两者都叫"统计"，但数据源与受众完全不同，因此分属两个 Service、两个 Controller。
 * 混在一起会让 {@code LogController} 依赖 {@code cms_article} 表，
 * 而"管日志的类居然要读文章表"是明显的边界错误。
 */
@Service
public class ReportServiceImpl implements ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

    /** 报表类型：文章状态分布。 */
    private static final int TYPE_ARTICLE_STATUS = 1;
    /** 报表类型：分类分布。 */
    private static final int TYPE_CATEGORY_DIST = 2;

    /** 分类分布最多返回多少个分类（按篇数倒序）。防止分类很多时响应被撑大。 */
    private static final int CATEGORY_DIST_LIMIT = 20;

    /** 统计周期的格式：只到月（与 {@code sys_report.stat_period} 的种子写法一致）。 */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private CmsCategoryMapper categoryMapper;

    @Autowired
    private CmsTagMapper tagMapper;

    @Autowired
    private CmsArticleTagMapper articleTagMapper;

    @Autowired
    private SysReportMapper reportMapper;

    /**
     * 项目自带的 {@code ObjectMapper}（见 {@code JacksonConfig}）。
     * <p>用它而不是 {@code new ObjectMapper()}：自定义版本注册了
     * {@code JavaTimeModule} 与 {@code NON_NULL} 策略，
     * 用默认实例序列化 {@code LocalDateTime} 会抛
     * {@code InvalidDefinitionException: Java 8 date/time type not supported by default}。
     */
    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 一、类型清单 ====================

    /**
     * 报表类型清单。
     *
     * <p>取值与说明来自 {@code sys_report.report_type} 的列注释
     * （1 文章状态分布 / 2 分类分布 / 3 审核效率 / 4 用户活跃 / 9 自定义）。
     * <p>⚠️ {@code implemented} 如实标注：只有 1 与 2 有计算逻辑。
     * 3（审核效率）与 4（用户活跃）需要按"审核耗时""用户行为"做统计，
     * 涉及文章审核流水与登录日志的联合分析，本期未实现 ——
     * 前端据此把这两项置灰，而不是让用户点了才收到 14001。
     */
    @Override
    public List<ReportTypeVO> listTypes() {
        List<ReportTypeVO> list = new ArrayList<>(5);
        list.add(type(TYPE_ARTICLE_STATUS, "文章状态分布", "按 10 个内容状态统计文章篇数", true));
        list.add(type(TYPE_CATEGORY_DIST, "分类分布", "按内容分类统计文章篇数", true));
        list.add(type(3, "审核效率", "初审/终审的平均耗时与通过率", false));
        list.add(type(4, "用户活跃", "按登录与操作频次统计用户活跃度", false));
        list.add(type(9, "自定义", "由使用方指定统计口径（占位）", false));
        return list;
    }

    private ReportTypeVO type(int code, String name, String description, boolean implemented) {
        ReportTypeVO vo = new ReportTypeVO();
        vo.setType(code);
        vo.setName(name);
        vo.setDescription(description);
        vo.setImplemented(implemented);
        return vo;
    }

    // ==================== 二、实时概览 ====================

    /**
     * 实时统计。
     *
     * <h3>⚠️ "总数"一律指"未逻辑删除的数据"</h3>
     * <p>{@code selectCount} 走的是 MP 生成的 SQL，会自动带 {@code deleted = 0}；
     * 手写的那几条聚合 SQL 也都自己写了 {@code deleted = 0}。
     * 所以本接口的所有数字都是"当前有效数据"的口径 ——
     * 与列表接口看到的一致。这一点必须明确，否则"报表说 20 篇、列表只看到 18 篇"
     * 会被当成 bug（实际是逻辑删除的口径差）。
     *
     * <h3>⚠️ 为什么分类分布不返回"未分类"这一组以外的空组</h3>
     * <p>{@code GROUP BY} 的语义就是"有才出现"。9 个种子里没被任何文章使用的分类
     * 不会出现在分类分布里 —— 这不是漏，是"没有数据就没有行"。
     * <p>补全需要额外查一次分类表做左连接，而收益只是"看到几个 0"。
     * 真正需要"每个分类各有多少篇"的场景（分类管理页）走的是
     * {@code CategoryVO.articleCount}，那里是逐分类给的。
     */
    @Override
    public ReportOverviewVO overview() {
        ReportOverviewVO vo = new ReportOverviewVO();

        // ---- 文章 ----
        vo.setArticleTotal(articleMapper.selectCount(Wrappers.<CmsArticle>lambdaQuery()));
        vo.setArticleStatusDistribution(articleStatusDistribution());

        // ---- 分类 ----
        vo.setCategoryTotal(categoryMapper.selectCount(Wrappers.<CmsCategory>lambdaQuery()));
        List<LabelCountVO> categoryDist = articleMapper.countAllByCategory(CATEGORY_DIST_LIMIT);
        vo.setCategoryDistribution(categoryDist == null ? new ArrayList<>() : categoryDist);

        // ---- 标签 ----
        vo.setTagTotal(tagMapper.selectCount(Wrappers.<CmsTag>lambdaQuery()));
        vo.setArticleTagTotal(articleTagMapper.selectCount(Wrappers.<CmsArticleTag>lambdaQuery()));

        vo.setStatPeriod(PERIOD_FORMAT.format(LocalDateTime.now()));
        vo.setGeneratedAt(LocalDateTime.now());
        return vo;
    }

    /**
     * 文章状态分布：SQL 只给状态码与计数，中文由 Java 补。
     *
     * <p>⚠️ {@code ArticleStatus.ofNullable} 查不到时给"未知状态"而不是抛异常：
     * 库里若有一条 {@code status = 99} 的脏数据，用 {@code ofNullable} 能跳过它、
     * 用 {@code ArticleStatus.of} 会让整个报表 500。
     * <p>这与 {@code ArticleServiceImpl#copyBaseFields} 的处理完全一致 ——
     * 状态中文的兜底口径全项目只有一条。
     */
    private List<StatusCountVO> articleStatusDistribution() {
        List<StatusCountVO> rows = articleMapper.countAllByStatus();
        List<StatusCountVO> list = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return list;
        }
        for (StatusCountVO row : rows) {
            ArticleStatus status = ArticleStatus.ofNullable(row.getStatus());
            row.setStatusDesc(status == null ? "未知状态" : status.getDescription());
            list.add(row);
        }
        return list;
    }

    // ==================== 三、落快照 ====================

    /**
     * 生成并保存一份报表快照。
     *
     * <h3>⚠️ {@code result_json} 是 MySQL 的 JSON 列，必须序列化而不能手拼</h3>
     * <p>手拼字符串（{@code "{\"a\":" + x + "}"}）的失败方式是运行期
     * {@code Invalid JSON text}，且只在某个字段恰好含引号/换行时触发 ——
     * 典型的"测试时都对，线上偶发 500"。这里一律走 {@code ObjectMapper}。
     *
     * <p>⚠️ 序列化用的是项目自定义的 {@code ObjectMapper} Bean，
     * 不是 {@code new ObjectMapper()}：后者没有注册 {@code JavaTimeModule}，
     * 遇到 {@code generatedAt}（{@code LocalDateTime}）会直接抛异常。
     *
     * <h3>⚠️ 异常要转成业务码 14002，不能让它变成 500</h3>
     * <p>{@code JsonProcessingException} 是<b>受检异常</b>，
     * 编译期就必须处理。转成 {@code REPORT_GENERATE_FAILED(14002)} 后，
     * 前端能收到"报表生成失败"这样的可读提示；
     * 直接往上层抛会让 {@code GlobalExceptionHandler} 的兜底分支接住，
     * 返回"系统内部错误，请联系管理员"—— 对使用者毫无信息量。
     *
     * <h3>⚠️ 两种类型的 payload 不同，这是刻意的</h3>
     * <p>类型 1 存整份概览；类型 2 只存分类相关的部分。
     * 若两种都存整份概览，{@code report_type} 这一列就失去了筛选意义
     * （按类型查快照会查到内容相同的重复数据）。
     *
     * @param reportType 1 或 2；其它值 → 14001
     * @return 新快照 id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveSnapshot(Integer reportType, Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        int type = (reportType == null) ? TYPE_ARTICLE_STATUS : reportType;

        ReportOverviewVO overview = overview();
        String name;
        Object payload;
        if (type == TYPE_ARTICLE_STATUS) {
            name = "文章状态分布统计";
            payload = overview;
        } else if (type == TYPE_CATEGORY_DIST) {
            name = "分类分布统计";
            // LinkedHashMap 保证 JSON 里字段顺序稳定 —— 便于人直接看 result_json，
            // 也让"两次生成的快照可否按文本比较"成为可能。
            Map<String, Object> categoryPart = new LinkedHashMap<>();
            categoryPart.put("categoryTotal", overview.getCategoryTotal());
            categoryPart.put("categoryDistribution", overview.getCategoryDistribution());
            categoryPart.put("statPeriod", overview.getStatPeriod());
            categoryPart.put("generatedAt", overview.getGeneratedAt());
            payload = categoryPart;
        } else {
            throw new BusinessException(ErrorCode.REPORT_TYPE_UNSUPPORTED,
                    "不支持的报表类型：" + type + "（当前支持 1 文章状态分布、2 分类分布）");
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("报表快照序列化失败 | type={} | payloadType={}",
                    type, payload.getClass().getName(), e);
            throw new BusinessException(ErrorCode.REPORT_GENERATE_FAILED);
        }

        SysReport entity = new SysReport();
        entity.setReportName(name);
        entity.setReportType(type);
        entity.setStatPeriod(overview.getStatPeriod());
        entity.setResultJson(json);
        entity.setGeneratorId(operatorId);
        entity.setGenerateTime(LocalDateTime.now());
        reportMapper.insert(entity);

        log.info("生成报表快照 | id={} | type={} | name={} | 周期={} | JSON 长度={} | 操作人={}",
                entity.getId(), type, name, overview.getStatPeriod(), json.length(), operatorId);
        return entity.getId();
    }
}
