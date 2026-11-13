package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 标签 Mapper。
 *
 * <p>全部能力用 {@code BaseMapper} + Wrapper 表达，<b>不写自定义 SQL、不写 XML</b>：
 * 能用 Wrapper 表达的就不手写，少写一处就少一处拼写错误与 SQL 注入面
 * （与 {@link CmsCategoryMapper} 同一口径）。
 *
 * <h3>⚠️ {@code use_count} 的自增自减不走这里</h3>
 * <p>"引用数 +1 / -1"看起来很适合放一个自定义方法，但本项目用
 * {@code LambdaUpdateWrapper#setSql} 在 Service 里表达：
 * <pre>
 *   update(null, Wrappers.&lt;CmsTag&gt;lambdaUpdate()
 *          .setSql("use_count = use_count + 1")
 *          .eq(CmsTag::getId, tagId));
 * </pre>
 * 这样做的两个好处：
 * <ul>
 *   <li><b>原子</b>：{@code use_count = use_count + 1} 由数据库做增量，
 *       不是"读出来 +1 再写回去"—— 后者在并发打标时会丢更新；</li>
 *   <li><b>不引入 XML</b>：仍然走 MP 的 Wrapper 通道，
 *       逻辑删除条件（{@code deleted = 0}）由 MP 自动追加，不需要人去记得写。</li>
 * </ul>
 */
@Mapper
public interface CmsTagMapper extends BaseMapper<CmsTag> {

    /**
     * 清理同名标签的"墓碑"（已逻辑删除的那一行），<b>这是一句物理删除</b>。
     *
     * <h2>⚠️ 为什么必须存在：唯一键 {@code uk_tag_name(tag_name, deleted)} 的代价真正落地了</h2>
     * <p>S18 给全库 9 个唯一键都把 {@code deleted} 追加成了末列，换来"逻辑删除后可以重建同名"。
     * 代价是<b>同一 {@code (tag_name, deleted=1)} 只能存在一行</b>。
     * 于是这条序列会失败：
     * <pre>
     *   ① 新建「活动」       库: [(活动, 0)]
     *   ② 删除「活动」       库: [(活动, 1)]
     *   ③ 再新建「活动」     库: [(活动, 1), (活动, 0)]   ← 不冲突，能插
     *   ④ 再删除「活动」     UPDATE 要产生第二行 (活动, 1) ⇒ <b>1062 Duplicate entry</b>
     * </pre>
     * <p>⚠️ S18 写建表脚本时已经在注释里<b>预告</b>过这件事，但那句注释当时只是纸面推演 ——
     * 标签功能还没做，这条代码路径<b>没人走到过</b>。S24 把标签接口做出来，
     * 它就从一个"预判"变成了"必须处理的事实"。
     *
     * <h2>为什么解法是"物理删除墓碑"，而不是别的</h2>
     * <ul>
     *   <li><b>不能改唯一键。</b>那要改已冻结的库表基线（S18 冻结、已写进部署文档与回归断言）。</li>
     *   <li><b>不能把标签也做成"复活"模式</b>（像 {@code cms_article_tag} 那样）。
     *       标签的 id 是会被 {@code cms_article_tag.tag_id} 引用的业务主键，
     *       "新建一个标签却返回一个旧 id"会让调用方与日志都难以理解；
     *       而关联表的行 id 从不对外暴露，复活不会有这个副作用。</li>
     *   <li><b>墓碑本身没有保留价值。</b>一条 {@code (名字, deleted=1)} 的行，
     *       信息量只有"这个名字曾经存在过" —— 而这件事在 {@code sys_oper_log}
     *       里有更完整的记录（谁、什么时候、删了哪个名字）。留着它反而挡路。</li>
     * </ul>
     *
     * <h2>⚠️ 这是全项目唯一一处"物理删除业务表"的地方，纪律如下</h2>
     * <ol>
     *   <li>条件必须<b>同时</b>带 {@code tag_name} 与 {@code deleted = 1}：
     *       少了后一个就会删掉活着的标签 —— 那是灾难性的。</li>
     *   <li>只允许在 {@code TagServiceImpl#deleteTag} 里调用，且必须在
     *       "确认该标签没有文章引用"之后。其它任何场景不许调用本方法。</li>
     *   <li>被删掉的行是<b>已经不可见</b>的数据（逻辑删除的意义就是"对应用不可见"），
     *       所以它不违反"业务数据不物理删除"的原则 —— 本方法清理的是缓存性的墓碑，
     *       不是业务事实。</li>
     * </ol>
     *
     * @param tagName 标签名（与活着的那个同名）
     * @return 清理掉的墓碑行数（0 或 1；历史脏数据下可能更多）
     */
    @Update("DELETE FROM cms_tag WHERE tag_name = #{tagName} AND deleted = 1")
    int purgeDeletedTombstones(@Param("tagName") String tagName);
}
