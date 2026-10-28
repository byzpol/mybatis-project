package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lab.cms.base.BaseServiceImpl;
import com.lab.cms.common.ArticleStateMachine;
import com.lab.cms.common.enums.ArticleAction;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.common.enums.AuditResultEnum;
import com.lab.cms.common.enums.AuditStageEnum;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticlePublishDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsArticleAuditLog;
import com.lab.cms.entity.CmsArticleVersion;
import com.lab.cms.entity.CmsCategory;
import com.lab.cms.entity.SysUser;
import com.lab.cms.mapper.CmsArticleAuditLogMapper;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsArticleVersionMapper;
import com.lab.cms.mapper.CmsCategoryMapper;
import com.lab.cms.mapper.SysUserMapper;
import com.lab.cms.service.ArticleService;
import com.lab.cms.vo.ArticleAuditLogVO;
import com.lab.cms.vo.BatchSubmitResultVO;
import com.lab.cms.vo.CmsArticleDetailVO;
import com.lab.cms.vo.CmsArticleVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章业务服务实现。
 *
 * <h3>S07 的"空壳 + 抛 UnsupportedOperationException"已全部替换</h3>
 * <p>S07 把四个方法都写成 {@code throw new UnsupportedOperationException(...)}，
 * 理由是"留空会静默地错，抛异常立刻炸"。S09 逐个替换为真实实现，
 * 替换顺序与入户线一致：先 {@code saveDraft}（唯一的新增入口），
 * 再 {@code updateDraft}（改动入口），再 {@code submitForAudit}（状态推进），
 * 最后 {@code batchSubmit}（前者的批量包装）。
 *
 * <h2>S09 的四个关键决策（每一条都在下面有对应注释）</h2>
 * <ol>
 *   <li><b>DTO → 实体只搬"可编辑的 6 个字段"</b>，状态/作者/浏览量/发布人一律由
 *       Service 决定，不接受入参 —— 防批量赋值（Mass Assignment）。</li>
 *   <li><b>状态推进一律走 {@link ArticleStateMachine#transfer}</b>，
 *       全类搜索不到一处 {@code setStatus(数字常量)} 或 {@code if (status == 1)}。</li>
 *   <li><b>越权校验用 {@code checkOwnership}</b>，只比对 {@code authorId}。</li>
 *   <li><b>版本快照取号用"SELECT MAX + 1 + 唯一索引重试"</b>，
 *       不用行锁 —— 理由见 {@link #insertVersionSnapshot}。</li>
 *   <li><b>修改草稿不用 {@code updateById(entity)}，用
 *       {@code update(carrier, wrapper)} 显式列出要写的列</b> ——
 *       因为 {@code updateById} 会跳过 null 字段，"清空摘要"会返回成功却什么都不改。
 *       这是本步实测抓到的静默失效，详见 {@link #updateEditableColumns}。</li>
 * </ol>
 *
 * <h3>S10 追加：{@link #audit}</h3>
 * <p>审核（编辑初审 / 主编终审）合并成<b>一个</b>方法实现，环节由当前状态反推，
 * 与 {@link #submitForAudit} 共用同一套"查文章 → 校验 → 状态机推进 → 写表"的骨架。
 * 两条与手册的差异都在方法注释里写明了：
 * ① 步骤顺序改为"先改状态、后写流水"（为了让"两个写操作在同一事务"这条不变式可测）；
 * ② 不做"该阶段已审核过"的存在性检查（按字面实现会打断"驳回 → 改稿 → 再审"的主链路）。
 * <p>⚠️ {@link #audit} <b>不创建</b>版本快照：审核不改内容，只改状态与写流水。
 * 快照由创建 / 修改 / 提交三个动作负责（见 {@link #insertVersionSnapshot} 的调用点）。
 *
 * <h3>⚠️ 关于 {@code @Transactional}</h3>
 * <p>三个"改数据 + 写快照"的方法都加了
 * {@code @Transactional(rollbackFor = Exception.class)}，理由：
 * <ul>
 *   <li>一次操作要同时写 {@code cms_article} 与 {@code cms_article_version} 两张表，
 *       必须同成功或同失败。若状态改了而快照没写进去，
 *       {@code cms_article_version} 就会出现"版本号跳号"，
 *       回滚功能（S15）拿到的是错的基线。</li>
 *   <li>⚠️ {@code rollbackFor = Exception.class} <b>不能省</b>：
 *       Spring 默认只在遇到 {@code RuntimeException} 与 {@code Error} 时回滚，
 *       受检异常不回滚。虽然本项目只抛 {@code BusinessException}（非受检），
 *       但将来接第三方 SDK 时很可能出现受检异常 —— 显式写出来，
 *       就把"任何异常都回滚"这个意图钉死了，而不是依赖默认值。</li>
 * </ul>
 * <p>⚠️ <b>{@code batchSubmit} 刻意<b>不加</b>事务注解</b>，理由见该方法注释 ——
 * 这不是漏写，而是"逐条独立事务"的前提条件。
 *
 * <h3>为什么"越权校验"是私有方法而不是工具类</h3>
 * <p>它依赖 {@code CmsArticle} 与 {@code ErrorCode}（都是文章域的东西），
 * 且将来 S10 的审核、S14 的撤回下架都要用同一套规则。
 * 放在本类里，改了只影响文章域；放进主干工具类则会变成"所有域共用一条归属规则"，
 * 而各域的归属语义并不相同（文章看 authorId，分类看部门）。
 */
@Service
public class ArticleServiceImpl extends BaseServiceImpl<CmsArticleMapper, CmsArticle>
        implements ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleServiceImpl.class);

    /**
     * 每批最多提交的文章数量。
     * <p>防止恶意构造超大 id 列表导致的资源耗尽（见 {@code ArticleService#batchSubmit} 类注释）。
     * <p>与 {@code BatchSubmitDTO#ids} 上的 {@code @Size(max = 100)} 数值一致，
     * 但**两者都要留着**：DTO 拦 HTTP 入口，这里拦"不经过 DTO 的调用方"
     * （定时任务、批处理脚本、将来的其他 Service）。
     */
    private static final int MAX_BATCH_SIZE = 100;

    /**
     * 版本快照取号冲突时的最大重试次数。
     *
     * <p>取 3 而不是取 10：并发取号冲突是"低概率、瞬时"的事件
     * （冲突的概率与并发写同一篇文章的请求数同阶，而项目里同一篇文章
     * 不会同时被多人改——只有作者本人能改）。连续 3 次都撞上，
     * 说明不是偶发并发，而是别的地方出了问题（比如有人绕过唯一索引限制
     * 造了脏数据），此时继续重试只是掩盖问题。
     */
    private static final int MAX_SNAPSHOT_RETRY = 3;

    /** MySQL 唯一键冲突的错误码。 */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /** 快照的修改说明：首次创建。 */
    private static final String REMARK_CREATE = "创建草稿";

    /** 快照的修改说明：修改草稿。 */
    private static final String REMARK_UPDATE = "更新草稿";

    /** 快照的修改说明：提交审核。 */
    private static final String REMARK_SUBMIT = "提交初审";

    /**
     * 排期时间与"当前时间"之间至少要留出的分钟数（S13）。
     *
     * <p>取 1 与定时任务的扫描频率（每分钟一次）对齐：
     * 若允许排到"几秒后"，用户点完"排期"立即去列表页看，
     * 很可能看到文章还停在"定时待发"，从而怀疑功能没生效。
     * 留满一分钟，保证"排期成功 → 到点发布"这个承诺一定是可兑现的。
     */
    private static final int MIN_SCHEDULE_AHEAD_MINUTES = 1;

    /**
     * 定时任务单次扫描最多处理多少篇（S13）。
     *
     * <p>这是"封顶"而不是"分页"：正常情况下每分钟到点的文章只有个位数，
     * 这个数永远不会被触及；它的作用是防止异常积压（比如定时任务停了三天后重启）
     * 让某一次扫描长时间占用调度线程。超出的部分下一分钟会继续扫到。
     */
    private static final int SCAN_BATCH_LIMIT = 200;

    /**
     * 系统用户 ID 哨兵值（0）。
     *
     * <p>语义与 {@code AuditMetaObjectHandler#SYSTEM_USER_ID} 完全一致
     * （那里用于填 {@code create_by / update_by}）：<b>0 = 由系统写入，不是人</b>。
     * {@code sys_user} 的 id 从 1 开始，所以 0 不会与任何真实账号冲突。
     *
     * <p>本类用它是因为"排期到点自动发布"没有人类操作者，
     * 而 {@code publisher_id} 是 NOT NULL 之外的可空列、必须有个说法。
     * 详见 {@link #publishScheduled(Long)} 的注释（含"为什么不记原作者"的取舍）。
     */
    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * 排期时间的展示格式（S13）。
     *
     * <p>⚠️ 必须与 {@code JacksonConfig#DATE_TIME_PATTERN}（{@code yyyy-MM-dd HH:mm:ss}）
     * 保持一致 <b>一个字符都不差</b>。原因是实测发现的：
     * 直接把 {@code LocalDateTime} 交给 {@code %s} 会走 {@code LocalDateTime.toString()}，
     * 输出成 ISO 的 {@code 2026-10-27T18:30}（带 T、秒被吞掉），
     * 于是一条错误提示里出现两种时间写法 —— 用户在界面上看到的是"传入 2020-01-01T00:00"，
     * 而同一个接口的其它地方又全是空格格式。排查的人会以为这是两个不同的字段。
     *
     * <p>{@code DateTimeFormatter} 是不可变且线程安全的，可以安全地做成静态常量。
     */
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 版本快照 Mapper。
     * <p>⚠️ 注意这里注入的是**另一个** Mapper（{@code CmsArticleVersionMapper}），
     * 与父类已有的 {@code baseMapper}（{@code CmsArticleMapper}）不是同一个。
     * {@code BaseServiceImpl} 的注释要求"不要重复注入自己的 baseMapper"，
     * 指的是后者；注入"同一业务域里的另一张表的 Mapper"是正常且必要的。
     */
    @Autowired
    private CmsArticleVersionMapper versionMapper;

    /**
     * 审核流水 Mapper（S10 起使用）。
     *
     * <p>与 {@code versionMapper} 同理：这是"文章业务域里的另一张表"，
     * 不是自己的 {@code baseMapper}（后者由父类持有），注入它不违反
     * {@code BaseServiceImpl} 的"不要重复注入自己的 baseMapper"。
     */
    @Autowired
    private CmsArticleAuditLogMapper auditLogMapper;

    /**
     * 用户 Mapper（S11 起使用），只为补 {@code authorName} / {@code auditorName}。
     *
     * <p>⚠️ 只调用 MP 自带的 {@code selectBatchIds}（一次 {@code IN} 查询），
     * 绝不在循环里 {@code selectById} —— 那是典型的 N+1，
     * 而"详情接口只发 3~4 条 SQL"正是本步的硬指标。
     */
    @Autowired
    private SysUserMapper userMapper;

    /**
     * 分类 Mapper（S11 起使用），只为补 {@code categoryName}。
     * <p>{@code cms_article} 表里只有 {@code category_id}，分类名必须查这张表。
     */
    @Autowired
    private CmsCategoryMapper categoryMapper;

    /**
     * 本 Service 的**自身代理**，专供 {@link #batchSubmit} 使用。
     *
     * <h3>为什么必须注入自己</h3>
     * <p>Spring 的 {@code @Transactional} 是靠 AOP 代理实现的：
     * 方法被调用时，真正执行的是"代理对象"里的增强逻辑（开启事务 → 调用目标方法 → 提交/回滚）。
     * 而 {@code this.submitForAudit(...)} 这种<b>同类内部自调用</b>绕过了代理，
     * 直接调用目标对象上的方法 —— <b>事务注解完全不会生效，
     * 而且不报任何错、不打任何日志</b>，是最典型的"静默失效"。
     *
     * <p>批量接口想要的语义恰恰是"每篇一个独立事务"：
     * 第 8 篇失败时，前 7 篇已经提交的结果必须保住
     * （这正是 {@link BatchSubmitResultVO} 存在的理由）。
     * 只有通过代理调用，才会每篇各开一个事务。
     *
     * <h3>为什么要加 {@code @Lazy}</h3>
     * <p>本类实现了 {@code ArticleService}，而这里又要注入 {@code ArticleService}，
     * 等于"自己依赖自己"。Spring 允许这种自引用（{@code allowCircularReferences}
     * 默认为 true），但注入发生在 Bean 属性填充阶段，若不加 {@code @Lazy}，
     * 容器需要在这一刻就把代理创建出来 —— 在"本 Bean 尚未完成初始化"的时点上
     * 去取自己的代理，是最容易踩到"提前初始化"陷阱的地方。
     * 加 {@code @Lazy} 后注入的是一个延迟解析的句柄，
     * 真正拿到代理是**第一次调用时**，此时容器早已启动完毕，绝无时序问题。
     *
     * <h3>⚠️ 备选方案与为什么没选</h3>
     * <p>另一条路是注入 {@code TransactionTemplate} 并设
     * {@code PROPAGATION_REQUIRES_NEW} 手动控制事务边界。它更"硬"
     * （即使将来有人给 {@code batchSubmit} 加上 {@code @Transactional}，
     * 逐条隔离也不会被破坏），但代价是：
     * <ul>
     *   <li>业务方法里混进事务模板代码，读起来要先理解事务传播</li>
     *   <li>{@code submitForAudit} 上那份 {@code rollbackFor = Exception.class}
     *       的声明就被架空了 —— 事务语义散落在两处，改一处忘一处</li>
     * </ul>
     * 权衡后选自身代理：语义集中、和 {@code submitForAudit} 的服务端契约一致。
     * <b>代价是 {@code batchSubmit} 必须永远保持"无事务"</b> ——
     * 这件事写在 {@link #batchSubmit} 的注释里当作不变式来维护。
     */
    @Autowired
    @Lazy
    private ArticleService selfProxy;

    // ==================== 一、新建草稿 ====================

    /**
     * 新建草稿：只落库，不进审核流程。
     *
     * <p>流程：DTO → 实体（只搬可编辑字段）→ 强行置 DRAFT →
     * 写库 → 写第 1 条版本快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(CmsArticleSaveDTO dto, Long operatorId) {
        // 操作人必须先校验：本方法可能被定时任务/脚本调用，那里没有登录态。
        // 与其让 author_id 收到一个 null 后在数据库层报 NOT NULL 违反
        // （错误信息是"Column 'author_id' cannot be null"，离现场很远），
        // 不如在这里就给出明确的 401。
        requireOperator(operatorId);

        CmsArticle article = new CmsArticle();
        applyEditableFields(article, dto);

        // ---- 以下五个字段一律由 Service 决定，绝不从 DTO 取 ----
        // 这是防"批量赋值漏洞"（Mass Assignment）的核心：
        // 若前端能传 status，构造 {"title":"x","categoryId":1,"status":5}
        // 就能凭空造出一篇"已发布"的文章，整条审核链被绕过。
        // CmsArticleSaveDTO 里本来就没有这些字段，这里是第二道防线 ——
        // 将来有人往 DTO 里加了字段，也不会顺带把它们写进库里。
        article.setStatus(ArticleStatus.DRAFT.getCode());
        article.setAuthorId(operatorId);
        article.setViewCount(0);
        // version 显式置 0：实体标了 @Version，虽然库里 DEFAULT 0 也能兜住，
        // 但"乐观锁的起点"是个语义值，写出来比依赖默认值清楚。
        article.setVersion(0);
        // publisherId / publishTime 保持 null：未发布的文章没有发布人和发布时间。
        // scheduleTime 由 applyEditableFields 从 DTO 搬（见该方法的说明）。

        int rows = baseMapper.insert(article);
        if (rows != 1) {
            // 正常情况 insert 必然返回 1。走到这里说明数据库层面出了异常情况，
            // 抛系统错误而不是"静默返回 null id"——后者会让调用方带着 null 继续走。
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文章草稿保存失败，请稍后重试");
        }

        Long articleId = article.getId();
        // 首次快照：versionNo = 1，说明"创建草稿"。
        // 它必须在同一个事务里 —— 若主表写成功、快照没写成功，
        // 这篇文章的版本链就缺了起点，S15 的回滚会拿到错的基线。
        insertVersionSnapshot(article, operatorId, REMARK_CREATE);

        log.info("新建草稿成功 | articleId={} | authorId={} | title={}",
                articleId, operatorId, article.getTitle());
        return articleId;
    }

    // ==================== 二、修改草稿 ====================

    /**
     * 修改草稿。
     *
     * <p>流程：查文章 → 越权校验 → 状态可编辑校验 →
     * 覆盖可编辑字段 → 乐观锁更新 → 写一条新快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long id, CmsArticleSaveDTO dto, Long operatorId) {
        CmsArticle article = requireArticle(id);
        checkOwnership(article, operatorId);
        requireEditable(article);

        applyEditableFields(article, dto);

        // ⚠️⚠️ 这里**不用** updateById(entity)，用的是 update(carrier, wrapper) ——
        // 原因是实测踩出来的一个静默失效，见 updateEditableColumns 的注释。
        // 直接说结论：updateById 默认的字段策略是 NOT_NULL，
        // 值为 null 的字段**不会**出现在 SET 子句里，
        // 于是"把摘要清空"这类操作会返回 200 却什么都不改。
        //
        // 乐观锁仍然生效：carrier 里带着查出来的 version，
        // MP 的 OptimisticLockerInnerInterceptor 会把它改写成
        // SET version = version + 1 ... WHERE ... AND version = ?（旧值）。
        // 不匹配时返回 0 —— 与 updateById 的行为一致，所以下面的判 0 照旧。
        if (baseMapper.update(optimisticLockCarrier(article), updateEditableColumns(article)) == 0) {
            // 能进入本方法说明文章刚被查出来过（存在），所以返回 0
            // 只可能是 version 被别人改过了（或期间被逻辑删除了）。
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        insertVersionSnapshot(article, operatorId, REMARK_UPDATE);
        log.info("修改草稿成功 | articleId={} | operatorId={}", id, operatorId);
    }

    // ==================== 三、提交审核 ====================

    /**
     * 提交审核：{@code DRAFT / REJECTED → PENDING_EDITOR}。
     *
     * <p>流程：查文章 → 越权校验 → <b>状态机判可提交</b> → 内容完整性校验 →
     * 改状态 → 写一条快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitForAudit(Long id, Long operatorId) {
        CmsArticle article = requireArticle(id);
        checkOwnership(article, operatorId);

        // 先把"原状态"留下来给日志用。⚠️ 必须在 setStatus 之前取 ——
        // setStatus 之后 article.getStatus() 已经是目标状态，
        // 日志会打印出「待编辑初审 → 待编辑初审」这种看不出问题的样子。
        ArticleStatus fromStatus = ArticleStatus.ofNullable(article.getStatus());

        // ⚠️ 状态推进**只走状态机**。下面这行同时完成了"能不能提交"与"提交后是什么状态"
        // 两件事，且把"只有 DRAFT 与 REJECTED 能提交"这条规则留在状态机里 ——
        // 本类不会出现第二处判断"当前状态是否允许提交"的代码。
        //
        // 用 transfer（抛异常版）而不是 nextStatus（返回 null 版）：
        // nextStatus 拿到 null 后若漏写 else 分支，操作会静默什么都不做，
        // 这类 bug 比抛异常难查得多（ArticleStateMachine 的注释专门强调过这点）。
        // transfer 会按情况抛三种错误码：
        //   状态为 null        → ARTICLE_NOT_FOUND(11001)
        //   状态是终态 ARCHIVED → ARTICLE_ARCHIVED_READONLY(11011)
        //   状态合法但动作不允许 → ARTICLE_STATUS_ILLEGAL(11002) + "文章当前状态为「已发布」，不允许执行「提交初审」"
        //
        // 为什么不按手册用 assertTransfer：它对任何非法情况都抛 11002，
        // 而"已归档"是本项目里语义很特殊的一类（终态、只读），
        // 给用户"已归档的文章不可修改"比"状态不允许操作"有用得多。
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), ArticleAction.SUBMIT);

        // 内容完整性校验放在状态校验**之后**：
        // 顺序反过来的话，对一篇已发布的文章提交会先被提示"正文为空"，
        // 而真正的原因是"这个状态根本不能提交"—— 提示要指向真问题。
        requireSubmittable(article);

        article.setStatus(target.getCode());
        if (baseMapper.updateById(article) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        // 提交时也写一条快照，说明是"提交初审"。
        // 理由：提交这一刻内容被"冻结"交给审核人，记下这一版，
        // 审核人看到的与事后追溯的才对得上（S15 的版本列表里这也是最有价值的一条）。
        insertVersionSnapshot(article, operatorId, REMARK_SUBMIT);

        // ⚠️ 快照用的是 setStatus 之后的实体，但快照只取标题与正文，
        // 与状态无关，所以不受影响。日志这里用提前留下的 fromStatus。
        String statusPath = (fromStatus == null ? "未知状态" : fromStatus.name()) + " → " + target.name();
        log.info("提交初审成功 | articleId={} | {} | operatorId={}", id, statusPath, operatorId);
    }

    // ==================== 四、批量提交 ====================

    /**
     * 批量提交审核：逐条容错，返回成功数 + 失败清单。
     *
     * <p>⚠️ <b>本方法刻意不加 {@code @Transactional}</b>，这不是漏写：
     * <ul>
     *   <li>逐条容错语义要求"第 8 篇失败时前 7 篇已提交的结果保住"。
     *       如果本方法自己开了事务，那么通过 {@code selfProxy} 调用的
     *       {@code submitForAudit}（传播行为 REQUIRED）会**加入外层事务**，
     *       于是每一篇不再是独立事务，隔离性消失 ——
     *       更糟的是，此时我们在循环里 catch 住异常后，
     *       该篇中途写进去的东西<b>不会回滚</b>（事务还没结束），
     *       产生"部分写入"的脏数据。</li>
     *   <li>所以不变式是：<b>{@code batchSubmit} 必须无事务，逐条隔离靠
     *       {@code selfProxy} 走代理实现。</b>将来若有人给它加事务注解，
     *       请先回头读这一段。</li>
     * </ul>
     *
     * <p>另外两件事在写循环之前就得做完（都属于"整批请求本身不合法"，
     * 不该退化成一堆失败明细）：
     * <ol>
     *   <li>参数合法性：ids 为空 / null / 元素全是 null / 超过 100 条 → 400。</li>
     *   <li>去重：同一 id 传两次，第二次必然因"状态已变成待初审"而失败，
     *       把它算进失败清单是误导 —— 用户会以为自己操作错了。</li>
     * </ol>
     */
    @Override
    public BatchSubmitResultVO batchSubmit(List<Long> ids, Long operatorId) {
        requireOperator(operatorId);

        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请至少选择一篇文章");
        }

        // 去重用 LinkedHashSet：既去掉重复，又保留用户勾选的顺序
        // （失败清单按用户传入的顺序输出，前端展示时不会"跳来跳去"）。
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(ids));

        // 上限校验放在去重**之后**：传 100 个相同的 id 实际上只处理 1 篇，
        // 不应该因为"传了 100 个"就拒掉。判据是"要处理几篇"，不是"传了几个"。
        if (distinctIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "单次最多批量提交 " + MAX_BATCH_SIZE + " 篇，本次共 " + distinctIds.size() + " 篇");
        }

        BatchSubmitResultVO result = new BatchSubmitResultVO();
        result.setTotal(distinctIds.size());

        int successCount = 0;
        for (Long id : distinctIds) {
            try {
                // ⚠️ 走自身代理，不是 this.submitForAudit(...) —— 见 selfProxy 的注释。
                selfProxy.submitForAudit(id, operatorId);
                successCount++;
            } catch (BusinessException e) {
                // 业务失败（状态不允许 / 不是自己的文章 / 内容不全）→ 记入失败清单。
                // 直接透传 e.getMessage()：ErrorCode 与状态机里的文案本来就是
                // 按"能展示给用户看"的标准写的，再包一层只会得到两套文案。
                log.warn("批量提交：单条业务失败 | articleId={} | code={} | msg={}",
                        id, e.getCode(), e.getMessage());
                result.getFailList().add(new BatchSubmitResultVO.FailItem(id, e.getMessage()));
            } catch (RuntimeException e) {
                // 非预期异常（数据库故障、空指针…）也**不能**整批中断：
                // 用户要的是"其余的先交上去"，而不是整批 500。
                // 但日志必须打 ERROR + 堆栈，因为这类问题需要人工介入，
                // 且返回给用户的文案要脱敏（不能把 SQL/堆栈漏出去）。
                log.error("批量提交：单条系统异常 | articleId={}", id, e);
                result.getFailList().add(new BatchSubmitResultVO.FailItem(id, "系统处理失败，请稍后重试"));
            }
        }

        result.setSuccessCount(successCount);
        result.setFailCount(distinctIds.size() - successCount);

        log.info("批量提交完成 | 操作人={} | 去重后 {} 篇 | 成功 {} 篇 | 失败 {} 篇",
                operatorId, result.getTotal(), result.getSuccessCount(), result.getFailCount());
        return result;
    }

    // ==================== 五、审核（编辑初审 / 主编终审共用） ====================

    /**
     * 审核：{@code PENDING_EDITOR → {PENDING_CHIEF | REJECTED}}、
     * {@code PENDING_CHIEF → {APPROVED | REJECTED}}。
     *
     * <p>环节由<b>当前状态</b>反推（见 {@link #resolveAuditStage}），
     * 所以初审与终审走的是同一段代码，方法里没有任何 {@code if (stage == 1)} 式的分支。
     *
     * <h3>⚠️⚠️ 步骤顺序与手册不同：先改状态、后写流水（有意为之）</h3>
     * <p>手册 S10 给的顺序是"5. 插审核流水 → 6. 更新文章状态"。
     * 这里反过来。这不是笔误，原因是一条<b>能否被验证</b>的差别：
     * <ul>
     *   <li>手册自己也强调了这条不变式：<b>"写流水和改状态必须在同一事务里，
     *       否则会出现状态改了但流水丢了"</b>。</li>
     *   <li>若按手册顺序（先流水后状态），要在测试里制造"流水写完、状态改失败"的场景，
     *       就必须让<b>状态更新</b>这一步失败 —— 而状态更新是
     *       {@code updateById}，在没有并发干扰的情况下它不会失败，
     *       只好去 mock 框架内部。等于这条不变式<b>测不了</b>。</li>
     *   <li>反过来写（先状态后流水），只要让<b>流水插入</b>失败即可 ——
     *       而流水插入的失败是<b>确定性、可构造</b>的：
     *       给一个超过 500 字的 {@code opinion}，MySQL 严格模式
     *       （本机 {@code sql_mode} 含 {@code STRICT_TRANS_TABLES}）会抛错误 1406。
     *       跑完断言"状态仍停在 PENDING_EDITOR、且流水表没有新增行"，
     *       才算真正证明了两个写操作在同一事务里。</li>
     * </ul>
     * <p>顺带还有一个好处：乐观锁冲突（{@code updateById} 返回 0）是<b>最先</b>被发现的，
     * 此时还什么都没写，不会留下一条随后要被回滚的流水。
     *
     * <h3>驳回必填意见</h3>
     * <p>在校验顺序上放在"状态推进"<b>之前</b>：驳回却不给理由，
     * 是"这一请求本身就不完整"，应当在动任何数据之前就拒掉。
     * <p>⚠️ 用 {@code StringUtils.hasText} 判断而不自己 {@code trim()}：
     * {@code trim()} 会吃掉码点 ≤ U+0020 的控制字符（项目里已记过这个坑），
     * 而 {@code hasText} 把"全空白"也算作空，正好是要的语义。
     * 存库时用<b>原文</b>不改写 —— 审核意见是给人看的凭证，不该由程序悄悄改字。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, ArticleAuditDTO dto, Long operatorId) {
        // ① 查文章（不存在 → 11001）
        CmsArticle article = requireArticle(id);
        requireOperator(operatorId);

        // ② 入参值域：auditAction 必须是 1 或 2。
        //    @NotNull 只挡"没传"，挡不住"传了 3 / 999"，这里补第二道。
        //    用 AuditResultEnum.isValid 而不是 result == 1 || result == 2：
        //    值域只有一处定义（枚举），将来加"转办"之类的新结论也只改枚举。
        if (dto == null || !AuditResultEnum.isValid(dto.getAuditAction())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "审核结论只能是 1（通过）或 2（驳回）");
        }

        // ③ 由当前状态推环节；状态不在审核环节 → 11002（终态 → 11011）
        AuditStageEnum stage = resolveAuditStage(article);

        // ④ 业务级越权：作者不能审自己的稿 → 11004
        checkNotSelfAudit(article, operatorId);

        // ⑤ 驳回必填意见 → 11014（放在动数据之前）
        boolean reject = AuditResultEnum.isReject(dto.getAuditAction());
        if (reject && !StringUtils.hasText(dto.getAuditOpinion())) {
            throw new BusinessException(ErrorCode.ARTICLE_AUDIT_OPINION_REQUIRED);
        }

        // ⑥ 状态推进走状态机。这里用 transfer 而不是 assertTransfer 的理由同 submitForAudit：
        //    transfer 能区分 11001 / 11011 / 11002，提示指向真问题。
        //    ⚠️ fromStatus 必须在 setStatus 之前取，否则日志会打成"目标状态 → 目标状态"。
        ArticleStatus fromStatus = ArticleStatus.ofNullable(article.getStatus());
        ArticleAction action = reject ? ArticleAction.AUDIT_REJECT : ArticleAction.AUDIT_PASS;
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), action);

        // ⑦ 改状态（乐观锁）。这里用 updateById 是安全的，与 updateDraft 的情形不同：
        //    updateById 的已知缺陷是"跳过 null 字段"，而 status 一定非 null，
        //    所以不存在"想清空某个字段却写不进去"的问题。
        //    并发两个审核请求时，第二个会因 version 不匹配返回 0 → 11010。
        article.setStatus(target.getCode());
        if (baseMapper.updateById(article) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        // ⑧ 写审核流水。放最后：它失败时状态会被同一事务回滚（见方法注释）。
        insertAuditLog(article, stage, operatorId, dto.getAuditAction(), dto.getAuditOpinion());

        String statusPath = (fromStatus == null ? "未知状态" : fromStatus.name()) + " → " + target.name();
        log.info("审核完成 | articleId={} | 环节={} | 结论={} | {} | 审核人={}",
                id, stage.getDescription(),
                AuditResultEnum.getDesc(dto.getAuditAction()), statusPath, operatorId);
    }

    // ==================== 六、详情与审核流水查询（S11） ====================

    /**
     * 文章详情：基本信息 + 正文 + 作者名 + 分类名 + 审核流水 + 最近驳回意见。
     *
     * <h3>SQL 只用 4 条（"3~4 条"这条核查项的落实方式）</h3>
     * <pre>
     *   ① SELECT ... FROM cms_article            WHERE id = ?                  （requireArticle）
     *   ② SELECT ... FROM cms_article_audit_log  WHERE article_id = ?          （查流水）
     *   ③ SELECT ... FROM sys_user              WHERE id IN (作者 ∪ 审核人)     （一次批量，去重）
     *   ④ SELECT ... FROM cms_category          WHERE id = ?                   （分类名）
     * </pre>
     * <p>关键在 ③：<b>把"作者"和"流水里的审核人"合并成一次 {@code IN} 查询</b>。
     * 若分成两次（作者一次、审核人一次），就是 5 条；
     * 若在循环里逐条查审核人，就是 1 + N 条。
     * <p>条数与文章长度、审核次数<b>无关</b>（审核人先去重再查），
     * 这才是"没有 N+1"的判据 —— 光看"这次跑了 4 条"不足以证明，
     * 要看"流水 0 条和 5 条时 SQL 条数一样"。
     *
     * <h3>⚠️ 实测修正：常态就是 4 条，不是 3 条</h3>
     * <p>本方法的第一版注释写过"草稿没流水时只有 3 条"——<b>这是错的</b>，
     * 真机实测（S11 联调，读 {@code cms.log} 的 {@code Preparing:}）：
     * 空流水草稿也是 <b>4</b> 条。原因是 ③ 的集合里<b>永远至少有"作者"</b>
     * （{@code authorId} 非空），所以 {@code sys_user} 那条查询不会被省掉；
     * 而作者名正是详情页要显示的东西，省掉反而是错的。
     * <p>真正会出现 3 条的唯一情形是 ④ 也省掉，即 {@code categoryId = null}
     * （此时 {@link #selectCategoryName} 直接返回 null 不发 SQL）。
     * <p>结论：<b>把"4 条"当常数来断言</b>，3 条只是 {@code categoryId} 缺失时的特例。
     */
    @Override
    public CmsArticleDetailVO getDetail(Long id) {
        // ① 文章本身（不存在 → 11001）
        CmsArticle article = requireArticle(id);

        CmsArticleDetailVO vo = new CmsArticleDetailVO();
        copyBaseFields(article, vo);
        vo.setContent(article.getContent());

        // ② 流水
        List<CmsArticleAuditLog> rows = selectAuditLogs(id);

        // ③ 作者 + 审核人，合并成一次 IN 查询
        Set<Long> userIds = new LinkedHashSet<>();
        if (article.getAuthorId() != null) {
            userIds.add(article.getAuthorId());
        }
        for (CmsArticleAuditLog row : rows) {
            if (row.getAuditorId() != null) {
                userIds.add(row.getAuditorId());
            }
        }
        Map<Long, String> nicknames = selectNicknames(userIds);

        vo.setAuthorName(article.getAuthorId() == null ? null : nicknames.get(article.getAuthorId()));
        vo.setAuditLogs(toAuditLogVOs(rows, nicknames));
        vo.setRejectReason(lastRejectOpinion(vo.getAuditLogs()));

        // ④ 分类名（cms_article 里只有 category_id）
        vo.setCategoryName(selectCategoryName(article.getCategoryId()));

        log.debug("查询文章详情 | articleId={} | 流水 {} 条 | 关联用户 {} 个",
                id, rows.size(), nicknames.size());
        return vo;
    }

    /**
     * 只查审核流水（供审核工作台单独刷新）。
     *
     * <p>SQL 3 条：① 文章是否存在（不存在要明确 11001，不能返回空列表）
     * ② 流水 ③ 审核人昵称批量。
     */
    @Override
    public List<ArticleAuditLogVO> listAuditLogs(Long id) {
        // 这一次查询刻意保留：否则对不存在的 id 会返回"空流水"，
        // 前端会显示"文章不存在但审核历史为空"这种自相矛盾的页面。
        requireArticle(id);

        List<CmsArticleAuditLog> rows = selectAuditLogs(id);
        Set<Long> auditorIds = new LinkedHashSet<>();
        for (CmsArticleAuditLog row : rows) {
            if (row.getAuditorId() != null) {
                auditorIds.add(row.getAuditorId());
            }
        }
        return toAuditLogVOs(rows, selectNicknames(auditorIds));
    }

    // ==================== 七、发布与排期发布（S13） ====================

    /**
     * 立即发布：{@code APPROVED / WITHDRAWN → PUBLISHED}。
     *
     * <h3>为什么这里<b>没有</b> {@code checkOwnership}</h3>
     * <p>这是本方法与 {@code updateDraft / submitForAudit} 的<b>有意差别</b>，
     * 不是漏写。发布是主编的职能：{@code article:publish} 只授予
     * {@code ROLE_CHIEF(12)} 与 {@code ROLE_ADMIN(20)}，
     * 而 {@code article:update} 连 {@code ROLE_AUTHOR(7)} 都有。
     * <p>若在这里加归属校验，主编就永远发布不了作者提交的稿 ——
     * 整个"作者写稿 → 编辑初审 → 主编终审 → 发布"链路会断在最后一步，
     * 而这最后一步恰恰是主编干的。
     *
     * <h3>四件事的顺序（每一步都有理由）</h3>
     * <ol>
     *   <li>查文章（不存在 → 11001）</li>
     *   <li>校验操作人非空（定时任务等无 HTTP 上下文的入口可能传 null）</li>
     *   <li>状态机判可否发布（{@code DRAFT} → 11002；已归档 → 11011）</li>
     *   <li>带乐观锁地更新"状态 + 发布人 + 发布时间 + 清空排期时间"</li>
     * </ol>
     * <p>⚠️ 注意第 3 步排在第 2 步之后：{@code transfer()} 在 status 为 null 时抛 11001，
     * 若先跑状态机、再校验操作人，会出现"操作人为 null 但报的是文章不存在"的错位提示。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishNow(Long id, Long operatorId) {
        CmsArticle article = requireArticle(id);
        requireOperator(operatorId);

        // 原状态在 setStatus 之前取出，只用于日志（否则会打成"已发布 → 已发布"）
        ArticleStatus fromStatus = ArticleStatus.ofNullable(article.getStatus());

        // 状态推进只走状态机（唯一入口）。PUBLISH_NOW 的合法起点是 APPROVED(3) 与
        // WITHDRAWN(7)，其余一律 11002（终态 ARCHIVED → 11011）。
        // 顺带说明一个易被误认为"能发布"的状态：OFFLINE(8) 只有 ARCHIVE 一条出边，
        // 用 PUBLISH_NOW 打它会得到 11002 —— 这是状态机的既定行为，不是 bug
        // （手册 F06「已下架 → 重新发布」的说法与状态机冲突，已登记）。
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), ArticleAction.PUBLISH_NOW);

        LocalDateTime now = LocalDateTime.now();

        // ⚠️ 与 updateDraft 同理，这里不能用 updateById：
        //    ① schedule_time 要写 NULL，而 updateById 的 NOT_NULL 策略会把 null 跳过
        //       → "清空排期时间"这个要求会静默失效（返 200 但库里没清）。
        //    ② 显式列列还能让"这次更新动了哪些列"一眼可见。
        // 乐观锁：carrier 里带着查出来的 version，插件会追加
        //    SET version = version + 1 ... AND version = 旧值
        //    → 并发第二个请求更新 0 行 → 11010。
        LambdaUpdateWrapper<CmsArticle> wrapper = Wrappers.<CmsArticle>lambdaUpdate()
                .eq(CmsArticle::getId, article.getId())
                .set(CmsArticle::getStatus, target.getCode())
                .set(CmsArticle::getPublisherId, operatorId)
                .set(CmsArticle::getPublishTime, now)
                // 立即发布 = 把"将来要发"的约定作废，必须清掉排期时间（见接口注释）
                .set(CmsArticle::getScheduleTime, null);

        if (baseMapper.update(optimisticLockCarrier(article), wrapper) == 0) {
            // 文章在上面的 selectById 里刚查到过，所以 0 行只可能是 version 被别人改过
            // （典型场景：两个主编同时点发布，或自己双击）
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        // 不写审核流水（原因见接口注释）：发布不是审核动作，
        // 枚举判据是 ArticleAction#isAuditAction()。
        // 操作留痕由 Controller 上的 @OperLog 落到 sys_oper_log。
        String statusPath = (fromStatus == null ? "未知状态" : fromStatus.name()) + " → " + target.name();
        log.info("立即发布成功 | articleId={} | 发布人={} | 发布时间={} | {}",
                id, operatorId, now.format(DISPLAY_TIME_FORMATTER), statusPath);
    }

    /**
     * 排期发布：{@code APPROVED → SCHEDULED}。
     *
     * <h3>⚠️ "必须晚于当前时间至少 1 分钟"用的是<b>同一个 now</b></h3>
     * <p>方法进来先取一次 {@code now}，判断与写库都用它。
     * 若判断时取一次、写库时再取一次，两次之间哪怕差几毫秒，
     * 边界上也会出现"校验时还没到、写库时已经到了"的抖动，
     * 表现为<b>偶发</b>的、极难复现的边界失败。
     * <p>为什么要求"至少 1 分钟"而不是"大于当前时间"：
     * 定时任务每分钟扫一次，若允许"排到 3 秒后"，用户会看到"排期成功"但
     * 这篇文章可能要到下一分钟才被扫到，或干脆因为扫描刚好错过而看起来"没生效"。
     * 留 1 分钟余量让"排期成功 → 到点发布"这个预期一定是可兑现的。
     *
     * <h3>为什么把 publish_time / publisher_id 显式写回 NULL</h3>
     * <p>排期只是"约定将来发"，此刻文章<b>还没发布</b>。
     * 这两个列在库表上都可空，语义分别是"实际发布时间"与"发布人"，
     * 在 {@code SCHEDULED} 状态下填着旧值就是脏数据（列表页会显示"排期待发 + 发布时间=昨天"）。
     * <p>⚠️ 这两个 NULL 同样只能靠显式 {@code set} 写进去（同 {@code publishNow}）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishSchedule(Long id, CmsArticlePublishDTO dto, Long operatorId) {
        CmsArticle article = requireArticle(id);
        requireOperator(operatorId);

        // DTO 上的 @NotNull 是第一道；Service 被非 HTTP 入口调用时可能绕过它，这里补第二道。
        // 错误码用 PARAM_ERROR 而不是 11007：11007 的语义是"排期时间非法"，
        // 而"根本没传"属于请求不完整，两者给用户的下一步动作不同。
        if (dto == null || dto.getScheduleTime() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "排期时间不能为空");
        }

        ArticleStatus fromStatus = ArticleStatus.ofNullable(article.getStatus());
        // PUBLISH_SCHEDULE 的唯一合法起点是 APPROVED(3)：
        // 直接对 DRAFT / PENDING_* / PUBLISHED 排期 → 11002。
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), ArticleAction.PUBLISH_SCHEDULE);

        // 时间基准只取一次，校验与写库共用（见方法注释）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scheduleTime = dto.getScheduleTime();
        if (scheduleTime.isBefore(now.plusMinutes(MIN_SCHEDULE_AHEAD_MINUTES))) {
            throw new BusinessException(ErrorCode.ARTICLE_SCHEDULE_TIME_INVALID,
                    String.format("排期时间必须晚于当前时间至少 %d 分钟（当前时间 %s，传入 %s）",
                            MIN_SCHEDULE_AHEAD_MINUTES,
                            now.format(DISPLAY_TIME_FORMATTER),
                            scheduleTime.format(DISPLAY_TIME_FORMATTER)));
        }

        LambdaUpdateWrapper<CmsArticle> wrapper = Wrappers.<CmsArticle>lambdaUpdate()
                .eq(CmsArticle::getId, article.getId())
                .set(CmsArticle::getStatus, target.getCode())
                .set(CmsArticle::getScheduleTime, scheduleTime)
                // 尚未发布 → 两个"发布痕迹"列显式清空（见方法注释）
                .set(CmsArticle::getPublishTime, null)
                .set(CmsArticle::getPublisherId, null);

        if (baseMapper.update(optimisticLockCarrier(article), wrapper) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        String statusPath = (fromStatus == null ? "未知状态" : fromStatus.name()) + " → " + target.name();
        log.info("排期发布成功 | articleId={} | 排期人={} | 排期时间={} | {}",
                id, operatorId, scheduleTime.format(DISPLAY_TIME_FORMATTER), statusPath);
    }

    /**
     * 查出"已到点该发布"的文章 ID（定时任务的扫描入口）。
     *
     * <h3>查询条件与索引的对应关系（这条是"用上索引"的判据）</h3>
     * <pre>
     *   WHERE status = ? AND schedule_time &lt;= ? ORDER BY schedule_time, id
     *   索引  idx_status_schedule_time (status, schedule_time)
     * </pre>
     * <p>等值列 {@code status} 在前、范围列 {@code schedule_time} 在后，
     * 与索引的列顺序完全一致，这正是复合索引"能走到范围扫描"的形态
     * （反过来写 {@code (schedule_time, status)} 就只能用到范围列，等值列退化为回表过滤）。
     * <p>⚠️ 判断标准不是"代码看起来对"，而是 {@code EXPLAIN} 的 {@code key} 列
     * 是否等于 {@code idx_status_schedule_time}。真机验证时以执行计划为准。
     * <p>{@code LIMIT} 是<b>常量</b>（不做字符串拼接用户输入），
     * 作用是把一次扫描的工作量封顶，防止异常积压把单次扫描拖死。
     * <p>⚠️ {@code deleted = 0} 由 {@code @TableLogic} 自动追加 ——
     * 因为这是 MP 生成的 {@code selectList}，不是手写 XML
     * （手写 XML 才需要自己写这个条件，项目里已记过这条坑）。
     */
    @Override
    public List<Long> findDueScheduledArticleIds(LocalDateTime deadline) {
        if (deadline == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "扫描时刻不能为空");
        }
        List<CmsArticle> rows = baseMapper.selectList(
                Wrappers.<CmsArticle>lambdaQuery()
                        .select(CmsArticle::getId)
                        .eq(CmsArticle::getStatus, ArticleStatus.SCHEDULED.getCode())
                        .le(CmsArticle::getScheduleTime, deadline)
                        .orderByAsc(CmsArticle::getScheduleTime)
                        .orderByAsc(CmsArticle::getId)
                        .last("LIMIT " + SCAN_BATCH_LIMIT));

        List<Long> ids = new ArrayList<>(rows.size());
        for (CmsArticle row : rows) {
            ids.add(row.getId());
        }
        return ids;
    }

    /**
     * 定时任务发起的单篇发布：{@code SCHEDULED → PUBLISHED}。
     *
     * <h3>与 {@code publishNow} 的三点不同（都是刻意的）</h3>
     * <ol>
     *   <li><b>没有 operatorId 入参</b>。定时任务没有 HTTP 请求、没有登录用户，
     *       硬造一个 {@code operatorId} 传进来只会让"谁发布的"更假。
     *       审计归属由 {@code AuditMetaObjectHandler} 处理 ——
     *       无 HTTP 上下文时 {@code update_by / create_by} 自动记
     *       {@code SYSTEM_USER_ID(0)}，语义就是"系统操作"，这条口径早就在主干里定好了。</li>
     *   <li><b>不抛业务异常，返回 boolean</b>。原因见接口注释：
     *       扫描每分钟一次、天然幂等，"状态已经变了/文章被删了"这类情况用异常表达
     *       会把错误日志淹掉。</li>
     *   <li><b>自己先做一次廉价的状态预检</b>，再走状态机。
     *       {@code transfer} 对"不在 SCHEDULED"会抛 11002，而这里期望的是"安静跳过"
     *       —— 所以在调用前先用 if 判断，避免用 try/catch 干正常流程的事
     *       （用异常做控制流会掩盖真正的异常）。</li>
     * </ol>
     *
     * <h3>⚠️ publisher_id 记的是 {@code SYSTEM_USER_ID(0)}，为什么不是原作者</h3>
     * <p>手册要求"记为系统用户或原提交人，并说明选了哪个"。
     * 这里选<b>系统用户 0</b>，理由是<b>事实准确性</b>：
     * 真正执行"发布"这个动作的不是人，是定时任务；
     * 把它记成作者等于说"这篇是作者发布的"，而作者可能只是当初投了稿。
     * <p>选 0 还带来口径统一：主干里 {@code 0 = 系统操作} 这层语义
     * 已经由 {@code AuditMetaObjectHandler#SYSTEM_USER_ID} 定义，
     * 定时任务写入时 {@code update_by} 本来就会是 0，
     * 两个字段指向同一个结论，排查时不出现"审计说是系统、业务字段说是张三"的矛盾。
     * <p>补充两点影响面（避免有人以为 0 会炸）：
     * ① 详情接口的 {@code sys_user} 批查集合只含 {@code 作者 ∪ 审核人}，
     * 不含 {@code publisher_id}，所以不会出现"0 查不到昵称"的问题；
     * ② 前端 {@code ArticleDetail.vue} 显示的 {@code publisherName} 目前恒为
     * {@code '-'}（VO 里没这个字段），本改动不会让页面变得更难看。
     * <p>若组内更看重"列表页要显示发布人=作者"，改动只有一行
     * （把下面的 {@code SYSTEM_USER_ID} 换成 {@code article.getAuthorId()}），
     * 但请连同"事实准确性"的取舍一起在文档里登记。
     *
     * <h3>事务边界</h3>
     * <p>本方法带 {@code @Transactional}，一次调用一个事务。
     * 但<b>它必须是"经代理调用"才会生效</b>：
     * {@code ArticlePublishScheduler} 注入的是 {@code ArticleService} 接口的代理，
     * 所以事务生效；若将来有人在同类内部直接 {@code this.publishScheduled(id)}，
     * 事务会静默失效（项目里已踩过 {@code @Transactional} 自调用这个坑）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publishScheduled(Long id) {
        // ① 预检：文章还在吗（可能刚被删 / 被逻辑删除）
        CmsArticle article = baseMapper.selectById(id);
        if (article == null) {
            log.warn("定时发布跳过：文章不存在或已被删除 | articleId={}", id);
            return false;
        }

        // ② 预检：状态还是 SCHEDULED 吗（可能刚被人工改掉）
        //
        // ⚠️ 这里刻意用"枚举比较"而不是 `statusCode != ArticleStatus.SCHEDULED.getCode()`：
        //    getStatus() 与 getCode() 返回的都是**包装类型 Integer**，
        //    两个 Integer 用 != 比的是**引用**而不是值。
        //    它在当前代码下"恰好"是对的（-128~127 有 Integer 缓存，6 会被复用同一个实例），
        //    这正是最危险的地方 —— 一旦状态码取值超出缓存范围（将来加更多状态），
        //    比较就会静默变成"永远不相等"，任务从此不再发布。
        //    用 ofNullable 转成枚举比较，既避免装箱陷阱，也顺手处理了 status 为 null 的脏数据。
        ArticleStatus current = ArticleStatus.ofNullable(article.getStatus());
        if (current != ArticleStatus.SCHEDULED) {
            log.warn("定时发布跳过：当前状态不是定时待发 | articleId={} | status={}",
                    id, article.getStatus());
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        // ③ 预检：到点了吗（可能刚被人工把排期时间改到将来）
        if (article.getScheduleTime() == null || article.getScheduleTime().isAfter(now)) {
            log.warn("定时发布跳过：未到排期时间 | articleId={} | scheduleTime={} | now={}",
                    id, article.getScheduleTime().format(DISPLAY_TIME_FORMATTER),
                    now.format(DISPLAY_TIME_FORMATTER));
            return false;
        }

        // ④ 状态推进走状态机（SCHEDULED 唯一的一条出边）
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(),
                ArticleAction.PUBLISH_SCHEDULED_TRIGGER);

        LambdaUpdateWrapper<CmsArticle> wrapper = Wrappers.<CmsArticle>lambdaUpdate()
                .eq(CmsArticle::getId, article.getId())
                .set(CmsArticle::getStatus, target.getCode())
                .set(CmsArticle::getPublisherId, SYSTEM_USER_ID)
                // ✅ publish_time 是"实际发布时间" → 填 now，不是排期时间。
                //    两者通常差几秒（扫描是每分钟一次），排期时间代表"约定"，
                //    这个列代表"事实"，所以取 now。
                //    核查项里"publishTime 是实际发布时间"考的就是这一点：
                //    若误填 article.getScheduleTime()，与约定时间永远相等，看着"很对"但语义是错的。
                .set(CmsArticle::getPublishTime, now)
                // 发布完成后排期时间使命结束，清空（与 publishNow 同一理由）
                .set(CmsArticle::getScheduleTime, null);

        if (baseMapper.update(optimisticLockCarrier(article), wrapper) == 0) {
            // 被人工并发改过。不抛异常：文章仍是 SCHEDULED 且已到点，
            // 下一分钟的扫描会重新处理它；抛异常只会刷一屏错误日志。
            log.warn("定时发布跳过：乐观锁冲突（文章刚被他人修改）| articleId={}", id);
            return false;
        }

        log.info("排期到点自动发布成功 | articleId={} | 排期时间={} | 实际发布时间={} | SCHEDULED → {}",
                id, article.getScheduleTime().format(DISPLAY_TIME_FORMATTER),
                now.format(DISPLAY_TIME_FORMATTER), target.name());
        return true;
    }

    // ==================== 八、撤回 / 下架 / 归档（S14） ====================

    /**
     * 撤回：{@code PUBLISHED → WITHDRAWN}。
     *
     * <p>本方法与下架、归档共用 {@link #changeStatusByAction}，只有两个参数不同：
     * 动作是 {@code WITHDRAW}，且<b>要</b>校验归属（第四参传 {@code true}）。
     * 为什么要校验归属、以及它与下架/归档的差别，见
     * {@link ArticleService#withdraw} 的对比表。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id, Long operatorId) {
        changeStatusByAction(id, operatorId, ArticleAction.WITHDRAW, true);
    }

    /**
     * 下架：{@code PUBLISHED → OFFLINE}。
     *
     * <h3>⚠️ 这里的非空校验是<b>第二道</b>，不是重复劳动</h3>
     * <p>第一道在 {@code CmsArticleOfflineDTO.reason} 的 {@code @NotBlank} 上，
     * 只有走 HTTP 入口（{@code @Valid} 生效）时才会执行。
     * Service 可能被别的入口调用（批处理、别的 Service、测试），
     * 那时 {@code @Valid} 根本不在链路上 —— 所以在这里再判一次。
     * <p>用 {@code StringUtils.hasText} 而不是 {@code !"".equals(reason)}：
     * 后者放行 {@code "   "}（三个空格），等于允许"填了个看不见的原因"。
     * 这与 {@link #requireSubmittable} 的判据一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long id, Long operatorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "下架原因不能为空");
        }

        changeStatusByAction(id, operatorId, ArticleAction.OFFLINE, false);

        // 原因的主持久化通道是 @OperLog → sys_oper_log.request_param（见接口注释的取舍说明）。
        // 这里额外打一条 WARN 级业务日志，是为了在不查库的情况下也能从
        // <Tomcat>/bin/logs/cms-business.log 里直接 grep 到"哪篇因为什么被下了"。
        // 用 WARN 而不是 INFO：下架是运营对外的破坏性动作，值得在日志里显眼一点。
        log.warn("文章下架 | articleId={} | 操作人={} | 原因={}", id, operatorId, reason);
    }

    /**
     * 归档：{@code WITHDRAWN / OFFLINE → ARCHIVED}（终态）。
     *
     * <p>"已归档不可再操作"的第一层保证在状态机（{@code TRANSITIONS} 没有以
     * {@code ARCHIVED} 为源的行，且 {@code transfer} 对终态专门抛 11011），
     * 第二层在 {@link #changeStatusByAction} 开头那句显式判断。
     * 为什么明知冗余还要写第二层，见 {@link ArticleService#archive}。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long id, Long operatorId) {
        changeStatusByAction(id, operatorId, ArticleAction.ARCHIVE, false);

        // 归档后要顺带让检索索引失效，但索引表由成员D 维护 → 本步只留调用点（见方法注释）
        markSearchIndexInvalidOnArchive(id);
    }

    /**
     * 撤回 / 下架 / 归档的<b>公共骨架</b>（三个动作只差"动作枚举"与"是否校验归属"）。
     *
     * <h3>⚠️ 为什么必须抽出来，而不是复制三遍</h3>
     * <p>这五步在三个动作里完全相同：查文章 → 校验操作人 → （可能要）校验归属 →
     * 状态机判合法 → 带乐观锁改状态。复制三遍的代价不是"多写了几十行"，
     * 而是<b>将来加一条规则时会漏改两处</b>——比如某天要给"状态变更"加一条
     * "文章存在未处理的举报时禁止操作"，三个入口漏掉一个就是一个后门。
     * <p>这类"同一规则散在多处"是本项目最忌讳的形态，所以宁可多一个参数。
     *
     * <h3>⚠️ 参数 {@code requireOwnership} 而不是在方法里按动作 {@code if} 判断</h3>
     * <p>写成 {@code if (action == WITHDRAW) checkOwnership(...)} 也能跑，
     * 但那样"哪些动作要校验归属"就<b>藏在骨架里</b>，读 {@code withdraw()} 的人
     * 看不出它跟归属校验有关。做成显式参数后，
     * 每个公开方法自己声明"我要不要归属校验"，一眼可见（三个入口一行一个，谁是什么一目了然）。
     *
     * <h3>为什么不顺手清空 {@code publish_time} / {@code publisher_id}</h3>
     * <p>撤回/下架都发生在"已经发布过"的文章上，那两个列记的是一次<b>已经发生的事实</b>
     * （"这篇文章在 X 时刻被 Y 发布过"）。把它们清掉等于篡改历史，
     * 事后追责时看不到"它曾经上线过"。这与 S13 里"立即发布必须清空 {@code schedule_time}"
     * 的情形不同 —— 后者清的是<b>对未来的约定</b>（约定作废了就该清），
     * 前者是<b>对过去的记录</b>（发生过就留着）。
     *
     * <h3>状态码为 null 的脏数据</h3>
     * <p>不做单独处理，让它走 {@code transfer} → 抛 {@code ARTICLE_NOT_FOUND(11001)}，
     * 与 {@link #publishNow} / {@link #publishSchedule} 的处理一致。
     * （真正想表达的是"状态异常"，但三处改口径要一起改，不在本步范围内。）
     *
     * @param id               文章 ID
     * @param operatorId       操作人 ID
     * @param action           动作（WITHDRAW / OFFLINE / ARCHIVE）
     * @param requireOwnership 是否要求"操作人必须是作者"（撤回 true；下架/归档 false）
     */
    private void changeStatusByAction(Long id, Long operatorId, ArticleAction action,
                                      boolean requireOwnership) {
        CmsArticle article = requireArticle(id);
        requireOperator(operatorId);

        if (requireOwnership) {
            checkOwnership(article, operatorId);
        }

        // 原状态在 update 之前取出，只用于日志（否则会打成"已归档 → 已归档"）
        ArticleStatus fromStatus = ArticleStatus.ofNullable(article.getStatus());

        // ★ 第二层"终态只读"保险（第一层在状态机里，见方法注释）。
        //   ⚠️ 手册这里写的是抛 ARTICLE_ARCHIVED(11010)，本项目没有这个码名：
        //   11010 是 ARTICLE_UPDATE_CONFLICT（乐观锁冲突），归档只读是 11011。
        //   属手册错误码错位区，以 ErrorCode 为准。
        if (fromStatus == ArticleStatus.ARCHIVED) {
            log.warn("归档文章被再次操作，已拦截 | articleId={} | 动作={} | 操作人={}",
                    id, action.name(), operatorId);
            throw new BusinessException(ErrorCode.ARTICLE_ARCHIVED_READONLY);
        }

        // 状态推进只走状态机（唯一入口）。非法组合一律 11002，文案带当前状态与动作，
        // 前端直接 toast 就是一句人话。
        ArticleStatus target = ArticleStateMachine.transfer(article.getStatus(), action);

        LambdaUpdateWrapper<CmsArticle> wrapper = Wrappers.<CmsArticle>lambdaUpdate()
                .eq(CmsArticle::getId, article.getId())
                .set(CmsArticle::getStatus, target.getCode());

        if (baseMapper.update(optimisticLockCarrier(article), wrapper) == 0) {
            // 刚 selectById 查到过，0 行只可能是 version 被并发改过（典型：同一篇文章
            // 在两个浏览器里同时点撤回/归档，或本人双击）
            throw new BusinessException(ErrorCode.ARTICLE_UPDATE_CONFLICT);
        }

        log.info("文章状态变更成功 | articleId={} | 操作人={} | 动作={} | {} → {}",
                id, operatorId, action.name(),
                fromStatus == null ? "未知状态" : fromStatus.name(), target.name());
    }

    /**
     * 归档后的"检索索引失效"调用点。
     *
     * TODO(S14 → 成员D)：{@code cms_article_index} 里这篇文章的索引应当失效
     *   （{@code index_status = 0}，或按业务约定逻辑删除该行），
     *   否则读者端的标题检索仍可能命中一篇已经归档、本不该再暴露的文章。
     *
     * <p>⚠️ <b>本步只留调用点，不实现</b>：{@code cms_article_index} 由成员D 主责维护，
     * 索引的写入/失效/重建口径都在他那边（S16 的跨域联调要接的正是这条链路）。
     * 本模块擅自在事务里改别人的表，会带来两个问题：
     * ① 事务边界被拉长到跨模块（成员D 若在新事务里做索引同步，本事务回滚时那部分不回滚）；
     * ② 出问题时排查要跨两个人的代码。
     * <p>留成"一个会打日志的空方法"而不是纯注释，是因为<b>调用点本身是有价值的</b>：
     * 成员D 接入时只要把这里换成真正的调用即可，
     * 不需要再去 {@code archive()} 里找"该在哪一行插"。
     */
    private void markSearchIndexInvalidOnArchive(Long articleId) {
        log.info("TODO(检索索引)：文章已归档，索引失效待成员D 的同步逻辑接管 | articleId={}", articleId);
    }

    /**
     * 查某篇文章的全部审核流水，<b>按 {@code audit_time} 正序</b>。
     *
     * <h3>⚠️ 为什么必须加第二排序键 {@code id}</h3>
     * <p>库表 {@code audit_time} 是 {@code DATETIME}（秒精度，没有小数位）。
     * 主链路自测里"提交 → 初审 → 终审"往往发生在<b>同一秒内</b>，
     * 只按 {@code audit_time} 排序时这几条的时间完全相同，
     * MySQL 返回的先后顺序<b>不确定</b>（取决于执行计划与物理存储顺序）。
     * 于是"第一条是不是初审"这类断言会偶发失败 —— 最难查的一类问题。
     * <p>加 {@code id} 作为第二键（{@code id} 自增，天然等于插入顺序）后顺序就确定了。
     * 这里没有用"把 audit_time 改成毫秒精度"的方案，因为那要改库表，
     * 而"自增主键就是发生顺序"是个更朴素可靠的保证。
     */
    private List<CmsArticleAuditLog> selectAuditLogs(Long articleId) {
        return auditLogMapper.selectList(
                Wrappers.<CmsArticleAuditLog>lambdaQuery()
                        .eq(CmsArticleAuditLog::getArticleId, articleId)
                        .orderByAsc(CmsArticleAuditLog::getAuditTime)
                        .orderByAsc(CmsArticleAuditLog::getId));
    }

    /**
     * 一次 {@code IN} 查询批量取昵称，返回 {@code id → nickname}。
     *
     * <p>⚠️ 空集合必须<b>提前返回</b>：{@code selectBatchIds(emptyCollection)} 会生成
     * {@code WHERE id IN ()}，这在 MySQL 里是<b>语法错误</b>（不是"查不到"）。
     * 这是个很容易漏的边界 —— 草稿文章没有任何审核流水时正好会走到这里。
     *
     * <p>返回空 Map 而不是 null，调用方直接 {@code get(id)} 即可，不用判空。
     */
    private Map<Long, String> selectNicknames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> users = userMapper.selectBatchIds(userIds);
        Map<Long, String> map = new HashMap<>(Math.max(4, users.size() * 2));
        for (SysUser user : users) {
            // ⚠️ 这里顺手做了逻辑删除过滤以外的兜底：selectBatchIds 会自动加 deleted = 0，
            // 所以已删除的用户查不出来，nickname 就是 null。刻意不编造"已注销用户"，
            // 让数据问题显式暴露（前端模板已有 `|| '—'` 兜底）。
            map.put(user.getId(), user.getNickname());
        }
        return map;
    }

    /** 取分类名；{@code categoryId} 为空或分类不存在时返回 null。 */
    private String selectCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        CmsCategory category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getCategoryName();
    }

    /**
     * 流水实体 → VO（含中文文案）。
     *
     * <p>{@code auditStageDesc} / {@code resultDesc} 在这里由枚举填好，
     * 而不是丢给前端映射：枚举里的文案是唯一的真相源，
     * 前端再造一份就必然出现"后端改了文案前端没跟上"。
     * <p>枚举的 {@code getDesc} 对未知码返回"未知环节/未知结论"而不是抛异常 ——
     * 因为库里可能有人手工改过数据，<b>一条脏流水不该让整个详情接口 500</b>。
     */
    private List<ArticleAuditLogVO> toAuditLogVOs(List<CmsArticleAuditLog> rows,
                                                  Map<Long, String> nicknames) {
        if (rows == null || rows.isEmpty()) {
            // 空列表而非 null：前端不用写 `|| []` 兜底
            return new ArrayList<>();
        }
        List<ArticleAuditLogVO> list = new ArrayList<>(rows.size());
        for (CmsArticleAuditLog row : rows) {
            ArticleAuditLogVO vo = new ArticleAuditLogVO();
            vo.setId(row.getId());
            vo.setAuditStage(row.getAuditStage());
            vo.setAuditStageDesc(AuditStageEnum.getDesc(row.getAuditStage()));
            vo.setAuditorId(row.getAuditorId());
            vo.setAuditorName(row.getAuditorId() == null ? null : nicknames.get(row.getAuditorId()));
            vo.setResult(row.getResult());
            vo.setResultDesc(AuditResultEnum.getDesc(row.getResult()));
            vo.setOpinion(row.getOpinion());
            vo.setAuditTime(row.getAuditTime());
            vo.setArticleVersion(row.getArticleVersion());
            list.add(vo);
        }
        return list;
    }

    /**
     * 最近一次驳回意见：从正序流水里<b>从后往前</b>找第一条 {@code result = 2} 的意见。
     *
     * <p>⚠️ 不是"最后一条流水的意见" —— 终审通过之后最后一条是"同意发布"，
     * 拿它当驳回意见展示就错了。
     * <p>⚠️ 也不判断"这条驳回是否已被后续提交覆盖"：前端只在
     * {@code status = REJECTED} 时展示 {@code rejectReason}，
     * 而已驳回状态下最后一条驳回意见必然就是当前要改的原因。
     * 多写一层判断只会多一个出错的地方。
     */
    private String lastRejectOpinion(List<ArticleAuditLogVO> logs) {
        for (int i = logs.size() - 1; i >= 0; i--) {
            if (AuditResultEnum.isReject(logs.get(i).getResult())) {
                return logs.get(i).getOpinion();
            }
        }
        return null;
    }

    /**
     * 实体 → VO 的公共字段搬运（详情 VO 与将来的列表 VO 共用）。
     *
     * <p>{@code statusDesc} / {@code statusColor} 由枚举填：
     * 库里若有 0~9 之外的脏值，{@code ofNullable} 返回 null，
     * 这里给"未知状态" + {@code info} 兜底，<b>不让一条脏数据把整个详情 500</b>。
     * <p>⚠️ 与 {@code CmsArticleVO} 的关系：详情 VO 继承它，
     * 所以这里参数收的是父类型，两个 VO 都能用。
     */
    private void copyBaseFields(CmsArticle src, CmsArticleVO target) {
        ArticleStatus status = ArticleStatus.ofNullable(src.getStatus());
        target.setId(src.getId());
        target.setTitle(src.getTitle());
        target.setSummary(src.getSummary());
        target.setCoverUrl(src.getCoverUrl());
        target.setCategoryId(src.getCategoryId());
        target.setStatus(src.getStatus());
        target.setStatusDesc(status == null ? "未知状态" : status.getDescription());
        target.setStatusColor(status == null ? "info" : status.getColor());
        target.setAuthorId(src.getAuthorId());
        target.setPublisherId(src.getPublisherId());
        target.setPublishTime(src.getPublishTime());
        target.setScheduleTime(src.getScheduleTime());
        target.setIsTop(src.getIsTop());
        target.setViewCount(src.getViewCount());
        target.setCreateTime(src.getCreateTime());
        target.setUpdateTime(src.getUpdateTime());
    }

    // ==================== 私有工具 ====================

    /**
     * 按 id 查文章，不存在直接抛 {@code ARTICLE_NOT_FOUND}。
     *
     * <p>抽成方法而不是每个入口各写一遍 if-null-throw：
     * 三处入口现在都能少写 3 行，且"文章不存在抛哪个码"只有一处定义。
     */
    private CmsArticle requireArticle(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文章 ID 不能为空");
        }
        CmsArticle article = baseMapper.selectById(id);
        if (article == null) {
            // ⚠️ 这里把"id 不存在"与"id 存在但被逻辑删除"合并成同一个结果：
            // MP 的 selectById 会自动加 deleted = 0，软删的行查不出来。
            // 这是刻意的 —— 对外不应该暴露"这条 id 曾经存在过"。
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return article;
    }

    /**
     * 校验操作人存在（即"已登录"）。
     *
     * <p>为什么每个写方法都要单独校验一次，而不是只在 Controller 里
     * {@code requireCurrentUserId()} 就够了：
     * Controller 那道是"HTTP 入口的守卫"，而 Service 是**可能被其他入口调用**的
     * —— 定时任务、批处理脚本、将来别的 Service。
     * 那些入口没有 Session，{@code operatorId} 完全可能是 null。
     * 若不在这里拦住，{@code author_id} 会写进 null，
     * 直到数据库报 {@code Column 'author_id' cannot be null} 才暴露 ——
     * 报错信息离现场太远，且日志里 `authorId=null` 看不出是哪次调用。
     */
    private void requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    /**
     * 水平越权校验：文章必须属于操作人本人。
     *
     * <h3>⚠️ 这里刻意<b>没有</b>"超级管理员短路"</h3>
     * <p>{@code docs/接口契约.md} §6.4 的示例片段里 {@code checkOwnership(article, user)}
     * 写的是"超管直接 return"。本项目<b>不采纳</b>那条，原因有三：
     * <ol>
     *   <li><b>签名里没有角色。</b>冻结的 Service 签名是
     *       {@code updateDraft(Long id, CmsArticleSaveDTO dto, Long operatorId)}，
     *       只传 id 不传 {@code LoginUser}。这是 S07 明确的设计取舍
     *       （可测试 / 可被无 HTTP 上下文的定时任务复用）。
     *       要做超管短路就得把签名改成传整个 {@code LoginUser}，
     *       那是改契约，代价大于收益。</li>
     *   <li><b>业务上也确实不该允许。</b>开发手册 S12 对"编辑/主编能否修改别人的
     *       文章内容"给的要求是"<b>建议不能</b>（审核意见可以写，正文不能改）"。
     *       内容归属是硬边界：审核人有自己的入口（{@code /articles/{id}/audit}），
     *       不需要靠"直接改写作者的正文"来完成审核。</li>
     *   <li><b>最小权限原则。</b>"能管理文章"（{@code article:manage}）是功能权限，
     *       "这篇文章是不是我写的"是数据归属，两者是正交的维度
     *       （{@code RequirePermission} 注解的注释专门讲了这一点）。</li>
     * </ol>
     * <p>⚠️ 由此产生的一处<b>前端待办</b>：{@code frontend/src/views/article/articleActions.js}
     * 目前只按权限码决定"编辑/提交/删除"按钮是否显示，不判断归属。
     * 于是主编（有 {@code article:update}）会看到别人草稿上的"编辑"按钮，
     * 点下去被本方法拒（11003）。按钮可见性与后端规则不一致属于缺陷，
     * 已登记为 F06 全流程 E2E 步骤（S14 之后）的必改项。
     *
     * @param article    已查出的文章
     * @param operatorId 操作人 ID
     * @throws BusinessException {@code ARTICLE_NOT_OWNER(11003)}
     */
    private void checkOwnership(CmsArticle article, Long operatorId) {
        requireOperator(operatorId);
        // 用 operatorId.equals(...) 而不是 article.getAuthorId().equals(operatorId)：
        // 后者在 authorId 为 null 时抛 NPE，前者只是返回 false 然后正常抛业务异常。
        // 库表上 author_id 是 NOT NULL，但"防御式编程要选不会炸的那种写法"。
        if (!operatorId.equals(article.getAuthorId())) {
            log.warn("水平越权拦截 | articleId={} | authorId={} | 操作人={}",
                    article.getId(), article.getAuthorId(), operatorId);
            throw new BusinessException(ErrorCode.ARTICLE_NOT_OWNER);
        }
    }

    /**
     * 校验文章当前状态允许编辑（仅 {@code DRAFT} 与 {@code REJECTED}）。
     *
     * <p>两种拒绝分开报码，是因为它们的"下一步动作"不同：
     * <ul>
     *   <li>{@code ARCHIVED} → 11011「已归档的文章不可修改」：终态，用户不用再想怎么办了。</li>
     *   <li>其余（审核中 / 已发布 / 已撤回 / 已下架 / 定时中）→ 11002
     *       「文章当前状态为「X」，只有草稿或已驳回的文章可以修改」：
     *       用户看了就知道该去走别的入口（比如已发布的要撤回再改）。</li>
     * </ul>
     */
    private void requireEditable(CmsArticle article) {
        ArticleStatus status = ArticleStatus.ofNullable(article.getStatus());
        if (status == null) {
            // 库里出现了 0~9 之外的状态码 → 脏数据。
            // ⚠️ 不抛 ARTICLE_STATUS_ILLEGAL：那会让用户以为是自己操作的问题。
            // 这是数据异常，应当走 ERROR 日志 + 让运维看到。
            log.error("文章状态码异常（脏数据）| articleId={} | status={}",
                    article.getId(), article.getStatus());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "文章状态异常，请联系管理员（articleId=" + article.getId() + "）");
        }
        if (status.isFinal()) {
            throw new BusinessException(ErrorCode.ARTICLE_ARCHIVED_READONLY);
        }
        if (!status.isEditable()) {
            throw new BusinessException(ErrorCode.ARTICLE_STATUS_ILLEGAL,
                    String.format("文章当前状态为「%s」，只有草稿或已驳回的文章可以修改",
                            status.getDescription()));
        }
    }

    /**
     * 校验"提交审核前内容必须完整"：标题、正文、分类三样齐全。
     *
     * <h3>⚠️ 为什么不用 DTO 校验，也不新增专属错误码</h3>
     * <p><b>不用 DTO 校验</b>：因为"保存草稿"允许只填标题
     * （投稿人先占个标题很常见），只有"提交"才要求完整。
     * 同一个 {@code CmsArticleSaveDTO} 被两个入口共用，
     * 挂在字段上的校验注解表达不了"看场景决定要不要"（除非上分组校验，
     * 而那会让"什么场景用哪个分组"散落在 Controller 与 Service 两处）。
     * 放在这里，只有一个地方定义"什么算完整"。
     *
     * <p><b>不新增 11xxx 错误码</b>：手册这里写的是"抛 {@code ARTICLE_TITLE_EMPTY} 等业务码"，
     * 但 {@code ErrorCode} 里从来没有这三个码，同段其它地方引用 11xxx 时也有错位
     * （详见 {@code docs/S09-草稿与投稿实测记录.md} 的"手册与代码不一致清单"）。
     * 项目口径是"以代码与库表为准"，因此这里**不擅自往主干新增错误码**
     * —— {@code ErrorCode} 属于冻结的主干，新增会牵动
     * {@code docs/接口契约.md} §6.4 的码表，而这三个场景本质就是
     * "请求内容不满足业务要求"，用通用的 {@code PARAM_ERROR(400)} + 具体中文文案，
     * 前端体验完全等价，且不产生契约漂移。
     */
    private void requireSubmittable(CmsArticle article) {
        // 用 StringUtils.hasText 而不是 !"" .equals(...)：
        // hasText 把"只有空白字符"也算作空（全空格/全换行的正文无法通过审核）。
        // 也刻意不用 String#trim() —— 它会吃掉码点 ≤ U+0020 的控制字符，
        // 项目在 PageQuery 里已记录过这个坑（"id\0" 会被 trim 洗白）。
        if (!StringUtils.hasText(article.getTitle())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "提交审核前请先填写文章标题");
        }
        if (!StringUtils.hasText(article.getContent())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "提交审核前请先填写文章正文");
        }
        if (article.getCategoryId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "提交审核前请先选择文章分类");
        }
    }

    /**
     * 由文章当前状态反推"这次审核发生在哪个环节"。
     *
     * <h3>为什么不接受入参传环节</h3>
     * <p>冗余入参 = 允许客户端声明"我认为现在是初审"，而这个声明与库里的状态
     * 完全可能不一致；到时程序要么信客户端（错），要么不信（参数白传）。
     * 环节由状态唯一决定，所以只在一处解析。
     *
     * <h3>为什么必须区分"终态"与"其余非法状态"</h3>
     * <p>与被拒后的"下一步动作"有关：
     * <ul>
     *   <li>已归档（终态）→ {@code ARTICLE_ARCHIVED_READONLY(11011)}
     *       "已归档的文章不可修改"：一句话说清，用户不用再想怎么办。</li>
     *   <li>其余（草稿 / 已驳回 / 已发布 / 已撤回 / 已下架 / 定时中）→
     *       {@code ARTICLE_STATUS_ILLEGAL(11002)} +
     *       "文章当前状态为「X」，不在审核环节，无法审核"：
     *       用户看了就知道"现在轮不到审核"。</li>
     * </ul>
     */
    private AuditStageEnum resolveAuditStage(CmsArticle article) {
        ArticleStatus status = ArticleStatus.ofNullable(article.getStatus());
        if (status == null) {
            // 库里出现 0~9 之外的码 → 脏数据。不抛 11002：
            // 那会让用户以为是自己操作的问题，掩盖了数据异常，应走 ERROR 日志。
            log.error("文章状态码异常（脏数据）| articleId={} | status={}",
                    article.getId(), article.getStatus());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "文章状态异常，请联系管理员（articleId=" + article.getId() + "）");
        }
        if (status.isFinal()) {
            throw new BusinessException(ErrorCode.ARTICLE_ARCHIVED_READONLY);
        }
        if (status == ArticleStatus.PENDING_EDITOR) {
            return AuditStageEnum.EDITOR_FIRST;
        }
        if (status == ArticleStatus.PENDING_CHIEF) {
            return AuditStageEnum.CHIEF_FINAL;
        }
        throw new BusinessException(ErrorCode.ARTICLE_STATUS_ILLEGAL,
                String.format("文章当前状态为「%s」，不在审核环节，无法审核", status.getDescription()));
    }

    /**
     * 审核越权校验：<b>不能审核自己创建的文章</b> → {@code ARTICLE_CANNOT_AUDIT_SELF(11004)}。
     *
     * <h3>为什么这条与 {@code checkOwnership} 的码不同（11004 vs 11003）</h3>
     * <p>两者方向恰好相反，语义也不同：
     * <ul>
     *   <li>{@code checkOwnership}（改草稿 / 提交）要求"<b>必须</b>是作者本人"，
     *       不是 → 11003「只能操作自己创建的文章」；</li>
     *   <li>本方法要求"<b>绝不能</b>是作者本人"，是 → 11004「不能审核自己创建的文章」。</li>
     * </ul>
     * 用同一个码会让前端无法给出正确提示。
     * <p>⚠️ 一处手册错位登记：手册 S10 说这条"抛 {@code ARTICLE_NOT_OWNER} 或新增一个业务码"。
     * {@code ErrorCode} 里本来就有专为此场景准备的
     * {@code ARTICLE_CANNOT_AUDIT_SELF(11004)}，不需要新增（主干禁止随意加码）。
     *
     * <p>⚠️ 用 {@code operatorId.equals(article.getAuthorId())} 而不是反过来：
     * {@code authorId} 理论上可能为 null（库表是 NOT NULL，但脏数据不排除），
     * 前者只是返回 false，后者会抛 NPE。防御式写法要选"不会炸"的那种。
     */
    private void checkNotSelfAudit(CmsArticle article, Long operatorId) {
        if (operatorId.equals(article.getAuthorId())) {
            log.warn("审核越权拦截（自审自稿）| articleId={} | authorId={} | 审核人={}",
                    article.getId(), article.getAuthorId(), operatorId);
            throw new BusinessException(ErrorCode.ARTICLE_CANNOT_AUDIT_SELF);
        }
    }

    /**
     * 写一条审核流水。
     *
     * <h3>为什么 {@code articleVersion} 取"最大版本号"而不是 {@code cms_article.version}</h3>
     * <p>两者是完全不同的东西，容易混：
     * <ul>
     *   <li>{@code cms_article.version} 是 <b>MP 乐观锁</b>的版本号，每次更新 +1，
     *       它衡量的是"这行被改过多少次"；</li>
     *   <li>{@code cms_article_version.version_no} 是<b>业务版本快照</b>的编号
     *       （创建草稿=1、改一次+1、提交再审+1），它衡量的是"第几版内容"。</li>
     * </ul>
     * 库表 {@code cms_article_audit_log.article_version} 的注释写明它对应的是
     * {@code cms_article_version.version_no}，所以要取 MAX 快照号，
     * 目的是钉死"这次审核通过的是第几版内容"。
     *
     * <p>⚠️ {@code selectMaxVersionNo} 可能返回 {@code null}（该文章一条快照都没有）。
     * 库表该列可空，所以**不兜底成 0** —— 0 是一个看起来合法的版本号，
     * 会把"没有快照"这个事实伪装成"第 0 版"。null 才是诚实的表达。
     *
     * <p>⚠️ {@code createBy / createTime} 等审计字段不在这里手工赋值：
     * {@code CmsArticleAuditLog} <b>继承 BaseEntity</b>（库表五列齐全），
     * 有 {@code @TableField(fill = ...)} 标注，由 {@code AuditMetaObjectHandler} 填充。
     * 这与 {@link #insertVersionSnapshot} 的处理<b>刻意不同</b> ——
     * 那张表不继承 BaseEntity，没有任何 fill 标注，所以才必须显式赋值。
     * <b>判据永远是"这个实体有没有 fill 标注"，不是"这张表重不重要"。</b>
     */
    private void insertAuditLog(CmsArticle article, AuditStageEnum stage,
                                Long operatorId, Integer result, String opinion) {
        CmsArticleAuditLog logEntity = new CmsArticleAuditLog();
        logEntity.setArticleId(article.getId());
        logEntity.setAuditStage(stage.getCode());
        logEntity.setAuditorId(operatorId);
        logEntity.setResult(result);
        logEntity.setOpinion(opinion);
        // audit_time 是"业务时间"（用户点下按钮的那一刻），与 createTime（入库时间）语义不同，
        // 显式给上；前端审核时间线展示的是这一列。
        logEntity.setAuditTime(LocalDateTime.now());
        logEntity.setArticleVersion(versionMapper.selectMaxVersionNo(article.getId()));

        int rows = auditLogMapper.insert(logEntity);
        if (rows != 1) {
            // 正常 insert 必返回 1。走到这里说明数据库层面异常，
            // 抛系统错误让整个事务回滚（这个事务同时管着刚才那次状态更新）。
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "审核流水写入失败，请稍后重试");
        }
    }

    /**
     * 把 DTO 里**允许被作者修改**的字段搬到实体上。
     *
     * <h3>搬哪些、不搬哪些</h3>
     * <table border="1">
     *   <tr><th>字段</th><th>搬?</th><th>理由</th></tr>
     *   <tr><td>title / summary / coverUrl / content / categoryId / isTop / scheduleTime</td>
     *       <td>✅</td><td>都是"作者能决定的内容"</td></tr>
     *   <tr><td>status / authorId / viewCount / publisherId / publishTime / version</td>
     *       <td>❌</td><td>由 Service 按业务规则决定；从入参取 = 批量赋值漏洞</td></tr>
     * </table>
     *
     * <p>⚠️ 关于 {@code scheduleTime}：DTO 里有这个字段，前端"保存草稿"时也会带上
     * （{@code ArticleEdit.vue} 的表单里有排期时间选择器）。这里选择**照存**，
     * 而不是丢弃。理由：丢了就是"用户填了、接口收下了、数据库没存"——
     * 属于本项目最忌讳的静默失效，用户下次打开会以为自己的输入白填了。
     * <p>它的<b>业务语义</b>（定时任务扫描的字段）只在 {@code status = SCHEDULED} 时有意义，
     * 这个约束由 S13 的排期发布负责建立与维持
     * —— 即 S13 的"立即发布"必须把 {@code scheduleTime} 清空，
     * 否则详情页会显示一个早已无意义的排期时间。已登记为 S13 的待办。
     *
     * <p>⚠️ {@code isTop} 用 PUT 的"整体替换"语义：没传（null）就当作 0（取消置顶），
     * 而不是"保持不变"。因为接口是 {@code PUT}（整体替换该资源），
     * 前端提交的是完整表示；若按"null 就保持原值"，前端想取消置顶时
     * 反而无法表达（传 0 与不传在语义上会变得无法区分）。
     */
    private void applyEditableFields(CmsArticle target, CmsArticleSaveDTO dto) {
        target.setTitle(dto.getTitle());
        target.setSummary(dto.getSummary());
        target.setCoverUrl(dto.getCoverUrl());
        target.setContent(dto.getContent());
        target.setCategoryId(dto.getCategoryId());
        target.setIsTop(normalizeIsTop(dto.getIsTop()));
        target.setScheduleTime(dto.getScheduleTime());
    }

    /**
     * 置顶标记归一化：只认 1 为"置顶"，其余一律 0。
     *
     * <p>为什么不用 {@code @Min/@Max} 校验住 0/1：DTO 上那层是"格式校验"，
     * 挡得住格式，挡不住"有人手工构造请求传 isTop=999"。
     * 归一化是"无论传什么都落到合法值"，比抛异常更合适 ——
     * 置顶与否不影响数据一致性，没必要为它中断用户的保存操作。
     */
    private Integer normalizeIsTop(Integer isTop) {
        return (isTop != null && isTop == 1) ? 1 : 0;
    }

    /**
     * 构造"只带 id 与 version"的更新载体。
     *
     * <h3>为什么不能直接把整篇 {@code article} 传进去</h3>
     * <p>{@code baseMapper.update(entity, wrapper)} 会把 <b>entity 里非 null 的字段</b>
     * 和 <b>wrapper 里 {@code .set(...)} 的列</b>拼成同一个 SET 子句。
     * 若 entity 是"查出来的完整文章"，而 wrapper 又显式 set 了同样的列，
     * 生成的 SQL 就会变成 {@code SET title=?, ..., title=?} ——
     * MySQL 直接报 1060「Column 'title' specified twice」。
     * <p>所以这里只给 id 与 version：id 用于定位，version 用于乐观锁
     * （插件需要从实体上读到 version 才会追加
     * {@code SET version = version+1 ... AND version = 旧值}）。
     * 其余列全部交给 wrapper 显式列出。
     */
    private CmsArticle optimisticLockCarrier(CmsArticle article) {
        CmsArticle carrier = new CmsArticle();
        carrier.setId(article.getId());
        carrier.setVersion(article.getVersion());
        return carrier;
    }

    /**
     * 显式列出 {@code updateDraft} 要写的全部列。
     *
     * <h3>⚠️ 为什么必须显式 set，不能靠 {@code updateById} 的字段策略（本步最值得记的一条坑）</h3>
     * <p>MP 的 {@code updateById(entity)} 默认字段策略是
     * {@code FieldStrategy.NOT_NULL}：<b>值为 null 的字段会被整段跳过，不出现在 SET 子句里。</b>
     * <p>对"保存草稿"这个功能，这条默认行为会造成一个典型的<b>静默失效</b>：
     * <pre>
     *   PUT /articles/76/draft  {"summary": null, "coverUrl": null, "content": null, ...}
     *   → HTTP 200 {"code":200,"message":"草稿已保存"}
     *   → 数据库里 summary 还是"原始摘要"、content 还是"原始正文"，一个字都没改
     * </pre>
     * 这是 S09 实测抓到的（见 {@code docs/S09-草稿与投稿实测记录.md} 的"清空字段失效"一节）：
     * 用户点了"保存"，系统回了"成功"，数据却纹丝不动 —— 比直接报错难查得多。
     *
     * <h3>为什么不用 {@code @TableField(updateStrategy = FieldStrategy.IGNORED)} 解决</h3>
     * <p>那会让 {@code CmsArticle} 上这几个列<b>在任何地方</b>的 {@code updateById} 都无条件写入。
     * 后果是：将来 S13/S14 若写
     * {@code new CmsArticle(){{ setId(id); setStatus(9); }}} 这种"只带要改的字段"的局部实体，
     * 那些没赋值的列会被一并写成 NULL —— <b>把标题和正文整段抹掉</b>。
     * 用一个全项目范围的风险，去换一个方法内的方便，不划算。
     *
     * <p>所以这里选择"<b>只在 updateDraft 这个方法里显式列出要写的列</b>"：
     * 影响面被限制在本方法内，读代码时"这次更新会动哪些列"一眼可见，
     * 而且 null 会被真正写进去（{@code LambdaUpdateWrapper#set} 不做 null 判断）。
     *
     * @param src 已经 {@code applyEditableFields} 过的文章（快照与落库取同一份内存值，
     *            保证"快照记录的"与"库里存的"永远一致）
     */
    private LambdaUpdateWrapper<CmsArticle> updateEditableColumns(CmsArticle src) {
        return Wrappers.<CmsArticle>lambdaUpdate()
                // ⚠️ eq(id) 必须写：update(entity, wrapper) 的 WHERE 完全来自 wrapper，
                // 不写条件就是全表更新（会被 BlockAttackInnerInterceptor 拦下并抛异常，
                // 但那时报的是"系统错误"，离真正的原因很远）。
                .eq(CmsArticle::getId, src.getId())
                .set(CmsArticle::getTitle, src.getTitle())
                .set(CmsArticle::getSummary, src.getSummary())
                .set(CmsArticle::getCoverUrl, src.getCoverUrl())
                .set(CmsArticle::getContent, src.getContent())
                .set(CmsArticle::getCategoryId, src.getCategoryId())
                .set(CmsArticle::getIsTop, src.getIsTop())
                .set(CmsArticle::getScheduleTime, src.getScheduleTime());
    }

    /**
     * 写一条版本快照。取号策略：<b>{@code SELECT MAX(version_no) + 1} + 唯一索引重试</b>。
     *
     * <h3>为什么不用行锁（{@code SELECT ... FOR UPDATE}）</h3>
     * <p>库表上有一把唯一索引 {@code uk_article_version(article_id, version_no)}，
     * 它已经能保证"同一篇文章同一版本号只允许一行"。既然数据库能兜住，
     * 就不需要在应用层再加一道锁：
     * <ul>
     *   <li>行锁要求 {@code FOR UPDATE} 的查询与后续 INSERT 在<b>同一个事务</b>里，
     *       这会拉长持锁时间（本方法在事务内，锁会一直持到事务提交）；
     *       而唯一索引方案只在真正冲突的那一次重试，代价是零。</li>
     *   <li>行锁需要"锁住一行"，但"下一篇的版本号"这一行<b>还不存在</b>，
     *       要锁就得锁"当前最大号那一行"，语义上绕了一层，
     *       读代码的人不容易看出它到底锁住了什么。</li>
     * </ul>
     *
     * <h3>为什么 {@code FOR UPDATE} 在这里其实也锁不住</h3>
     * <p>更实际的原因是：本项目同一篇文章<b>不会</b>被多人同时写
     * （{@code checkOwnership} 保证只有作者本人能改）。真正的并发风险来自
     * "同一用户双击提交"这种瞬时重复请求，两个请求相差几毫秒。
     * 这种情况下唯一索引 + 重试的代价最小、语义最直白。
     *
     * <h3>⚠️ 为什么用 {@code attempt} 循环 + 重新取号，而不是"拿到同一个号再 +1"</h3>
     * <p>冲突后必须<b>重新查一次 MAX</b>（循环体第一行就在做这件事），
     * 因为冲突意味着"别人已经占用了这个号"，直接 +1 会拿到下一个同样可能被占用的号 ——
     * 那叫瞎猜，不叫重试。
     *
     * @param article    文章（标题与正文从这里取快照）
     * @param operatorId 修改人
     * @param remark     修改说明（"创建草稿"/"更新草稿"/"提交初审"）
     */
    private void insertVersionSnapshot(CmsArticle article, Long operatorId, String remark) {
        for (int attempt = 1; attempt <= MAX_SNAPSHOT_RETRY; attempt++) {
            Integer maxNo = versionMapper.selectMaxVersionNo(article.getId());
            int nextNo = (maxNo == null ? 0 : maxNo) + 1;

            CmsArticleVersion snapshot = new CmsArticleVersion();
            snapshot.setArticleId(article.getId());
            snapshot.setVersionNo(nextNo);
            snapshot.setTitleSnapshot(article.getTitle());
            snapshot.setContentSnapshot(article.getContent());
            snapshot.setModifierId(operatorId);
            snapshot.setModifyRemark(remark);
            // ⚠️ createTime 与 deleted 显式赋值，不依赖 MetaObjectHandler 的自动填充。
            // 原因：CmsArticleVersion **不继承 BaseEntity**（库表缺 create_by/update_by/update_time），
            // 它没有任何 @TableField(fill = ...) 标注的字段，
            // 所以 MP 的 insertFill 是否会被触发是"取决于 MP 内部判定"的，
            // 不能想当然（项目里已经为"MetaObjectHandler 静默失效"踩过一次坑）。
            // 这两列都是 NOT NULL，一旦没填上就是 Column 'create_time' cannot be null。
            // 显式赋值 = 行为不依赖框架内部实现细节。
            snapshot.setCreateTime(LocalDateTime.now());
            snapshot.setDeleted(0);

            try {
                versionMapper.insert(snapshot);
                log.debug("版本快照已写入 | articleId={} | versionNo={} | remark={}",
                        article.getId(), nextNo, remark);
                return;
            } catch (RuntimeException e) {
                if (!isDuplicateKey(e) || attempt == MAX_SNAPSHOT_RETRY) {
                    // 不是唯一键冲突 → 是真的出错了（列名不对、连接断了……），
                    // 原样抛出，交给上层事务回滚 + 全局异常处理器记 ERROR。
                    throw e;
                }
                log.warn("版本号冲突，重新取号重试 | articleId={} | 冲突号={} | 第 {}/{} 次",
                        article.getId(), nextNo, attempt, MAX_SNAPSHOT_RETRY);
            }
        }
    }

    /**
     * 判断异常链里是否有"唯一键冲突"。
     *
     * <h3>⚠️ 先说实测结论（这句是本类最值得记的一条）</h3>
     * <p>本项目在"纯 SSM 手工装配 + MyBatis 的 {@code @Mapper}"下，
     * 唯一键冲突抛出来的<b>确实是</b>
     * {@code org.springframework.dao.DuplicateKeyException}，
     * 其 cause 是 {@code java.sql.SQLIntegrityConstraintViolationException}
     * （实测原文见 {@code docs/evidence/S09-*.md}）：
     * <pre>
     * org.springframework.dao.DuplicateKeyException :
     *   ### Error updating database. Cause: java.sql.SQLIntegrityConstraintViolationException:
     *   Duplicate entry '95-1' for key 'cms_article_version.uk_article_version'
     * </pre>
     *
     * <p>⚠️ <b>写这段代码时我的先验判断是"不会翻译"</b>（理由：没有
     * {@code PersistenceExceptionTranslationPostProcessor}、Mapper 用的是
     * MyBatis 的 {@code @Mapper} 而不是 Spring 的 {@code @Repository}，所以
     * {@code DataAccessException} 体系不生效）。那个判断是<b>错的</b> ——
     * mybatis-spring 2.1.2 这套装配下异常确实被翻译了。
     * <b>如果按错判断去写"只 catch PersistenceException"，这段重试就是一段永不执行的死代码。</b>
     * 是"把异常类型打印出来看一眼"这个动作纠正了它，而不是更仔细地推理。
     *
     * <h3>为什么明知当前抛 DuplicateKeyException，仍然沿异常链判断三种形态</h3>
     * <ul>
     *   <li>异常类型属于<b>框架装配的产物</b>，不是我们的代码能保证的东西：
     *       换个 mybatis-spring 版本、加一个
     *       {@code PersistenceExceptionTranslationPostProcessor}、或者哪天
     *       Mapper 上加了 {@code @Repository}，译文形态都可能变。</li>
     *   <li>沿 cause 链走一圈的成本是"几次 instanceof"，而写错的成本是
     *       "并发时偶发提交失败且无法复现"。</li>
     *   <li>本项目已经吃过一次"想当然认为框架会/不会做某件事"的亏
     *       （见 {@code AuditMetaObjectHandler} 的注释），这里按最宽的口径识别。</li>
     * </ul>
     *
     * <p>可见性为包级（{@code static}）是<b>为了可测</b>：并发冲突无法在单线程测试里
     * 稳定复现，但"给定这个异常，识别得了吗"是可以直接断言的
     * （见 {@code com.lab.cms.service.impl.VersionSnapshotDuplicateKeyTest}）。
     */
    static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException && ((SQLException) t).getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
            // 自引用（某些包装异常会把 cause 指回自己）会让 for 循环死转，显式断开
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
