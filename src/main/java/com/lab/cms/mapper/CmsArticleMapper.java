package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.vo.CategoryCountVO;
import com.lab.cms.vo.LabelCountVO;
import com.lab.cms.vo.StatusCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文章主表 Mapper。
 *
 * <p>继承 MP 的 {@link BaseMapper} 即获得 20 余个通用方法
 * （{@code selectById / selectList / selectPage / insert / updateById / deleteById ...}）。
 * 加分页插件后 {@code selectPage} 的 SQL 会被自动改写为带 {@code LIMIT} 的形式。
 *
 * <h3>⚠️ 关于这里的方法名与 MP 内置方法的冲突</h3>
 * <p>实验一实测过一条容易踩的规则：<b>如果 XML 里写了与 MP 内置方法同名的方法
 * （如 {@code selectById}），XML 优先，MP 会打 WARN 并跳过自己的实现</b>。
 * 表现是"不报错但行为变了"。所以本 Mapper 的自定义方法一律用
 * <b>不与 MP 重名</b>的前缀，例如下面的 {@code selectIdTitleByKeyword}。
 *
 * <h3>⚠️ 逻辑删除与手写 XML 的配合（本类最需要注意的点）</h3>
 * <p>{@code CmsArticle} 的 {@code deleted} 标了 {@code @TableLogic}，
 * 所以 <b>MP 生成的 SQL</b>会自动带 {@code AND deleted = 0}。
 * 但 <b>手写 Mapper XML 里的 SQL 不会</b>——XML 是原样执行。
 * 因此 {@link #selectIdTitleByKeyword} 的 XML 实现里必须自己写
 * {@code AND deleted = 0}，否则会查出已删除的文章。
 * 这是 MP 逻辑删除最经典的坑，写 XML 时务必记住。
 */
@Mapper
public interface CmsArticleMapper extends BaseMapper<CmsArticle> {

    /**
     * 按关键字模糊检索文章的 id 与标题（轻量列表，不取正文）。
     *
     * <p><b>为什么单独开这个方法而不是用 Wrapper</b>：
     * 本方法模拟"站点内检索"场景，只需要 {@code id + title} 两列
     * （正文是 LONGTEXT，全查会拖慢响应并浪费带宽）。
     * 用 {@code QueryWrapper.select("id", "title")} 也能实现，
     * 但这里是<b>为了演示"手写 XML + 逻辑删除手工处理"</b>这条路径 ——
     * 企业项目里复杂联表查询（如后面 S17 的检索优化）绕不开手写 XML，
     * 早点在这一步把配套的写法与坑暴露出来。
     *
     * <p>⚠️ {@code @Param("keyword")} 必须写：本方法只有一个参数，
     * 但 XML 里要用 {@code #{keyword}} 引用，MyBatis 对单参数
     * 虽然能自动命名（{@code param1} 或 {@code arg0}），但依赖这个
     * 会写不出可读的 XML，且一旦将来加第二个参数就全崩。显式起名最稳。
     *
     * <p><b>本方法是 S07 声明的签名，SQL 已在 S09 补齐</b>（见
     * {@code src/main/resources/com/lab/cms/mapper/CmsArticleMapper.xml}
     * 的 {@code selectIdTitleByKeyword}）。S07 当时只声明接口、XML 留 TODO，
     * 是为了让"骨架先行、SQL 后补"的顺序在 git 历史里看得见；
     * 现状是**接口与 XML 都已就位**，可以直接调用。
     * <p>⚠️ XML 里手写了 {@code AND deleted = 0} —— {@code @TableLogic} 只作用于
     * MP 自动生成的 SQL，手写 XML 必须自己写。
     *
     * @param keyword 检索关键字（会对标题做 {@code LIKE %keyword%} 匹配）
     * @return 命中文章的 id 与 title 列表；无命中返回空列表（不返回 null）
     */
    List<CmsArticle> selectIdTitleByKeyword(@Param("keyword") String keyword);

    /**
     * 【S24 新增】文章按状态分组计数（统计报表用）。
     *
     * <p>⚠️ 两句聚合 SQL 都<b>显式写了 {@code deleted = 0}</b>：
     * 本方法返回的是 VO 而不是带 {@code @TableLogic} 的实体，
     * MP 的逻辑删除改写够不着注解 SQL（与 {@link CmsArticleTagMapper} 是同一个道理）。
     * 漏了不会报错，只会让统计数字偏大 —— 属于"静默算错"，
     * 比报错更难发现。
     *
     * <p>中文状态名不在这里拼（回到 Java 用 {@code ArticleStatus} 补），
     * 避免 SQL 里出现第二份状态枚举（理由见 {@link StatusCountVO} 类注释）。
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM cms_article "
            + "WHERE deleted = 0 GROUP BY status ORDER BY status ASC")
    List<StatusCountVO> countAllByStatus();

    /**
     * 【S24 新增】文章按分类分组计数，直接带出分类名（统计报表用）。
     *
     * <h3>⚠️ 这里为什么可以 JOIN，而列表接口却刻意不 JOIN</h3>
     * <p>{@code ArticleServiceImpl#pageArticles} 选的是"方案 B：主表分页 + 批量补名"，
     * 理由是<b>JOIN 会放大分页的 {@code COUNT(*)}</b>（一旦关联一对多的表，
     * 一个文章会被数成多行）。那条理由在本方法上<b>不成立</b>：
     * <ul>
     *   <li>本方法是<b>聚合查询</b>（{@code GROUP BY}），不是分页查询，没有
     *       "count 被放大"的问题；</li>
     *   <li>关联方向是"文章 → 分类"（多对一），JOIN 不会让行数变多；</li>
     *   <li>一次 JOIN 拿到名字，比"先查计数、再 IN 查名字"少一次网络往返。</li>
     * </ul>
     * <p>结论：方案 B 是<b>分页场景</b>的结论，不是全项目的教条。
     * 把结论的适用边界写清楚，比"一律不许 JOIN"有用。
     *
     * <p>⚠️ {@code LEFT JOIN} 的 {@code c.deleted = 0} 放在 ON 而不是 WHERE：
     * 放在 WHERE 会把"分类已被逻辑删除"的文章整行过滤掉，
     * 于是各分类篇数之和 < 文章总数 —— 对不上账。
     * 放在 ON 则这些文章保留、名字为 NULL，外层 {@code COALESCE} 兜成"未分类"。
     */
    @Select("SELECT COALESCE(c.category_name, '未分类') AS label, COUNT(*) AS cnt "
            + "FROM cms_article a LEFT JOIN cms_category c "
            + "       ON c.id = a.category_id AND c.deleted = 0 "
            + "WHERE a.deleted = 0 "
            + "GROUP BY a.category_id, c.category_name "
            + "ORDER BY cnt DESC, label ASC LIMIT #{limit}")
    List<LabelCountVO> countAllByCategory(@Param("limit") int limit);

    /**
     * 【S24 新增】文章按分类 ID 分组计数（分类列表页回填用）。
     *
     * <h3>⚠️ 为什么与上面的 {@link #countAllByCategory(int)} 是两个方法，而不合并</h3>
     * <p>两者的<b>键不同</b>，而这是本质差异：
     * <ul>
     *   <li>上面那个以<b>分类名</b>为键 —— 给人看报表；</li>
     *   <li>本方法以<b>分类 ID</b> 为键 —— 给列表页回填。</li>
     * </ul>
     * <p>不能统一用名字，因为 {@code cms_category.category_name} 上只有
     * 普通索引 {@code idx_category_name}，<b>不是唯一键</b> ——
     * 不同父级下完全可以有同名分类。用名字回填，会把 A 分类的计数填到同名的 B 分类上，
     * 而接口依然返回 200、数字看起来也"合理"，是最难被发现的那类错。
     *
     * <p>⚠️ {@code AND category_id IS NOT NULL} 不是多余的：{@code cms_article.category_id}
     * 建表时是 {@code NULL DEFAULT NULL}，确实允许未分类的文章。
     * 不排除它们会多出一个 {@code categoryId = null} 的组，
     * 回填时 {@code map.get(null)} 会命中它 —— 语义混乱。
     * （报表侧的 SQL 反而<b>应该</b>保留这些行并显示为"未分类"，
     * 所以两个方法连 WHERE 都不同，进一步说明不该合并。）
     */
    @Select("SELECT category_id AS categoryId, COUNT(*) AS cnt FROM cms_article "
            + "WHERE deleted = 0 AND category_id IS NOT NULL GROUP BY category_id")
    List<CategoryCountVO> countAllGroupByCategoryId();
}
