package com.lab.cms.service;

import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.config.SpringConfig;
import com.lab.cms.dto.TagQueryDTO;
import com.lab.cms.dto.TagSaveDTO;
import com.lab.cms.vo.TagVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 【S24】标签管理接口的业务规则测试（真库，事务内回滚）。
 *
 * <h3>本类的三根"钉子"</h3>
 * <p>标签域是全项目<b>唯一</b>会主动绕过 MyBatis-Plus 逻辑删除的地方，
 * 三处绕行各有一个只能靠真库实测才能暴露的失败模式：
 * <ol>
 *   <li>{@link #testBindRevivesInsteadOfReinserting()} ——
 *       {@code cms_article_tag} 的 {@code uk_article_tag(article_id, tag_id, deleted)}
 *       决定了"取消过的标签不能靠 INSERT 重新打上"，否则第二次取消撞 1062。
 *       本用例断言 {@code (article_id, tag_id)} 在库里<b>永远恰好一行</b>。</li>
 *   <li>{@link #testDeleteTagThenRecreateSameNameTwice()} ——
 *       {@code uk_tag_name(tag_name, deleted)} 决定了"同名标签只能被逻辑删除一次"。
 *       第二次删除必须先把上一轮的墓碑物理清掉，否则撞 1062。</li>
 *   <li>{@link #testGetTagRealUseCountIsNotCachedUseCount()} ——
 *       {@code cms_tag.use_count} 是缓存列，S24 实测发现它与关联表真实分布
 *       <b>本就不一致</b>（种子数据即如此）。断言"真实数"取的是实时 COUNT，
 *       而不是读缓存列。</li>
 * </ol>
 * <p>⚠️ 本类所有断言都不依赖"库里总共有多少条"这类会随联调增长的数字，
 * 需要条数时一律算"我自己造出来的那几条"。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = SpringConfig.class)
@Transactional
public class TagServiceRuleTest {

    /** 编辑账号（种子里有 {@code category:manage}）。 */
    private static final Long OPERATOR_ID = 2L;

    /**
     * 种子中<b>无人引用</b>的标签：id=8「SQL优化」。
     * <p>实测：种子 10 个标签里 8 个被引用、{@code id=3(Vue3)} 与 {@code id=8(SQL优化)} 未被引用，
     * 关联表共 16 行。可删的只剩这两个。
     */
    private static final Long FREE_TAG_ID = 8L;

    /** 种子中被 3 篇文章引用的标签：id=1「Spring」。 */
    private static final Long REFERENCED_TAG_ID = 1L;

    /**
     * 演示文章：id=2（草稿）。
     * <p>实测：关联表已占用的文章是 1/3/5/7/8/11/13/19，<b>id=2 没有任何标签</b>，
     * 拿它做"打标序列"实验不会碰到种子数据。
     */
    private static final Long ARTICLE_ID = 2L;

    /** 另一篇同样没打过标签的文章（用于"空集合"场景）。 */
    private static final Long ARTICLE_ID_2 = 4L;

    /** 一定不存在的 id。 */
    private static final Long NOT_EXIST_ID = 99999999L;

    @Autowired
    private TagService tagService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    private String uniqueTagName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private TagSaveDTO dto(String name) {
        TagSaveDTO dto = new TagSaveDTO();
        dto.setTagName(name);
        return dto;
    }

    private List<Long> ids(Long... values) {
        List<Long> list = new ArrayList<>(values.length);
        for (Long v : values) {
            list.add(v);
        }
        return list;
    }

    // ==================== 私有工具：直接查库 ====================

    /** ⚠️ 不带 {@code deleted = 0} —— 要数的正是"含墓碑一共几行"。 */
    private int totalRelationRows(Long articleId, Long tagId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_tag WHERE article_id = ? AND tag_id = ?",
                Integer.class, articleId, tagId);
        return n == null ? 0 : n;
    }

    private int activeRelationRows(Long articleId, Long tagId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_tag WHERE article_id = ? AND tag_id = ? AND deleted = 0",
                Integer.class, articleId, tagId);
        return n == null ? 0 : n;
    }

    private int tagRowsByName(String name, int deleted) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_tag WHERE tag_name = ? AND deleted = ?",
                Integer.class, name, deleted);
        return n == null ? 0 : n;
    }

    private Integer useCountOf(Long tagId) {
        return jdbc().queryForObject("SELECT use_count FROM cms_tag WHERE id = ?", Integer.class, tagId);
    }

    private Integer deletedOfTag(Long tagId) {
        return jdbc().queryForObject("SELECT deleted FROM cms_tag WHERE id = ?", Integer.class, tagId);
    }

    private String tagNameOf(Long tagId) {
        return jdbc().queryForObject("SELECT tag_name FROM cms_tag WHERE id = ?", String.class, tagId);
    }

    // ==================== 一、⚠️ 打标序列：必须复活而不是重插 ====================

    /**
     * 本用例是"唯一键残留"问题的主判据。
     *
     * <p>序列：打标 → 取消 → 再打标 → 再取消。
     * <p>若 {@code bindArticleTags} 的 {@code toAdd} 分支直接 {@code INSERT}：
     * <ul>
     *   <li>第三步仍会成功（{@code (a,t,0)} 不与 {@code (a,t,1)} 冲突），
     *       但库里变成 <b>2 行</b> —— 本用例的"恰好一行"断言会失败；</li>
     *   <li>第四步会试图产生第二行 {@code (a,t,1)} ⇒ <b>1062</b>。</li>
     * </ul>
     */
    @Test
    public void testBindRevivesInsteadOfReinserting() {
        String name = uniqueTagName("S24复活");
        Long tagId = tagService.createTag(dto(name), OPERATOR_ID);
        assertEquals("新标签初始用量为 0", Integer.valueOf(0), useCountOf(tagId));

        // ① 打标
        assertEquals(1, tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID));
        assertEquals("① 库里应当有 1 行（含墓碑）", 1, totalRelationRows(ARTICLE_ID, tagId));
        assertEquals("① 有效行 1", 1, activeRelationRows(ARTICLE_ID, tagId));
        assertEquals("① use_count 应 +1", Integer.valueOf(1), useCountOf(tagId));

        // ② 取消（空集合 = 清空）
        assertEquals(0, tagService.bindArticleTags(ARTICLE_ID, new ArrayList<>(), OPERATOR_ID));
        assertEquals("② 墓碑行留下，总数仍是 1", 1, totalRelationRows(ARTICLE_ID, tagId));
        assertEquals("② 有效行 0", 0, activeRelationRows(ARTICLE_ID, tagId));
        assertEquals("② use_count 应 -1 归 0", Integer.valueOf(0), useCountOf(tagId));

        // ③ 再打标 —— 必须"复活"那一行，而不是插第二行
        assertEquals(1, tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID));
        assertEquals("⚠️ ③ 复活后库里仍应恰好 1 行 —— 若为 2 行说明走了 INSERT，"
                + "下一次取消会撞 uk_article_tag", 1, totalRelationRows(ARTICLE_ID, tagId));
        assertEquals("③ 有效行 1", 1, activeRelationRows(ARTICLE_ID, tagId));
        assertEquals("③ use_count 回到 1", Integer.valueOf(1), useCountOf(tagId));

        // ④ 再取消 —— 没有"复活"的话这一步就是 1062
        try {
            tagService.bindArticleTags(ARTICLE_ID, new ArrayList<>(), OPERATOR_ID);
        } catch (DuplicateKeyException e) {
            fail("第二次取消撞了 uk_article_tag —— 说明第③步是 INSERT 而非复活：" + e.getMessage());
        }
        assertEquals("④ 总数仍恰好 1", 1, totalRelationRows(ARTICLE_ID, tagId));
        assertEquals("④ 有效行 0", 0, activeRelationRows(ARTICLE_ID, tagId));
        assertEquals("④ use_count 归 0", Integer.valueOf(0), useCountOf(tagId));

        System.out.println("[S24] 打标序列「打→取消→再打→再取消」通过："
                + "(article=" + ARTICLE_ID + ", tag=" + tagId + ") 全程恰好 1 行");
    }

    @Test
    public void testBindIsIdempotentForSameSet() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24幂等")), OPERATOR_ID);

        tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID);
        assertEquals(Integer.valueOf(1), useCountOf(tagId));

        // 重复提交同一集合：差集两侧都为空 ⇒ 一条写都不发生，use_count 不该被重复加
        tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID);
        assertEquals("重复提交同一集合不应重复计数", Integer.valueOf(1), useCountOf(tagId));
        assertEquals("行数也不该变", 1, totalRelationRows(ARTICLE_ID, tagId));
        System.out.println("[S24] 覆盖式打标幂等：重复提交后 use_count 仍为 1");
    }

    @Test
    public void testBindDeduplicatesRepeatedIds() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24去重")), OPERATOR_ID);

        // 传重复 id 是常见前端失误：去重后必须只算一次
        int result = tagService.bindArticleTags(ARTICLE_ID, ids(tagId, tagId, tagId), OPERATOR_ID);
        assertEquals("返回值应为去重后的集合大小", 1, result);
        assertEquals("库里只应 1 行", 1, totalRelationRows(ARTICLE_ID, tagId));
        assertEquals("use_count 只 +1", Integer.valueOf(1), useCountOf(tagId));
        System.out.println("[S24] 重复 tagId 已去重，use_count 未被重复计数");
    }

    @Test
    public void testUseCountFloorAtZero() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24兜零")), OPERATOR_ID);
        tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID);
        assertEquals(Integer.valueOf(1), useCountOf(tagId));

        // 人为把缓存列打成 0（模拟历史漂移），再取消一次 ——
        // GREATEST(use_count - 1, 0) 应把它兜在 0，而不是 -1
        jdbc().update("UPDATE cms_tag SET use_count = 0 WHERE id = ?", tagId);
        tagService.bindArticleTags(ARTICLE_ID, new ArrayList<>(), OPERATOR_ID);

        assertEquals("use_count 不应变成负数", Integer.valueOf(0), useCountOf(tagId));
        System.out.println("[S24] use_count 减法兜底：漂移为 0 后再减仍为 0（未变负数）");
    }

    // ==================== 二、⚠️ 标签删除的墓碑清理 ====================

    /**
     * 本用例是"同名标签只能被逻辑删除一次"的主判据。
     *
     * <p>序列：新建 → 删除 → 再新建（同名）→ 再删除。
     * <p>第 3 步不会冲突（{@code (name,0)} 与 {@code (name,1)} 可以共存）；
     * <b>第 4 步才是关键</b>：它要产生第二行 {@code (name,1)} ⇒ 1062。
     * 必须先物理清掉上一轮的墓碑。
     */
    @Test
    public void testDeleteTagThenRecreateSameNameTwice() {
        String name = uniqueTagName("S24墓碑");

        Long id1 = tagService.createTag(dto(name), OPERATOR_ID);
        tagService.deleteTag(id1, OPERATOR_ID);
        assertEquals("第一次删除后应有 1 行墓碑", 1, tagRowsByName(name, 1));
        assertEquals("有效行应为 0", 0, tagRowsByName(name, 0));
        assertEquals("应被逻辑删除（行还在）", Integer.valueOf(1), deletedOfTag(id1));

        // 同名重建：不与墓碑冲突（唯一键最后一位不同）
        Long id2 = tagService.createTag(dto(name), OPERATOR_ID);
        assertNotNull(id2);
        assertTrue("应是一条新行", !id2.equals(id1));
        assertEquals("此时同名有效行 1", 1, tagRowsByName(name, 0));
        assertEquals("此时同名墓碑 1", 1, tagRowsByName(name, 1));

        // ⚠️ 关键一步：再删除。不先清墓碑就会撞 uk_tag_name
        try {
            tagService.deleteTag(id2, OPERATOR_ID);
        } catch (DuplicateKeyException e) {
            fail("第二次删除同名标签撞了 uk_tag_name —— purgeDeletedTombstones 未生效或顺序反了："
                    + e.getMessage());
        }

        assertEquals("第二次删除后同名有效行应为 0", 0, tagRowsByName(name, 0));
        assertEquals("⚠️ 同名墓碑应仍只有 1 行（旧的被物理清掉、新的补上），"
                + "若为 2 行说明墓碑没清干净", 1, tagRowsByName(name, 1));
        System.out.println("[S24] 「新建→删→重建→删」通过：同名墓碑始终只有 1 行");
    }

    @Test
    public void testDeleteTagRejectedWhenReferenced() {
        // 标签 1「Spring」被 3 篇文章引用（实测）
        try {
            tagService.deleteTag(REFERENCED_TAG_ID, OPERATOR_ID);
            fail("仍被文章引用的标签不应允许删除");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.CATEGORY_HAS_ARTICLES.getCode(), e.getCode());
            System.out.println("[S24] 被引用标签删除被拒：" + e.getMessage());
        }
        assertEquals("被拒后不应被逻辑删除", Integer.valueOf(0), deletedOfTag(REFERENCED_TAG_ID));
    }

    @Test
    public void testDeleteTagSucceedsWhenNotReferenced() {
        // id=8「SQL优化」实测无人引用
        Long refs = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_tag WHERE tag_id = ? AND deleted = 0",
                Long.class, FREE_TAG_ID);
        assertEquals("基线数据异常：标签 8 实测应无人引用", Long.valueOf(0L), refs);

        tagService.deleteTag(FREE_TAG_ID, OPERATOR_ID);
        assertEquals("应被逻辑删除", Integer.valueOf(1), deletedOfTag(FREE_TAG_ID));
        System.out.println("[S24] 无人引用的标签删除成功：id=" + FREE_TAG_ID);
    }

    // ==================== 三、标签基本 CRUD ====================

    @Test
    public void testCreateTagRejectsDuplicateName() {
        String name = uniqueTagName("S24重名标签");
        tagService.createTag(dto(name), OPERATOR_ID);

        try {
            tagService.createTag(dto(name), OPERATOR_ID);
            fail("同名标签应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.TAG_NAME_DUPLICATE.getCode(), e.getCode());
            System.out.println("[S24] 标签重名被拒：" + e.getMessage());
        }

        // ⚠️ 但墓碑不算"已存在"：删掉后同名可以重建（selectCount 自带 deleted = 0）
        Long id = jdbc().queryForObject(
                "SELECT id FROM cms_tag WHERE tag_name = ? AND deleted = 0", Long.class, name);
        tagService.deleteTag(id, OPERATOR_ID);
        Long rebuilt = tagService.createTag(dto(name), OPERATOR_ID);
        assertNotNull("墓碑不应阻止同名重建", rebuilt);
        System.out.println("[S24] 删除后同名重建成功，id=" + rebuilt);
    }

    @Test
    public void testCreateTagTrimsName() {
        String base = uniqueTagName("S24去空白");
        Long id = tagService.createTag(dto("   " + base + "  "), OPERATOR_ID);
        assertEquals("标签名应被 trim", base, tagNameOf(id));
        System.out.println("[S24] 标签名已 trim：" + tagNameOf(id));
    }

    @Test
    public void testCreateTagWithoutOperatorIsRejected() {
        try {
            tagService.createTag(dto(uniqueTagName("S24无操作人")), null);
            fail("operatorId 为 null 时应被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.UNAUTHORIZED.getCode(), e.getCode());
        }
    }

    @Test
    public void testUpdateTagAllowsOwnNameButRejectsOthers() {
        String nameA = uniqueTagName("S24改A");
        String nameB = uniqueTagName("S24改B");
        Long idA = tagService.createTag(dto(nameA), OPERATOR_ID);
        tagService.createTag(dto(nameB), OPERATOR_ID);

        // 改成别人的名字 → 拒
        try {
            tagService.updateTag(idA, dto(nameB), OPERATOR_ID);
            fail("改成别的标签的名字应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.TAG_NAME_DUPLICATE.getCode(), e.getCode());
        }

        // 保持自己的名字 → 允许（重名检查必须排除自己）
        tagService.updateTag(idA, dto(nameA), OPERATOR_ID);
        assertEquals("名字未变", nameA, tagNameOf(idA));
        System.out.println("[S24] 改标签名：撞别人名被拒、保持己名通过");
    }

    @Test
    public void testGetUnknownTagReturns12008() {
        try {
            tagService.getTag(NOT_EXIST_ID);
            fail("不存在的标签应报 12008");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.TAG_NOT_FOUND.getCode(), e.getCode());
        }
    }

    // ==================== 四、⚠️ 缓存列 vs 真实引用数 ====================

    /**
     * 钉住"展示用缓存、保护用真相"这条设计。
     *
     * <p>种子数据本身就存在漂移（实测 {@code cms_tag} 有 6 处
     * {@code use_count} 与关联表不一致），所以"缓存列等于真实数"这个断言
     * <b>本来就不成立</b>。本用例只断言"真实数 = 实时 COUNT"这一条不变量，
     * 并把缓存值打印出来 —— 两者不一致时正是可被发现的漂移。
     */
    @Test
    public void testGetTagRealUseCountIsNotCachedUseCount() {
        TagVO vo = tagService.getTag(FREE_TAG_ID);

        Long liveRefs = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article_tag WHERE tag_id = ? AND deleted = 0",
                Long.class, FREE_TAG_ID);

        assertEquals("realUseCount 必须来自关联表实时 COUNT，而不是 use_count 缓存列",
                liveRefs, vo.getRealUseCount());
        assertNotNull("缓存列 use_count 也应在（展示用）", vo.getUseCount());

        System.out.println("[S24] 标签 " + FREE_TAG_ID + "「" + vo.getTagName() + "」："
                + "缓存 use_count=" + vo.getUseCount() + "，真实引用数=" + vo.getRealUseCount()
                + (vo.getUseCount().equals(vo.getRealUseCount().intValue()) ? "（一致）" : "（⚠️ 存在漂移）"));

        // 详情里的状态中文
        assertEquals("启用", vo.getStatusDesc());
    }

    @Test
    public void testListArticleTagsAndUnknownArticle() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24列标签")), OPERATOR_ID);

        // 打标前：文章 4 实测没有标签 ⇒ 空集合（不是 null）
        List<TagVO> before = tagService.listArticleTags(ARTICLE_ID_2);
        assertNotNull(before);
        assertEquals("未打标时应返回空集合", 0, before.size());

        tagService.bindArticleTags(ARTICLE_ID_2, ids(tagId), OPERATOR_ID);
        List<TagVO> after = tagService.listArticleTags(ARTICLE_ID_2);
        assertEquals(1, after.size());
        assertEquals(tagId, after.get(0).getId());
        assertEquals(tagNameOf(tagId), after.get(0).getTagName());

        // 文章不存在 → 借文章域的 11001（本域造"标签不存在"会误导调用方）
        try {
            tagService.listArticleTags(NOT_EXIST_ID);
            fail("文章不存在应报 11001");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.ARTICLE_NOT_FOUND.getCode(), e.getCode());
        }
        System.out.println("[S24] 查文章标签：空集合 → 打标后 1 个；文章不存在报 11001");
    }

    // ==================== 五、打标的各种拒绝分支 ====================

    @Test
    public void testBindRejectedForUnknownArticle() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24文章不存在")), OPERATOR_ID);
        try {
            tagService.bindArticleTags(NOT_EXIST_ID, ids(tagId), OPERATOR_ID);
            fail("文章不存在应报 11001");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.ARTICLE_NOT_FOUND.getCode(), e.getCode());
        }
    }

    @Test
    public void testBindRejectedForUnknownTag() {
        try {
            tagService.bindArticleTags(ARTICLE_ID, ids(NOT_EXIST_ID), OPERATOR_ID);
            fail("标签不存在应报 12008");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.TAG_NOT_FOUND.getCode(), e.getCode());
            System.out.println("[S24] 打标时标签不存在被拒：" + e.getMessage());
        }
    }

    @Test
    public void testBindRejectedForDisabledTag() {
        Long tagId = tagService.createTag(dto(uniqueTagName("S24停用标签")), OPERATOR_ID);
        jdbc().update("UPDATE cms_tag SET status = 0 WHERE id = ?", tagId);

        try {
            tagService.bindArticleTags(ARTICLE_ID, ids(tagId), OPERATOR_ID);
            fail("停用的标签不应能被选中");
        } catch (BusinessException e) {
            // ⚠️ 借用的 12005（本段位没有"标签已停用"专用码，本步不新增码）
            assertEquals(ErrorCode.CATEGORY_DISABLED.getCode(), e.getCode());
            System.out.println("[S24] 停用标签打标被拒：" + e.getMessage());
        }
    }

    @Test
    public void testBindRejectedWhenOverLimit() {
        // 21 个 id —— 尺寸校验排在"存在性校验"之前，所以随便给 21 个不存在的 id
        // 也应当先拿到 400（而不是 12008）
        List<Long> tooMany = new ArrayList<>(21);
        for (int i = 1; i <= 21; i++) {
            tooMany.add(90000000L + i);
        }
        try {
            tagService.bindArticleTags(ARTICLE_ID, tooMany, OPERATOR_ID);
            fail("超过 20 个标签应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
            System.out.println("[S24] 超上限打标被拒：" + e.getMessage());
        }
    }

    @Test
    public void testBindRejectedForNullTagIdInList() {
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);
        try {
            tagService.bindArticleTags(ARTICLE_ID, withNull, OPERATOR_ID);
            fail("tagIds 含 null 应当被拒");
        } catch (BusinessException e) {
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        }
    }

    // ==================== 六、列表查询 ====================

    @Test
    public void testPageTagsEscapesLikeWildcard() {
        // 造一对"只差一个字符"的标签：一个含字面量 %，一个含 X。
        // 若 LIKE 没转义 %，搜 "100%" 会同时命中两条（% 被当成通配符）。
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String withPercent = "S24pct-100%" + uid;
        String withX = "S24pct-100X" + uid;
        tagService.createTag(dto(withPercent), OPERATOR_ID);
        tagService.createTag(dto(withX), OPERATOR_ID);

        TagQueryDTO q1 = new TagQueryDTO();
        q1.setTagName("100%" + uid);
        q1.setSize(100);
        List<TagVO> hit = tagService.pageTags(q1).getRecords();
        assertEquals("含 % 的关键字应只命中 1 条（% 必须被转义成字面量）", 1, hit.size());
        assertEquals(withPercent, hit.get(0).getTagName());

        TagQueryDTO q2 = new TagQueryDTO();
        q2.setTagName("100X" + uid);
        q2.setSize(100);
        List<TagVO> hit2 = tagService.pageTags(q2).getRecords();
        assertEquals(1, hit2.size());
        assertEquals(withX, hit2.get(0).getTagName());

        System.out.println("[S24] LIKE 通配符已转义：搜「100%」只命中含字面量 % 的那条");
    }

    @Test
    public void testPageTagsFiltersByStatusAndOrdersByUseCount() {
        Long id = tagService.createTag(dto(uniqueTagName("S24停用筛")), OPERATOR_ID);
        jdbc().update("UPDATE cms_tag SET status = 0 WHERE id = ?", id);

        TagQueryDTO q = new TagQueryDTO();
        q.setStatus(0);
        q.setSize(100);
        List<TagVO> disabled = tagService.pageTags(q).getRecords();
        assertTrue("停用筛选应至少命中自己造的那条", disabled.size() >= 1);
        for (TagVO vo : disabled) {
            assertEquals("status=0 的筛选应当只返回停用标签", Integer.valueOf(0), vo.getStatus());
        }

        // 默认排序：use_count 倒序（并列时按 id 倒序，保证全序）
        TagQueryDTO all = new TagQueryDTO();
        all.setSize(100);
        List<TagVO> allTags = tagService.pageTags(all).getRecords();
        assertTrue("应有标签", allTags.size() >= 2);
        for (int i = 1; i < allTags.size(); i++) {
            Integer prev = allTags.get(i - 1).getUseCount();
            Integer cur = allTags.get(i).getUseCount();
            assertTrue("默认排序应按 use_count 倒序：位置 " + i
                            + " 出现 " + prev + " → " + cur,
                    prev >= cur);
        }
        System.out.println("[S24] 标签列表：status=0 筛选命中 " + disabled.size()
                + " 条；默认 use_count 倒序（共 " + allTags.size() + " 条）");
    }

    @Test
    public void testPageTagsFiltersByNameAndReturnsTotal() {
        String name = uniqueTagName("S24按名查");
        tagService.createTag(dto(name), OPERATOR_ID);

        TagQueryDTO q = new TagQueryDTO();
        q.setTagName(name);
        q.setSize(100);
        assertEquals("按唯一名应该正好 1 条", Long.valueOf(1L), tagService.pageTags(q).getTotal());

        TagQueryDTO none = new TagQueryDTO();
        none.setTagName(uniqueTagName("S24根本不存在"));
        assertEquals("查不到时应 total=0 且 records 为空",
                Long.valueOf(0L), tagService.pageTags(none).getTotal());
        assertEquals(0, tagService.pageTags(none).getRecords().size());
    }

    @Test
    public void testNullQueryFallsBackToDefaults() {
        // query 传 null 应当等价于空条件（而不是 NPE）
        assertNotNull(tagService.pageTags(null));
        assertTrue("空条件下应有记录", tagService.pageTags(null).getTotal() > 0);
        assertNull("TagVO 无 realUseCount 回填时字段应为 null（列表不逐行 COUNT）",
                tagService.pageTags(null).getRecords().get(0).getRealUseCount());
    }
}
