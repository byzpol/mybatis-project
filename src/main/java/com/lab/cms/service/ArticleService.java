package com.lab.cms.service;

import com.lab.cms.base.BaseService;
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
}
