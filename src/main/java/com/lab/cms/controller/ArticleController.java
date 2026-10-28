package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import com.lab.cms.base.BaseController;
import com.lab.cms.common.enums.OperType;
import com.lab.cms.common.result.Result;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.BatchSubmitDTO;
import com.lab.cms.dto.CmsArticleOfflineDTO;
import com.lab.cms.dto.CmsArticlePublishDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.service.ArticleService;
import com.lab.cms.vo.ArticleAuditLogVO;
import com.lab.cms.vo.BatchSubmitResultVO;
import com.lab.cms.vo.CmsArticleDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 文章接口（S09 建立"草稿与投稿"四个，S10 追加"审核"一个，
 * S11 追加"详情/流水"两个，S13 追加"立即发布/排期发布"两个，
 * S14 追加"撤回/下架/归档"三个）。
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

    /**
     * 审核（编辑初审 / 主编终审共用）。
     *
     * <p>{@code POST /api/v1/articles/{id}/audit}，幂等性：<b>否</b>。
     * 重复审核第二次会因"状态已推进到下一环节"被拒（11002）——
     * 与"提交"同理，明确报错比静默成功好。
     *
     * <h3>为什么初审和终审是同一个 URL，而不是 /audit/first 与 /audit/final</h3>
     * <p>契约（§4.1 第 8 项及其下的说明）已经定了这一条：<b>环节由文章当前状态决定</b>。
     * 若拆成两个 URL，前端就得"先查一次状态、再选 URL"，既多一次请求，
     * 也会产生竞态（查到状态后、发审核请求前，别人把状态推进了）。
     * 所以请求体里<b>没有</b> auditStage 字段 —— 详见
     * {@link ArticleAuditDTO} 的类注释（那里也登记了手册要求传 stage 的差异）。
     *
     * <h3>权限只要求 {@code article:audit}</h3>
     * <p>初审与终审<b>不在这里区分权限</b>。两者的差别是"谁能把文章推到下一环节"，
     * 而"这一步该不该轮到我"由 Service 按当前状态判断。
     * 将来若确实需要"只有主编能终审"，那属于功能权限的细化，
     * 应当在 {@code sys_role_permission} 那一层表达，而不是在本方法里写 if。
     *
     * <h3>为什么本接口的 {@code @OperLog} 保留参数记录</h3>
     * <p>与草稿的"新建/修改"不同（那两个 {@code saveParams = false}，因为正文是 LONGTEXT）。
     * 审核的参数是 {@code {auditAction, auditOpinion}}，其中<b>审核意见本身就是要留档的东西</b>
     * —— 排查"这条稿子为什么被驳回"时，日志表里的意见比流水表更早被查到。
     * 单个意见最长 500 字，不构成撑爆日志表的风险，因此保持默认的记录参数。
     */
    @PostMapping("/{id}/audit")
    @RequirePermission("article:audit")
    @OperLog(module = "文章管理", type = OperType.AUDIT, desc = "审核文章")
    public Result<Void> audit(@PathVariable("id") Long id,
                              @RequestBody @Valid ArticleAuditDTO dto) {
        articleService.audit(id, dto, requireCurrentUserId());
        return Result.<Void>success("审核完成", null);
    }

    /**
     * 文章详情（含正文、作者名、分类名、审核流水、最近驳回意见）。
     *
     * <p>{@code GET /api/v1/articles/{id}}，权限 {@code article:view}。
     *
     * <p>⚠️ 本接口<b>不加 {@code @OperLog}</b>：查询类接口不记操作日志。
     * 理由是 {@code sys_oper_log} 是"谁改了数据"的追责表，
     * 把每次详情查询都记进去会让它迅速膨胀到无法使用（列表页一点就是几十条）。
     * 这也与项目里其他查询接口的处理一致。
     *
     * <p>⚠️ {@code id} 不存在时返回 <b>11001</b>（业务码，HTTP 保持 200），
     * 而不是 404。理由见 {@code ErrorCode} 的说明：本项目约定
     * 10xxx 段业务码走"HTTP 200 + body 里带码"，前端拦截器统一读 {@code code}。
     */
    @GetMapping("/{id}")
    @RequirePermission("article:view")
    public Result<CmsArticleDetailVO> getDetail(@PathVariable("id") Long id) {
        return Result.success(articleService.getDetail(id));
    }

    /**
     * 审核流水查询（给审核工作台单独刷新用）。
     *
     * <p>{@code GET /api/v1/articles/{id}/audit-logs}，权限 {@code article:view}。
     *
     * <p>为什么不复用详情接口：审核人点一次"通过"后只需要刷新流水，
     * 没必要把整篇正文（LONGTEXT）再拉一遍。见
     * {@code ArticleService#listAuditLogs} 的说明。
     *
     * <p>返回按 {@code auditTime} <b>正序</b>的列表；界面上的"最新在上"
     * 由 {@code AuditTimeline.vue} 自己排序实现。
     */
    @GetMapping("/{id}/audit-logs")
    @RequirePermission("article:view")
    public Result<List<ArticleAuditLogVO>> listAuditLogs(@PathVariable("id") Long id) {
        return Result.success(articleService.listAuditLogs(id));
    }

    /**
     * 立即发布：{@code APPROVED / WITHDRAWN → PUBLISHED}（S13）。
     *
     * <p>{@code POST /api/v1/articles/{id}/publish}，权限 {@code article:publish}
     * （库表里只授予 {@code ROLE_CHIEF} 与 {@code ROLE_ADMIN}）。
     *
     * <p>幂等性：<b>否</b>。第二次调用会因"当前状态已是已发布"被状态机拒（11002）。
     * 这与"提交/审核"的处理一致 —— 明确报错比静默成功好，
     * 用户至少知道第一次已经生效了。
     *
     * <h3>为什么 {@code @OperLog} 的 type 用 {@code PUBLISH} 而不是手册说的 {@code UPDATE}</h3>
     * <p>手册 S13 的提示词里写的是 {@code type = UPDATE}。本项目改用
     * {@code OperType.PUBLISH("发布")} —— 枚举里<b>本来就为发布留了这个取值</b>，
     * 它存在的意义就是把"发布"这类动作从"修改"里分出来。
     * <p>实际影响：{@code sys_oper_log.oper_type} 存的是枚举名
     * （{@code WebLogAspect} 写的是 {@code annotation.type().name()}），
     * 用 {@code UPDATE} 的话"发布一篇文章"与"改一次草稿"在库里是同一个值，
     * 按 {@code oper_type} 筛选日志时区分不开；
     * 用 {@code PUBLISH} 则可以直接 {@code WHERE oper_type = 'PUBLISH'} 查发布记录。
     * <p>结论：项目口径是"<b>代码里有更贴切的枚举取值就用它</b>"，手册的 {@code UPDATE} 是就近选的默认值。
     *
     * <p>⚠️ 参数保持默认记录（不写 {@code saveParams = false}）：
     * 本接口<b>没有请求体</b>，被记录下来的只有路径变量 id，
     * 而"谁在什么时候发布了哪一篇"恰恰是最需要留档的信息。
     *
     * <h3>发布<b>不</b>校验归属（与修改草稿不同）</h3>
     * <p>发布在业务上就是主编的职能，主编要能发布作者提交的稿。
     * 详见 {@code ArticleService#publishNow} 的注释与
     * {@code docs/接口分类与鉴权表.md} 的接口分类表。
     */
    @PostMapping("/{id}/publish")
    @RequirePermission("article:publish")
    @OperLog(module = "文章管理", type = OperType.PUBLISH, desc = "发布文章")
    public Result<Void> publishNow(@PathVariable("id") Long id) {
        articleService.publishNow(id, requireCurrentUserId());
        return Result.<Void>success("文章已发布", null);
    }

    /**
     * 排期发布：{@code APPROVED → SCHEDULED}（S13）。
     *
     * <p>{@code POST /api/v1/articles/{id}/schedule}，权限 {@code article:publish}
     * （与立即发布同一个权限码：它们都是"主编决定什么时候让文章上线"，
     * 差别只在时间，没有理由要求不同的角色）。
     *
     * <p>请求体：{@code {"scheduleTime": "2026-10-27 18:30:00"}}。
     * ⚠️ 时间格式是 <b>{@code yyyy-MM-dd HH:mm:ss}（空格分隔）</b>，<b>不是</b> ISO-8601 的
     * {@code 2026-10-27T18:30:00}。这是 {@code JacksonConfig} 给 {@code LocalDateTime}
     * 全局注册的唯一格式（自定义的 {@code LocalDateTimeDeserializer}），传 {@code T} 分隔的
     * 字符串会直接返回 400「请求体格式不正确」—— 真机联调时第一版脚本就是这么写错的
     * （见 {@code docs/S13-发布与排期实测记录.md}）。
     * <p>⚠️ 手册 S13 写的是"排期时间传 ISO 8601"，与本项目实际配置不符，以代码为准。
     * <p>不要在某个接口单独做格式兼容（比如再注册一个能接受 {@code T} 的解析器），
     * 否则项目里会出现两套时间口径 —— 前端 {@code ArticleEdit.vue} 的日期选择器
     * 已经统一提交这种格式。
     *
     * <p>幂等性：<b>否</b>。第二次调用会因"当前状态是定时发布中"被状态机拒（11002）
     * —— 想改排期时间，当前状态机下没有"直接改"的路径（{@code SCHEDULED} 只有定时触发一条出边），
     * 这是状态机的既定行为，已登记在 {@code docs/状态机图.md} 的待议项里。
     *
     * <p>⚠️ 排期时间必须<b>晚于当前时间至少 1 分钟</b>，否则返回
     * {@code ARTICLE_SCHEDULE_TIME_INVALID(11007)}。
     * 这条校验在 Service 里做（不在 DTO 上用 {@code @Future}），
     * 理由见 {@code ArticleService#publishSchedule} 的注释。
     */
    @PostMapping("/{id}/schedule")
    @RequirePermission("article:publish")
    @OperLog(module = "文章管理", type = OperType.PUBLISH, desc = "排期发布")
    public Result<Void> publishSchedule(@PathVariable("id") Long id,
                                        @RequestBody @Valid CmsArticlePublishDTO dto) {
        articleService.publishSchedule(id, dto, requireCurrentUserId());
        return Result.<Void>success("排期设置成功", null);
    }

    /**
     * 撤回：{@code PUBLISHED → WITHDRAWN}（S14）。<b>可以再发布</b>。
     *
     * <p>{@code POST /api/v1/articles/{id}/withdraw}，权限 {@code article:withdraw}。
     * <p>⚠️ 权限码跟随<b>库表</b>：契约 §1.3 给"撤回"写的也是 {@code article:withdraw}
     * （这个码库里确实有，且 {@code ROLE_AUTHOR(7)} 就有）。
     * 与之对照，契约给"下架/归档"写的 {@code article:offline} / {@code article:archive}
     * <b>库里一个都没有</b>，所以那两个接口用 {@code article:manage}（见下）。
     *
     * <p>⚠️ 本接口<b>要校验归属</b>（只能撤回自己的文章），与下架/归档相反 ——
     * 完整对比表见 {@code ArticleService#withdraw} 的 Javadoc。
     * 归属不符时返回 {@code ARTICLE_NOT_OWNER(11003)}。
     *
     * <p>幂等性：<b>否</b>。第二次调用会因"当前状态是已撤回"被状态机拒（11002）——
     * 符合本接口的既定风格（明确报错优于静默成功，用户至少知道第一次已生效）。
     */
    @PostMapping("/{id}/withdraw")
    @RequirePermission("article:withdraw")
    @OperLog(module = "文章管理", type = OperType.WITHDRAW, desc = "撤回文章")
    public Result<Void> withdraw(@PathVariable("id") Long id) {
        articleService.withdraw(id, requireCurrentUserId());
        return Result.<Void>success("文章已撤回", null);
    }

    /**
     * 下架：{@code PUBLISHED → OFFLINE}（S14）。<b>不可逆</b>，只能再归档。
     *
     * <p>{@code POST /api/v1/articles/{id}/offline}，权限 {@code article:manage}
     * （库表口径：主编 / 管理员）。
     * <p>⚠️ 契约 §1.3 给"下架"写的是 {@code article:offline}，
     * 但 {@code sys_permission} 里<b>没有这个码</b>。按项目口径"库表 &gt; 契约"，
     * 这里用 {@code article:manage}。若照契约写，{@code PermissionAspect}
     * 会认为所有角色（含 admin）都缺该权限，结果是所有人都被 403 拦死。
     * 该差异已登记在 {@code docs/接口分类与鉴权表.md}。
     *
     * <p>请求体：{@code {"reason": "内容涉及侵权，应版权方要求下架"}}。
     * <p>⚠️ <b>reason 必填</b>（{@code @NotBlank} + 长度 ≤200），不填返回 400。
     * 原因不落审核流水表，落 {@code @OperLog} → {@code sys_oper_log.request_param}
     * （取舍理由见 {@code ArticleService#offline} 的 Javadoc）。
     *
     * <p>⚠️ 本接口<b>不校验归属</b>：下架是运营职能，主编要能下架任何一篇已发布文章
     * （包括别人写的）。加归属校验会让审核者只能管自己的稿，职能失效。
     *
     * <p>⚠️ 参数保持默认记录（不写 {@code saveParams = false}）：
     * 原因必须被记进日志，这正是它唯一的持久化通道。
     */
    @PostMapping("/{id}/offline")
    @RequirePermission("article:manage")
    @OperLog(module = "文章管理", type = OperType.OFFLINE, desc = "下架文章")
    public Result<Void> offline(@PathVariable("id") Long id,
                                @RequestBody @Valid CmsArticleOfflineDTO dto) {
        articleService.offline(id, requireCurrentUserId(), dto.getReason());
        return Result.<Void>success("文章已下架", null);
    }

    /**
     * 归档：{@code WITHDRAWN / OFFLINE → ARCHIVED}（S14）。<b>终态，之后不可再操作</b>。
     *
     * <p>{@code POST /api/v1/articles/{id}/archive}，权限 {@code article:manage}
     * （与下架同码，理由同上）。
     *
     * <p>⚠️ 对<b>已归档</b>的文章调用本接口（或调用撤回/下架），返回
     * {@code ARTICLE_ARCHIVED_READONLY(11011)}。手册 S14 写的是 11010，
     * 但本项目 11010 是乐观锁冲突（{@code ARTICLE_UPDATE_CONFLICT}）——
     * 属手册错误码错位区，以 {@code ErrorCode} 为准。
     *
     * <p>⚠️ 本接口<b>不校验归属</b>（同下架）。
     * <p>⚠️ 归档顺带"让检索索引失效"这件事由成员D 负责，本步只在 Service 里留了
     * 调用点（{@code ArticleServiceImpl#markSearchIndexInvalidOnArchive}）。
     */
    @PostMapping("/{id}/archive")
    @RequirePermission("article:manage")
    @OperLog(module = "文章管理", type = OperType.ARCHIVE, desc = "归档文章")
    public Result<Void> archive(@PathVariable("id") Long id) {
        articleService.archive(id, requireCurrentUserId());
        return Result.<Void>success("文章已归档", null);
    }
}
