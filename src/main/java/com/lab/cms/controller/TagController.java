package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.ArticleTagBindDTO;
import com.lab.cms.dto.TagQueryDTO;
import com.lab.cms.dto.TagSaveDTO;
import com.lab.cms.service.TagService;
import com.lab.cms.vo.TagVO;
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
 * 标签接口。<b>共 7 个端点</b>（4 个写、3 个读）。
 *
 * <h3>⚠️ 为什么"文章打标"放在标签域而不是文章域</h3>
 * <p>更"RESTful"的写法是 {@code PUT /api/v1/articles/{id}/tags}，
 * 但那会把标签功能塞进 {@code ArticleController}，带来两个具体问题：
 * <ol>
 *   <li><b>权限域会串。</b>{@code ArticleController} 上有一条被测试钉住的规则：
 *       "本控制器所有端点的权限码必须属于 {@code article:} 域"
 *       （见 {@code ApiPermissionCoverageTest#testEveryArticleEndpointHasExistingPermissionCode}）。
 *       给文章加标签的权限语义是 {@code category:manage}（标签是分类标签域的东西），
 *       混进去会让那条断言直接失败 —— 而它是一条<b>有价值的</b>护栏，不该为了
 *       一个 URL 的形状去放宽它。</li>
 *   <li><b>模块归属会混。</b>{@code @OperLog(module = "文章管理")} 与
 *       {@code module = "标签管理"} 混在一个类里，按 module 统计操作量时
 *       要靠人记住"这个类里哪几个方法属于另一个模块"。</li>
 * </ol>
 * <p>所以路径取 {@code /api/v1/tags/articles/{articleId}}：
 * <b>资源是"文章上的标签"</b>，它天然属于标签域，只是以文章为限定条件。
 *
 * <h3>权限码</h3>
 * <ul>
 *   <li>读（标签列表 / 详情 / 某文章的标签）→ {@code category:view}</li>
 *   <li>写（建 / 改 / 删标签、打标）→ {@code category:manage}</li>
 * </ul>
 * <p>⚠️ 库表 tag 域<b>没有独立的权限码</b>（{@code sys_permission} 里 20 条中
 * 与标签相关的只有 {@code category:*} 三条），所以标签沿用分类的权限码。
 * 这与前端路由 {@code /tags} 用的 {@code category:manage} 是同一个口径
 * （见 {@code frontend/src/router/index.js}）。
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController extends BaseController {

    @Autowired
    private TagService tagService;

    /**
     * 标签分页列表。{@code GET /api/v1/tags}，权限 {@code category:view}。
     *
     * <p>参数：{@code page / size / sortField / sortOrder} + {@code tagName / status}。
     * <p>可排序列：{@code use_count}（热度）/ {@code tag_name} / {@code create_time} /
     * {@code update_time} / {@code id}。默认按热度倒序。
     * <p>⚠️ 不加 {@code @OperLog}（查询接口）。
     */
    @GetMapping
    @RequirePermission("category:view")
    public Result<PageResult<TagVO>> pageTags(TagQueryDTO query) {
        return Result.success(tagService.pageTags(query));
    }

    /**
     * 标签详情。{@code GET /api/v1/tags/{id}}，权限 {@code category:view}。
     *
     * <p>⚠️ 本接口比其他读接口多一个字段 {@code realUseCount}：
     * 关联表里的<b>真实</b>引用数（列表接口不填，避免 N+1）。
     * 它存在的意义是"让 {@code use_count} 缓存列的漂移可被发现"。
     */
    @GetMapping("/{id}")
    @RequirePermission("category:view")
    public Result<TagVO> getTag(@PathVariable("id") Long id) {
        return Result.success(tagService.getTag(id));
    }

    /**
     * 查某篇文章已打的标签。
     * {@code GET /api/v1/tags/articles/{articleId}}，权限 {@code category:view}。
     *
     * <p>⚠️ 与 {@code GET /api/v1/tags/{id}} <b>不会冲突</b>：
     * 路径段数不同（1 段 vs 2 段），Spring 匹配的是两套不同的 pattern。
     * 路径段数相同才会产生"literal 优先于变量"的讨论。
     *
     * <p>⚠️ 文章不存在时返回 <b>11001</b>（借文章域的错误码）——
     * 这里要表达的确实是"文章不存在"，造一个"标签不存在"反而误导。
     */
    @GetMapping("/articles/{articleId}")
    @RequirePermission("category:view")
    public Result<List<TagVO>> listArticleTags(@PathVariable("articleId") Long articleId) {
        return Result.success(tagService.listArticleTags(articleId));
    }

    /**
     * 新建标签。{@code POST /api/v1/tags}，权限 {@code category:manage}。
     *
     * <p>请求体：{@code {"tagName":"新标签"}}。
     * <p>⚠️ 请求体里没有 {@code status} 与 {@code useCount} —— 新建一律启用、
     * 计数一律从 0 开始（见 {@code TagSaveDTO} 的说明）。
     * <p>重名 → 12007。
     */
    @PostMapping
    @RequirePermission("category:manage")
    @OperLog(module = "标签管理", type = OperType.CREATE, desc = "新建标签")
    public Result<Long> createTag(@RequestBody @Valid TagSaveDTO dto) {
        Long id = tagService.createTag(dto, requireCurrentUserId());
        return Result.success("标签创建成功", id);
    }

    /**
     * 编辑标签（改名）。{@code PUT /api/v1/tags/{id}}，权限 {@code category:manage}。
     *
     * <p>重名 → 12007；不存在 → 12008。
     */
    @PutMapping("/{id}")
    @RequirePermission("category:manage")
    @OperLog(module = "标签管理", type = OperType.UPDATE, desc = "编辑标签")
    public Result<Void> updateTag(@PathVariable("id") Long id,
                                  @RequestBody @Valid TagSaveDTO dto) {
        tagService.updateTag(id, dto, requireCurrentUserId());
        return Result.<Void>success("标签已保存", null);
    }

    /**
     * <b>覆盖式</b>打标：把文章的标签集合设为请求体里的那组。
     * {@code PUT /api/v1/tags/articles/{articleId}}，权限 {@code category:manage}。
     *
     * <p>请求体：{@code {"tagIds":[1,2,3]}}。
     * <ul>
     *   <li><b>空数组 {@code []} = 清空该文章的所有标签</b>（合法操作）；</li>
     *   <li>{@code null}（不传字段）= 400（不把"前端忘了传"当成"清空"）；</li>
     *   <li>重复 id 会自动去重，不会重复计数；</li>
     *   <li>上限 20 个。</li>
     * </ul>
     *
     * <p>⚠️ 幂等：重复提交同一组 {@code tagIds}，一条写都不会发生。
     * <p>⚠️ 返回的是"打标后该文章的标签数量"，不是"新增加了几个" ——
     * 因为覆盖式语义下后者没有稳定含义（同一个请求在不同初始态下新增数不同）。
     *
     * <p>⚠️ 用 {@code PUT} 而不是 {@code POST}：整体替换 + 幂等，
     * 正是 PUT 的定义。
     */
    @PutMapping("/articles/{articleId}")
    @RequirePermission("category:manage")
    @OperLog(module = "标签管理", type = OperType.UPDATE, desc = "文章打标")
    public Result<Integer> bindArticleTags(@PathVariable("articleId") Long articleId,
                                           @RequestBody @Valid ArticleTagBindDTO dto) {
        int count = tagService.bindArticleTags(articleId, dto.getTagIds(), requireCurrentUserId());
        return Result.success("标签已保存", count);
    }

    /**
     * 删除标签（<b>逻辑删除</b>）。{@code DELETE /api/v1/tags/{id}}，权限 {@code category:manage}。
     *
     * <p>⚠️ 仍被文章引用时拒绝 → 12003（沿用"有文章不可删"这个码，
     * 因为 12xxx 段位里没有"标签被引用"的专用码，而本步的纪律是不新增码）。
     * <p>⚠️ 删除前会先清理同名墓碑（物理删除 {@code (同名, deleted=1)} 的行），
     * 否则"新建→删除→再新建→再删除"会在第四步撞唯一键 {@code uk_tag_name} 报 1062。
     * 这是全项目唯一一处物理删除，纪律见 {@code CmsTagMapper#purgeDeletedTombstones}。
     */
    @DeleteMapping("/{id}")
    @RequirePermission("category:manage")
    @OperLog(module = "标签管理", type = OperType.DELETE, desc = "删除标签")
    public Result<Void> deleteTag(@PathVariable("id") Long id) {
        tagService.deleteTag(id, requireCurrentUserId());
        return Result.<Void>success("标签已删除", null);
    }
}
