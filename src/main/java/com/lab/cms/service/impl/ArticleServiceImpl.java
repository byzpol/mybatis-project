package com.lab.cms.service.impl;

import com.lab.cms.base.BaseServiceImpl;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.service.ArticleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文章业务服务实现。
 *
 * <h3>⚠️ S07 阶段本类全是空壳</h3>
 * <p>按开发手册要求"只留方法空壳 + TODO，本步不要写业务实现"。
 * <b>每个方法都直接抛 {@code UnsupportedOperationException}</b>，
 * 而不是"留空什么都不做" —— 这是刻意的：
 * <ul>
 *   <li>若留空（方法体为空或 {@code return null}），
 *       {@code saveDraft} 返回 null 而调用方以为是新 id，
 *       后续逻辑会带着一个 null id 往下走，错误在很远的地方才爆出来</li>
 *   <li>抛异常能让"忘实现"立刻暴露在第一次调用时，
 *       且异常信息里点名了是哪个方法、在哪一步实现</li>
 * </ul>
 * 这是"Fail Fast"的具体应用：<b>宁可立刻炸，不要静默地错。</b>
 * 等 S09 补齐实现时，逐个替换掉这些异常即可（异常本身也是"还没写"的清单）。
 *
 * <h3>关于继承 {@code BaseServiceImpl}</h3>
 * <p>泛型参数是 {@code <CmsArticleMapper, CmsArticle>}，
 * 与 {@code BaseServiceImpl} 的约束 {@code <M extends BaseMapper<T>, T extends BaseEntity>} 对齐。
 * 好处之一（见 {@code BaseServiceImpl} 类注释）就是<b>编译器会强制</b>
 * Mapper 与 Entity 配对正确 —— 若写成
 * {@code BaseServiceImpl<CmsArticleAuditLogMapper, CmsArticle>}，
 * 编译期就报错，而不是运行时报"找不到某个方法"。
 *
 * <h3>⚠️ 关于 {@code @Transactional}</h3>
 * <p>S07 不写实现，暂不加事务注解。补齐实现时必须注意：
 * <ul>
 *   <li>写操作（提交、审核、发布）需要事务 —— 因为一次操作要同时更新
 *       {@code cms_article} 与插入 {@code cms_article_audit_log} /
 *       {@code cms_article_version} 两张表，必须同成功或同失败</li>
 *   <li>只读查询不应加事务（或加 {@code readOnly = true}）</li>
 *   <li>⚠️ <b>{@code @Transactional} 必须加在 public 方法上，
 *       且同类内部自调用不生效</b>（Spring AOP 代理机制的固有限制）——
 *       {@code batchSubmit} 若想"循环调用 {@code submitForAudit} 并让每次独立事务"，
 *       直接 {@code this.submitForAudit(...)} 是<b>不会</b>开启新事务的。
 *       届时需要通过注入自身代理、或用 {@code TransactionTemplate} 显式控制。
 *       这一点在 S09 实现 {@code batchSubmit} 时必须处理，先记在这里。</li>
 * </ul>
 */
@Service
public class ArticleServiceImpl extends BaseServiceImpl<CmsArticleMapper, CmsArticle>
        implements ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleServiceImpl.class);

    /**
     * 每批最多提交的文章数量。
     * <p>防止恶意构造超大 id 列表导致的资源耗尽（见 {@code ArticleService#batchSubmit} 类注释）。
     */
    private static final int MAX_BATCH_SIZE = 100;

    @Override
    public Long saveDraft(CmsArticleSaveDTO dto, Long operatorId) {
        // TODO(S09) 新建草稿：
        //   1. 把 dto 拷贝到 CmsArticle（状态强行置 DRAFT，禁止前端指定状态）
        //   2. 设 authorId = operatorId
        //   3. isTop 归一化为 0/1；viewCount 置 0
        //   4. insert，返回新 id
        // 注意：不要接受 dto 里任何"状态/作者/浏览量"类的字段，防批量赋值。
        throw new UnsupportedOperationException(
                "ArticleServiceImpl.saveDraft 将在 S09 实现（S07 只建骨架）");
    }

    @Override
    public void updateDraft(Long id, CmsArticleSaveDTO dto, Long operatorId) {
        // TODO(S09) 修改草稿：
        //   1. 按 id 查文章（不存在 → ARTICLE_NOT_FOUND）
        //   2. 校验 authorId == operatorId（否则 → ARTICLE_NOT_OWNER，水平越权）
        //   3. 校验当前状态可编辑（ArticleStatus#isEditable；不可编辑 → ARTICLE_STATUS_ILLEGAL）
        //   4. 更新允许修改的字段（标题/摘要/封面/正文/分类/置顶）
        //      注意：不要更新 status / authorId / viewCount
        throw new UnsupportedOperationException(
                "ArticleServiceImpl.updateDraft 将在 S09 实现（S07 只建骨架）");
    }

    @Override
    public void submitForAudit(Long id, Long operatorId) {
        // TODO(S09) 提交审核：
        //   1. 查文章 + 校验归属（ARTICLE_NOT_OWNER）
        //   2. ArticleStateMachine.transfer(status, SUBMIT) 取目标状态（非法自动抛异常）
        //   3. 校验提交前内容完整（标题/正文/分类齐全）
        //   4. 更新状态，并写一条版本快照（cms_article_version）
        // 必须走状态机，禁止直接 setStatus(1)。
        throw new UnsupportedOperationException(
                "ArticleServiceImpl.submitForAudit 将在 S09 实现（S07 只建骨架）");
    }

    @Override
    public void batchSubmit(List<Long> ids, Long operatorId) {
        // TODO(S09) 批量提交：
        //   1. 校验 ids 非空且 size <= MAX_BATCH_SIZE（超出 → PARAM_ERROR）
        //   2. 去重（同一 id 传两次会重复处理）
        //   3. 逐篇独立事务地调用提交逻辑，收集成功/失败结果
        //      —— 不能用 this.submitForAudit(...) 自调用（事务不生效），
        //         见类注释里 @Transactional 的说明
        //   4. 返回值应改为"逐条结果"集合（当前 void 是占位，见接口注释）
        if (ids != null && ids.size() > MAX_BATCH_SIZE) {
            // 连这个上限检查的"正确抛法"都留到 S09 —— 这里只是提醒实现者别漏
            log.warn("[S07骨架] batchSubmit 收到 {} 个 id，超过上限 {}", ids.size(), MAX_BATCH_SIZE);
        }
        throw new UnsupportedOperationException(
                "ArticleServiceImpl.batchSubmit 将在 S09 实现（S07 只建骨架）");
    }
}
