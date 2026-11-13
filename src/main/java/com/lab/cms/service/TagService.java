package com.lab.cms.service;

import com.lab.cms.base.BaseService;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.dto.TagQueryDTO;
import com.lab.cms.dto.TagSaveDTO;
import com.lab.cms.entity.CmsTag;
import com.lab.cms.vo.TagVO;

import java.util.List;

/**
 * 标签管理服务。
 *
 * <p>分工口径与 {@link CategoryService} 相同：本域属成员B，
 * S24 由成员A 代为实现接口层，<b>不新增任何 {@code 12xxx} 错误码</b>，
 * 只用 {@code ErrorCode} 里已有的 {@code 12007}（标签重名）/{@code 12008}（标签不存在）。
 *
 * <h2>⚠️ {@code use_count} 维护与"唯一键残留"的处理（本域最容易出问题的两处）</h2>
 * <ol>
 *   <li><b>引用计数</b>：打标 {@code +1}、取消 {@code -1}，用
 *       {@code setSql("use_count = use_count ± 1")} 让数据库做原子增量，
 *       不读出来改回去（后者并发下会丢更新）。减的时候兜一个
 *       {@code GREATEST(..., 0)}，避免缓存漂移导致负数。</li>
 *   <li><b>标签删除时会撞唯一键</b>：{@code uk_tag_name(tag_name, deleted)} 决定了
 *       "同名标签只能被逻辑删除一次"。{"新建 → 删除 → 再新建 → 再删除"}
 *       这条序列在第四步会撞 1062 —— 本服务用"先清理同名墓碑"解决，
 *       细节见 {@code CmsTagMapper#purgeDeletedTombstones}。</li>
 * </ol>
 *
 * <h2>业务规则</h2>
 * <ol>
 *   <li><b>重名拒绝</b> → 12007；<b>不存在</b> → 12008。</li>
 *   <li><b>删除保护</b>：仍被文章引用的标签不可删 → 12003（沿用"有文章不可删"，
 *       本段位没有专门的"标签被引用"码，且本步不新增码）。</li>
 *   <li><b>打标是覆盖式的</b>：{@code PUT /tags/articles/{articleId}} 传的就是"最终集合"，
 *       原有而本次未出现的标签会被取消。理由见 {@code ArticleTagBindDTO}。</li>
 *   <li><b>停用的标签不可再被打上</b>，但已打上的不会被自动摘除
 *       （自动摘除是"用户没做的数据变更"，不做）。</li>
 * </ol>
 */
public interface TagService extends BaseService<CmsTag> {

    /** 分页查询标签。 */
    PageResult<TagVO> pageTags(TagQueryDTO query);

    /** 按 id 查标签详情（含关联表里的真实引用数）。 */
    TagVO getTag(Long id);

    /** 新建标签，返回新标签 ID。 */
    Long createTag(TagSaveDTO dto, Long operatorId);

    /** 编辑标签（改名）。 */
    void updateTag(Long id, TagSaveDTO dto, Long operatorId);

    /** 逻辑删除标签（仍被文章引用时拒绝）。 */
    void deleteTag(Long id, Long operatorId);

    /** 查某篇文章已打的标签。 */
    List<TagVO> listArticleTags(Long articleId);

    /**
     * 覆盖式打标：把文章的标签集合设为 {@code tagIds}。
     *
     * @param tagIds 目标标签集合；<b>空列表表示清空</b>
     * @return 打标后该文章的标签数量
     */
    int bindArticleTags(Long articleId, List<Long> tagIds, Long operatorId);
}
