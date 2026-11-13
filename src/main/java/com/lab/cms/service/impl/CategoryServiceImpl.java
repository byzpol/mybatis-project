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
import com.lab.cms.dto.CategoryQueryDTO;
import com.lab.cms.dto.CategorySaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsCategory;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsCategoryMapper;
import com.lab.cms.service.CategoryService;
import com.lab.cms.vo.CategoryCountVO;
import com.lab.cms.vo.CategoryTreeVO;
import com.lab.cms.vo.CategoryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类管理服务实现。
 *
 * <h3>本类"没有"做什么（同样是设计的一部分）</h3>
 * <ul>
 *   <li><b>不做分类审批</b>。库里有 {@code cms_category_audit_log} 表、
 *       有 {@code category:audit} 权限码、{@code cms_category.audit_status}
 *       也是三态设计，但本期没有实现"提交 → 审批 → 生效"的流程。
 *       因此新建的分类直接置 {@code audit_status = 1}（已生效）——
 *       否则新建出来的分类永远不可被文章选用，功能是断的。
 *       这个缺口在 {@code docs/S24-分类标签与日志接口实测记录.md} 里如实登记。</li>
 *   <li><b>不支持调整分类的父级</b>（即"移动分类"）。见 {@link #updateCategory}。</li>
 *   <li><b>不级联删除</b>。见 {@link #deleteCategory}。</li>
 * </ul>
 *
 * <h3>⚠️ 本类所有"更新"都走 {@code LambdaUpdateWrapper}，不用 {@code updateById}</h3>
 * <p>这是项目已记录的高频陷阱：{@code updateById(entity)} 受 MP 的
 * {@code FieldStrategy.NOT_NULL} 影响会<b>静默跳过 null 字段</b> ——
 * 于是"把描述改成空"会返回成功却什么都没改。
 * <p>本类最典型的受害场景是 {@link #updateCategory}：用户把描述清空、
 * 提交后看到"保存成功"，刷新却发现描述还在。用 Wrapper 显式列出要写的列
 * （{@code .set(...)} 对 null 也会生成 {@code SET x = NULL}）才能表达"清空"。
 */
@Service
public class CategoryServiceImpl extends BaseServiceImpl<CmsCategoryMapper, CmsCategory>
        implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    /** 分类的最大层级。库表 {@code level} 的注释是"1=一级 2=二级"，业务规则同样只到两级。 */
    private static final int MAX_LEVEL = 2;

    /** 顶级分类的 {@code parent_id} 约定值（不是 {@code null}，建表时的 DEFAULT 就是 0）。 */
    private static final Long ROOT_PARENT_ID = 0L;

    /**
     * 分类列表允许排序的列（<b>数据库列名</b>，不是 Java 属性名）。
     * <p>默认排序是 {@code sort ASC, id ASC} ——
     * {@code sort} 是业务人员手工调的展示顺序，它不是唯一值，
     * 所以必须再补 {@code id} 才能得到一个<b>确定的全序</b>，
     * 否则翻页时同一行可能出现在两页（MySQL 对"没有全序的 ORDER BY"不保证稳定顺序）。
     */
    private static final String[] LIST_SORTABLE_COLUMNS = {
            "sort", "level", "category_name", "create_time", "update_time", "id"
    };

    @Autowired
    private CmsArticleMapper articleMapper;

    // ==================== 一、查询 ====================

    @Override
    public PageResult<CategoryVO> pageCategories(CategoryQueryDTO query) {
        CategoryQueryDTO q = (query == null) ? new CategoryQueryDTO() : query;
        q.normalize();

        LambdaQueryWrapper<CmsCategory> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(q.getCategoryName())) {
            // ⚠️ 用 SqlLikeUtil 转义，不用 MP 的 .like()：
            //    .like() 生成 `LIKE CONCAT('%', ?, '%')`，用户传 "%" 会变成 '%%%'，
            //    "搜含百分号的分类"会静默变成"匹配所有分类"。理由详见 SqlLikeUtil。
            //    {0} 是 MP 的参数占位符（最终走 PreparedStatement），不是字符串拼接。
            wrapper.apply("category_name LIKE {0}", SqlLikeUtil.containsPattern(q.getCategoryName()));
        }
        // ⚠️ 判断必须是 != null 而不是 > 0：parentId = 0 是一个有意义的筛选值（"只看顶级分类"）
        if (q.getParentId() != null) {
            wrapper.eq(CmsCategory::getParentId, q.getParentId());
        }
        if (q.getStatus() != null) {
            wrapper.eq(CmsCategory::getStatus, q.getStatus());
        }
        if (q.getAuditStatus() != null) {
            wrapper.eq(CmsCategory::getAuditStatus, q.getAuditStatus());
        }

        Page<CmsCategory> mpPage = q.toMpPage(LIST_SORTABLE_COLUMNS);
        if (!q.hasSortField()) {
            mpPage.addOrder(OrderItem.asc("sort"), OrderItem.asc("id"));
        }

        IPage<CmsCategory> page = baseMapper.selectPage(mpPage, wrapper);
        List<CmsCategory> rows = page.getRecords();
        if (rows.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), page.getTotal(), page.getCurrent(), page.getSize());
        }

        // 一次 GROUP BY 拿到全部分类的文章数，再回填本页 ——
        // ⚠️ 不逐行查询（那是 N+1）。分类总量很小（种子 9 条），
        //    所以"查全部再筛"比"按本页 id 拼 IN"更简单，且没有 `IN ()` 空集合的语法风险。
        Map<Long, Long> counts = articleCountsByCategoryId();

        List<CategoryVO> list = new ArrayList<>(rows.size());
        for (CmsCategory row : rows) {
            list.add(toVO(row, counts.get(row.getId())));
        }
        log.debug("分类列表查询 | page={} size={} total={} 本页={} 条",
                page.getCurrent(), page.getSize(), page.getTotal(), list.size());
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public List<CategoryTreeVO> listTree() {
        // 一次查全部（不加分页）：分类是"配置类"数据，量级是几十条，
        // 加 LIMIT 反而会让树缺枝。排序与列表接口一致。
        // ⚠️ 按 parent_id 升序保证父节点一定排在子节点之前吗？—— 不保证（父 id 不一定小于子 id）。
        //    所以下面的挂载逻辑不依赖遍历顺序，而是"两趟扫描"（先建节点，再挂关系）。
        List<CmsCategory> all = baseMapper.selectList(Wrappers.<CmsCategory>lambdaQuery()
                .orderByAsc(CmsCategory::getSort)
                .orderByAsc(CmsCategory::getId));

        Map<Long, Long> counts = articleCountsByCategoryId();

        // 第一趟：每个分类生成一个节点（用 LinkedHashMap 保留 selectList 的顺序）
        Map<Long, CategoryTreeVO> nodes = new LinkedHashMap<>(Math.max(16, all.size() * 2));
        for (CmsCategory row : all) {
            CategoryTreeVO node = new CategoryTreeVO();
            fill(node, row, counts.get(row.getId()));
            nodes.put(row.getId(), node);
        }

        // 第二趟：把每个节点挂到父节点下；父节点找不到的（含 parent_id=0）当根
        List<CategoryTreeVO> roots = new ArrayList<>();
        for (CmsCategory row : all) {
            CategoryTreeVO node = nodes.get(row.getId());
            Long parentId = row.getParentId();
            CategoryTreeVO parent = (parentId == null || parentId == 0L) ? null : nodes.get(parentId);
            if (parent == null) {
                // ⚠️ 父节点不存在时**提取为根**，而不是丢掉这条数据。
                //    场景：父分类被逻辑删除了、或数据被手工改过造成孤儿节点。
                //    丢掉会让"明明库里有一条分类、树上却看不到"，属于最难排查的那种问题；
                //    提到根上至少用户能看到它、能去处理。
                roots.add(node);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        log.debug("分类树查询 | 分类总数={} 根节点={}", all.size(), roots.size());
        return roots;
    }

    @Override
    public CategoryVO getCategory(Long id) {
        CmsCategory category = requireCategory(id);
        return toVO(category, articleCountOf(id));
    }

    // ==================== 二、写入 ====================

    /**
     * 新建分类。
     *
     * <h3>⚠️ 两个"不由调用方决定"的字段</h3>
     * <ul>
     *   <li>{@code level}：由父级推导（父级 0 → 1；父级为一级 → 2）。
     *       若允许入参，就会出现"level=1 但 parent_id=1"这种自相矛盾的行。</li>
     *   <li>{@code audit_status}：本步固定为 1（已生效）。
     *       理由：分类审批流程本期未实现，若置 0（待审批）则新建的分类
     *       永远停在待审批、无法被文章选用，功能是断的。
     *       —— 这是<b>有意的取舍</b>，不是忘了，缺口已登记在实测记录里。</li>
     * </ul>
     *
     * <h3>⚠️ 同级重名检查有一个已知的并发窗口</h3>
     * <p>本方法用 {@code SELECT COUNT} 判断重名，然后 {@code INSERT}。
     * 严格来说这是"检查后使用"（TOCTOU）：两个请求同时通过检查、同时插入，
     * 就会得到两条同名分类。
     *
     * <p>为什么不在数据库上加唯一键彻底解决：
     * {@code cms_category} 当前只有 {@code idx_category_name}（普通索引），
     * 要加唯一键就是<b>改表结构</b>，而库表基线已在 S18 冻结、
     * 并被打进了部署文档与回归断言。本步选择"应用层查重 + 如实登记这个窗口"，
     * 而不是在一个补接口的步骤里悄悄改表。
     * <p>这会作为待办项写进实测记录（与内存里"唯一键残留"那条是同一类问题）。
     *
     * @return 新分类的 id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(CategorySaveDTO dto, Long operatorId) {
        requireOperator(operatorId);

        String name = dto.getCategoryName().trim();
        Long parentId = (dto.getParentId() == null) ? ROOT_PARENT_ID : dto.getParentId();

        int level = 1;
        if (!ROOT_PARENT_ID.equals(parentId)) {
            CmsCategory parent = requireCategory(parentId);
            if (parent.getLevel() != null && parent.getLevel() >= MAX_LEVEL) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "分类最多两级，不能给二级分类「" + parent.getCategoryName() + "」再挂子级");
            }
            if (!Integer.valueOf(1).equals(parent.getStatus())) {
                // 父级停用时不允许新增子分类：否则会出现"父级停用、子级启用"的矛盾态，
                // 而文章的级联筛选是按子级走的，父级停用就形同虚设。
                throw new BusinessException(ErrorCode.CATEGORY_DISABLED,
                        "父分类「" + parent.getCategoryName() + "」已停用，不能在其下新建子分类");
            }
            level = (parent.getLevel() == null ? 1 : parent.getLevel()) + 1;
        }

        requireNameUnique(parentId, name, null);

        CmsCategory entity = new CmsCategory();
        entity.setParentId(parentId);
        entity.setCategoryName(name);
        entity.setLevel(level);
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        entity.setDescription(dto.getDescription());
        entity.setStatus(1);
        entity.setAuditStatus(1);

        baseMapper.insert(entity);
        log.info("新建分类 | id={} | name={} | parentId={} | level={} | 操作人={}",
                entity.getId(), name, parentId, level, operatorId);
        return entity.getId();
    }

    /**
     * 编辑分类：改名称、排序、描述。
     *
     * <h3>⚠️ 为什么不支持改 {@code parentId}（移动分类）</h3>
     * <p>请求体里虽然带了 {@code parentId}，但只要它与库里当前的值不同，
     * 本方法就<b>明确拒绝（400）</b>，而不是静默忽略。理由：
     * <ol>
     *   <li><b>要级联更新整棵子树。</b>把一级分类 A 移到另一个父级下，
     *       A 的所有子分类的 {@code level} 都要重算 —— 漏一个就出现
     *       "level=2 但它的父级也是 level=2"这种破坏树不变式的数据。</li>
     *   <li><b>要防成环。</b>把 A 挂到 A 自己的子分类下，树会变成环，
     *       任何递归渲染都会无限递归（栈溢出/页面卡死）。
     *       防环要额外做一次"祖先链上有没有自己"的查询。</li>
     *   <li><b>收益低。</b>课程项目里"移动分类"不是高频操作，
     *       需要时"新建一个分类、把文章迁过去、删掉旧的"就够了。</li>
     * </ol>
     * <p>静默忽略比拒绝更糟：前端传了 {@code parentId} 却没生效，
     * 用户会以为移动成功了、刷新才发现还在原地 —— 又是"接口 200 但什么都没做"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(Long id, CategorySaveDTO dto, Long operatorId) {
        requireOperator(operatorId);
        CmsCategory category = requireCategory(id);

        if (dto.getParentId() != null && !dto.getParentId().equals(category.getParentId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "暂不支持调整分类的父级（需要级联更新整棵子树的层级），请新建分类后迁移文章");
        }

        String name = dto.getCategoryName().trim();
        requireNameUnique(category.getParentId(), name, id);

        // ⚠️ 必须用 Wrapper 显式列列，不能用 updateById(entity)：
        //    description 传 null 时 updateById 会跳过它，于是"清空描述"永远不生效，
        //    而接口返回 200 —— 项目里已把这个陷阱记为高频坑。
        //    LambdaUpdateWrapper.set(...) 对 null 会生成 SET description = NULL，正是我们要的。
        LambdaUpdateWrapper<CmsCategory> update = Wrappers.<CmsCategory>lambdaUpdate()
                .set(CmsCategory::getCategoryName, name)
                .set(CmsCategory::getSort, dto.getSort() == null ? 0 : dto.getSort())
                .set(CmsCategory::getDescription, dto.getDescription())
                .set(CmsCategory::getUpdateBy, operatorId)
                .set(CmsCategory::getUpdateTime, LocalDateTime.now())
                .eq(CmsCategory::getId, id);

        // ⚠️ 必须判返回值：0 代表"没有匹配到行"。
        //    这里前面已经 requireCategory 查过一次，理论上不可能为 0；
        //    但并发下（别人刚好把这条删了）确实会为 0 —— 那时应当报"分类不存在"，
        //    而不是静默返回 200 让前端以为改成功了。
        int rows = baseMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        log.info("编辑分类 | id={} | name={} | 操作人={}", id, name, operatorId);
    }

    /**
     * 启用 / 停用分类。
     *
     * <p>幂等：对已经是目标状态的分类再调一次，不报错、不重复写操作日志之外的行为差异
     * （会真的执行一次 UPDATE，但结果相同）。
     *
     * <p>⚠️ 停用前必须没有<b>未删除的子分类</b>：否则父级已停用、子级仍是启用态，
     * 而文章选用分类走的是子级 —— 停用父级就完全没有效果，属于"看起来生效了其实没有"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status, Long operatorId) {
        requireOperator(operatorId);
        CmsCategory category = requireCategory(id);

        if (Integer.valueOf(0).equals(status)) {
            long children = baseMapper.selectCount(Wrappers.<CmsCategory>lambdaQuery()
                    .eq(CmsCategory::getParentId, id));
            if (children > 0) {
                throw new BusinessException(ErrorCode.CATEGORY_HAS_CHILDREN,
                        "该分类下还有 " + children + " 个子分类，请先停用子分类");
            }
        }

        LambdaUpdateWrapper<CmsCategory> update = Wrappers.<CmsCategory>lambdaUpdate()
                .set(CmsCategory::getStatus, status)
                .set(CmsCategory::getUpdateBy, operatorId)
                .set(CmsCategory::getUpdateTime, LocalDateTime.now())
                .eq(CmsCategory::getId, id);

        int rows = baseMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        log.info("分类状态变更 | id={} | {} → {} | 操作人={}",
                id, category.getStatus(), status, operatorId);
    }

    /**
     * 逻辑删除分类。
     *
     * <h3>两道保护，顺序有意为之</h3>
     * <ol>
     *   <li><b>先查子分类</b> → 12002。先查它是因为"删父级"的错更明显、
     *       提示更具体（用户能立刻知道"我该先处理子分类"）。</li>
     *   <li><b>再查文章引用</b> → 12003。文章引用是"逻辑外键"
     *       （{@code cms_article.category_id} 上没有真实外键约束），
     *       数据库不会拦，只能应用层拦。</li>
     * </ol>
     *
     * <h3>⚠️ 为什么不级联删除子分类与文章</h3>
     * <p>级联删除让"删一个分类"变成"可能删掉一批文章"的不可预期操作 ——
     * 用户点的是一个小按钮，后果却是批量数据消失。所以本接口<b>只拒绝、不代劳</b>，
     * 让用户显式地先把文章迁走。
     *
     * <h3>⚠️ 逻辑删除要自己补审计列（不用 {@code deleteById}）</h3>
     * <p>MP 的 {@code deleteById} 生成的 SQL 是
     * {@code UPDATE cms_category SET deleted = 1 WHERE id = ?} ——
     * <b>SET 里只有 {@code deleted}</b>，{@code update_by} / {@code update_time} 一个都不填。
     * 后果是"分类被删了，但看不出是谁删的、什么时候删的"。
     * 这里用 Wrapper 显式补上两列。
     *
     * <p>⚠️ WHERE 里的 {@code deleted = 0} 由 MP 自动追加（{@code @TableLogic}），
     * 所以这一句只会命中"还活着的那一行"，返回 0 即代表已被别人删掉。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id, Long operatorId) {
        requireOperator(operatorId);
        CmsCategory category = requireCategory(id);

        long children = baseMapper.selectCount(Wrappers.<CmsCategory>lambdaQuery()
                .eq(CmsCategory::getParentId, id));
        if (children > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_CHILDREN,
                    "该分类下还有 " + children + " 个子分类，请先删除或调整子分类");
        }

        long articles = articleMapper.selectCount(Wrappers.<CmsArticle>lambdaQuery()
                .eq(CmsArticle::getCategoryId, id));
        if (articles > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_ARTICLES,
                    "该分类下还有 " + articles + " 篇文章，请先转移或删除这些文章");
        }

        LambdaUpdateWrapper<CmsCategory> update = Wrappers.<CmsCategory>lambdaUpdate()
                .setSql("deleted = 1")
                .set(CmsCategory::getUpdateBy, operatorId)
                .set(CmsCategory::getUpdateTime, LocalDateTime.now())
                .eq(CmsCategory::getId, id);

        int rows = baseMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        log.info("删除分类（逻辑）| id={} | name={} | 操作人={}", id, category.getCategoryName(), operatorId);
    }

    // ==================== 三、私有工具 ====================

    /**
     * 按 id 查分类，不存在直接抛 {@code CATEGORY_NOT_FOUND(12001)}。
     *
     * <p>抽成方法的意义与文章域的 {@code requireArticle} 相同：
     * "分类不存在抛哪个码"只有一处定义，且五个入口都能少写三行 if-throw。
     * <p>⚠️ MP 的 {@code selectById} 自带 {@code deleted = 0}，
     * 所以"id 不存在"与"id 存在但已逻辑删除"合并成同一个结果 —— 这是刻意的，
     * 对外不该暴露"这条 id 曾经存在过"。
     */
    private CmsCategory requireCategory(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类 ID 不能为空");
        }
        CmsCategory category = baseMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /**
     * 校验操作人存在（即"已登录"）。
     *
     * <p>与文章域 {@code requireOperator} 同理：Service 可能被非 HTTP 入口调用
     * （定时任务、别的 Service），那时 {@code operatorId} 会是 null，
     * 不在这里拦住就会写出 {@code create_by = null} 的脏行。
     */
    private void requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    /**
     * 同级重名检查。
     *
     * @param excludeId 编辑场景要排除自己，否则"不改名只改排序"会被自己判为重复
     */
    private void requireNameUnique(Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<CmsCategory> wrapper = Wrappers.<CmsCategory>lambdaQuery()
                .eq(CmsCategory::getParentId, parentId)
                .eq(CmsCategory::getCategoryName, name);
        if (excludeId != null) {
            wrapper.ne(CmsCategory::getId, excludeId);
        }
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATE,
                    "同级下已存在同名分类「" + name + "」");
        }
    }

    /** 单个分类的文章数（详情接口用；列表用批量版，避免 N+1）。 */
    private Long articleCountOf(Long categoryId) {
        return articleMapper.selectCount(Wrappers.<CmsArticle>lambdaQuery()
                .eq(CmsArticle::getCategoryId, categoryId));
    }

    /**
     * 全部分类的文章数（一次 {@code GROUP BY}，返回 categoryId → 文章数）。
     *
     * <p>⚠️ 刻意"查全部再筛本页"而不是"按本页 id 拼 {@code IN}"：
     * 后者的 {@code selectBatchIds(空集合)} 会拼出 {@code IN ()} ——
     * 那是 MySQL <b>语法错误</b>（不是"返回空"），项目在 S11 已经踩过一次。
     * 分类总量很小，全量聚合的开销可以忽略。
     *
     * <p>⚠️ 返回的 Map 可能<b>不含</b>本页某些分类（那些分类下没有文章）——
     * 调用方用 {@code map.get(id)} 拿到 null，VO 里显示为 null 而不是 0。
     * 这是刻意的：{@code null} 表示"没查到这个分组"，{@code 0} 表示"明确是 0 篇"。
     * 前端用 {@code ?? 0} 兜底即可。
     */
    private Map<Long, Long> articleCountsByCategoryId() {
        List<CategoryCountVO> rows = articleMapper.countAllGroupByCategoryId();
        Map<Long, Long> map = new HashMap<>(Math.max(8, rows.size() * 2));
        for (CategoryCountVO row : rows) {
            map.put(row.getCategoryId(), row.getCnt());
        }
        return map;
    }

    /** Entity → VO（含中文状态与文章数）。 */
    private CategoryVO toVO(CmsCategory src, Long articleCount) {
        CategoryVO vo = new CategoryVO();
        fill(vo, src, articleCount);
        return vo;
    }

    /**
     * 把实体字段搬到 VO 上。
     *
     * <p>参数收的是父类型 {@link CategoryVO}，所以列表 VO 与树 VO
     * （{@code CategoryTreeVO extends CategoryVO}）共用同一份映射 ——
     * 不会出现"列表改了字段树没跟上"。
     */
    private void fill(CategoryVO target, CmsCategory src, Long articleCount) {
        target.setId(src.getId());
        target.setParentId(src.getParentId());
        target.setCategoryName(src.getCategoryName());
        target.setLevel(src.getLevel());
        target.setSort(src.getSort());
        target.setDescription(src.getDescription());
        target.setStatus(src.getStatus());
        target.setStatusDesc(statusDesc(src.getStatus()));
        target.setAuditStatus(src.getAuditStatus());
        target.setAuditStatusDesc(auditStatusDesc(src.getAuditStatus()));
        target.setArticleCount(articleCount);
        target.setCreateTime(src.getCreateTime());
        target.setUpdateTime(src.getUpdateTime());
    }

    /**
     * 启用状态的中文。
     * <p>⚠️ 兜底给"未知"而不是抛异常：库里若出现 {@code 0/1} 之外的脏值，
     * 应当只影响这一个字段的展示，<b>不能让整个分类列表 500</b>。
     */
    private static String statusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        if (status == 1) {
            return "启用";
        }
        return status == 0 ? "停用" : "未知";
    }

    /** 审批状态的中文：0 待审批 / 1 已生效 / 2 已驳回。 */
    private static String auditStatusDesc(Integer auditStatus) {
        if (auditStatus == null) {
            return "未知";
        }
        switch (auditStatus) {
            case 0:
                return "待审批";
            case 1:
                return "已生效";
            case 2:
                return "已驳回";
            default:
                return "未知";
        }
    }
}
