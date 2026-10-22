package com.lab.cms.service;

import com.lab.cms.base.BaseService;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.vo.BatchSubmitResultVO;

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
}
