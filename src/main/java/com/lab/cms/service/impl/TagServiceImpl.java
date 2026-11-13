package com.lab.cms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lab.cms.base.BaseServiceImpl;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.common.util.SqlLikeUtil;
import com.lab.cms.dto.TagQueryDTO;
import com.lab.cms.dto.TagSaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsArticleTag;
import com.lab.cms.entity.CmsTag;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsArticleTagMapper;
import com.lab.cms.mapper.CmsTagMapper;
import com.lab.cms.service.TagService;
import com.lab.cms.vo.TagVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签管理服务实现。
 *
 * <h3>本类的核心是三件"看起来简单但坑很深"的事</h3>
 * <ol>
 *   <li>{@link #bindArticleTags} —— 覆盖式打标，且必须复用被逻辑删除的关联行
 *       （否则第二次取消同一标签会撞 {@code uk_article_tag}）。</li>
 *   <li>{@link #deleteTag} —— 删除前要先清理同名墓碑
 *       （否则"新建→删除→新建→删除"的第四步会撞 {@code uk_tag_name}）。</li>
 *   <li>{@link #maintainUseCount} —— 冗余计数用数据库原子增量维护，
 *       且减的时候兜底不小于 0。</li>
 * </ol>
 * <p>三件事都在 {@code docs/S24-分类标签与日志接口实测记录.md} 里有真机复现步骤与输出，
 * 不是纸面推理。
 */
@Service
public class TagServiceImpl extends BaseServiceImpl<CmsTagMapper, CmsTag> implements TagService {

    private static final Logger log = LoggerFactory.getLogger(TagServiceImpl.class);

    /**
     * 一篇文章最多打多少个标签。
     * <p>与 {@code ArticleTagBindDTO#tagIds} 上的 {@code @Size(max = 20)} 数值一致，
     * 但<b>两处都要留着</b>：DTO 只拦 HTTP 入口，拦不住"别的 Service 直接调本方法"
     * （项目在批量提交那里已经写过同一条理由）。
     */
    private static final int MAX_TAGS_PER_ARTICLE = 20;

    /** 标签列表允许排序的列（数据库列名）。 */
    private static final String[] LIST_SORTABLE_COLUMNS = {
            "use_count", "tag_name", "create_time", "update_time", "id"
    };

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private CmsArticleTagMapper articleTagMapper;

    // ==================== 一、标签本身 ====================

    @Override
    public PageResult<TagVO> pageTags(TagQueryDTO query) {
        TagQueryDTO q = (query == null) ? new TagQueryDTO() : query;
        q.normalize();

        LambdaQueryWrapper<CmsTag> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(q.getTagName())) {
            // 转义 % 与 _，理由见 SqlLikeUtil
            wrapper.apply("tag_name LIKE {0}", SqlLikeUtil.containsPattern(q.getTagName()));
        }
        if (q.getStatus() != null) {
            wrapper.eq(CmsTag::getStatus, q.getStatus());
        }

        Page<CmsTag> mpPage = q.toMpPage(LIST_SORTABLE_COLUMNS);
        if (!q.hasSortField()) {
            // 默认按热度倒序；use_count 会撞（种子数据里有多个 2、多个 3），
            // 所以必须补 id 才能得到确定的全序，否则翻页会重复/漏行。
            mpPage.addOrder(OrderItem.desc("use_count"), OrderItem.desc("id"));
        }

        IPage<CmsTag> page = baseMapper.selectPage(mpPage, wrapper);
        List<TagVO> list = new ArrayList<>(page.getRecords().size());
        for (CmsTag row : page.getRecords()) {
            // ⚠️ 列表不填 realUseCount：那需要对每一行发一条 COUNT，
            //    是本项目反复强调的 N+1。列表显示 use_count 缓存列就够了。
            list.add(toVO(row, null));
        }
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public TagVO getTag(Long id) {
        CmsTag tag = requireTag(id);
        // 详情接口只查一行，这里补真实引用数不构成 N+1，且能让使用者发现
        // "缓存列和真实数对不上"这件事。
        return toVO(tag, countArticleRefs(id));
    }

    /**
     * 新建标签。
     *
     * <h3>为什么重名检查不能用 {@code uk_tag_name} 兜底</h3>
     * <p>库里<b>确实有</b> {@code uk_tag_name(tag_name, deleted)}，理论上可以"直接插、
     * 撞了就把 1062 翻译成 12007"。但仍然在前置做一次 {@code SELECT COUNT}，因为：
     * <ul>
     *   <li>能给出<b>更精确的提示</b>（"标签名 X 已被使用"，而 1062 的原始 message
     *       是 {@code Duplicate entry 'X-0' for key 'cms_tag.uk_tag_name'}，直接给用户看很糟）；</li>
     *   <li>唯一键本身<b>不是</b>"逻辑上唯一"的保障：{@code (X,1)} 与 {@code (X,0)}
     *       可以共存，所以"同名"这件事在库层面本来就是应用层语义。</li>
     * </ul>
     * <p>⚠️ 代价：仍有一个 TOCTOU 并发窗口（查完到插入之间别人插了同名）。
     * 这次不同了 —— 唯一键会真的把它拦下来，抛 1062 而不是静默插入重复数据。
     * 属于"有兜底，但兜底给出的是 500 而非友好的 12007"，作为已知不足登记。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTag(TagSaveDTO dto, Long operatorId) {
        requireOperator(operatorId);
        String name = dto.getTagName().trim();
        requireNameUnique(name, null);

        CmsTag entity = new CmsTag();
        entity.setTagName(name);
        entity.setUseCount(0);
        entity.setStatus(1);
        baseMapper.insert(entity);
        log.info("新建标签 | id={} | name={} | 操作人={}", entity.getId(), name, operatorId);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTag(Long id, TagSaveDTO dto, Long operatorId) {
        requireOperator(operatorId);
        requireTag(id);
        String name = dto.getTagName().trim();
        requireNameUnique(name, id);

        int rows = baseMapper.update(null, Wrappers.<CmsTag>lambdaUpdate()
                .set(CmsTag::getTagName, name)
                .set(CmsTag::getUpdateBy, operatorId)
                .set(CmsTag::getUpdateTime, LocalDateTime.now())
                .eq(CmsTag::getId, id));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        log.info("编辑标签 | id={} | name={} | 操作人={}", id, name, operatorId);
    }

    /**
     * 逻辑删除标签。
     *
     * <h3>处理顺序（顺序本身就是逻辑，不能调换）</h3>
     * <ol>
     *   <li>查标签存在 → 12008</li>
     *   <li>查是否仍被文章引用（<b>查关联表实时 COUNT，不查 {@code use_count} 缓存</b>）
     *       → 12003</li>
     *   <li><b>清理同名墓碑</b>（物理删除 {@code (同名, deleted=1)} 的行）——
     *       这一步不做，下面第四步会撞 {@code uk_tag_name} 报 1062</li>
     *   <li>逻辑删除当前行，并<b>显式补审计列</b></li>
     * </ol>
     *
     * <h3>⚠️ 为什么删除保护用"实时 COUNT"而不是 {@code use_count}</h3>
     * <p>{@code use_count} 是缓存列，S24 实测发现它<b>与关联表的真实分布并不一致</b>
     * （种子数据就有偏差）。如果拿它做删除判断，会出现两种错：
     * <ul>
     *   <li>缓存偏大 → 明明没人用的标签删不掉；</li>
     *   <li>缓存偏小（比如漂移成 0）→ 还在被引用的标签被删掉，
     *       文章上留下一堆指向不存在标签的关联，前端显示空白标签。</li>
     * </ul>
     * <p>原则：<b>展示可以用缓存，保护必须用真相。</b>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(Long id, Long operatorId) {
        requireOperator(operatorId);
        CmsTag tag = requireTag(id);

        long refs = countArticleRefs(id);
        if (refs > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_ARTICLES,
                    "该标签仍被 " + refs + " 篇文章使用，请先取消这些文章的标签");
        }

        // ⚠️ 先清墓碑再删自己，顺序不能反：
        //    反过来的话，第二步的 UPDATE 直接就会撞 uk_tag_name 抛 1062（未捕获 → 500）。
        int purged = baseMapper.purgeDeletedTombstones(tag.getTagName());
        if (purged > 0) {
            log.info("清理同名标签墓碑 | name={} | 清理行数={}", tag.getTagName(), purged);
        }

        int rows = baseMapper.update(null, Wrappers.<CmsTag>lambdaUpdate()
                .setSql("deleted = 1")
                .set(CmsTag::getUpdateBy, operatorId)
                .set(CmsTag::getUpdateTime, LocalDateTime.now())
                .eq(CmsTag::getId, id));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        log.info("删除标签（逻辑）| id={} | name={} | 操作人={}", id, tag.getTagName(), operatorId);
    }

    // ==================== 二、文章打标 ====================

    @Override
    public List<TagVO> listArticleTags(Long articleId) {
        CmsArticle article = articleMapper.selectById(articleId);
        if (article == null) {
            // 借文章域的错误码：这里要表达的确实是"文章不存在"，用本域造一个
            // "标签不存在"反而误导调用方。
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        List<Long> tagIds = activeTagIdsOf(articleId);
        if (tagIds.isEmpty()) {
            return new ArrayList<>();
        }
        // 一次 IN 查询取回全部标签（不是逐个 selectById —— 那是 N+1）
        List<CmsTag> tags = baseMapper.selectBatchIds(tagIds);
        Map<Long, CmsTag> byId = new HashMap<>(Math.max(4, tags.size() * 2));
        for (CmsTag tag : tags) {
            byId.put(tag.getId(), tag);
        }
        List<TagVO> list = new ArrayList<>(tagIds.size());
        for (Long tagId : tagIds) {
            CmsTag tag = byId.get(tagId);
            if (tag == null) {
                // 关联存在但标签已被删除（历史脏数据）：跳过而不是报错，
                // 否则一篇文章的历史垃圾会让整个"查标签"失败。
                log.warn("文章标签关联指向不存在的标签 | articleId={} | tagId={}", articleId, tagId);
                continue;
            }
            list.add(toVO(tag, null));
        }
        return list;
    }

    /**
     * 覆盖式打标。
     *
     * <h3>算法：算差集，然后"新增 / 取消"各走各的路</h3>
     * <pre>
     *   目标集合 D（去重后）      当前集合 C（关联表里 deleted = 0 的）
     *   toAdd    = D - C   → 逐条：查有无历史行 ⇒ 有则"复活"，无则 INSERT
     *   toRemove = C - D   → 逐条：逻辑删除 + use_count - 1
     * </pre>
     *
     * <h3>⚠️ 最关键的一点：toAdd 里必须"先查历史行"</h3>
     * <p>不能直接 INSERT。因为 {@code uk_article_tag(article_id, tag_id, deleted)}
     * 决定了同一个人可能已经留下过一行 {@code deleted = 1} 的记录
     * （取消过这个标签）。此时：
     * <ul>
     *   <li>直接 INSERT {@code (a,t,0)} <b>能成功</b>（不与 {@code (a,t,1)} 冲突）——
     *       但库里就有两行了；</li>
     *   <li>下一次取消就会试图产生<b>第二行</b> {@code (a,t,1)} ⇒ <b>1062</b>。</li>
     * </ul>
     * <p>所以本方法改成"查有没有任意一行（含已删除）→ 有就把它 {@code deleted} 改回 0"。
     * 这样库里对任意 {@code (article_id, tag_id)} <b>永远恰好一行</b>，
     * 整个序列可以无限循环。⚠️ 查询与复活都必须绕过 MP 的逻辑删除
     * （MP 生成的 SQL 会自动加 {@code deleted = 0}，查不到也改不到墓碑行）——
     * 这正是 {@link CmsArticleTagMapper} 里那两句手写 SQL 存在的全部理由。
     *
     * <h3>⚠️ 幂等</h3>
     * <p>重复提交同一个 {@code tagIds}：{@code toAdd} 与 {@code toRemove} 都是空集，
     * 一条写都不发生（{@code use_count} 也不会被重复加减）。
     * 这是选"覆盖式"而不是"追加"的直接好处。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int bindArticleTags(Long articleId, List<Long> tagIds, Long operatorId) {
        requireOperator(operatorId);

        CmsArticle article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (tagIds != null && tagIds.size() > MAX_TAGS_PER_ARTICLE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "一篇文章最多打 " + MAX_TAGS_PER_ARTICLE + " 个标签");
        }

        // 去重（保持顺序，便于日志可读）。传重复 id 是常见的前端失误，
        // 去重后不会导致"同一标签被算两次 use_count"。
        Set<Long> desired = new LinkedHashSet<>();
        if (tagIds != null) {
            for (Long tagId : tagIds) {
                if (tagId == null) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "标签 ID 不能为空");
                }
                desired.add(tagId);
            }
        }
        if (desired.size() > MAX_TAGS_PER_ARTICLE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "一篇文章最多打 " + MAX_TAGS_PER_ARTICLE + " 个标签（去重后仍超出）");
        }

        requireTagsUsable(desired);

        // 当前有效关联：tagId → 关联行
        Map<Long, CmsArticleTag> current = activeRelationsOf(articleId);
        Set<Long> currentTagIds = new HashSet<>(current.keySet());

        List<Long> toRemove = new ArrayList<>();
        for (Long tagId : currentTagIds) {
            if (!desired.contains(tagId)) {
                toRemove.add(tagId);
            }
        }
        List<Long> toAdd = new ArrayList<>();
        for (Long tagId : desired) {
            if (!currentTagIds.contains(tagId)) {
                toAdd.add(tagId);
            }
        }

        for (Long tagId : toRemove) {
            CmsArticleTag row = current.get(tagId);
            // 显式 SQL：既补审计列（MP 的 removeById 不会填 update_by），
            // 又确保只删"还活着的那一行"
            int n = articleTagMapper.softDeleteRow(row.getId(), operatorId);
            if (n > 0) {
                maintainUseCount(tagId, -1);
            } else {
                // 理论不可达（这一行是刚查出来的、deleted=0）。真出现说明有并发，
                // 记 WARN 但不失败 —— 用户的意图（取消该标签）已经达成了。
                log.warn("取消标签时未匹配到有效关联行（可能并发）| articleId={} | tagId={}",
                        articleId, tagId);
            }
        }

        for (Long tagId : toAdd) {
            // ⚠️ 必须查"含已删除"的任意一行 —— 直接 INSERT 会让库里出现同一个
            //    (article_id, tag_id) 的两行，下一次取消就撞唯一键。
            Long anyRowId = articleTagMapper.selectAnyRowId(articleId, tagId);
            if (anyRowId == null) {
                CmsArticleTag entity = new CmsArticleTag();
                entity.setArticleId(articleId);
                entity.setTagId(tagId);
                articleTagMapper.insert(entity);
                maintainUseCount(tagId, 1);
            } else {
                int n = articleTagMapper.reviveRow(anyRowId, operatorId);
                if (n > 0) {
                    maintainUseCount(tagId, 1);
                } else {
                    // 复活 0 行 ⇒ 那一行其实是 deleted=0（本来就活着）。
                    // 正常情况下它会出现在 current 里、根本进不了 toAdd，
                    // 所以走到这里意味着并发或历史脏数据 —— 不重复计数即可。
                    log.warn("复活关联行未生效（该行可能本就有效）| articleId={} | tagId={} | rowId={}",
                            articleId, tagId, anyRowId);
                }
            }
        }

        log.info("文章打标完成 | articleId={} | 目标={} | 新增={} | 取消={} | 操作人={}",
                articleId, desired.size(), toAdd.size(), toRemove.size(), operatorId);
        return desired.size();
    }

    // ==================== 三、私有工具 ====================

    private CmsTag requireTag(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标签 ID 不能为空");
        }
        CmsTag tag = baseMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        return tag;
    }

    private void requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private void requireNameUnique(String name, Long excludeId) {
        LambdaQueryWrapper<CmsTag> wrapper = Wrappers.<CmsTag>lambdaQuery()
                .eq(CmsTag::getTagName, name);
        if (excludeId != null) {
            wrapper.ne(CmsTag::getId, excludeId);
        }
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.TAG_NAME_DUPLICATE, "已存在同名标签「" + name + "」");
        }
    }

    /**
     * 校验目标标签全部存在且处于启用态。
     *
     * <p>一次 {@code IN} 查询拿全部，而不是逐个 {@code selectById}。
     * <p>⚠️ 循环里用 {@code desired}（有序）而不是 {@code byId.keySet()}：
     * 报错信息要给出"是哪一个 id 有问题"，而 {@code HashMap} 的遍历顺序不保证稳定，
     * 一次报 A、一次报 B 会让排查显得很随机。
     */
    private void requireTagsUsable(Collection<Long> desired) {
        if (desired == null || desired.isEmpty()) {
            return;
        }
        List<CmsTag> tags = baseMapper.selectBatchIds(desired);
        Map<Long, CmsTag> byId = new HashMap<>(Math.max(4, tags.size() * 2));
        for (CmsTag tag : tags) {
            byId.put(tag.getId(), tag);
        }
        for (Long tagId : desired) {
            CmsTag tag = byId.get(tagId);
            if (tag == null) {
                // 覆盖"标签不存在"与"标签已被逻辑删除"两种情况 ——
                // MP 的 selectBatchIds 自带 deleted = 0，墓碑行查不出来。
                // 对外不暴露"这个 id 曾经存在过"，与文章域 requireArticle 口径一致。
                throw new BusinessException(ErrorCode.TAG_NOT_FOUND, "标签不存在或已被删除：id=" + tagId);
            }
            if (!Integer.valueOf(1).equals(tag.getStatus())) {
                // ⚠️ 这里借用 CATEGORY_DISABLED(12005)（"该分类已停用，不可选用"）。
                //    原因：12000~12999 段位里没有"标签已停用"的专用码，
                //    而本步的纪律是**不新增错误码**（该段位属成员B）。
                //    自定义 message 把语义说清楚，比新增一个码去越界更稳妥。
                throw new BusinessException(ErrorCode.CATEGORY_DISABLED,
                        "标签「" + tag.getTagName() + "」已停用，不能使用");
            }
        }
    }

    /** 某文章当前有效的标签 ID（保持 {@code create_time} 倒序，最新打的在前）。 */
    private List<Long> activeTagIdsOf(Long articleId) {
        List<CmsArticleTag> rows = articleTagMapper.selectList(Wrappers.<CmsArticleTag>lambdaQuery()
                .eq(CmsArticleTag::getArticleId, articleId)
                .orderByDesc(CmsArticleTag::getId));
        List<Long> ids = new ArrayList<>(rows.size());
        for (CmsArticleTag row : rows) {
            ids.add(row.getTagId());
        }
        return ids;
    }

    /**
     * 某文章当前的"有效关联"（tagId → 关联行）。
     *
     * <p>⚠️ 用循环 + {@code putIfAbsent} 而不是 {@code Collectors.toMap}：
     * 后者在遇到重复 key（脏数据下同一个 tagId 有两行）时会抛
     * {@code IllegalStateException: Duplicate key} —— 那是"一条脏数据把接口打挂"。
     * 这里选择保留先遇到的那一行 + 记 WARN，让接口继续可用。
     */
    private Map<Long, CmsArticleTag> activeRelationsOf(Long articleId) {
        List<CmsArticleTag> rows = articleTagMapper.selectList(Wrappers.<CmsArticleTag>lambdaQuery()
                .eq(CmsArticleTag::getArticleId, articleId));
        Map<Long, CmsArticleTag> map = new HashMap<>(Math.max(4, rows.size() * 2));
        for (CmsArticleTag row : rows) {
            CmsArticleTag old = map.putIfAbsent(row.getTagId(), row);
            if (old != null) {
                log.warn("文章标签存在重复有效关联（脏数据）| articleId={} | tagId={} | 保留行={} 忽略行={}",
                        articleId, row.getTagId(), old.getId(), row.getId());
            }
        }
        return map;
    }

    /** 该标签被多少篇文章引用（关联表实时 COUNT，不是读 {@code use_count} 缓存）。 */
    private long countArticleRefs(Long tagId) {
        return articleTagMapper.selectCount(Wrappers.<CmsArticleTag>lambdaQuery()
                .eq(CmsArticleTag::getTagId, tagId));
    }

    /**
     * 维护 {@code cms_tag.use_count} 缓存列。
     *
     * <h3>⚠️ 为什么用 {@code setSql} 做原子增量，而不是"读出来 +1 再写回"</h3>
     * <p>{@code use_count = use_count + 1} 由数据库在一条语句里完成读改写；
     * 而"读出来、算出新值、写回去"在两次操作之间会被别的请求插进来，
     * 结果是<b>两边的加一变成了只加一</b>（丢更新）。
     * <p>SQL 里的 {@code UPDATE ... SET x = x + 1} 本身就等价于一个行级原子操作，
     * 不需要额外加锁。
     *
     * <h3>⚠️ 减的时候为什么套 {@code GREATEST(..., 0)}</h3>
     * <p>缓存列一旦因为历史原因漂移（S24 实测种子数据就对不上），
     * 减法可能把它变成负数 —— 而 {@code use_count} 在 DDL 里是
     * {@code NOT NULL DEFAULT 0} 的语义（"次数"不该为负）。
     * 负数还会让"按热度排序"把无人使用的标签排到最前面。
     * 兜到 0 是"宁可显示不准也不显示荒谬值"。
     *
     * @param delta {@code +1} 或 {@code -1}
     */
    private void maintainUseCount(Long tagId, int delta) {
        String expr = (delta >= 0)
                ? "use_count = use_count + 1"
                : "use_count = GREATEST(use_count - 1, 0)";
        // ⚠️ 返回值不判：维护失败（比如标签恰好被删）不该让整个打标事务回滚 ——
        //    用户的意图是"给文章打标"，关联表已经写好了。缓存列不一致由
        //    "保护逻辑用实时 COUNT"这条设计兜住（见 deleteTag）。
        //    这里记 WARN 是为了让漂移可被发现。
        int rows = baseMapper.update(null, Wrappers.<CmsTag>lambdaUpdate()
                .setSql(expr)
                .eq(CmsTag::getId, tagId));
        if (rows == 0) {
            log.warn("use_count 维护未命中标签 | tagId={} | delta={}", tagId, delta);
        }
    }

    private TagVO toVO(CmsTag src, Long realUseCount) {
        TagVO vo = new TagVO();
        vo.setId(src.getId());
        vo.setTagName(src.getTagName());
        vo.setUseCount(src.getUseCount());
        vo.setStatus(src.getStatus());
        vo.setStatusDesc(statusDesc(src.getStatus()));
        vo.setRealUseCount(realUseCount);
        vo.setCreateTime(src.getCreateTime());
        vo.setUpdateTime(src.getUpdateTime());
        return vo;
    }

    /** 状态中文；脏值给"未知"，不让整个列表 500。 */
    private static String statusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        if (status == 1) {
            return "启用";
        }
        return status == 0 ? "停用" : "未知";
    }
}
