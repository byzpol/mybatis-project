package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.CategoryQueryDTO;
import com.lab.cms.dto.CategorySaveDTO;
import com.lab.cms.dto.CategoryStatusDTO;
import com.lab.cms.service.CategoryService;
import com.lab.cms.vo.CategoryTreeVO;
import com.lab.cms.vo.CategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 分类接口。<b>共 7 个端点</b>（5 个写、2 个读 + 1 个树查询）。
 *
 * <h3>本类只做四件事</h3>
 * <p>收参 → {@code @Valid} 校验 → 调 Service → 封装 {@code Result}。
 * 全类<b>没有一处 if 业务判断</b>：没有"父级是不是二级"（在 Service），
 * 没有"有没有子分类"（在 Service），没有"同级重名"（在 Service）。
 * <p>这条约束与 {@code ArticleController} 一致，理由也一致：
 * 业务规则写在 Controller 里，别的入口（定时任务、别的 Service）就绕过了它。
 *
 * <h3>权限码全部来自库表 {@code sys_permission}，不是契约草稿</h3>
 * <ul>
 *   <li>读（列表 / 树 / 详情）→ {@code category:view}（四个角色都有）</li>
 *   <li>写（新建 / 编辑 / 启停用 / 删除）→ {@code category:manage}（编辑 / 主编 / 管理员）</li>
 * </ul>
 * <p>⚠️ 库表里还有 {@code category:audit}（分类审批），本类<b>没有</b>接口用它 ——
 * 分类审批流程本期未实现，这一点在 {@code CategoryService} 与实测记录里都写明了。
 * 正因为存在"有码无接口"，本类<b>不</b>参与 {@code ApiPermissionCoverageTest}
 * 里那条"权限码必须被用满"的断言（那条目前只覆盖 {@code article:} 域）。
 *
 * <h3>⚠️ {@code GET /tree} 与 {@code GET /{id}} 的路径歧义</h3>
 * <p>两者都是 {@code GET /api/v1/categories/} 下的一段路径。
 * Spring 的路径匹配<b>优先精确匹配</b>（literal 段优先于 URI 变量段），
 * 所以 {@code /categories/tree} 一定命中本类的 {@link #listTree()}，
 * 不会被 {@link #getCategory(Long)} 抢走 —— 这一点不需要额外配置，
 * 但要知道它成立，否则会误以为"tree 会被当成 id=tree 去查库"。
 * （顺带：{@code tree} 也无法被解析成 {@code Long}，真走错会得到 400 而不是空数据。）
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController extends BaseController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 分类分页列表。{@code GET /api/v1/categories}，权限 {@code category:view}。
     *
     * <p>参数：{@code page / size / sortField / sortOrder} +
     * {@code categoryName / parentId / status / auditStatus}（全部可选）。
     * <p>⚠️ {@code parentId=0} 是<b>有意义的筛选值</b>（只看顶级分类），
     * 与"不传"（看全部）不同。
     *
     * <p>⚠️ <b>不加 {@code @OperLog}</b>：查询类接口不记操作日志。
     * 这是全项目口径（文章域从 S11 起就是这么定的），
     * 原因是列表页翻一页就是一条日志，{@code sys_oper_log} 会被淹掉，
     * 真正需要追责的写操作反而查不到。
     */
    @GetMapping
    @RequirePermission("category:view")
    public Result<PageResult<CategoryVO>> pageCategories(CategoryQueryDTO query) {
        return Result.success(categoryService.pageCategories(query));
    }

    /**
     * 分类树（两级）。{@code GET /api/v1/categories/tree}，权限 {@code category:view}。
     *
     * <p>为什么单独一个接口而不是"前端拿列表自己拼树"：前端要拼树就得把
     * <b>全部分类</b>拉下来（绕过分页），而且拼接逻辑要在每个用到分类树的地方
     * 各写一遍（文章编辑的级联选择、分类管理页、报表筛选项）。
     * 服务端一次组装好，前端只负责渲染。
     *
     * <p>⚠️ 不加 {@code @OperLog}（查询接口）。
     */
    @GetMapping("/tree")
    @RequirePermission("category:view")
    public Result<List<CategoryTreeVO>> listTree() {
        return Result.success(categoryService.listTree());
    }

    /**
     * 分类详情。{@code GET /api/v1/categories/{id}}，权限 {@code category:view}。
     *
     * <p>⚠️ id 不存在时返回 <b>12001</b>（HTTP 保持 200）——
     * 与全项目口径一致：10xxx/12xxx 段业务码走"HTTP 200 + body 带码"，
     * 前端拦截器统一读 {@code code}。
     */
    @GetMapping("/{id}")
    @RequirePermission("category:view")
    public Result<CategoryVO> getCategory(@PathVariable("id") Long id) {
        return Result.success(categoryService.getCategory(id));
    }

    /**
     * 新建分类。{@code POST /api/v1/categories}，权限 {@code category:manage}。
     *
     * <p>请求体：{@code {"categoryName":"...","parentId":1,"sort":10,"description":"..."}}。
     * <p>⚠️ 请求体里<b>没有</b> {@code level} / {@code status} / {@code audit_status} ——
     * 这三个由 Service 决定，不接受入参（防批量赋值）。
     *
     * <p><b>幂等性：否。</b>每调一次多一个分类。这与文章草稿的选择一致：
     * 连点两次得到两个分类，而不是第二次静默覆盖第一个 ——
     * 后者会让用户以为"点了没反应"。
     *
     * <p>{@code @OperLog} 保持默认记录参数：分类的字段都很短，
     * 不存在撑爆 {@code request_param} 的风险（对比文章草稿正文是 LONGTEXT，
     * 那里显式写了 {@code saveParams = false}）。
     */
    @PostMapping
    @RequirePermission("category:manage")
    @OperLog(module = "分类管理", type = OperType.CREATE, desc = "新建分类")
    public Result<Long> createCategory(@RequestBody @Valid CategorySaveDTO dto) {
        Long id = categoryService.createCategory(dto, requireCurrentUserId());
        return Result.success("分类创建成功", id);
    }

    /**
     * 编辑分类（名称 / 排序 / 描述）。
     * {@code PUT /api/v1/categories/{id}}，权限 {@code category:manage}。
     *
     * <p>幂等性：是（PUT 语义 = 整体替换）。
     * <p>⚠️ 请求体里若带了与库里不同的 {@code parentId}，会返回 <b>400</b>
     * 而不是静默忽略 —— 理由见 {@code CategoryServiceImpl#updateCategory}。
     */
    @PutMapping("/{id}")
    @RequirePermission("category:manage")
    @OperLog(module = "分类管理", type = OperType.UPDATE, desc = "编辑分类")
    public Result<Void> updateCategory(@PathVariable("id") Long id,
                                       @RequestBody @Valid CategorySaveDTO dto) {
        categoryService.updateCategory(id, dto, requireCurrentUserId());
        return Result.<Void>success("分类已保存", null);
    }

    /**
     * 启用 / 停用分类。
     * {@code PUT /api/v1/categories/{id}/status}，权限 {@code category:manage}。
     *
     * <p>请求体：{@code {"status":0}}（0 停用 / 1 启用）。
     *
     * <h3>为什么是独立端点而不是"编辑分类时顺带传 status"</h3>
     * <p>启停用在业务上是<b>开关动作</b>，不是"内容编辑"：
     * <ul>
     *   <li>它有自己的保护规则（有子分类不许停用），而编辑没有；</li>
     *   <li>它需要被单独记录到操作日志（"谁在什么时候停用了哪个分类"
     *       是一个高频审计问题），合并进编辑接口后就只能看到"编辑了分类"。</li>
     * </ul>
     * <p>用 {@code PUT} 而不是 {@code POST}：语义是"把状态置为某个值"，
     * 幂等 —— 调用两次的结果与一次相同。
     */
    @PutMapping("/{id}/status")
    @RequirePermission("category:manage")
    @OperLog(module = "分类管理", type = OperType.UPDATE, desc = "分类启停用")
    public Result<Void> changeStatus(@PathVariable("id") Long id,
                                     @RequestBody @Valid CategoryStatusDTO dto) {
        categoryService.changeStatus(id, dto.getStatus(), requireCurrentUserId());
        return Result.<Void>success(dto.getStatus() == 1 ? "分类已启用" : "分类已停用", null);
    }

    /**
     * 删除分类（<b>逻辑删除</b>）。
     * {@code DELETE /api/v1/categories/{id}}，权限 {@code category:manage}。
     *
     * <p>⚠️ 两道保护：有子分类 → 12002；有文章引用 → 12003。
     * <b>不级联删除</b>（见 {@code CategoryServiceImpl#deleteCategory}）。
     *
     * <p>⚠️ 逻辑删除会<b>显式补 {@code update_by} / {@code update_time}</b> ——
     * MP 的 {@code deleteById} 生成的 SET 里只有 {@code deleted = 1}，
     * 那样在库里看不出是谁删的。这是项目记录过的高频陷阱。
     */
    @DeleteMapping("/{id}")
    @RequirePermission("category:manage")
    @OperLog(module = "分类管理", type = OperType.DELETE, desc = "删除分类")
    public Result<Void> deleteCategory(@PathVariable("id") Long id) {
        categoryService.deleteCategory(id, requireCurrentUserId());
        return Result.<Void>success("分类已删除", null);
    }
}
