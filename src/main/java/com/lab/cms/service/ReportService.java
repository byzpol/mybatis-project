package com.lab.cms.service;

import com.lab.cms.vo.ReportOverviewVO;
import com.lab.cms.vo.ReportTypeVO;

import java.util.List;

/**
 * 统计报表服务。
 *
 * <h2>⚠️ 本接口为什么不继承 {@code BaseService}</h2>
 * <p>与 {@link LogService} 同理：报表的"计算"跨 {@code cms_article} /
 * {@code cms_category} / {@code cms_tag} / {@code cms_article_tag} 四张表，
 * 只有"存快照"这一步落在 {@code sys_report} 上。
 * 为了用 {@code BaseService} 而把接口绑定到 {@code SysReport} 这个实体，
 * 会让"概览"与"类型清单"两个方法显得莫名其妙。
 *
 * <h2>⚠️ 三个方法的权限码不同</h2>
 * <ul>
 *   <li>{@link #listTypes()} 与 {@link #overview()}：{@code report:view}
 *       （库表里授予主编与管理员）。</li>
 *   <li>{@link #saveSnapshot}：同样 {@code report:view} ——
 *       <b>没有</b>要求更高的权限。理由：生成一份快照是"把看到的数字存下来"，
 *       不产生新的业务数据、不改变任何文章状态，它就是一次带副作用的查询。
 *       {@code report:export}（导出）才是更高权限的动作，本期未实现。</li>
 * </ul>
 */
public interface ReportService {

    /** 支持的报表类型清单（供前端渲染下拉框）。 */
    List<ReportTypeVO> listTypes();

    /** 实时统计概览（不落库）。 */
    ReportOverviewVO overview();

    /**
     * 把当前统计结果存为一条报表快照（写 {@code sys_report}）。
     *
     * @param reportType 报表类型：1 文章状态分布 / 2 分类分布；
     *                   其它值 → {@code REPORT_TYPE_UNSUPPORTED(14001)}
     * @return 新快照的 id
     */
    Long saveSnapshot(Integer reportType, Long operatorId);
}
