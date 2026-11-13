package com.lab.cms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.SpringConfig;
import com.lab.cms.dto.CategoryQueryDTO;
import com.lab.cms.dto.CategorySaveDTO;
import com.lab.cms.entity.CmsArticle;
import com.lab.cms.entity.CmsCategory;
import com.lab.cms.mapper.CmsArticleMapper;
import com.lab.cms.mapper.CmsCategoryMapper;
import com.lab.cms.vo.CategoryTreeVO;
import com.lab.cms.vo.CategoryVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 【S24】分类管理接口的业务规则测试（真库，事务内回滚）。
 *
 * <h3>为什么用 {@code @Transactional} 而不是"造完自己清理"</h3>
 * <p>本类的每个用例都在事务里跑，方法结束时自动回滚 ——
 * 不需要写 {@code @After} 物理删除，也不会给基线数据留下残留。
 * <p>⚠️ 这条能成立的前提是"被测代码不自己开新事务"：
 * {@code CategoryServiceImpl} 上的 {@code @Transactional} 是默认传播（REQUIRED），
 * 会<b>加入</b>测试的事务而不是新建一个 —— 所以回滚有效。
 * 若哪天有人把某个方法改成 {@code REQUIRES_NEW}，本类的清理保证就失效了
 * （这是 {@code AbstractArticleApiTest} 类注释里同样提醒过的一条边界）。
 *
 * <h3>本类专门钉住的两个"静默失效"陷阱</h3>
 * <ol>
 *   <li><b>{@code updateById} 跳过 null 字段</b> → "把描述清空"会返回成功却没改。
 *       用例 {@link #testUpdateClearsDescriptionAndSortToNullishValues()} 直接查库断言。</li>
 *   <li><b>逻辑删除不填审计列</b> → "删了但看不出谁删的"。
 *       用例 {@link #testDeleteWritesAuditColumns()} 断言 {@code update_by}。</li>
 * </ol>
 * <p>两条都是"接口返回 200、日志无异常、只有库里不对"的典型，
 * 靠肉眼 review 很难发现，必须用断言钉住。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = SpringConfig.class)
@Transactional
public class CategoryServiceRuleTest {

    /** 编辑账号：种子数据里它有 category:manage。 */
    private static final Long EDITOR_ID = 2L;

    /** 演示用的顶级分类（"技术资讯"，种子里 level=1）。 */
    private static final Long TOP_CATEGORY_ID = 1L;

    /**
     * 种子数据里"挂了文章"的叶子分类（id=12「后端开发」，实测 7 篇）。
     *
     * <p>⚠️ 实测发现：<b>种子文章全部挂在二级分类上，顶级分类（1/2/3）下没有任何文章</b>。
     * 这是一条业务事实（文章挂叶子、顶级只做聚合），不是代码强制的约束 ——
     * 因此本类不再假设"顶级分类下有文章"，需要文章的场景一律用本常量。
     * <p>断言只写 {@code > 0} 而不写具体条数：Task #72 真机联调会往库里新增文章，
     * 硬编码 7 会在那之后变成假失败。
     */
    private static final Long LEAF_CATEGORY_WITH_ARTICLES = 12L;

    /** 一定不存在的 id。 */
    private static final Long NOT_EXIST_ID = 99999999L;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CmsCategoryMapper categoryMapper;

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private CategorySaveDTO dto(String name, Long parentId) {
        CategorySaveDTO dto = new CategorySaveDTO();
        dto.setCategoryName(name);
        dto.setParentId(parentId);
        dto.setSort(5);
        return dto;
    }

    // ==================== 一、新建与层级 ====================

    @Test
    public void testCreateComputesLevelAndAppearsInTree() {
        String top = uniqueName("S24顶级");
        String child = uniqueName("S24子级");

        Long topId = categoryService.createCategory(dto(top, 0L), EDITOR_ID);
        assertNotNull(topId);
        Long childId = categoryService.createCategory(dto(child, topId), EDITOR_ID);

        // level 由 parentId 推导，不由入参决定
        // ⚠️ 两侧都用 Integer.valueOf：若写成 `1, levelOf(...)`（基本类型字面量 + 包装类型）
        //    会同时匹配 assertEquals(String,long,long) 与 assertEquals(String,Object,Object) → 编译期歧义
        assertEquals("顶级分类 level 应为 1", Integer.valueOf(1), levelOf(topId));
        assertEquals("二级分类 level 应为 2", Integer.valueOf(2), levelOf(childId));

        // 挂在正确的父节点下
        List<CategoryTreeVO> tree = categoryService.listTree();
        CategoryTreeVO topNode = findNode(tree, topId);
        assertNotNull("新建的顶级分类应出现在树根上", topNode);
        assertNotNull("顶级分类应带上 children", topNode.getChildren());
        assertEquals("顶级分类下应有 1 个子节点", 1, topNode.getChildren().size());
        assertEquals(childId, topNode.getChildren().get(0).getId());

        // 叶子节点没有 children（Jackson 会因此不输出该字段）
        assertNull("叶子节点的 children 应为 null（而不是空数组）",
                topNode.getChildren().get(0).getChildren());

        System.out.println("[S24] 分类树：顶级 id=" + topId + "（level=" + levelOf(topId)
                + "），子级 id=" + childId + "（level=" + levelOf(childId) + "）");
    }

    @Test
    public void testCreateRejectsThirdLevel() {
        Long topId = categoryService.createCategory(dto(uniqueName("S24一级"), 0L), EDITOR_ID);
        Long midId = categoryService.createCategory(dto(uniqueName("S24二级"), topId), EDITOR_ID);

        try {
            categoryService.createCategory(dto(uniqueName("S24三级"), midId), EDITOR_ID);
            fail("给二级分类再挂子级应当被拒（本项目只支持两级）");
        } catch (BusinessException e) {
            assertEquals("应返回 400 参数错误", ErrorCode.PARAM_ERROR.getCode(), e.getCode());
            System.out.println("[S24] 三级分类被拒：" + e.getMessage());
        }
    }

    @Test
    public void testCreateRejectsDuplicateNameAmongSiblings() {
        String name = uniqueName("S24重名");
        categoryService.createCategory(dto(name, 0L), EDITOR_ID);

        try {
            categoryService.createCategory(dto(name, 0L), EDITOR_ID);
            fail("同级重名应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_NAME_DUPLICATE.getCode(), e.getCode());
            System.out.println("[S24] 同级重名被拒：" + e.getMessage());
        }

        // ⚠️ 关键补充：**不同父级下同名是允许的**（cms_category 上没有唯一键，
        //    只有 idx_category_name 普通索引）。这条断言把"同级"这个限定钉住。
        Long anotherParent = categoryService.createCategory(dto(uniqueName("S24另一父"), 0L), EDITOR_ID);
        Long sameNameElsewhere = categoryService.createCategory(dto(name, anotherParent), EDITOR_ID);
        assertNotNull("不同父级下允许同名分类", sameNameElsewhere);
        System.out.println("[S24] 不同父级下同名分类创建成功，id=" + sameNameElsewhere);
    }

    @Test
    public void testCreateRejectsDisabledParent() {
        Long parentId = categoryService.createCategory(dto(uniqueName("S24待停用父"), 0L), EDITOR_ID);
        categoryService.changeStatus(parentId, 0, EDITOR_ID);

        try {
            categoryService.createCategory(dto(uniqueName("S24停用父下的子"), parentId), EDITOR_ID);
            fail("父分类停用时不应允许新建子分类");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_DISABLED.getCode(), e.getCode());
            System.out.println("[S24] 停用父级下建子级被拒：" + e.getMessage());
        }
    }

    @Test
    public void testCreateRejectsUnknownParent() {
        try {
            categoryService.createCategory(dto(uniqueName("S24孤儿"), NOT_EXIST_ID), EDITOR_ID);
            fail("父分类不存在时应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        }
    }

    @Test
    public void testCreateWithoutOperatorIsRejected() {
        try {
            categoryService.createCategory(dto(uniqueName("S24无操作人"), 0L), null);
            fail("operatorId 为 null 时应被拒（Service 可能被非 HTTP 入口调用）");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.UNAUTHORIZED.getCode(), e.getCode());
        }
    }

    // ==================== 二、编辑（钉住 updateById 跳过 null 的陷阱） ====================

    /**
     * ⚠️ 本用例是"静默失效"陷阱的判据。
     *
     * <p>若把 {@code CategoryServiceImpl#updateCategory} 改成
     * {@code updateById(entity)}（把 description 设为 null），
     * 这条断言会失败 —— 因为 MP 的 {@code FieldStrategy.NOT_NULL}
     * 会让 null 字段根本不进 SET 子句：接口返回 200，库里描述还在。
     */
    @Test
    public void testUpdateClearsDescriptionAndSortToNullishValues() {
        String name = uniqueName("S24清空描述");
        CategorySaveDTO create = dto(name, 0L);
        create.setDescription("这一段稍后要被清空");
        Long id = categoryService.createCategory(create, EDITOR_ID);
        assertEquals("这一段稍后要被清空", descriptionOf(id));

        // 第二次把 description 传 null（"清空"）、sort 传 null（"归 0"）
        CategorySaveDTO update = new CategorySaveDTO();
        update.setCategoryName(name);
        update.setParentId(0L);
        update.setSort(null);
        update.setDescription(null);
        categoryService.updateCategory(id, update, EDITOR_ID);

        assertNull("⚠️ 描述应被真正清空为 NULL —— 若这里是「这一段稍后要被清空」，"
                + "说明 updateById 的 NOT_NULL 策略又把 null 跳过了", descriptionOf(id));
        assertEquals("sort 传 null 应归 0", Integer.valueOf(0), sortOf(id));
        System.out.println("[S24] 描述已清空为 NULL、sort 已归 0 —— updateById 陷阱未被踩中");
    }

    @Test
    public void testUpdateRejectsParentChange() {
        Long id = categoryService.createCategory(dto(uniqueName("S24拒绝改父级"), 0L), EDITOR_ID);
        CategorySaveDTO update = dto(uniqueName("S24拒绝改父级-new"), TOP_CATEGORY_ID);

        try {
            categoryService.updateCategory(id, update, EDITOR_ID);
            fail("调整父级应当被明确拒绝（400），而不是静默忽略");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
            System.out.println("[S24] 改父级被拒：" + e.getMessage());
        }
        // 名字也不该被改掉（整条请求被拒，不留半成品）
        assertEquals("请求被拒时不应发生部分更新", Long.valueOf(0L), parentIdOf(id));
    }

    @Test
    public void testUpdateKeepsOwnNameAllowed() {
        String name = uniqueName("S24只改排序");
        Long id = categoryService.createCategory(dto(name, 0L), EDITOR_ID);
        CategorySaveDTO update = new CategorySaveDTO();
        update.setCategoryName(name);      // 名字没改
        update.setParentId(0L);
        update.setSort(99);
        // 不应被判为"与自己重名"
        categoryService.updateCategory(id, update, EDITOR_ID);
        assertEquals(Integer.valueOf(99), sortOf(id));
        System.out.println("[S24] 不改名只改排序通过 —— 重名检查正确排除了自己");
    }

    // ==================== 三、启停用 ====================

    @Test
    public void testChangeStatusRejectsWhenHasChildren() {
        Long parentId = categoryService.createCategory(dto(uniqueName("S24停用有子级"), 0L), EDITOR_ID);
        categoryService.createCategory(dto(uniqueName("S24它的子级"), parentId), EDITOR_ID);

        try {
            categoryService.changeStatus(parentId, 0, EDITOR_ID);
            fail("有子分类时不应允许停用父分类");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN.getCode(), e.getCode());
            System.out.println("[S24] 停用有子级的分类被拒：" + e.getMessage());
        }
        assertEquals("被拒后状态不应变化", Integer.valueOf(1), statusOf(parentId));
    }

    @Test
    public void testChangeStatusRoundTrip() {
        Long id = categoryService.createCategory(dto(uniqueName("S24启停"), 0L), EDITOR_ID);
        assertEquals(Integer.valueOf(1), statusOf(id));

        categoryService.changeStatus(id, 0, EDITOR_ID);
        assertEquals(Integer.valueOf(0), statusOf(id));

        categoryService.changeStatus(id, 1, EDITOR_ID);
        assertEquals(Integer.valueOf(1), statusOf(id));
        System.out.println("[S24] 启 → 停 → 启 往返正常");

        // ⚠️ 状态由 Service 显式 set，且**必须**能写回 0 ——
        //    若用 updateById，status=0 会被 NOT_NULL 放过（0 不是 null，这点没问题），
        //    但为了口径统一本类一律用 Wrapper；这里顺带验证 0 确实写进去了。
    }

    // ==================== 四、删除 ====================

    @Test
    public void testDeleteRejectsWhenHasChildren() {
        Long parentId = categoryService.createCategory(dto(uniqueName("S24删有子级"), 0L), EDITOR_ID);
        categoryService.createCategory(dto(uniqueName("S24删它的子级"), parentId), EDITOR_ID);

        try {
            categoryService.deleteCategory(parentId, EDITOR_ID);
            fail("有子分类时不应允许删除");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN.getCode(), e.getCode());
        }
        assertEquals("被拒后不应被逻辑删除", Integer.valueOf(0), deletedOf(parentId));
    }

    @Test
    public void testDeleteRejectsWhenArticlesExist() {
        // ⚠️ 用叶子分类 12（实测 7 篇）而不是顶级分类 1 ——
        //    实测：种子文章全部挂在二级分类上，分类 1 下 0 篇。
        //    这条断言前半段顺便兜住"基线数据被破坏"。
        Long articles = articleMapper.selectCount(Wrappers.<CmsArticle>lambdaQuery()
                .eq(CmsArticle::getCategoryId, LEAF_CATEGORY_WITH_ARTICLES));
        assertTrue("基线数据异常：分类 " + LEAF_CATEGORY_WITH_ARTICLES + " 下应当有种子文章", articles > 0);

        try {
            categoryService.deleteCategory(LEAF_CATEGORY_WITH_ARTICLES, EDITOR_ID);
            fail("有文章引用的分类不应允许删除");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_HAS_ARTICLES.getCode(), e.getCode());
            System.out.println("[S24] 有文章的分类删除被拒：" + e.getMessage());
        }
        assertEquals("被拒后不应被逻辑删除", Integer.valueOf(0), deletedOf(LEAF_CATEGORY_WITH_ARTICLES));
    }

    /**
     * ⚠️ 本用例钉住第二个静默失效陷阱：<b>逻辑删除要自己补审计列</b>。
     *
     * <p>若改用 MP 的 {@code deleteById}，生成的 SQL 是
     * {@code UPDATE cms_category SET deleted = 1 WHERE id = ?} ——
     * {@code update_by} / {@code update_time} 都不会被写入，
     * 于是"谁删的"永远查不到，而接口一切正常。
     */
    @Test
    public void testDeleteWritesAuditColumns() {
        Long id = categoryService.createCategory(dto(uniqueName("S24删并留痕"), 0L), EDITOR_ID);

        // 造数据时 create_by 由 MetaObjectHandler 填（无 HTTP 上下文 → 这里是测试线程，
        // 拿不到登录用户，所以可能是 null 或 0；这里只断言"删除动作"留下的痕迹）
        categoryService.deleteCategory(id, EDITOR_ID);

        assertEquals("应被逻辑删除", Integer.valueOf(1), deletedOf(id));
        assertEquals("⚠️ update_by 必须等于操作人 —— 若为 null 说明用了 MP 的 deleteById",
                EDITOR_ID, updateByOf(id));
        assertNotNull("⚠️ update_time 必须被写入", updateTimeOf(id));
        System.out.println("[S24] 逻辑删除留下痕迹：deleted=1, update_by=" + updateByOf(id));
    }

    @Test
    public void testDeleteThenNotVisibleInList() {
        String name = uniqueName("S24删后不可见");
        Long id = categoryService.createCategory(dto(name, 0L), EDITOR_ID);
        categoryService.deleteCategory(id, EDITOR_ID);

        CategoryQueryDTO query = new CategoryQueryDTO();
        query.setCategoryName(name);
        assertEquals("逻辑删除后列表应查不到", 0, categoryService.pageCategories(query).getTotal().intValue());

        try {
            categoryService.getCategory(id);
            fail("逻辑删除后按 id 查详情应报 12001");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        }
        System.out.println("[S24] 逻辑删除后：列表 0 条、详情 12001");
    }

    // ==================== 五、查询 ====================

    @Test
    public void testGetCategoryFillsArticleCountAndStatusDesc() {
        // 详情接口要能回填三样东西：状态中文、审批中文、文章数（实时 COUNT，不是缓存列）
        CategoryVO vo = categoryService.getCategory(LEAF_CATEGORY_WITH_ARTICLES);
        assertNotNull(vo);
        assertEquals("后端开发", vo.getCategoryName());   // 种子 id=12 的名字
        assertEquals("启用", vo.getStatusDesc());
        assertEquals("已生效", vo.getAuditStatusDesc());
        assertNotNull("应带上文章数", vo.getArticleCount());
        assertTrue("分类 " + LEAF_CATEGORY_WITH_ARTICLES + " 下应当有文章（回填未生效会是 0）",
                vo.getArticleCount() > 0);
        System.out.println("[S24] 分类详情：name=" + vo.getCategoryName()
                + " statusDesc=" + vo.getStatusDesc()
                + " auditStatusDesc=" + vo.getAuditStatusDesc()
                + " articleCount=" + vo.getArticleCount());
    }

    @Test
    public void testTopLevelCategoryHasZeroDirectArticles() {
        // ⚠️ 把"文章只挂叶子分类"这条业务事实钉成断言：
        //    顶级分类的文章数应当为 0（它只是聚合容器）。
        //    若哪天有人往顶级分类挂文章，这条会失败并提示口径变化 —— 这正是我们想知道的。
        CategoryVO top = categoryService.getCategory(TOP_CATEGORY_ID);
        assertEquals("技术资讯", top.getCategoryName());
        assertEquals("实测：种子文章全部挂二级分类，顶级分类直接挂载数应为 0",
                Long.valueOf(0L), top.getArticleCount());
        System.out.println("[S24] 顶级分类「" + top.getCategoryName() + "」直接挂载文章数 = "
                + top.getArticleCount() + "（文章只挂叶子分类）");
    }

    @Test
    public void testPageFiltersByParentIdZeroMeansTopLevel() {
        CategoryQueryDTO query = new CategoryQueryDTO();
        query.setParentId(0L);
        query.setSize(100);
        List<CategoryVO> records = categoryService.pageCategories(query).getRecords();
        assertTrue("应当有顶级分类", !records.isEmpty());
        for (CategoryVO vo : records) {
            // ⚠️ 这条断言钉住"parentId=0 是有效筛选值"这个语义：
            //    若实现里写成 `if (parentId > 0)`，这条会失败（会返回全部而非只看顶级）
            assertEquals("parentId=0 的筛选应当只返回顶级分类",
                    Long.valueOf(0L), vo.getParentId());
        }
        System.out.println("[S24] parentId=0 筛出 " + records.size() + " 个顶级分类");
    }

    @Test
    public void testGetUnknownCategoryReturns12001() {
        try {
            categoryService.getCategory(NOT_EXIST_ID);
            fail("不存在的分类应报 12001");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), e.getCode());
        }
    }

    // ==================== 私有工具：直接查库（绕过 MP 的逻辑删除包装） ====================

    /** ⚠️ 这些查询刻意<b>不带 {@code deleted = 0}</b>，因为要验证的就是"删除标记本身"。 */
    private Integer levelOf(Long id) {
        return jdbc().queryForObject("SELECT level FROM cms_category WHERE id = ?", Integer.class, id);
    }

    private Long parentIdOf(Long id) {
        Number n = jdbc().queryForObject("SELECT parent_id FROM cms_category WHERE id = ?", Number.class, id);
        return n == null ? null : n.longValue();
    }

    private Integer sortOf(Long id) {
        return jdbc().queryForObject("SELECT sort FROM cms_category WHERE id = ?", Integer.class, id);
    }

    private String descriptionOf(Long id) {
        return jdbc().queryForObject("SELECT description FROM cms_category WHERE id = ?", String.class, id);
    }

    private Integer statusOf(Long id) {
        return jdbc().queryForObject("SELECT status FROM cms_category WHERE id = ?", Integer.class, id);
    }

    private Integer deletedOf(Long id) {
        return jdbc().queryForObject("SELECT deleted FROM cms_category WHERE id = ?", Integer.class, id);
    }

    private Long updateByOf(Long id) {
        Number n = jdbc().queryForObject("SELECT update_by FROM cms_category WHERE id = ?", Number.class, id);
        return n == null ? null : n.longValue();
    }

    private Object updateTimeOf(Long id) {
        return jdbc().queryForObject("SELECT update_time FROM cms_category WHERE id = ?", Object.class, id);
    }

    /** 在树里按 id 深度优先找节点。 */
    private CategoryTreeVO findNode(List<CategoryTreeVO> nodes, Long id) {
        if (nodes == null) {
            return null;
        }
        for (CategoryTreeVO node : nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
            CategoryTreeVO found = findNode(node.getChildren(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 保留给"用 mapper 直接查"的用例（当前未用到，但避免 import 被误删后编译失败）。 */
    @SuppressWarnings("unused")
    private CmsCategory selectById(Long id) {
        return categoryMapper.selectById(id);
    }
}
