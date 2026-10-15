package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     * <p><b>⚠️ 本步（S07）只声明接口，XML 留 TODO —— 不写 SQL</b>，
     * 按手册要求放在业务步骤（S09 检索相关）再补。
     * 此时若直接调用本方法会抛 {@code BindingException: Invalid bound statement}
     * —— 这是预期行为，不是 bug。
     *
     * @param keyword 检索关键字（会对标题做 {@code LIKE %keyword%} 匹配）
     * @return 命中文章的 id 与 title 列表；无命中返回空列表（不返回 null）
     */
    List<CmsArticle> selectIdTitleByKeyword(@Param("keyword") String keyword);
}
