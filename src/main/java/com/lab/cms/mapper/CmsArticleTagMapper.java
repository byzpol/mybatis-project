package com.lab.cms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lab.cms.entity.CmsArticleTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文章-标签关联 Mapper。
 *
 * <h2>⚠️ 本类是"必须手写 SQL"的一个真实例外，原因只有一个：要绕过逻辑删除</h2>
 *
 * <p>项目其他 Mapper 一律"能用 {@code BaseMapper} + Wrapper 就不手写"
 * （见 {@link CmsCategoryMapper} / {@link CmsTagMapper} 的类注释）。
 * 本类破例，因为 MP 给带 {@code @TableLogic} 的实体生成的 SQL
 * <b>会被自动追加 {@code deleted = 0}</b>，于是下面两件事用 Wrapper <b>做不到</b>：
 *
 * <ol>
 *   <li><b>查已逻辑删除的那一行。</b>打标前必须先看看"这文章以前有没有过这个标签"
 *       （可能是被取消过、那行是 {@code deleted = 1}）。MP 的
 *       {@code selectOne} / {@code selectById} 都会加上 {@code deleted = 0}，
 *       <b>永远查不到它</b>，于是代码会以为"从没打过"，直接 INSERT —— 然后被唯一键拒。</li>
 *   <li><b>把那一行改回 {@code deleted = 0}（复活）。</b>MP 的 {@code update(entity, wrapper)}
 *       同样自动加 {@code deleted = 0}，所以它<b>只可能更新到活着的行</b>，
 *       对已删除的那一行完全无效 —— 而且不报错，返回影响行数 0，属于典型静默失效。</li>
 * </ol>
 *
 * <p>这两条都是 S24 实测确认的（不是读文档推的）：见
 * {@code docs/S24-分类标签与日志接口实测记录.md} 的"逻辑删除绕过"一节。
 *
 * <h3>为什么自定义 SQL 不受逻辑删除影响</h3>
 * <p>MP 的逻辑删除是<b>改写它自己生成的那几条 SQL</b>（由 {@code LogicDeleteInterceptor}
 * 一类的注入器完成），而注解上的 {@code @Select} / {@code @Update} 是原样交给
 * MyBatis 执行的用户 SQL，不经过那套改写。
 * <p>⚠️ 由此产生一条必须写下来的纪律：<b>本类里的 SQL 没有任何自动的
 * {@code deleted = 0} 保护，每一句都必须自己写全条件</b>
 * （这正是 {@code BaseEntity} 类注释里反复强调的那条 —— 手写 XML/SQL 必须自己带条件）。
 * 下面三条 SQL 都显式带了 {@code deleted} 条件，且方向各不相同，逐条看清楚了再改。
 *
 * <h3>⚠️ 为什么用 {@code NOW()} 而不是传一个 {@code LocalDateTime}</h3>
 * <p>审计时间的口径在项目里是"由 {@code AuditMetaObjectHandler} 在 Java 侧填"。
 * 但那个填充器<b>只对 MP 生成/改写的 SQL 生效</b>，自定义 SQL 它够不着。
 * 与其在方法签名里多挂一个时间参数（调用方还可能传错），
 * 不如让数据库用自己的时钟 —— 连接串里 {@code serverTimezone=Asia/Shanghai}，
 * 与 {@code LocalDateTime.now()} 同一时区，实测两者相差在毫秒级。
 * <p>⚠️ <b>但 {@code update_by} 必须显式传</b>：它是"谁操作的"，
 * 数据库无从知道，这正是"逻辑删除不填审计列"那个坑的修补点 ——
 * MP 的 {@code removeById} 生成的 {@code SET} 里<b>只有 {@code deleted = 1}</b>，
 * {@code update_by} / {@code update_time} 一个都不填，
 * 结果是"删了，但看不出谁删的"。下面第二条 SQL 显式补上这两列。
 */
@Mapper
public interface CmsArticleTagMapper extends BaseMapper<CmsArticleTag> {

    /**
     * 查 {@code (article_id, tag_id)} 是否存在<b>任意一行</b>（含已逻辑删除的），返回其主键。
     *
     * <p>返回 {@code Long id} 而不是实体对象，是刻意的"最小暴露"：
     * 调用方只需要知道"有没有、是哪一行"，把整行读回来反而更容易被误用
     * （比如拿旧的 {@code update_time} 去写回）。
     *
     * <p>⚠️ {@code LIMIT 1} 不是可有可无的：正常情况下最多只有一行
     * （理由见 {@link CmsArticleTag} 类注释的"复活"设计），
     * 但如果历史数据里出现了两行（S18 之前建的库可能），
     * 不加 LIMIT 会让 {@code selectOne} 抛 {@code TooManyResultsException} ——
     * 那是"因为脏数据把接口打挂"，比"取到其中一行"更糟。
     *
     * @return 行主键；不存在时返回 {@code null}
     */
    @Select("SELECT id FROM cms_article_tag "
            + "WHERE article_id = #{articleId} AND tag_id = #{tagId} LIMIT 1")
    Long selectAnyRowId(@Param("articleId") Long articleId, @Param("tagId") Long tagId);

    /**
     * 复活一行被逻辑删除的关联（{@code deleted: 1 → 0}）并补审计列。
     *
     * <p>⚠️ 条件里必须同时有 {@code id} 与 {@code deleted = 1}：
     * 只写 {@code id} 的话，这一句在"目标行其实还活着"时也会执行成功
     * （把 {@code update_time} 无意义地刷一次），调用方拿到的
     * "影响行数 = 1"就无法区分"真复活了"与"本来就是活的"。
     * 加上 {@code deleted = 1} 后，返回 0 即代表"调用方的假设错了"，
     * 是可断言的失败而不是静默。
     *
     * @return 影响行数（1 = 复活成功；0 = 该行不存在或本来就活着）
     */
    @Update("UPDATE cms_article_tag SET deleted = 0, update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 1")
    int reviveRow(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /**
     * 逻辑删除一行关联，并<b>显式补上审计列</b>。
     *
     * <p>⚠️ 这就是 {@code BaseEntity} 里那条"逻辑删除不填审计列"陷阱的修补版：
     * MP 的 {@code deleteById} 生成的是 {@code UPDATE ... SET deleted = 1 WHERE id = ?}，
     * 没有 {@code update_by} 也没有 {@code update_time} —— 在库里看不出是谁取消的标签。
     * 本方法把两列补全。
     *
     * <p>⚠️ 条件带 {@code deleted = 0}：确保只删"还活着的"那一行。
     * 若不写，对已经删除的行再执行一次也会返回 1，同样让调用方无法判断真实状态。
     *
     * @return 影响行数（1 = 删除成功；0 = 该行不存在或已被删除）
     */
    @Update("UPDATE cms_article_tag SET deleted = 1, update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int softDeleteRow(@Param("id") Long id, @Param("operatorId") Long operatorId);
}
