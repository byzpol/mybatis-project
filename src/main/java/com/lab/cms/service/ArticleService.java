package com.lab.cms.service;

import com.lab.cms.base.BaseService;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticlePublishDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.vo.ArticleAuditLogVO;
import com.lab.cms.vo.BatchSubmitResultVO;
import com.lab.cms.vo.CmsArticleDetailVO;
import com.lab.cms.vo.CmsArticleVersionVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章业务服务接口。
 *
 * <p>继承 {@link BaseService} 获得 MP 的通用 CRUD 能力；
 * 本接口只声明<b>文章域特有的、无法用通用 CRUD 表达的</b>业务动作。
 *
 * <h3>⚠️ S07 只定签名，不写实现</h3>
 * <p>本步（S07）的产出目标是"骨架"：把方法名、参数、返回值定下来，
 * 让前后端与同组其他人能据此对齐接口。
 * 具体实现（状态流转、写审核流水、版本快照、并发控制）安排在 S09 起逐步补齐，
 * 届时 {@code ArticleServiceImpl} 中对应方法体里的 {@code TODO} 会被替换。
 *
 * <h3>⚠️ 为什么这些方法要写在 Service 而不是 Controller</h3>
 * <p>以 {@link #submitForAudit(Long, Long)} 为例，它实际要做的事：
 * <ol>
 *   <li>查文章（不存在 → 抛 {@code ARTICLE_NOT_FOUND}）</li>
 *   <li>校验"当前用户是不是作者"（不是 → 抛 {@code ARTICLE_NOT_OWNER}，水平越权防护）</li>
 *   <li>校验"当前状态能不能提交"（走 {@code ArticleStateMachine}，非法 → 抛 {@code ARTICLE_STATUS_ILLEGAL}）</li>
 *   <li>校验"提交前内容是否完整"</li>
 *   <li>写状态 + 写版本快照 + 记录操作人</li>
 * </ol>
 * 这五步里有<b>事务边界</b>、<b>权限判断</b>、<b>状态机调用</b> ——
 * 任何一步放在 Controller 都会导致"换个入口就能绕过校验"。
 * Controller 只负责"收参 → 校验格式 → 调这一个方法 → 封装返回"。
 *
 * <h3>参数里为什么既有 {@code id} 又有 {@code operatorId}</h3>
 * <p>{@code operatorId}（操作人）显式作为参数传入，<b>不从 Session 里就地取</b>。
 * 三个理由：
 * <ul>
 *   <li><b>可测试</b>：单元测试不用起 Web 容器、不用造 Session，直接传 id</li>
 *   <li><b>可复用</b>：将来定时任务、批处理脚本调同一方法时，
 *       没有 HTTP 请求上下文，取不到 Session —— 显式传参则天然支持</li>
 *   <li><b>可审计</b>：方法签名就把"谁干的"这件事摆明了，
 *       读代码时不用去猜"这个 user 是从哪来的"</li>
 * </ul>
 * 代价是 Controller 要多写一行 {@code requireCurrentUserId()}，
 * 这个代价换来的是可测试性与可复用性，划算。
 */
public interface ArticleService extends BaseService<CmsArticle> {

    /**
     * 新建草稿。
     *
     * <p>只做"存下来"，<b>不进入审核流程</b> —— 新建后的状态固定为
     * {@code DRAFT(0)}，不校验能否提交。
     *
     * <p>⚠️ 状态由 Service 强行置为 {@code DRAFT}，<b>不接受 DTO 里传状态</b>：
     * 否则恶意请求可以构造 {@code status=5} 直接"新建一篇已发布的文章"，
     * 绕过整条审核链。这是典型的"批量赋值（Mass Assignment）"漏洞。
     *
     * @param dto        保存内容（标题必填、分类必填，正文可空）
     * @param operatorId 操作人 ID（作者），由 Controller 从登录态取出后传入
     * @return 新文章 ID
     */
    Long saveDraft(CmsArticleSaveDTO dto, Long operatorId);

    /**
     * 修改草稿。
     *
     * <p>只允许 {@code DRAFT} 与 {@code REJECTED} 两种状态（见
     * {@code ArticleStatus#isEditable()}）；审核中/已发布等状态一律拒绝。
     *
     * <p>⚠️ 必须校验"文章属于 {@code operatorId}"（水平越权防护，对应
     * {@code ARTICLE_NOT_OWNER}）。仅凭"能访问这个 URL"不足以证明有权修改。
     *
     * @param id         文章 ID
     * @param dto        保存内容
     * @param operatorId 操作人 ID
     */
    void updateDraft(Long id, CmsArticleSaveDTO dto, Long operatorId);

    /**
     * 提交审核：{@code DRAFT / REJECTED → PENDING_EDITOR}。
     *
     * <p>提交前应校验内容完整性（标题、正文、分类齐全）——
     * 这些校验放在这里而不是 DTO 上，因为"保存草稿"允许内容不全，
     * 只有"提交"才要求齐全（见 {@code CmsArticleSaveDTO} 类注释）。
     *
     * <p>本方法内部必须通过
     * {@code ArticleStateMachine.transfer(status, ArticleAction.SUBMIT)}
     * 得到目标状态，<b>不允许直接 {@code setStatus(1)}</b>。
     *
     * @param id         文章 ID
     * @param operatorId 操作人 ID（必须是文章作者）
     */
    void submitForAudit(Long id, Long operatorId);

    /**
     * 批量提交审核。
     *
     * <h3>为什么要有这个方法，而不是让前端循环调单个提交</h3>
     * <p>作者常一次写完好几篇草稿，逐个点太累。但批量接口不是"循环调用单条"的简单包装，
     * 它有三个必须单独考虑的点：
     * <ol>
     *   <li><b>部分成功怎么反馈</b>：10 篇里有 2 篇状态不合法，
     *       是整批回滚（全失败）还是成功 8 篇、返回 2 条失败原因？
     *       本项目选择<b>后者</b>（宽松），因为"回滚全部"会让用户反复重试已成功的部分。
     *       于是返回值需要能表达"逐条结果"。</li>
     *   <li><b>事务边界</b>：整批一个大事务 vs 每篇一个小事务。
     *       若用大事务，"第 8 篇失败"会回滚前 7 篇已提交的 —— 与第 1 点矛盾。
     *       所以这里应"逐篇独立事务"，实现时通过 {@code REQUIRES_NEW} 或
     *       在 Service 内循环调用另一个带 {@code @Transactional} 的方法实现。</li>
     *   <li><b>数量上限</b>：不限制的话，有人传 10 万个 id 就是一次 DoS。
     *       实现时应限制批大小（如 ≤ 100），超出抛参数错误。</li>
     * </ol>
     *
     * <p>⚠️ S07 阶段返回值定为 {@code void} 是临时的占位。
     * 按上面第 1 点的分析，S09 实现时应改为返回"逐条结果"集合
     * （如 {@code List<BatchResultItem>}，含 id / 是否成功 / 失败原因）。
     * <b>这里保持 {@code void} 是为了先让接口编译通过、让 Controller 能对接，
     * 不代表最终形态。</b>改动会在 S09 明确记录，不静默变更契约。
     *
     * <h3>✅ S09 落实情况（本条即"明确记录"本身）</h3>
     * <p>返回类型已由 {@code void} 改为 {@link BatchSubmitResultVO}，
     * 字段为 {@code total / successCount / failCount / failList}，
     * 其中 {@code failList} 的元素是 {@code {id, reason}}。
     * 这是 S07→S09 之间**唯一一处接口签名变更**，其余方法签名一律未动。
     *
     * <p>为什么是 {@code BatchSubmitResultVO} 而不是手册里写的
     * {@code {successCount, failList}} 两个字段：多出的 {@code total} 用来表达
     * "去重后实际处理了几条"，多出的 {@code failCount} 是为了避免前端自己写减法。
     * 三个计数之间的关系是 {@code total = successCount + failCount}（有断言测试保证）。
     *
     * <p>⚠️ 顺带修正一处契约漂移：{@code docs/接口契约.md} §4.1 的示例片段里
     * 这个接口写成了 {@code Result<Integer> batchSubmit(dto.getIds())}
     * —— 既没有 {@code operatorId} 参数（会导致越权校验拿不到操作人），
     * 返回值也只是个 {@code Integer}（表达不了逐条失败原因）。
     * 那段代码的用途是"演示 {@code @RequirePermission} 怎么写"，
     * 不是接口定义，故以本接口为准；已在实测记录里登记该差异。
     *
     * @param ids        文章 ID 列表
     * @param operatorId 操作人 ID（每一篇都必须是该作者的，越权的那些记为失败）
     * @return 逐条结果（去重后的总数 / 成功数 / 失败数 / 失败明细）
     */
    BatchSubmitResultVO batchSubmit(List<Long> ids, Long operatorId);

    /**
     * 审核：编辑初审与主编终审<b>共用这一个方法</b>（S10 初审 / S11 终审同一段实现）。
     *
     * <h3>为什么要合并成一个方法，而不是 {@code auditFirst} + {@code auditFinal}</h3>
     * <p>手册 S11 也要求"两段逻辑合并成一段统一实现，如果无法合并要说明为什么"。
     * 这里可以合并，而且必须合并 —— 因为两个环节<b>除了"从哪个状态出发、到哪个状态去"之外，
     * 其余每一步完全相同</b>：查文章、推环节、防自审、驳回校意见、状态机推进、写流水。
     * 唯一会变的那部分（状态流转）已经全部封装在
     * {@link ArticleStateMachine#transfer(Integer, com.lab.cms.common.enums.ArticleAction)}
     * 的静态表里了，本方法不需要知道"初审能到哪、终审能到哪"。
     * <p>反过来，如果拆成两个方法，上面那五步会被复制两份 ——
     * 于是"某天给审核加一条规则（比如加一个敏感词校验）"就会漏改一处。
     * 这是项目最忌讳的"同一规则散在多处"。
     *
     * <h3>环节从哪里来：由文章<b>当前状态</b>反推，不由入参传</h3>
     * <p>{@code PENDING_EDITOR} → 编辑初审；{@code PENDING_CHIEF} → 主编终审。
     * 原因见 {@link com.lab.cms.dto.ArticleAuditDTO} 的类注释（冗余入参必然打架，
     * 且前端已按"不传 stage"实现）。
     * <p>状态不在这两者时：终态（已归档）→ {@code ARTICLE_ARCHIVED_READONLY(11011)}；
     * 其余（草稿 / 已驳回 / 已发布 / 已撤回 / 已下架 / 定时中）→
     * {@code ARTICLE_STATUS_ILLEGAL(11002)}。
     *
     * <h3>权限与越权</h3>
     * <ul>
     *   <li><b>功能权限</b>（{@code article:audit}）：由 Controller 上的
     *       {@code @RequirePermission} + {@code PermissionAspect} 完成，
     *       本方法<b>不重复校验</b> —— 同一份权限规则写两遍，改的时候必然漏一处。</li>
     *   <li><b>业务级越权</b>（作者不能审自己的稿）：这一条<b>必须</b>在本方法里做。
     *       它不是"有没有 article:audit 权限"的问题 —— 编辑和主编<b>都有</b>这个权限，
     *       拦住他们的是"这篇稿子是不是你自己写的"。
     *       命中抛 {@code ARTICLE_CANNOT_AUDIT_SELF(11004)}。</li>
     * </ul>
     *
     * <h3>⚠️ 为什么本方法<b>不</b>做"该阶段已审核过"的存在性检查</h3>
     * <p>{@code CmsArticleAuditLog} 的类注释把"防重复审核"列为这张表的用途之一，
     * 实现方式写的是"查 (article_id, audit_stage) 是否已有记录，有则拒绝"。
     * <b>按字面实现会打断主链路</b>，所以这里没有照做：
     * <pre>
     *   提交 → 初审驳回（写入 stage=1 的流水）→ 作者改稿 → 再次提交
     *   → 初审（此时 (article_id, 1) 已经有记录了 → 被误判为"重复审核"）
     * </pre>
     * 而"初审驳回 → 改稿 → 再初审"正是 S11 主链路自测清单里必须走通的一步。
     * <p>真正拦住重复审核的是<b>状态机 + 乐观锁</b>，而且这里比"查流水"更彻底：
     * <b>"在同一个环节上审两次"在结构上就不存在</b> ——
     * 审核成功后状态必然改变（通过则进下一环节、驳回则回 {@code REJECTED}），
     * 于是第二次调用会被推导成<b>另一个环节</b>（初审通过后再调 = 主编终审），
     * 而不是"同一环节重复"。
     * 想让状态回到 {@code PENDING_EDITOR}，只能走"驳回 → 作者改稿 → 重新提交"，
     * 那是新的一轮审核。
     * 并发的两个重复请求则由乐观锁兜住：第二个 {@code version} 不匹配 → 11010。
     * <p>（第一版注释里曾写"初审通过后再调初审会因状态不支持而 11002"，
     * 那是错的 —— 实测跑出来它会当成终审并成功。以本条为准。）
     *
     * <h3>⚠️ 已知缺口（挂起待定，需组内定）</h3>
     * <p>因为"环节由状态推导"、且库里文章域<b>只有一个</b> {@code article:audit} 权限码
     * （没有 first / final 之分），<b>任何持有 {@code article:audit} 的账号都能把一篇文章
     * 从初审一路审到 {@code APPROVED}</b> —— 编辑（{@code ROLE_EDITOR}）
     * 与主编（{@code ROLE_CHIEF}）都有这个权限，所以编辑也能做终审。
     * 也就是说"初审归编辑、终审归主编"这条业务分工<b>在当前权限模型里表达不出来</b>。
     * <p>本步<b>刻意不擅自修</b>：这既不是 S10 手册要求（手册只要求"作者不能审自己的稿"），
     * 也会改动权限模型（属成员C 的主责范围）。两条候选方案都已登记在
     * {@code docs/S10-审核与流水实测记录.md} 的"挂起待定"一节：
     * ① 在 {@code sys_permission} 增加 {@code article:audit:final}；
     * ② 增加"同一篇文章初审人与终审人不能同一人"的规则（四眼原则）。
     * <p>证据在测试 {@code ArticleAuditServiceTest#testSecondAuditCallBecomesFinalAuditNotDuplicate()}。
     *
     * <h3>事务边界（与手册的一个有意差异，见实现处注释）</h3>
     * <p>本方法加 {@code @Transactional(rollbackFor = Exception.class)}：
     * "改状态"与"写流水"必须同成功或同失败。
     * 手册给的步骤顺序是"先写流水、后改状态"；实现里<b>把顺序反了过来</b>
     * （先改状态、后写流水），理由写在实现里 —— 简言之，
     * 这个顺序才能把手册自己担心的"状态改了但流水丢了"这条不变式<b>测出来</b>。
     *
     * @param id         文章 ID
     * @param dto        审核入参（{@code auditAction} 1 通过 / 2 驳回；驳回时 {@code auditOpinion} 必填）
     * @param operatorId 审核人 ID，由 Controller 从登录态取出后传入
     */
    void audit(Long id, ArticleAuditDTO dto, Long operatorId);

    /**
     * 查询文章详情：基本信息 + 正文 + 最近驳回意见 + 审核流水。
     *
     * <h3>⚠️ 这个接口的硬指标是"SQL 条数"</h3>
     * <p>手册 S11 的核查项要求"详情接口只发 3~4 条 SQL，不是 N+1"。
     * 详情要跨三张表补数据（作者名、分类名、流水里的审核人名），
     * 最容易的写法是"循环里逐条查"，那会变成 1 + N + N + N 条。
     * 本实现的定式是<b>"收集 id → 一次 {@code IN} 查回 → 内存拼装"</b>：
     * <ol>
     *   <li>查文章本身（1 条）</li>
     *   <li>查作者昵称（1 条，{@code sys_user} 批量）</li>
     *   <li>查分类名（1 条，{@code cms_category} 批量）</li>
     *   <li>查审核流水（1 条）</li>
     *   <li>查流水里涉及的审核人昵称（1 条，去重后批量）</li>
     * </ol>
     * 即 <b>4~5 条</b>，且条数与文章数量无关。
     * 实测条数由真机联调时读 {@code logs/cms.log} 的 {@code Preparing:} 行确认
     * （这是本项目验证 SQL 行为的唯一可靠判据）。
     *
     * <p>⚠️ 查询<b>不做</b>越权过滤：{@code article:view} 是"能看文章"的功能权限，
     * 详情页本身也是公开信息的载体。数据级权限（比如"草稿只有作者能看"）
     * 属于更细的规则，手册在 S12 才会谈，本步不擅自加。
     *
     * @param id 文章 ID
     * @return 详情 VO；文章不存在时抛 {@code ARTICLE_NOT_FOUND(11001)}
     */
    CmsArticleDetailVO getDetail(Long id);

    /**
     * 只查审核流水（给审核工作台单独刷新用）。
     *
     * <h3>为什么详情已经带流水了，还要单独开一个接口</h3>
     * <p>因为两者的"刷新粒度"不同：
     * 审核人点一次"通过"之后只需要刷新流水与状态，<b>没必要把整篇正文再拉一遍</b>
     * （正文是 LONGTEXT，可能几百 KB）。
     * {@code ArticleDetail.vue} 实测就是调这个接口来刷流水的，
     * 该页面自己注释了原因："流水单独取一次，不必为了看几条流水把整篇正文再拉一遍"。
     *
     * <p>⚠️ 仍然要先查一次文章是否存在 —— 否则对不存在的 id 会返回"空流水列表"，
     * 前端会显示一个"文章不存在但审核历史为空"的怪页面，而正确做法是明确的 11001。
     * 这一次额外查询是刻意的，不是"多余的一条 SQL"。
     *
     * @param id 文章 ID
     * @return 按 {@code auditTime} 正序的流水；没有记录时返空列表（不是 null）
     */
    List<ArticleAuditLogVO> listAuditLogs(Long id);

    // ==================== 七、发布与排期发布（S13） ====================

    /**
     * 立即发布：{@code APPROVED / WITHDRAWN → PUBLISHED}。
     *
     * <h3>为什么<b>不</b>校验归属（与 {@link #updateDraft} 正好相反）</h3>
     * <p>发布这件事在业务上就<b>不该</b>由作者独占：
     * 作者写完、编辑初审、主编终审通过之后，是主编决定"什么时候放出去"。
     * 库表口径也印证了这一点 —— {@code article:publish} 只授予
     * {@code ROLE_CHIEF} 与 {@code ROLE_ADMIN}，而 {@code article:update}
     * 是连 {@code ROLE_AUTHOR} 都有的。
     * <p>也就是说"能不能发布"是<b>功能权限</b>（在 {@code @RequirePermission} 与
     * {@code sys_role_permission} 那一层表达），"这篇文章归谁"是<b>数据归属</b>。
     * 若在这里加 {@code checkOwnership}，主编就永远无法发布作者提交的稿，
     * 整个审核发布链路会断在最后一步。<b>S12 的接口分类表已把这条写死</b>
     * （见 {@code docs/接口分类与鉴权表.md}：发布=仅主编+管理员，不校验归属）。
     * <p>⚠️ 与它形成对照的是"撤回"：撤回是作者把自己交出去的东西收回来，
     * 所以 S14 的 {@code withdraw} <b>要</b>校验归属。两者不能互相套用。
     *
     * <h3>⚠️ 立即发布必须清空 {@code scheduleTime}</h3>
     * <p>一篇稿子完全可以先排期（{@code SCHEDULED}），随后主编改主意要立刻发。
     * 但 {@code SCHEDULED} 只有"定时触发"一条出边，走不到 {@code PUBLISHED}。
     * 实际路径是：排期 → <b>还没到点之前先撤回</b>？也走不通
     * （{@code SCHEDULED} 出边只有定时触发，撤回要从 {@code PUBLISHED} 出发）。
     * 也就是说"排期后想立刻发"在当前状态机下<b>没有合法路径</b>。
     * 这条不是本方法的责任，但正因如此，{@code scheduleTime} 一旦残留就会变成
     * <b>脏数据</b>：文章已 {@code PUBLISHED}，而 {@code schedule_time} 还写着未来的时间，
     * 定时任务未来扫到它时状态条件不匹配（只扫 {@code SCHEDULED}）倒不会误发，
     * 但列表页/报表会显示"已发布 + 排期时间=明天"这种自相矛盾的数据。
     * <p>所以本方法<b>无条件把 {@code schedule_time} 写回 NULL</b>。
     * ⚠️ 这件事只能靠"显式列列的 {@code LambdaUpdateWrapper.set}"做到 ——
     * {@code updateById} 的 NOT_NULL 策略会把 null 整段跳过，写不进去。
     *
     * <h3>不写审核流水</h3>
     * <p>因为发布<b>不是审核动作</b>。库表 {@code cms_article_audit_log} 的语义是
     * "谁在哪一环节对内容给出了什么结论"，列有 {@code audit_stage}（1 初审 / 2 终审）
     * 与 {@code result}（1 通过 / 2 驳回），
     * "发布"填不进这两列的任何合法取值（{@code audit_stage} 是 NOT NULL）。
     * <p>枚举上也留了判据：{@link com.lab.cms.common.enums.ArticleAction#isAuditAction()}
     * 只认 {@code AUDIT_PASS / AUDIT_REJECT}，发布动作返回 false。
     * 操作的留痕走另一条线：{@code @OperLog} → {@code sys_oper_log}
     * （记"谁在什么时候点了发布"），而不是审核流水表。
     *
     * <h3>并发安全：乐观锁</h3>
     * <p>两个主编同时点"立即发布"，第二个会因 {@code version} 不匹配
     * 更新 0 行 → 抛 {@code ARTICLE_UPDATE_CONFLICT(11010)}。
     * 结果必然是"只有一个人发布成功"，且 {@code publish_time} 只有一次写入、
     * 不会留下两条发布记录（本方法压根不写流水表，更不存在第二条记录）。
     *
     * @param id         文章 ID
     * @param operatorId 发布人（主编/管理员），由 Controller 从登录态取出
     */
    void publishNow(Long id, Long operatorId);

    /**
     * 排期发布：{@code APPROVED → SCHEDULED}。
     *
     * <h3>为什么入参是 DTO 而不是裸的 {@code LocalDateTime}</h3>
     * <p>手册给的签名是 {@code publishSchedule(Long id, LocalDateTime scheduleTime, Long operatorId)}。
     * 实现改成 {@link CmsArticlePublishDTO}，三个理由：
     * <ol>
     *   <li><b>时间格式统一由 Jackson 处理</b>。{@code LocalDateTime} 直接做
     *       {@code @RequestBody} 参数时，解析失败会抛 {@code HttpMessageNotReadableException}，
     *       走全局异常处理器变成 400；包一层 DTO 后能在字段上写校验注解与中文提示。</li>
     *   <li><b>与已有接口同构</b>。{@code audit} 用的是 {@code ArticleAuditDTO}，
     *       本方法用 DTO 后"写接口的入参一律是 DTO"这条规律不出现例外。</li>
     *   <li><b>将来加字段不用改签名</b>。常见的下一个需求是"到点前是否发提醒"
     *       或"排期备注"，加在 DTO 上是<b>加字段</b>，加在方法签名上是<b>改签名</b> ——
     *       而主干契约要求"只许加方法、不许改签名"。</li>
     * </ol>
     *
     * <h3>⚠️ "必须晚于当前时间"这条校验为什么<b>不</b>放在 DTO 上</h3>
     * <p>直觉写法是给 {@code scheduleTime} 挂 {@code @Future}。
     * 本项目不这么做，有两条实测层面的理由：
     * <ul>
     *   <li><b>错误码不对</b>。{@code @Future} 校验失败会被 {@code @Valid} 汇总成
     *       {@code MethodArgumentNotValidException} → 全局处理器返回 <b>400 + 通用校验码</b>，
     *       而这条业务规则在 {@code ErrorCode} 里有专属码
     *       {@code ARTICLE_SCHEDULE_TIME_INVALID(11007)}。
     *       前端要靠码来定位问题，用法校注解会把 11007 弄丢。</li>
     *   <li><b>校验时点不对</b>。{@code @Future} 在参数绑定阶段就执行了，
     *       比 Service 打开事务早得多。本项目在 Service 内用<b>同一个 {@code now}</b>
     *       做判断与写入，时间基准只有一处；分散到两处会出现
     *       "校验时还没到、写库时已经到了"的边界抖动。</li>
     * </ul>
     * <p>所以 DTO 上只留 {@code @NotNull}（挡"根本没传"），
     * "要晚于当前时间至少 1 分钟"由本方法判断并抛 11007。
     *
     * <p>⚠️ 排期时 {@code publish_time} 与 {@code publisher_id} 会被显式写回 NULL：
     * 文章此刻<b>还没发布</b>，这两个列保留上一轮的旧值就是脏数据。
     *
     * @param id         文章 ID
     * @param dto        排期入参（{@code scheduleTime} 必填，且须晚于当前时间至少 1 分钟）
     * @param operatorId 排期人（主编/管理员）
     */
    void publishSchedule(Long id, CmsArticlePublishDTO dto, Long operatorId);

    /**
     * 查出"已到点该发布"的文章 ID 列表（供定时任务扫描）。
     *
     * <h3>为什么单独开这个方法，而不是让定时任务直接拿 Mapper 去查</h3>
     * <p>定时任务属于"入口"，按项目分层约定，入口只能调 Service，不能直接碰 Mapper
     * —— 否则"到底用哪个条件筛选排期文章"会出现第二处定义，
     * 将来改筛选口径（比如加"排除已归档"）就会漏改一处。
     *
     * <h3>⚠️ 这个查询必须走索引，不能全表扫</h3>
     * <p>条件固定是 {@code status = ? AND schedule_time <= ?}，
     * 而 {@code cms_article} 上恰好有复合索引
     * {@code idx_status_schedule_time(status, schedule_time)}（S04 建表时就留好了），
     * 两列的顺序与该索引的列顺序<b>一致</b>，所以能用上。
     * <p>⚠️ 判断"索引是否真的用上"不能靠看代码，要看执行计划
     * （{@code EXPLAIN} 或真机日志里 {@code Preparing:} 出来的 SQL）。
     * <p>{@code ORDER BY schedule_time, id} 只是为了"先到点的先发"，
     * 排序方向与索引一致，不会引入额外排序。
     * <p>末尾的 {@code LIMIT} 是<b>常量</b>（不是拼接用户输入），
     * 作用是防止某次异常情况下"积压了几万条排期文章"把一次扫描拖死；
     * 剩下的下一分钟自然会扫到。
     *
     * @param deadline 判定时刻（一般传"当前时间"；显式传参是为了可测试）
     * @return 到点的文章 ID 列表（按排期时间正序）；无到点文章返空列表（不是 null）
     */
    List<Long> findDueScheduledArticleIds(LocalDateTime deadline);

    /**
     * 由定时任务发起的单篇发布：{@code SCHEDULED → PUBLISHED}。
     *
     * <h3>⚠️ 为什么这个方法是 public 的、而且必须由外部 Bean 调用</h3>
     * <p>因为它需要 {@code @Transactional} 且<b>每篇独立</b>：
     * 一篇失败不能影响同批次的其他篇。而 Spring 的 {@code @Transactional}
     * 靠 AOP 代理实现，<b>同类内部自调用不经过代理</b>（静默失效，项目里已记过这个坑）。
     * 定时任务是一个独立的 {@code @Component}，它注入的是
     * {@code ArticleService} 接口的代理对象，调用本方法时会正常进入代理 → 事务生效。
     * <p>所以事务边界就落在本方法上：<b>一次调用 = 一个事务 = 一篇文章</b>。
     * 同一批次里第 2 篇抛异常并回滚，第 1 篇已经提交的不会跟着回滚。
     *
     * <h3>为什么返回 boolean 而不是抛异常</h3>
     * <p>扫描是<b>幂等</b>的、每分钟一次。绝大多数"没发成"都不是故障，而是
     * "查出来之后状态已经变了"/"排期时间被改到未来了"/"文章被删了"这类正常情况，
     * 用异常表达会让错误日志被噪声淹没。
     * <p>因此本方法对上述情况统一 {@code return false} + WARN 日志
     * （下一分钟会重新扫，或在状态条件上已经天然排除），
     * 只有真正的意外（数据库连不上之类）才会抛出去。
     *
     * @param id 文章 ID
     * @return {@code true} = 本次已发布；{@code false} = 本次未发布（原因见 WARN 日志）
     */
    boolean publishScheduled(Long id);

    // ==================== 八、撤回 / 下架 / 归档（S14） ====================

    /**
     * 撤回：{@code PUBLISHED → WITHDRAWN}。
     *
     * <h3>三个"把文章拿下来"的动作到底差在哪</h3>
     * <table border="1">
     *   <tr><th></th><th>撤回 withdraw</th><th>下架 offline</th><th>归档 archive</th></tr>
     *   <tr><td>谁来做</td><td><b>作者本人</b>（{@code article:withdraw}，{@code ROLE_AUTHOR} 就有）</td>
     *       <td>主编 / 管理员（{@code article:manage}）</td>
     *       <td>主编 / 管理员（{@code article:manage}）</td></tr>
     *   <tr><td>源状态</td><td>仅 {@code PUBLISHED}</td><td>仅 {@code PUBLISHED}</td>
     *       <td>{@code WITHDRAWN} 或 {@code OFFLINE}</td></tr>
     *   <tr><td>目标状态</td><td>{@code WITHDRAWN}</td><td>{@code OFFLINE}</td>
     *       <td>{@code ARCHIVED}（终态）</td></tr>
     *   <tr><td>能否恢复</td><td><b>能</b>（{@code WITHDRAWN + PUBLISH_NOW → PUBLISHED}）</td>
     *       <td><b>不能</b>（{@code OFFLINE} 只有 {@code ARCHIVE} 一条出边）</td>
     *       <td><b>不能</b>（终态，无任何出边）</td></tr>
     *   <tr><td>要不要归属校验</td><td><b>要</b>（只能收回自己的稿）</td>
     *       <td>不要（运营动作，与"这篇归谁"无关）</td>
     *       <td>不要（同左）</td></tr>
     *   <tr><td>要不要填原因</td><td>不要</td><td><b>要</b>（下架必须说明理由）</td><td>不要</td></tr>
     * </table>
     * <p>语义上的区别一句话说清：<b>撤回是"我先拿回来改改"（可逆），
     * 下架是"这篇有问题，别让人看了"（不可逆但仍留在库里），
     * 归档是"这篇的生命周期结束了"（终态，从此只读）</b>。
     * <p>前台可见性：三者都<b>不出现在读者端列表</b>里。差别在"能不能回来"——
     * 撤回的文章作者改完可以重新发布，下架/归档的不能。
     *
     * <h3>⚠️ 为什么撤回要校验归属，而下架/归档不校验</h3>
     * <p>这是 S12 的接口分类表已经写死的口径（{@code docs/接口分类与鉴权表.md}）：
     * <ul>
     *   <li><b>撤回是"作者收回自己交出去的东西"</b>。它的权限码
     *       {@code article:withdraw} 连 {@code ROLE_AUTHOR(7)} 都有，
     *       如果再不校验归属，任何作者都能把别人的已发布文章撤下来 ——
     *       这是一个真实的破坏性漏洞（已发布文章被莫名下线的责任事故）。
     *       校验命中抛 {@code ARTICLE_NOT_OWNER(11003)}。</li>
     *   <li><b>下架/归档是运营职能</b>，权限码 {@code article:manage} 只授予
     *       {@code ROLE_CHIEF(12)} 与 {@code ROLE_ADMIN(20)}。
     *       主编要能下架任何一篇已发布文章（包括别人写的）——
     *       这就是这个职能存在的意义。加归属校验等于让审核者只能管自己的稿，职能失效。</li>
     * </ul>
     * <p>⚠️ 注意这与"发布不校验归属"（见 {@link #publishNow}）是同一个道理，
     * 但结论与"修改草稿"（{@link #updateDraft} 要校验）相反 ——
     * <b>判据是"这个动作是作者对自己的内容负责，还是运营对全站内容负责"</b>，
     * 不是"所有写操作都校验"或"都不校验"。
     *
     * <h3>不写审核流水</h3>
     * <p>同 {@link #publishNow}：{@code cms_article_audit_log} 的 {@code result} 只有
     * 1 通过 / 2 驳回 两个合法取值，"撤回"填不进这两列；
     * 而且该表还有一个更硬的副作用 —— 详情接口的 {@code rejectReason} 是
     * "从后往前找第一条 {@code result = 2} 的意见"，
     * 一旦往下架/撤回流水里塞 {@code result=2}，用户会在文章详情页上看到
     * "驳回原因：内容涉及侵权"这种张冠李戴的提示。
     * <p>留痕走 {@code @OperLog} → {@code sys_oper_log}（记"谁在什么时候撤回了哪一篇"）。
     *
     * @param id         文章 ID
     * @param operatorId 操作人 ID（必须是文章作者本人）
     */
    void withdraw(Long id, Long operatorId);

    /**
     * 下架：{@code PUBLISHED → OFFLINE}。<b>不可逆</b>（{@code OFFLINE} 只有 {@code ARCHIVE} 一条出边）。
     *
     * <h3>⚠️ 为什么不收 DTO 而收裸的 {@code String reason}（与 {@link #publishSchedule} 相反）</h3>
     * <p>{@code publishSchedule} 收 DTO 的理由是"把 {@code LocalDateTime} 的解析交给字段上的
     * 校验注解、并让时间格式只有一处定义"，那是<b>技术理由</b>。
     * 这里的原因字段就是一个非空字符串，Service 层没有任何格式需要处理，
     * 包一层 DTO 只是多一个类。DTO（{@link com.lab.cms.dto.CmsArticleOfflineDTO}）
     * 只出现在 Controller 的 {@code @RequestBody} 上 —— <b>HTTP 边界需要它，Service 边界不需要</b>。
     * <p>⚠️ 但"非空"这件事<b>不能只靠 Controller 的 {@code @Valid}</b>：
     * Service 可能被非 HTTP 入口调用（批处理、别的 Service），
     * 那时 {@code @Valid} 根本不会执行。所以本方法内部会再判一次（抛 400 + 中文提示）。
     *
     * <h3>⚠️ 下架原因写到哪：选 {@code sys_oper_log.request_param}，不用审核流水表</h3>
     * <p>手册给了两个候选（复用 {@code cms_article_audit_log} 加一个新 {@code auditStage}、
     * 或新增一张"状态变更记录表"），本项目<b>两个都不选</b>，理由分别是：
     * <ol>
     *   <li><b>不能复用审核流水表。</b>{@code audit_stage} 的列注释写死"1=初审 2=终审"，
     *       {@code result} 是 NOT NULL 且只允许 1/2。要给下架找位置，
     *       就得塞一个非法语义的 {@code result}（选 1 表示"通过下架"？选 2？）——
     *       而 {@code result=2} 会污染详情接口的 {@code rejectReason}（见 {@link #withdraw} 的说明），
     *       选 1 则会让审核时间线上出现一条"主编终审·通过"的假记录。
     *       另有前端成本：{@code AuditTimeline.vue} 按 {@code auditStage} 渲染，
     *       stage=3 在它那里显示成"未知环节"。</li>
     *   <li><b>不新增表/列。</b>17 张表是四人共用的冻结资产，加表要全组同步
     *       （实体、Mapper、init.sql、成员B/C/D 的文档）；
     *       而 {@code cms_article} 加一列 {@code offline_reason} 同样动的是公共表。
     *       为一句运营备注付这个代价不成比例 —— 这与 S13 拒绝手册要求的
     *       "为乐观锁加 ALTER"是同一条判据：<b>能不加 DDL 就不加</b>。</li>
     * </ol>
     * <p>结论：<b>原因的持久化通道就是 {@code @OperLog}</b>。
     * {@code WebLogAspect} 会把方法入参用 Jackson 序列化成 JSON 写进
     * {@code sys_oper_log.request_param}（默认 {@code saveParams = true}），
     * 于是这一条日志天然包含 {@code [文章id, "下架原因", 操作人id]}。
     * {@code sys_oper_log} 本来就是"谁在什么时候做了什么"的追责表，
     * 而"下架"恰恰是一个操作（不是审核结论）—— 语义完全对得上。
     * <p>⚠️ 如果组内后来确实要"在文章详情里回显下架原因"，最小改动是给
     * {@code cms_article} 加一列；本步<b>刻意不做</b>，只把结论登记在
     * {@code docs/S14-撤回下架归档实测记录.md} 的"挂起待定"一节。
     *
     * @param id         文章 ID
     * @param operatorId 操作人 ID（主编 / 管理员）
     * @param reason     下架原因（非空，最长 200 字）
     */
    void offline(Long id, Long operatorId, String reason);

    /**
     * 归档：{@code WITHDRAWN / OFFLINE → ARCHIVED}。<b>终态</b>，之后任何动作都非法。
     *
     * <h3>⚠️ "已归档真的是终态"这件事由两层共同保证</h3>
     * <ol>
     *   <li><b>状态机层</b>：{@code TRANSITIONS} 里没有任何以 {@code ARCHIVED} 为源状态的行，
     *       且 {@code transfer()} 对终态<b>单独</b>抛
     *       {@code ARTICLE_ARCHIVED_READONLY(11011)}（比"状态不允许操作"提示更明确）。</li>
     *   <li><b>业务入口层（本步新增的"双保险"）</b>：三个方法共用的私有流转方法开头显式判一次
     *       {@code status == ARCHIVED} → 11011。</li>
     * </ol>
     * <p>第 2 层在逻辑上<b>是冗余的</b>（第 1 层已经能拦住），项目仍然加上它，因为：
     * <ul>
     *   <li>"终态只读"是这个领域最容易被后人无意破坏的一条不变式 ——
     *       将来有人想加"从归档恢复"，改的会是状态机表，那时这条显式判断
     *       会立刻在他的测试里失败，起到"喊一声"的作用。</li>
     *   <li>它把"归档不可再操作"这个业务结论写在<b>业务代码</b>里，
     *       而不是只藏在状态机的数据表里。读 {@code archive()} 的人不用跳三层文件。</li>
     * </ul>
     * <p>⚠️ 手册 S14 说这条抛 {@code ARTICLE_ARCHIVED}（11010）。本项目<b>没有</b>这个码名，
     * 11010 是 {@code ARTICLE_UPDATE_CONFLICT}（乐观锁冲突）；
     * 归档只读的码是 {@code ARTICLE_ARCHIVED_READONLY(11011)}。属手册错误码错位区，以代码为准。
     *
     * <h3>⚠️ 归档要不要顺带让检索索引失效</h3>
     * <p>要，但<b>本步不实现</b>：{@code cms_article_index} 由成员D 维护，
     * 本步只在实现里留一个带 {@code TODO} 的调用点 + 日志（见
     * {@code ArticleServiceImpl#markSearchIndexInvalidOnArchive}）。
     * 这是刻意的边界纪律 —— 直接改别人负责的表，出问题时要跨模块排查。
     *
     * @param id         文章 ID
     * @param operatorId 操作人 ID（主编 / 管理员）
     */
    void archive(Long id, Long operatorId);

    // ==================== 九、版本快照与回滚（S15） ====================

    /**
     * 查某篇文章的全部版本快照。<b>只读，不返回正文</b>。
     *
     * <h3>⚠️ 为什么这个接口<b>不</b>做归属校验</h3>
     * <p>与 {@link #getDetail(Long)}、{@link #listAuditLogs(Long)} 同一条口径
     * （见 {@code docs/接口分类与鉴权表.md} §二.5）：后台是协作场景，
     * 编辑/主编必须能打开任何一篇文章才能审、才能核对，被限制的是"改"而不是"看"。
     * 版本列表里只有标题快照与修改说明，没有任何正文，暴露面比详情接口还小 ——
     * 详情都放开了，这里单独收紧只会自相矛盾。
     *
     * <p>⚠️ 但它<b>会</b>校验文章是否存在：查不到抛
     * {@code ARTICLE_NOT_FOUND(11001)}。这与 {@code listAuditLogs} 的处理一致
     * —— 一个不存在的文章 id 应该明确报错，而不是安静地返回空列表
     * （前端无法区分"这文章没有版本"和"这文章根本不存在"）。
     *
     * <h3>排序与 {@code isCurrent}</h3>
     * <p><b>后端按 {@code version_no} 正序返回</b>（1 → N），
     * 由前端自己倒序展示。这样定的原因是"接口返回物理顺序、展示顺序归 UI"：
     * 如果后端直接倒序返回，将来前端想要正序（比如画版本演进图）就得反转数组，
     * 而反转一个"已经倒序的数组"会让代码里出现两处方向相反的排序逻辑。
     * <p>（前端 {@code ArticleDetail.vue} 已经这么做了：拿到后
     * {@code [...list].sort((a, b) => Number(b.versionNo) - Number(a.versionNo))}。）
     *
     * <p>{@code modifierName} 用<b>一次</b> {@code IN} 查询批量取（不是 N+1）；
     * {@code isCurrent} 由"与最大版本号比较"得出，一篇文章最多一条为 {@code true}。
     *
     * @param articleId 文章 ID
     * @return 版本快照列表（按 versionNo 正序）；没有快照时返回空列表
     * @throws com.lab.cms.common.exception.BusinessException 文章不存在 → 11001
     */
    List<CmsArticleVersionVO> listVersions(Long articleId);

    /**
     * 回滚到指定版本：把该版本的标题与正文覆盖回文章，<b>并生成一个新版本</b>。
     *
     * <h3>允许回滚的状态（白名单，不是黑名单）</h3>
     * <p>只有 {@code DRAFT(0)} / {@code REJECTED(4)} / {@code WITHDRAWN(7)}
     * 三个状态可以回滚，其余一律 {@code ARTICLE_STATUS_ILLEGAL(11002)}；
     * 已归档额外走 {@code ARTICLE_ARCHIVED_READONLY(11011)}。
     *
     * <p><b>为什么"已发布(5)"不能直接回滚</b>：已发布的内容是<b>线上</b>内容。
     * 允许一步回滚意味着"任何有这个权限的人可以在不经过审核的情况下改变读者看到的东西"，
     * 而那正是审核流程存在的意义。想改已发布文章的正文，正确路径是
     * <b>先撤回 → 再回滚 → 重新提交审核 → 重新发布</b>，
     * 每一步都在状态机里有据可查。这条约束让"回滚"永远只作用于
     * "还没对读者生效"的稿子，风险面小得多。
     * <p>更硬的一条理由：{@code PUBLISHED(5)} 与 {@code PENDING_*}/{@code SCHEDULED(6)}
     * 一样属于"在流转中"的状态，而这些状态的出边都被状态机管着；
     * 回滚不会改变状态、只改内容，它绕过了状态机 —— 越是这样，
     * 越要靠一条显式白名单把"能让它绕过状态机的场景"限制到最小。
     *
     * <h3>⚠️ 回滚只恢复 {@code title} 与 {@code content}</h3>
     * <p>因为 {@code cms_article_version} <b>只快照了这两列</b>
     * （{@code title_snapshot} / {@code content_snapshot}）。
     * {@code summary} / {@code cover_url} / {@code category_id} / {@code is_top}
     * <b>不在快照里，因此回滚不会、也无法恢复它们</b>。
     * <p>这一点必须说清楚，否则用户会以为"回滚 = 回到那一版的样子"，
     * 而实际上分类、封面、摘要都还是现在的值。前端确认弹窗的文案已经按
     * "以 vN 的内容新建一个版本"来表达，不敢写"恢复到 vN"。
     *
     * <h3>⚠️ 为什么要"再生成一个新版本"，而不是删掉后面的版本</h3>
     * <p>回滚的语义是<b>"以旧内容新建一版"</b>，不是"撤销"。
     * 如果删掉后面的版本，会有三个后果：
     * <ol>
     *   <li><b>历史不可信</b>：版本表本来是只增不改的审计性数据
     *       （{@code CmsArticleVersion} 连 {@code update_by}/{@code update_time}
     *       都没有，见其类注释），删除等于允许改写历史。</li>
     *   <li><b>版本号会重复</b>：删掉 N+1 之后下一个新版本又会取到 N+1，
     *       而"第 N+1 版"在别人眼里已经出现过一次，含义完全不同。</li>
     *   <li><b>回滚本身会变得无法追溯</b>：谁也说不清"文章现在为什么是这个内容"。</li>
     * </ol>
     * <p>所以回滚 = 覆盖正文 + 追加一条 {@code version_no = max + 1}、
     * {@code modify_remark = "回滚至版本 N"} 的快照。版本号<b>只增不减</b>。
     *
     * <h3>为什么这里不校验状态机</h3>
     * <p>状态机管的是<b>状态之间的迁移</b>，而回滚<b>不改变状态</b>
     * （{@code ArticleAction.ROLLBACK} 在 {@code TRANSITIONS} 里没有任何一条边）。
     * 所以它走的是"状态白名单 + 归属校验"，不是 {@code transfer}。
     *
     * @param articleId  文章 ID
     * @param versionNo  目标版本号（1 起）
     * @param operatorId 操作人 ID
     * @throws com.lab.cms.common.exception.BusinessException
     *         文章不存在→11001；非作者→11003；状态不允许→11002；
     *         已归档→11011；版本不存在→11009；乐观锁冲突→11010
     */
    void rollbackVersion(Long articleId, Integer versionNo, Long operatorId);
}
