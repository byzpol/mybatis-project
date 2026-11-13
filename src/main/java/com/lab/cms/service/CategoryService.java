package com.lab.cms.service;

import com.lab.cms.base.BaseService;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.dto.CategoryQueryDTO;
import com.lab.cms.dto.CategorySaveDTO;
import com.lab.cms.entity.CmsCategory;
import com.lab.cms.vo.CategoryTreeVO;
import com.lab.cms.vo.CategoryVO;

import java.util.List;

/**
 * 分类管理服务。
 *
 * <h2>⚠️ 关于"这是谁的主责域"——先把话说清楚</h2>
 * <p>按项目分工，分类与标签属于<b>成员B</b>的域（错误码段位 {@code 12000~12999}
 * 在 {@code ErrorCode} 的类注释里就写着"成员B 扩充"）。
 * 本次（S24）由成员A 代为实现接口层，因为：
 * <ul>
 *   <li>表结构、错误码、权限码（{@code category:view/manage/audit}）在 S06/S18 就已就绪，
 *       属于"公共基础设施"的交付范围；</li>
 *   <li>前端路由 {@code /categories}、{@code /tags} 与对应权限码
 *       （见 {@code frontend/src/router/index.js}）也早就登记好了，
 *       但一直是 {@code Placeholder} 占位组件 —— 界面点得进去、数据拿不到；</li>
 *   <li>本服务<b>没有新增任何错误码</b>，只用 {@code ErrorCode} 里已有的
 *       {@code 12001~12008}，因此没有越界改别人的段位。</li>
 * </ul>
 * <p>这条口径会同步写进 {@code docs/AI应用记录.md} 与实测记录，
 * 不把"代做"说成"本来就是我做的"。
 *
 * <h2>业务规则（本接口的"判据"，逐条可在真机复现）</h2>
 * <ol>
 *   <li><b>两级分类</b>：{@code level} 由 {@code parentId} 推导，不接收入参。
 *       给二级分类再挂子级 → 400。</li>
 *   <li><b>同级不同名</b>：同一 {@code parentId} 下不得重名 → 12004。
 *       ⚠️ 注意"同级"这个限定：不同父级下可以有同名分类，
 *       因为 {@code uk} 在 {@code cms_category} 上<b>并不存在</b>
 *       （只有 {@code idx_category_name} 普通索引）。</li>
 *   <li><b>删除保护</b>：有子分类 → 12002；有文章引用 → 12003。
 *       两者都<b>不</b>级联处理 —— 级联删除会让"删一个分类"变成不可预期的批量数据变更。</li>
 *   <li><b>停用保护</b>：停用前必须没有子分类，否则子分类仍可被文章选用，
 *       出现"父级已停用、子级还在用"的矛盾态。</li>
 *   <li><b>新建即启用</b>（{@code status = 1}，{@code audit_status = 1}）——
 *       关于 {@code audit_status} 的取舍见 {@code createCategory} 的说明。</li>
 * </ol>
 */
public interface CategoryService extends BaseService<CmsCategory> {

    /** 分页查询分类（含各分类的文章数）。 */
    PageResult<CategoryVO> pageCategories(CategoryQueryDTO query);

    /**
     * 查询完整分类树（两级）。
     * <p>返回根节点列表；子级挂在父节点的 {@code children} 上。
     */
    List<CategoryTreeVO> listTree();

    /** 按 id 查分类详情（含文章数与中文状态）。 */
    CategoryVO getCategory(Long id);

    /** 新建分类，返回新分类 ID。 */
    Long createCategory(CategorySaveDTO dto, Long operatorId);

    /** 编辑分类（名称 / 排序 / 描述）。 */
    void updateCategory(Long id, CategorySaveDTO dto, Long operatorId);

    /** 启用 / 停用分类。 */
    void changeStatus(Long id, Integer status, Long operatorId);

    /** 逻辑删除分类（有子分类或有文章时拒绝）。 */
    void deleteCategory(Long id, Long operatorId);
}
