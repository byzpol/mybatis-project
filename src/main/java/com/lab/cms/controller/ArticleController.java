package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.BatchSubmitDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.service.ArticleService;
import com.lab.cms.vo.BatchSubmitResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 文章接口（S09 建立，本步只含"草稿与投稿"四个）。
 *
 * <h3>本类只做四件事</h3>
 * <p>收参 → 交给 {@code @Valid} 校验 → 调 Service → 封装 {@code Result}。
 * 全类<b>没有一处 if 业务判断</b>：没有"id 是否为空"的检查（交给 Service 的
 * {@code requireArticle}），没有"当前状态能不能提交"的判断（在状态机里），
 * 没有"是不是自己的文章"的判断（在 {@code checkOwnership} 里）。
 *
 * <p>这个约束不是洁癖：同一个业务规则如果在 Controller 里也写一遍，
 * 那么当别处（定时任务、别的 Service）调用同一个 Service 方法时
 * 就绕过了这份判断 —— 越权漏洞通常就是这么来的。
 *
 * <h3>为什么每个方法都要写 {@code requireCurrentUserId()}</h3>
 * <p>它在取不到登录用户时抛 {@code BusinessException(UNAUTHORIZED)}（HTTP 401），
 * 而不是返回一个 null 让 {@code operatorId} 一路往下走。
 * 前置的 {@code LoginInterceptor} 已经拦掉了"完全没登录"的请求，
 * 这里再取一次是<b>第二道防线</b>：拦截器放行的白名单将来若被改动，
 * 或者请求经过某些转发路径绕开了拦截器，这一层仍然能兜住。
 *
 * <p>⚠️ 为什么用 {@code BaseController.currentUserId()} 而不是直接读
 * {@code HttpServletRequest#getSession()}：项目里"当前用户是谁"只有一种取法
 * （{@code LoginUser} + {@code SessionConstant}），
 * 自己从 Request 里扒会造出第二套口径 —— 将来 Session 键名一改就漏掉一处。
 *
 * <h3>权限码为什么与 {@code docs/接口契约.md} §1.3 不完全一致</h3>
 * <p>契约表里"编辑草稿"写的是 {@code article:edit}、"下架"写的是 {@code article:offline}。
 * 但库表 {@code sys_permission} 里<b>根本没有这两个码</b>
 * （实际只有 {@code article:view/create/update/submit/audit/publish/withdraw/manage/delete}）。
 * 若照契约写，{@code PermissionAspect} 会认为"谁都缺这个权限"，
 * 结果是所有角色（包括 admin）都被 403 拦死。
 * <p>项目口径是"<b>库表 > 契约</b>"（契约是设计草稿，库表是运行时真相），
 * 因此这里用的是 {@code article:update}。契约 §1.3 的 4 处偏差已登记待修。
 *
 * <h3>⚠️ 关于 {@code @OperLog} 的 {@code saveParams}</h3>
 * <p>新建/修改草稿这两个接口显式写了 {@code saveParams = false}，
 * 原因见 {@code OperLog#saveParams()} 的注释：参数里含文章正文（LONGTEXT），
 * 原样写进 {@code sys_oper_log.request_param} 会把日志表撑爆。
 * 提交类接口参数只有一个 id（或一个短数组），保持默认的"记录参数"即可 ——
 * 排查"谁在什么时候提交了哪篇"时，参数恰恰是最有用的信息。
 */
@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController extends BaseController {

    @Autowired
    private ArticleService articleService;

    /**
     * 新建草稿。
     *
     * <p>{@code POST /api/v1/articles/draft}，幂等性：<b>否</b>。
     * 每调一次就多一篇文章，这是刻意的（作者连点两次会得到两篇草稿，
     * 而不是第二次静默覆盖第一篇 —— 后者会让用户以为"点了没反应"）。
     * 前端应在请求期间禁用按钮（{@code ArticleEdit.vue} 里用了 loading 状态）。
     *
     * @return 新文章 ID（前端拿到后 {@code router.replace} 到编辑路由，
     *         后续保存走的是"修改草稿"，不会重复创建）
     */
    @PostMapping("/draft")
    @RequirePermission("article:create")
    @OperLog(module = "文章管理", type = OperType.CREATE, desc = "新建草稿", saveParams = false)
    public Result<Long> saveDraft(@RequestBody @Valid CmsArticleSaveDTO dto) {
        Long articleId = articleService.saveDraft(dto, requireCurrentUserId());
        return Result.success("草稿保存成功", articleId);
    }

    /**
     * 编辑草稿。
     *
     * <p>{@code PUT /api/v1/articles/{id}/draft}，幂等性：是（PUT 语义 = 整体替换）。
     *
     * <p>⚠️ id 走路径参数，请求体里<b>没有</b> id 字段
     * （{@code CmsArticleSaveDTO} 的类注释解释了原因：
     * 两个地方都能传 id 就要定义"以哪个为准"，而规则本身是 bug 温床）。
     */
    @PutMapping("/{id}/draft")
    @RequirePermission("article:update")
    @OperLog(module = "文章管理", type = OperType.UPDATE, desc = "修改草稿", saveParams = false)
    public Result<Void> updateDraft(@PathVariable("id") Long id,
                                    @RequestBody @Valid CmsArticleSaveDTO dto) {
        articleService.updateDraft(id, dto, requireCurrentUserId());
        return Result.<Void>success("草稿已保存", null);
    }

    /**
     * 提交初审：{@code DRAFT / REJECTED → PENDING_EDITOR}。
     *
     * <p>{@code POST /api/v1/articles/{id}/submit}，幂等性：<b>否</b>。
     * 重复提交第二次会因"当前状态是待编辑初审"被状态机拒（11002）——
     * 这比"静默成功"更好：用户明确知道第一次已经生效了。
     *
     * <p>为什么"提交"用 {@code POST /{id}/submit} 而不是
     * {@code PUT /{id}/status}：提交审核是一个<b>领域动作</b>，
     * 不是"把 status 字段替换成某个值"。用动作式 URL 后，
     * 客户端无法直接指定目标状态 —— 目标状态由状态机决定，
     * 客户端连"我想跳到已发布"这种表达机会都没有。
     */
    @PostMapping("/{id}/submit")
    @RequirePermission("article:submit")
    @OperLog(module = "文章管理", type = OperType.UPDATE, desc = "提交初审")
    public Result<Void> submitForAudit(@PathVariable("id") Long id) {
        articleService.submitForAudit(id, requireCurrentUserId());
        return Result.<Void>success("已提交初审", null);
    }

    /**
     * 批量提交初审。
     *
     * <p>{@code POST /api/v1/articles/batch-submit}，幂等性：否。
     *
     * <p>⚠️ <b>返回值是 200 且 body 里有失败明细</b>，不是"有失败就返回 400/207"。
     * 理由见 {@link BatchSubmitResultVO} 的类注释：这是"聚合操作"的固有语义 ——
     * 请求本身合法且被处理了，失败是逐条业务规则的判定结果。
     * 前端必须读 {@code data.failList} 而不是只看 HTTP 状态码。
     *
     * <p>权限只要求 {@code article:submit}：批量提交与单篇提交是同一件事，
     * 不应该因为"批量"就要求更高权限（否则作者反而不能用这个便利接口）。
     * 越权由 Service 逐条判断（别人的文章会被记为失败，而不是整批拒绝）——
     * 这是刻意的：整批拒绝会让"一次勾选了 5 篇自己的 + 1 篇别人的"变成全失败，
     * 而用户的本意只是想提交自己的那 5 篇。
     */
    @PostMapping("/batch-submit")
    @RequirePermission("article:submit")
    @OperLog(module = "文章管理", type = OperType.UPDATE, desc = "批量提交初审")
    public Result<BatchSubmitResultVO> batchSubmit(@RequestBody @Valid BatchSubmitDTO dto) {
        return Result.success(articleService.batchSubmit(dto.getIds(), requireCurrentUserId()));
    }
}
