package com.lab.cms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.dto.CmsArticleSaveDTO;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 文章接口层测试（S19）。对应手册 S19 四类测试中的
 * 「3. ArticleControllerTest —— 用 MockMvc 做接口层测试」。
 *
 * <h2>为什么必须补这一层（S18 的机械扫描给出了证据）</h2>
 * <p>本类之前，全仓 18 个测试类、254 个用例，<b>{@code MockMvc} 出现次数为 0</b>。
 * 也就是说：拦截器、{@code @RequirePermission} 切面、{@code @Valid}、
 * {@code GlobalExceptionHandler}、Jackson 时间格式 ——
 * <b>所有"只存在于 Web 层"的组件，自动化测试一个都没覆盖</b>。
 * 而 S12/S13 真实踩过的坑恰恰都在这一段（排期时间带 {@code T} 返回 400、
 * 越权角色返回 403 还是 11003 取决于校验顺序）。
 *
 * <h2>本类覆盖的六组</h2>
 * <ol>
 *   <li>统一返回体结构（成功与失败两份都要有 code/message/timestamp）</li>
 *   <li>{@code @Valid} 校验失败 → HTTP 400（六个接口各一条）</li>
 *   <li>业务异常 → 正确业务码（11001/11002/11003/11004/11007/11009/11011）</li>
 *   <li>权限三态：401（未登录）/ 403（垂直越权）/ 11003（水平越权）</li>
 *   <li>请求体格式错误（时间格式）→ 400，不是 500</li>
 *   <li>审计字段：HTTP 链路上 {@code create_by} 是<b>登录用户</b>；
 *       无 HTTP 上下文时按约定落到 <b>0</b>（手册 S19 问题 2 的实测答案）</li>
 * </ol>
 *
 * <p>回滚机制、上下文装配、登录态来源见父类 {@link AbstractArticleApiTest} 的类注释。
 */
public class ArticleControllerTest extends AbstractArticleApiTest {

    // ==================== 一、统一返回体结构 ====================

    /**
     * 成功响应必须是 {@code {code, message, data, timestamp}}。
     *
     * <p>用列表接口做样本：它是全项目唯一"返回分页"的接口，
     * 响应体最深（{@code data.records[]}），结构最容易被改坏。
     */
    @Test
    public void testUnifiedResultShapeOnSuccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .session(loginAs(ADMIN_ID)))
                .andReturn();

        assertEquals("列表接口应返回 HTTP 200", 200, result.getResponse().getStatus());
        assertResultShape(result);

        JsonNode json = body(result);
        assertEquals("成功业务码应为 200", 200, json.get("code").asInt());
        assertEquals("未指定自定义文案时应是固定文案「操作成功」",
                "操作成功", json.get("message").asText());

        JsonNode data = json.get("data");
        assertNotNull("分页数据不应为 null", data);
        assertTrue("分页数据应含 records 数组", data.get("records").isArray());
        assertTrue("分页数据应含 total", data.has("total"));
        assertTrue("分页数据应含 current", data.has("current"));
        assertTrue("分页数据应含 size", data.has("size"));
        assertTrue("分页数据应含 pages", data.has("pages"));
    }

    /**
     * 失败响应也必须是同一个结构 —— 前端拦截器只认 {@code code}，
     * 若失败时返回别的形状（例如 Spring 默认的 error JSON），前端会解析失败。
     */
    @Test
    public void testUnifiedResultShapeOnBusinessFailure() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles/" + NOT_EXIST_ID)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();

        assertResultShape(result);
        assertCode(result, 11001);
        assertEquals("11001 是业务码，HTTP 应保持 200（10xxx 段不同步到 HTTP 状态）",
                200, result.getResponse().getStatus());
    }

    // ==================== 二、@Valid 校验失败 → HTTP 400 ====================

    /** 标题全是空白 → {@code @NotBlank} 拦下，400。 */
    @Test
    public void testBlankTitleReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"categoryId\":1}"))
                .andReturn();

        assertEquals("校验失败必须是 HTTP 400", 400, result.getResponse().getStatus());
        assertCode(result, 400);
        String message = body(result).get("message").asText();
        assertTrue("提示应指出是标题的问题，实际：" + message, message.contains("文章标题"));
    }

    /** 缺 categoryId → {@code @NotNull} 拦下，400。 */
    @Test
    public void testMissingCategoryReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"S19 缺分类\"}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertCode(result, 400);
        assertTrue("提示应指向分类，实际：" + body(result).get("message").asText(),
                body(result).get("message").asText().contains("分类"));
    }

    /** 审核缺 auditAction → 400。 */
    @Test
    public void testAuditWithoutActionReturns400() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PENDING_EDITOR);
        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/audit")
                        .session(loginAs(EDITOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditOpinion\":\"只有意见没有结论\"}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertCode(result, 400);
        assertTrue(body(result).get("message").asText().contains("审核结果"));
    }

    /** 下架原因传纯空白 → {@code @NotBlank} 拦下（不是只挡 null），400。 */
    @Test
    public void testOfflineWithoutReasonReturns400() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PUBLISHED);
        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/offline")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andReturn();

        assertEquals("@NotBlank 应挡掉纯空白串", 400, result.getResponse().getStatus());
        assertCode(result, 400);
        assertTrue(body(result).get("message").asText().contains("下架原因"));
    }

    /** 回滚版本号传 0 → {@code @Min(1)} 拦下，400。 */
    @Test
    public void testRollbackWithVersionZeroReturns400() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);
        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/rollback")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionNo\":0}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertCode(result, 400);
        assertTrue(body(result).get("message").asText().contains("版本号"));
    }

    /** 批量提交传空数组 → {@code @NotEmpty}（不是 {@code @NotNull}）拦下，400。 */
    @Test
    public void testBatchSubmitWithEmptyIdsReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/batch-submit")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andReturn();

        assertEquals("空数组必须被 @NotEmpty 挡住（@NotNull 挡不住它）",
                400, result.getResponse().getStatus());
        assertCode(result, 400);
        assertTrue(body(result).get("message").asText().contains("至少选择"));
    }

    // ==================== 三、业务异常 → 正确业务码 ====================

    /** 详情查不存在的文章 → 11001。 */
    @Test
    public void testDetailOfMissingArticleReturns11001() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles/" + NOT_EXIST_ID)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(result, 11001);
        assertEquals("11001 是业务码，HTTP 仍为 200", 200, result.getResponse().getStatus());
    }

    /**
     * 已发布的文章再提交 → 11002，且文案里要带「当前状态」与「动作」两件事。
     *
     * <p>这条同时钉住了 S18 的用语统一：动作名必须是「提交初审」，
     * 不能又冒出一个「提交审核」。
     */
    @Test
    public void testSubmitOnPublishedReturns11002() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PUBLISHED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/submit")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();

        assertCode(result, 11002);
        String message = body(result).get("message").asText();
        assertTrue("文案应说明当前状态，实际：" + message, message.contains("已发布"));
        assertTrue("动作名应统一为「提交初审」，实际：" + message, message.contains("提交初审"));
        assertFalse("不应再出现旧文案「提交审核」，实际：" + message, message.contains("提交审核"));
    }

    /** 改别人的草稿 → 水平越权 11003（注意：投稿人 2 <b>有</b> article:update 权限，403 拦不住他）。 */
    @Test
    public void testUpdateOthersDraftReturns11003() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);

        MvcResult result = mockMvc.perform(put("/api/v1/articles/" + id + "/draft")
                        .session(loginAs(AUTHOR2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"S19 越权改标题\",\"categoryId\":1}"))
                .andReturn();

        assertCode(result, 11003);
        assertTrue("越权提示应说清楚，实际：" + body(result).get("message").asText(),
                body(result).get("message").asText().contains("自己创建"));
    }

    /** 撤回别人的已发布文章 → 11003（撤回是唯一"既要权限、又要归属"的写接口）。 */
    @Test
    public void testWithdrawOthersArticleReturns11003() throws Exception {
        Long id = seedArticle(AUTHOR2_ID, ArticleStatus.PUBLISHED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/withdraw")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();

        assertCode(result, 11003);
    }

    /**
     * 审核自己创建的稿 → 11004。
     *
     * <p>⚠️ 这里必须用<b>编辑</b>身份：编辑有 {@code article:audit}，
     * 权限切面会放行，才会走到 Service 里的"自审"判断。
     * 若用投稿人身份，会在切面就被 403 拦掉，<b>测不到 11004</b>——
     * 这正是"垂直越权层先于水平越权层"那句话的实测含义。
     */
    @Test
    public void testAuditOwnArticleReturns11004() throws Exception {
        Long ownDraft = articleService.saveDraft(saveDto(uniqueTitle("S19-编辑自己的稿")), EDITOR_ID);
        articleService.submitForAudit(ownDraft, EDITOR_ID);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + ownDraft + "/audit")
                        .session(loginAs(EDITOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":1,\"auditOpinion\":\"自己审自己\"}"))
                .andReturn();

        assertCode(result, 11004);
        assertTrue(body(result).get("message").asText().contains("自己创建"));
    }

    /** 排期时间只晚于当前时间不到 1 分钟 → 11007（Service 层校验，不是 DTO 的 {@code @Future}）。 */
    @Test
    public void testScheduleTimeTooSoonReturns11007() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.APPROVED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/schedule")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleTime\":\"" + LocalDateTime.now().format(TIME_FORMAT) + "\"}"))
                .andReturn();

        assertEquals("这是业务校验不是格式错误，HTTP 应保持 200", 200, result.getResponse().getStatus());
        assertCode(result, 11007);
        assertTrue(body(result).get("message").asText().contains("排期"));
    }

    /** 回滚到不存在的版本 → 11009（{@code @Min(1)} 只挡格式，挡不住"版本不存在"）。 */
    @Test
    public void testRollbackToMissingVersionReturns11009() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/rollback")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionNo\":99}"))
                .andReturn();

        assertCode(result, 11009);
    }

    /** 对已归档的文章下架 → 11011（终态只读），而不是笼统的 11002。 */
    @Test
    public void testOperateOnArchivedReturns11011() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.ARCHIVED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/offline")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"S19 对已归档文章下架\"}"))
                .andReturn();

        assertCode(result, 11011);
        assertTrue(body(result).get("message").asText().contains("归档"));
    }

    // ==================== 四、请求体格式错误 → 400（不是 500） ====================

    /**
     * 时间传 ISO-8601 的 {@code T} 分隔 → 400。
     *
     * <p>这是 S13 真机联调踩出来的坑：{@code JacksonConfig} 给
     * {@code LocalDateTime} 全局注册的唯一格式是 {@code yyyy-MM-dd HH:mm:ss}（空格）。
     * 前端 {@code el-date-picker} 已按此配 {@code value-format}；
     * 若后端哪天"顺手"也接受 {@code T}，项目里就会出现两套时间口径。
     * <p>⚠️ 关键是<b>不能是 500</b>：Jackson 反序列化失败要落到
     * {@code HttpMessageNotReadableException} 的处理器上。
     */
    @Test
    public void testScheduleTimeWithIsoSeparatorReturns400() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.APPROVED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/schedule")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleTime\":\"2026-12-01T10:00:00\"}"))
                .andReturn();

        assertEquals("带 T 的时间应被拒绝为 400", 400, result.getResponse().getStatus());
        assertCode(result, 400);
    }

    /** 请求体根本不是合法 JSON → 400（同样是格式错，不能是 500）。 */
    @Test
    public void testMalformedJsonReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{title: 没引号的键}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertCode(result, 400);
    }

    // ==================== 五、权限三态：401 / 403 / 11003 ====================

    /** 完全不带 Session → 拦截器 401。 */
    @Test
    public void testAnonymousReturns401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")).andReturn();

        assertEquals("未登录必须是 HTTP 401（前端靠它跳登录页）",
                401, result.getResponse().getStatus());
        assertCode(result, 401);
        assertResultShape(result);
    }

    /** Session 存在但没有登录用户（模拟 Session 过期 / key 被清）→ 同样 401。 */
    @Test
    public void testSessionWithoutLoginUserReturns401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/articles")
                        .session(new MockHttpSession()))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertCode(result, 401);
    }

    /**
     * 投稿人调审核接口 → 403（垂直越权）。
     *
     * <p>⚠️ 权限切面<b>先于</b>参数解析之外的一切业务判断，所以这里连"文章存不存在"
     * 都不会被查 —— 用随便一个 id 就能验证。
     * <p>对照：同一个 URL 换成编辑身份、且文章是编辑自己写的 → 11004（见上文）。
     */
    @Test
    public void testAuthorCannotAuditReturns403() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/1/audit")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":1}"))
                .andReturn();

        assertEquals("418 是投稿人缺 article:audit，应被拦成 403",
                403, result.getResponse().getStatus());
        assertCode(result, 403);
    }

    /** 编辑调发布接口 → 403（编辑有 article:audit，但没有 article:publish）。 */
    @Test
    public void testEditorCannotPublishReturns403() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.APPROVED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/publish")
                        .session(loginAs(EDITOR_ID)))
                .andReturn();

        assertEquals("article:publish 只授予主编与管理员", 403, result.getResponse().getStatus());
        assertCode(result, 403);
    }

    /** 投稿人调下架接口 → 403（下架用 article:manage，投稿人没有）。 */
    @Test
    public void testAuthorCannotOfflineReturns403() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PUBLISHED);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/offline")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"S19 越权下架\"}"))
                .andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertCode(result, 403);
    }

    /**
     * ⚠️ 权限真值守门人。
     *
     * <p>上面四条 403 用例只有在"库表里确实是这么授权的"前提下才有意义。
     * 若某天有人给投稿人加上 {@code article:audit}，那四条用例会开始失败 ——
     * 这是好事；但如果没人察觉，别人可能"顺手把用例改成别的角色"，问题就被掩盖了。
     * <p>本用例把库表真值直接钉住：改动种子数据必须同步改测试，
     * 也就必须在评审里说明理由。
     */
    @Test
    public void testPermissionTruthGuard() {
        Set<String> author = permissionsOf(AUTHOR_ID);
        Set<String> editor = permissionsOf(EDITOR_ID);
        Set<String> chief = permissionsOf(CHIEF_ID);
        Set<String> admin = permissionsOf(ADMIN_ID);

        assertTrue("投稿人应有 article:submit", author.contains("article:submit"));
        assertFalse("投稿人不应有 article:audit（403 用例的前提）", author.contains("article:audit"));
        assertFalse("投稿人不应有 article:publish", author.contains("article:publish"));
        assertFalse("投稿人不应有 article:manage", author.contains("article:manage"));

        assertTrue("编辑应有 article:audit（11004 用例的前提）", editor.contains("article:audit"));
        assertFalse("编辑不应有 article:publish（403 用例的前提）", editor.contains("article:publish"));

        assertTrue("主编应有 article:publish", chief.contains("article:publish"));
        assertTrue("主编应有 article:manage", chief.contains("article:manage"));

        assertEquals("管理员应是权限最全的角色（20 个码）", 20, admin.size());
        assertTrue("管理员应包含全部业务域权限",
                admin.containsAll(java.util.Arrays.asList(
                        "article:audit", "article:publish", "article:manage",
                        "log:view", "user:manage", "role:manage", "permission:manage")));
    }

    // ==================== 六、正向链路（真 HTTP、真事务、真回滚） ====================

    /** 建草稿：返回新 id，且 id 在库里真实存在、状态为草稿。 */
    @Test
    public void testCreateDraftReturnsPersistedId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + uniqueTitle("S19-建草稿")
                                + "\",\"summary\":\"摘要\",\"content\":\"正文\",\"categoryId\":1,\"isTop\":0}"))
                .andReturn();

        assertResultShape(result);
        assertCode(result, 200);
        assertEquals("自定义成功文案应原样返回", "草稿保存成功", body(result).get("message").asText());

        JsonNode data = body(result).get("data");
        assertTrue("data 应是新文章 id（数字）", data.isNumber());
        long newId = data.asLong();

        assertEquals("库里状态应为草稿(0)", ArticleStatus.DRAFT.getCode().intValue(), statusOf(newId));
    }

    /**
     * ★ 手册 S19 问题 2 的实测答案：<b>HTTP 链路上 {@code create_by} 是登录用户</b>，
     * 而<b>无 HTTP 上下文时按约定落到 0</b>。
     *
     * <p>这两条断言放在同一个用例里是刻意的：它们共同证明
     * {@code AuditMetaObjectHandler} 的"可选依赖"设计是<b>对</b>的 ——
     * 同一个 {@code saveDraft} 方法，有请求时填登录人、没请求时填 0，
     * <b>既不抛异常、也不需要调用方感知</b>。若当初实现成"拿不到就抛 NPE"，
     * 定时任务与所有 Service 测试都会炸；若实现成"拿不到就跳过填充"，
     * 则会违反 {@code create_by NOT NULL}。
     *
     * <p>⚠️ 顺序不能反：先做"无 HTTP 上下文"的直接 Service 调用，
     * 再发 HTTP 请求。反过来的话 {@code RequestContextHolder} 虽然会在请求结束后被
     * {@code FrameworkServlet} 清掉，但依赖"它一定被清干净"是个不必要的假设。
     */
    @Test
    public void testCreateByIsLoginUserOverHttpAndZeroWithoutHttpContext() throws Exception {
        // ① 直接调 Service：本测试方法所在线程没有任何请求上下文
        CmsArticleSaveDTO dto = saveDto(uniqueTitle("S19-无请求上下文"));
        Long noHttpId = articleService.saveDraft(dto, AUTHOR_ID);
        assertEquals("无 HTTP 上下文时 create_by 应填 SYSTEM_USER_ID=0（不是 null）",
                Long.valueOf(0L), createByOf(noHttpId));

        // ② 走 HTTP：会话里是 author（id=1）
        MvcResult result = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + uniqueTitle("S19-带请求上下文")
                                + "\",\"content\":\"正文\",\"categoryId\":1}"))
                .andReturn();
        long httpId = body(result).get("data").asLong();

        assertEquals("HTTP 链路上 create_by 应是登录用户 id",
                AUTHOR_ID, createByOf(httpId));

        // ③ 换一个登录人再做一次，证明它跟的是"会话里的人"而不是某个固定值
        MvcResult byAuthor2 = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR2_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + uniqueTitle("S19-换个人建稿")
                                + "\",\"content\":\"正文\",\"categoryId\":1}"))
                .andReturn();
        long byAuthor2Id = body(byAuthor2).get("data").asLong();
        assertEquals("换登录人后 create_by 应随之改变",
                AUTHOR2_ID, createByOf(byAuthor2Id));
    }

    /** 完整审核链路：建草稿 → 改草稿 → 提交初审 → 审核 → 详情/流水状态一致。 */
    @Test
    public void testDraftToPublishedLifecycleOverHttp() throws Exception {
        String title = uniqueTitle("S19-全链路");

        // 1) 建草稿
        MvcResult created = mockMvc.perform(post("/api/v1/articles/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"正文\",\"categoryId\":1}"))
                .andReturn();
        long id = body(created).get("data").asLong();
        assertEquals(0, statusOf(id));

        // 2) 改草稿（PUT 语义 = 整体替换）
        MvcResult updated = mockMvc.perform(put("/api/v1/articles/" + id + "/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"summary\":\"改过的摘要\","
                                + "\"content\":\"改过的正文\",\"categoryId\":1}"))
                .andReturn();
        assertCode(updated, 200);
        assertEquals("改草稿不改状态", 0, statusOf(id));

        // 3) 提交初审
        MvcResult submitted = mockMvc.perform(post("/api/v1/articles/" + id + "/submit")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(submitted, 200);
        assertEquals("已提交初审", body(submitted).get("message").asText());
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode().intValue(), statusOf(id));

        // 4) 编辑审核通过 → 待主编终审
        MvcResult audited = mockMvc.perform(post("/api/v1/articles/" + id + "/audit")
                        .session(loginAs(EDITOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":1,\"auditOpinion\":\"S19 初审通过\"}"))
                .andReturn();
        assertEquals("审核完成", body(audited).get("message").asText());
        assertEquals(ArticleStatus.PENDING_CHIEF.getCode().intValue(), statusOf(id));

        // 5) 详情接口与库表状态必须一致
        MvcResult detail = mockMvc.perform(get("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        JsonNode detailData = body(detail).get("data");
        assertEquals("详情返回的状态应与库里一致", statusOf(id), detailData.get("status").asInt());
        assertEquals("详情应带正文", "改过的正文", detailData.get("content").asText());
        assertTrue("详情应带审核流水数组", detailData.get("auditLogs").isArray());
        assertEquals("此时应已有 1 条流水", 1, detailData.get("auditLogs").size());

        // 6) 单独的流水接口条数应与详情里的流水条数一致
        MvcResult logs = mockMvc.perform(get("/api/v1/articles/" + id + "/audit-logs")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertEquals("流水接口与详情的流水必须一致",
                detailData.get("auditLogs").size(), body(logs).get("data").size());

        // 7) 主编终审通过 → 已审核通过
        mockMvc.perform(post("/api/v1/articles/" + id + "/audit")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":1,\"auditOpinion\":\"S19 终审通过\"}"))
                .andReturn();
        assertEquals(ArticleStatus.APPROVED.getCode().intValue(), statusOf(id));

        // 8) 立即发布
        MvcResult published = mockMvc.perform(post("/api/v1/articles/" + id + "/publish")
                        .session(loginAs(CHIEF_ID)))
                .andReturn();
        assertEquals("文章已发布", body(published).get("message").asText());
        assertEquals(ArticleStatus.PUBLISHED.getCode().intValue(), statusOf(id));

        // 9) 撤回（只有作者本人能撤）
        mockMvc.perform(post("/api/v1/articles/" + id + "/withdraw")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertEquals(ArticleStatus.WITHDRAWN.getCode().intValue(), statusOf(id));
    }

    /** 驳回链路：驳回必须带意见，驳回后作者能改并再次提交。 */
    @Test
    public void testRejectThenResubmitOverHttp() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PENDING_EDITOR);

        // 驳回不填意见 → 11014
        MvcResult noOpinion = mockMvc.perform(post("/api/v1/articles/" + id + "/audit")
                        .session(loginAs(EDITOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":2}"))
                .andReturn();
        assertEquals("驳回不填意见应是业务码 11014，不是 400（格式是对的，是业务规则不允许）",
                11014, body(noOpinion).get("code").asInt());

        // 驳回并填意见 → 已驳回
        mockMvc.perform(post("/api/v1/articles/" + id + "/audit")
                        .session(loginAs(EDITOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditAction\":2,\"auditOpinion\":\"S19 标题需要更具体\"}"))
                .andReturn();
        assertEquals(ArticleStatus.REJECTED.getCode().intValue(), statusOf(id));

        // 已驳回的文章可以再次提交
        MvcResult resubmit = mockMvc.perform(post("/api/v1/articles/" + id + "/submit")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(resubmit, 200);
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode().intValue(), statusOf(id));
    }

    /** 批量提交：两篇自己的草稿都成功，failList 为空。 */
    @Test
    public void testBatchSubmitOverHttp() throws Exception {
        Long a = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);
        Long b = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/batch-submit")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + b + "]}"))
                .andReturn();

        JsonNode data = body(result).get("data");
        assertEquals("总数应为 2", 2, data.get("total").asInt());
        assertEquals("成功应为 2", 2, data.get("successCount").asInt());
        assertEquals("失败应为 0", 0, data.get("failCount").asInt());
        assertEquals("失败明细应为空数组", 0, data.get("failList").size());
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode().intValue(), statusOf(a));
        assertEquals(ArticleStatus.PENDING_EDITOR.getCode().intValue(), statusOf(b));
    }

    /**
     * 批量提交的"逐条容错"：一篇自己的 + 一篇别人的 → 200 且 failList 里有 1 条。
     *
     * <p>⚠️ 这是刻意的设计（不是 bug）：整批拒绝会让"勾选了 5 篇自己的 + 1 篇别人的"
     * 变成全失败，用户的本意只是提交自己那几篇。
     */
    @Test
    public void testBatchSubmitIsPerItemTolerant() throws Exception {
        Long mine = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);
        Long others = seedArticle(AUTHOR2_ID, ArticleStatus.DRAFT);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/batch-submit")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + mine + "," + others + "]}"))
                .andReturn();

        assertEquals("请求本身是合法且被处理了的，HTTP 200", 200, result.getResponse().getStatus());
        JsonNode data = body(result).get("data");
        assertEquals(1, data.get("successCount").asInt());
        assertEquals(1, data.get("failCount").asInt());
        assertEquals(1, data.get("failList").size());
        assertEquals("失败那条应是别人的文章",
                others.longValue(), data.get("failList").get(0).get("id").asLong());
        assertEquals("我的那篇应已提交", ArticleStatus.PENDING_EDITOR.getCode().intValue(), statusOf(mine));
        assertEquals("别人的那篇应原封不动", ArticleStatus.DRAFT.getCode().intValue(), statusOf(others));
    }

    /** 排期发布：合法时间 → 定时发布中(6)；且 {@code schedule_time} 真的落库。 */
    @Test
    public void testScheduleOverHttp() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.APPROVED);
        String future = LocalDateTime.now().plusMinutes(30).withNano(0).format(TIME_FORMAT);

        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + id + "/schedule")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleTime\":\"" + future + "\"}"))
                .andReturn();

        assertEquals("排期设置成功", body(result).get("message").asText());
        assertEquals(ArticleStatus.SCHEDULED.getCode().intValue(), statusOf(id));
        String stored = jdbc().queryForObject(
                "SELECT DATE_FORMAT(schedule_time, '%Y-%m-%d %H:%i:%s') FROM cms_article WHERE id = ?",
                String.class, id);
        assertEquals("排期时间必须原样落库（空格分隔格式）", future, stored);
    }

    /** 下架 → 归档：下架不可逆、归档是终态，两步状态都要对。 */
    @Test
    public void testOfflineThenArchiveOverHttp() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.PUBLISHED);

        MvcResult offlined = mockMvc.perform(post("/api/v1/articles/" + id + "/offline")
                        .session(loginAs(CHIEF_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"S19 下架：内容涉及侵权\"}"))
                .andReturn();
        assertEquals("文章已下架", body(offlined).get("message").asText());
        assertEquals(ArticleStatus.OFFLINE.getCode().intValue(), statusOf(id));

        MvcResult archived = mockMvc.perform(post("/api/v1/articles/" + id + "/archive")
                        .session(loginAs(CHIEF_ID)))
                .andReturn();
        assertEquals("文章已归档", body(archived).get("message").asText());
        assertEquals(ArticleStatus.ARCHIVED.getCode().intValue(), statusOf(id));

        // 归档后任何写操作都只读
        MvcResult again = mockMvc.perform(post("/api/v1/articles/" + id + "/archive")
                        .session(loginAs(CHIEF_ID)))
                .andReturn();
        assertCode(again, 11011);
    }

    /** 版本列表 + 回滚：改一次草稿产生 version_no=1，再回滚到它。 */
    @Test
    public void testVersionsAndRollbackOverHttp() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.DRAFT);

        // 改一次草稿 → 产生一条业务快照
        mockMvc.perform(put("/api/v1/articles/" + id + "/draft")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"S19 版本测试-第一次修\",\"content\":\"正文 v1\",\"categoryId\":1}"))
                .andReturn();

        MvcResult versions = mockMvc.perform(get("/api/v1/articles/" + id + "/versions")
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        JsonNode list = body(versions).get("data");
        assertTrue("应至少有 1 个版本", list.size() >= 1);
        int versionNo = list.get(0).get("versionNo").asInt();
        assertEquals("第一个版本号应从 1 开始", 1, versionNo);
        assertFalse("版本列表不应带正文快照（LONGTEXT）", list.get(0).has("contentSnapshot"));

        MvcResult rollback = mockMvc.perform(post("/api/v1/articles/" + id + "/rollback")
                        .session(loginAs(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionNo\":" + versionNo + "}"))
                .andReturn();
        assertEquals("已回滚到指定版本", body(rollback).get("message").asText());
        assertEquals("回滚不改状态，仍在草稿", ArticleStatus.DRAFT.getCode().intValue(), statusOf(id));
    }

    // ==================== 七、删除接口（S20 补齐的 DELETE /articles/{id}） ====================

    /*
     * 为什么这一组是 S20 才有的：契约 §1.3 第 5 行从 S05 起就写着
     * DELETE /api/v1/articles/{id} | 删除（仅草稿/驳回态） | article:delete，
     * 但后端一直没实现（S18 的记录里"文章域没有删除入口"）——
     * 前端列表页那颗「删除」按钮点下去必然落到统一 JSON 404 上。
     * S20 回归实测到这一点后，按 S18 留下的方案补齐（S18 的理由是
     * "行为变更必须配独立用例与回归"，S20 正是带独立用例的回归步）。
     */

    /**
     * 作者删自己的草稿：200 + 库里 {@code deleted=1} + 详情/列表都查不到。
     *
     * <p>这条同时钉住手册 S20 回归清单第 7 项
     * （"逻辑删除：删除后列表与详情都查不到"）的三个判据。
     */
    @Test
    public void testAuthorDeletesOwnDraft() throws Exception {
        String title = uniqueTitle("S20-删除-自己的草稿");
        Long id = articleService.saveDraft(saveDto(title), AUTHOR_ID);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();

        assertEquals("删除草稿应返回 HTTP 200", 200, result.getResponse().getStatus());
        assertResultShape(result);
        JsonNode json = body(result);
        assertEquals("业务码应为 200", 200, json.get("code").asInt());
        assertEquals("删除接口的成功文案", "文章已删除", json.get("message").asText());

        assertEquals("库里 deleted 应置 1（逻辑删除）", 1, deletedOf(id));
        assertEquals("物理行必须仍在（逻辑删除不是物理删除）", 1, rowCountOf(id));
        assertEquals("删除不改 status —— 删的是 deleted 列，不是状态机",
                ArticleStatus.DRAFT.getCode().intValue(), statusRawOf(id));
        assertEquals("审计列 update_by 应记为删除人（MP 的 removeById 不会填这两列，"
                + "所以实现里改用了显式列列的 LambdaUpdateWrapper）",
                AUTHOR_ID, updateByOf(id));

        MvcResult detail = mockMvc.perform(get("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(detail, 11001);

        MvcResult list = mockMvc.perform(get("/api/v1/articles")
                        .param("title", title)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertEquals("逻辑删除后列表 total 应为 0", 0,
                body(list).get("data").get("total").asInt());
    }

    /**
     * 已驳回的稿子同样可删（口径是"草稿 + 已驳回"，不是"只有草稿"）。
     *
     * <p>这条与 {@code requireEditable}（改草稿）保持同一集合 ——
     * 能改的就能删，否则用户会遇到"能编辑但不能删除"的费解组合。
     */
    @Test
    public void testAuthorDeletesOwnRejectedArticle() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.REJECTED);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();

        assertCode(result, 200);
        assertEquals("已驳回稿被逻辑删除", 1, deletedOf(id));
    }

    /**
     * 非作者删别人的草稿 → 11003（水平越权），且<b>什么都不改</b>。
     *
     * <p>权限码 {@code article:delete} 连 {@code ROLE_AUTHOR(7)} 都有，
     * 若不做归属校验，任何作者都能删光别人的草稿。
     */
    @Test
    public void testDeleteOthersDraftReturns11003() throws Exception {
        Long id = articleService.saveDraft(saveDto(uniqueTitle("S20-删除-越权")), AUTHOR_ID);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR2_ID)))
                .andReturn();

        assertCode(result, 11003);
        assertEquals("被拒后 deleted 必须仍是 0", 0, deletedOf(id));
    }

    /**
     * 已发布态删除 → 11013；已归档 → 11011。
     *
     * <p>两个码分开是刻意的：归档是终态（"永久留档"），
     * 与"上线过了所以不能删"（该走归档）是不同性质的两个结论。
     */
    @Test
    public void testDeleteNonDraftStatusReturns11013() throws Exception {
        Long published = seedArticle(AUTHOR_ID, ArticleStatus.PUBLISHED);
        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + published)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(result, 11013);
        assertEquals("被拒后 deleted 必须仍是 0", 0, deletedOf(published));
    }

    @Test
    public void testDeleteArchivedReturns11011() throws Exception {
        Long archived = seedArticle(AUTHOR_ID, ArticleStatus.ARCHIVED);
        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + archived)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(result, 11011);
    }

    /**
     * 归属校验<b>排在</b>状态校验之前：对"别人的已归档文章"删除，
     * 拿到的是 11003（越权）而不是 11011（只读）。
     *
     * <p>⚠️ 这不是缺陷，是全项目一致的既定顺序（S20 回归报告里登记为"刻意行为"）：
     * 撤回 / 回滚 / 改草稿 / 提交四条路径都是同一顺序。改顺序会牵动
     * {@code checkOwnership} 的位置，进而让所有用例的错误码全体漂移。
     */
    @Test
    public void testDeleteOthersArchivedReturns11003Not11011() throws Exception {
        Long archived = seedArticle(AUTHOR_ID, ArticleStatus.ARCHIVED);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + archived)
                        .session(loginAs(AUTHOR2_ID)))
                .andReturn();

        assertCode(result, 11003);
    }

    /** 未登录 → 401（拦截器层），不是 403、不是业务码。 */
    @Test
    public void testDeleteWithoutLoginReturns401() throws Exception {
        Long id = articleService.saveDraft(saveDto(uniqueTitle("S20-删除-未登录")), AUTHOR_ID);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + id)).andReturn();

        assertEquals("未登录应返回 HTTP 401", 401, result.getResponse().getStatus());
    }

    /** 对不存在的 id 删除 → 11001（先查文章，再谈状态）。 */
    @Test
    public void testDeleteMissingArticleReturns11001() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + NOT_EXIST_ID)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(result, 11001);
    }

    /**
     * 版本快照与审核流水<b>不级联</b>逻辑删除。
     *
     * <p>这是 S18 定下的口径（见 {@code docs/重构记录.md} 第一节 (3) 的推荐处置表），
     * 这里把它变成一条可执行的断言 —— 否则"级联删除"很容易在后续重构里被顺手加上。
     */
    @Test
    public void testDeleteDoesNotCascadeToVersionsOrAuditLogs() throws Exception {
        Long id = seedArticle(AUTHOR_ID, ArticleStatus.REJECTED);
        int versionsBefore = countOf("cms_article_version", id);
        int logsBefore = countOf("cms_article_audit_log", id);
        assertTrue("准备数据：已驳回稿应至少有 1 个版本快照", versionsBefore >= 1);
        assertTrue("准备数据：已驳回稿应至少有 1 条审核流水", logsBefore >= 1);

        MvcResult result = mockMvc.perform(delete("/api/v1/articles/" + id)
                        .session(loginAs(AUTHOR_ID)))
                .andReturn();
        assertCode(result, 200);

        assertEquals("版本快照条数不应因删除而变化", versionsBefore, countOf("cms_article_version", id));
        assertEquals("审核流水条数不应因删除而变化", logsBefore, countOf("cms_article_audit_log", id));
    }

    // ==================== 私有辅助 ====================

    /**
     * 查 {@code deleted} 真值。
     *
     * <p>⚠️ 不能用父类的 {@code statusOf}：它的 WHERE 里带 {@code deleted = 0}，
     * 逻辑删除之后会返回空结果集（{@code EmptyResultDataAccessException}），
     * 而不是"deleted = 1"。判断"有没有删掉"必须读这一列本身。
     */
    private int deletedOf(Long articleId) {
        Integer deleted = jdbc().queryForObject(
                "SELECT deleted FROM cms_article WHERE id = ?", Integer.class, articleId);
        return deleted == null ? -1 : deleted;
    }

    /** 查物理行数（不带 {@code deleted} 条件）—— 用来证明"逻辑删除没删物理行"。 */
    private int rowCountOf(Long articleId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM cms_article WHERE id = ?", Integer.class, articleId);
        return n == null ? 0 : n;
    }

    /** 查 {@code status}，不带 {@code deleted} 条件（逻辑删除后仍要能读到它）。 */
    private int statusRawOf(Long articleId) {
        Integer status = jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ?", Integer.class, articleId);
        return status == null ? -1 : status;
    }

    /** 数某张关联表里属于该文章的、未逻辑删除的行数。 */
    private int countOf(String table, Long articleId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE article_id = ? AND deleted = 0",
                Integer.class, articleId);
        return n == null ? 0 : n;
    }

    /** 直接查库表拿权限码真值（与生产登录同一条 SQL）。 */
    private Set<String> permissionsOf(Long userId) {
        String raw = sysUserMapper.selectPermissionCodesByUserId(userId);
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            set.addAll(java.util.Arrays.asList(raw.split(",")));
        }
        return set;
    }
}
