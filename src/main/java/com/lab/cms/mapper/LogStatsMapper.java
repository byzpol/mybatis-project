package com.lab.cms.mapper;

import com.lab.cms.vo.DailyLoginCountVO;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.StatusCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计聚合 Mapper（跨表，不绑定单一实体）。
 *
 * <h2>⚠️ 这是一个"不继承 {@code BaseMapper} 的 Mapper"，为什么它也能工作</h2>
 * <p>本项目其余 Mapper 都 {@code extends BaseMapper<T>}。本类不继承，因为
 * 它<b>没有对应的实体</b> —— 它干的是 {@code GROUP BY} 聚合，
 * 返回的是"一行不是一张表"的统计结果。
 *
 * <h3>⚠️ 本类只放"日志表的聚合"</h3>
 * <p>文章侧的聚合（按状态、按分类）放在 {@code CmsArticleMapper} 上 ——
 * 理由很简单：<b>Mapper 应该按"它读哪张表"归类，而不是按"谁来用"归类</b>。
 * 把 {@code cms_article} 的 SQL 放进一个叫 {@code LogStatsMapper} 的类里，
 * 下一个人改文章表结构时绝对想不到要来这里看一眼。
 *
 * <p>它仍然能被注册，是因为 {@code MybatisConfig#mapperScannerConfigurer} 扫的是
 * <b>整个 {@code com.lab.cms.mapper} 包下的接口</b>，而不是"带 {@code @Mapper} 的接口"
 * （MyBatis-Spring 的 {@code MapperScannerConfigurer} 默认按包扫，
 * 不检查父接口；{@code @Mapper} 注解在这里是给 IDE 与 Code Review 看的）。
 * <p>⚠️ 反过来说：<b>不要把非 Mapper 的接口放进这个包</b>，否则会莫名其妙
 * 变成一个可以被注入的 Mapper Bean，然后在调用时抛 "Invalid bound statement"。
 *
 * <h2>⚠️ 这里的 SQL 为什么必须手写 {@code deleted = 0}</h2>
 * <p>本类返回的是 VO，不是带 {@code @TableLogic} 的实体，
 * MP 的逻辑删除改写<b>完全够不着</b>这些语句。所以每一句都自己带了
 * {@code deleted = 0} —— 漏一句就会把已删除的文章/日志算进统计里，
 * 而且不报错（数字只是偏大）。
 * <p>这是 {@code BaseEntity} 类注释里"手写 SQL 必须自己写 AND deleted = 0"
 * 那条纪律在本项目的第三处落地（前两处是手写 XML 与
 * {@code CmsArticleTagMapper}）。
 *
 * <h2>⚠️ 为什么用注解而不是 XML</h2>
 * <p>项目里手写 SQL 目前有三种载体：XML（{@code CmsArticleMapper.xml} 等）、
 * 注解（{@code CmsArticleTagMapper}）、MP Wrapper。本类选注解，因为：
 * <ul>
 *   <li>语句都很短（一行 {@code SELECT ... GROUP BY ...}），XML 的
 *       {@code <select>} 骨架比 SQL 本身还长；</li>
 *   <li>没有动态 SQL 需求（条件全在调用方定，本类只提供固定的几种聚合）——
 *       而需要 {@code <if>} 的地方才是 XML 的真正优势所在。</li>
 * </ul>
 */
@Mapper
public interface LogStatsMapper {

    /**
     * 操作日志按模块分组计数。
     *
     * <p>⚠️ {@code ORDER BY cnt DESC} 用的是<b>别名</b>，MySQL 允许在
     * {@code ORDER BY} 里引用 select 列表的别名。这是能省的写法，
     * 但换成 {@code ORDER BY COUNT(*) DESC} 在标准 SQL 上更保险 ——
     * 此处用别名是因为同时限制了 {@code LIMIT}，语义更清晰。
     *
     * <p>⚠️ {@code label} 这个别名必须与 {@link LabelCountVO#getLabel()} 逐字一致，
     * 否则自动映射失败、返回一列 {@code null}，而且不报错。
     *
     * @param limit 最多返回多少组（防止线上模块名很多时把响应撑大）
     */
    @Select("SELECT module AS label, COUNT(*) AS cnt FROM sys_oper_log "
            + "WHERE deleted = 0 GROUP BY module ORDER BY cnt DESC LIMIT #{limit}")
    List<LabelCountVO> countOperLogByModule(@Param("limit") int limit);

    /**
     * 操作日志按执行结果分组计数。
     *
     * <p>⚠️ 结果是 {@code 0/1}（{@code 1 成功 / 0 失败}），
     * 与下面登录日志的 {@code 1/2} <b>不同口径</b> —— 两个方法名字里都写清楚了，
     * 调用方（{@code LogServiceImpl}）也分别按各自常量解释，不互相套用。
     *
     * <p>用一次 {@code GROUP BY} 拿到"成功 + 失败"两行，而不是发两条
     * {@code SELECT COUNT(*)}：少一次往返，且两者必然来自同一个快照时刻
     * （否则并发写入时可能出现"成功数 + 失败数 > 总数"）。
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM sys_oper_log "
            + "WHERE deleted = 0 GROUP BY status")
    List<StatusCountVO> countOperByStatus();

    /**
     * 登录日志按登录结果分组计数。
     *
     * <p>⚠️ 结果是 {@code 1 成功 / 2 失败}（<b>不是 1/0</b>）。
     * 若照操作日志的口径去读，失败数会被算成 0 —— 属于静默算错。
     */
    @Select("SELECT login_status AS status, COUNT(*) AS cnt FROM sys_login_log "
            + "WHERE deleted = 0 GROUP BY login_status")
    List<StatusCountVO> countLoginByStatus();

    /**
     * 登录日志按天聚合成功/失败数。
     *
     * <p>⚠️ {@code SUM(login_status = 1)} 依赖 MySQL 里"布尔表达式求值为 1/0"
     * 这一特性。它在 MySQL 上是对的，但<b>不是 SQL 标准</b>；
     * 换成 PostgreSQL 必须写 {@code COUNT(*) FILTER (WHERE login_status = 1)}
     * 或 {@code SUM(CASE WHEN ... THEN 1 ELSE 0 END)}。
     * 本项目锁死 MySQL（{@code DbType.MYSQL}），所以用短写法，
     * 但要在这里写明这个前提 —— 将来换库时这是必须检查的一处。
     *
     * <p>⚠️ 只返回"有数据的天"（见 {@link DailyLoginCountVO} 的类注释），
     * 长度不保证等于窗口天数。
     *
     * @param since 统计起点（含）
     */
    @Select("SELECT DATE_FORMAT(login_time, '%Y-%m-%d') AS day, "
            + "SUM(login_status = 1) AS successCnt, SUM(login_status = 2) AS failCnt "
            + "FROM sys_login_log WHERE deleted = 0 AND login_time >= #{since} "
            + "GROUP BY day ORDER BY day ASC")
    List<DailyLoginCountVO> countLoginByDay(@Param("since") LocalDateTime since);
}
