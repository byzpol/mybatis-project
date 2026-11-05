package com.lab.cms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.constant.SessionConstant;
import com.lab.cms.common.enums.ArticleStatus;
import com.lab.cms.config.SpringConfig;
import com.lab.cms.config.SpringMvcConfig;
import com.lab.cms.dto.ArticleAuditDTO;
import com.lab.cms.dto.CmsArticlePublishDTO;
import com.lab.cms.dto.CmsArticleSaveDTO;
import com.lab.cms.mapper.SysUserMapper;
import com.lab.cms.service.ArticleService;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文章接口层测试的公共基类（S19）。
 *
 * <h2>为什么需要它</h2>
 * <p>S19 之前全仓有 18 个测试类、254 个用例，但<b>没有一处用过 MockMvc</b> ——
 * 也就是说所有测试都从 Service 层往下，<b>「HTTP 这一层」完全没有自动化覆盖</b>。
 * 那一段恰好是踩坑最多的地方：拦截器、权限切面、{@code @Valid}、
 * 统一异常处理器、Jackson 时间格式 —— 都是"只在 Web 层存在"的组件。
 *
 * <p>本类把这一层搭起来，让接口测试可以像写 Service 测试一样简单。
 *
 * <h2>⚠️ 为什么上下文要同时加载 {@link SpringConfig} 与 {@link SpringMvcConfig}</h2>
 * <p>生产环境是<b>双容器</b>（父容器装 Service/Mapper，子容器装 Controller），
 * 由 {@code ServletContainersInitConfig} 建立父子关系。MockMvc 不经过
 * {@code ServletContainerInitializer}，没法复现"两个容器"这件事，
 * 所以这里把两个配置类放进<b>同一个</b>上下文。
 *
 * <p>这样做的效果与生产等价，且更严格：
 * <ul>
 *   <li>{@code @Controller} 只被 {@code SpringMvcConfig} 的扫描注册一份
 *       （{@code SpringConfig} 把它 exclude 掉了），不会出现双实例；</li>
 *   <li>{@code GlobalExceptionHandler}（{@code @RestControllerAdvice}）
 *       被 {@code SpringConfig} 的默认扫描收进来 —— 这是它能生效的原因：
 *       生产环境里 {@code ExceptionHandlerExceptionResolver} 会沿父子链
 *       向上找 advice，单上下文下它本来就在，行为一致；</li>
 *   <li>{@code SchedulingConfig} 被 {@code SpringConfig} 显式排除，
 *       因此测试期间<b>不会有定时任务后台线程</b>（S13 踩过的坑：
 *       它曾让种子文章 id=14 在跑测试时被顺手发布）。</li>
 * </ul>
 *
 * <h2>为什么 {@link Transactional} 能真的回滚（本类最关键的一条）</h2>
 * <p>{@code SpringJUnit4ClassRunner} + {@code @Transactional} 会在
 * <b>测试方法所在的那一个线程</b>上开事务，测试结束回滚。
 *
 * <p>关键在于：<b>MockMvc 是同步的、在同线程内直接调 {@code DispatcherServlet}</b>，
 * 不会像真实容器那样把请求交给另一个工作线程。所以整条
 * 「DispatcherServlet → 拦截器 → 切面 → Controller → Service → Mapper → JDBC」
 * 都跑在测试自己的事务里，用的是同一个被事务绑定的 {@code Connection}
 * （`DataSourceUtils` 按 {@code DataSource} 复用事务连接），
 * 于是所有写入在方法结束时一起回滚。
 *
 * <p>⚠️ 这条结论有边界，写用例时不能忘：
 * <ul>
 *   <li>任何 {@code REQUIRES_NEW} / 自建线程 / 异步方法里的写库<b>不会</b>被回滚
 *       —— 本项目实测全仓无 {@code REQUIRES_NEW}（只在注释里被提到），所以安全；</li>
 *   <li>被测试代码<b>自己 catch 掉</b>的异常不会让事务标脏，但"想验证失败后已回滚"
 *       那类断言就必须改成显式查库（本项目 S10 验证审核回滚就是这么做的）；</li>
 *   <li>真实的 {@code commit} 是否成功这一层，本类<b>测不到</b> ——
 *       那属于 {@code ArticleDraftServiceTest} 等"真落库 + 物理清理"类测试的职责，
 *       两套写法是分工，不是重复。</li>
 * </ul>
 *
 * <h2>⚠️ 登录态从哪来：从库表装配，不手写权限码</h2>
 * <p>测试里最危险的做法是"手写 {@code permissions = {"article:audit"}}" 然后断言 403 ——
 * 那是用测试自己的假设去验证测试自己的假设，一旦 {@code sys_role_permission}
 * 的种子数据变了，用例会继续"通过"，而真实权限已经不一样了。
 *
 * <p>本类的 {@link #loginAs(Long)} 与生产登录（{@code AuthServiceImpl#login}）
 * 走<b>完全相同</b>的两条 SQL（{@code selectRoleCodesByUserId} /
 * {@code selectPermissionCodesByUserId}）来装配 {@link LoginUser}，
 * 因此"谁能做什么"这件事的真相只有库表一个来源。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SpringConfig.class, SpringMvcConfig.class})
@Transactional
public abstract class AbstractArticleApiTest {

    // ==================== 演示账号（与 sql/init.sql 种子一致，由 guard 用例钉住） ====================

    /** 投稿人 1（张晓）：article:create/update/submit/withdraw/view/delete + category:view */
    protected static final Long AUTHOR_ID = 1L;
    /** 编辑：在投稿人基础上多 article:audit + category:manage */
    protected static final Long EDITOR_ID = 2L;
    /** 主编：再多 article:manage + article:publish + report:view */
    protected static final Long CHIEF_ID = 3L;
    /** 管理员：20 个权限码全有 */
    protected static final Long ADMIN_ID = 4L;
    /** 投稿人 2（赵投稿）：权限同投稿人 1，用来构造水平越权 */
    protected static final Long AUTHOR2_ID = 5L;

    /** 演示数据里的分类：技术资讯（一级分类）。 */
    protected static final Long CATEGORY_ID = 1L;

    /** 一定不存在的文章 id，用来验证 11001。 */
    protected static final Long NOT_EXIST_ID = 99999999L;

    /** 与 {@code JacksonConfig.DATE_TIME_PATTERN} 一致：项目全局唯一的时间入参格式。 */
    protected static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected MockMvc mockMvc;

    @Autowired
    protected WebApplicationContext wac;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ArticleService articleService;

    @Autowired
    protected SysUserMapper sysUserMapper;

    @Autowired
    protected DataSource dataSource;

    private JdbcTemplate jdbc;

    @Before
    public void setUpMockMvc() {
        // 与生产一致：DispatcherServlet 从 WebApplicationContext 里找
        // HandlerMapping / HandlerAdapter / ExceptionResolver / Interceptor / 消息转换器。
        // 不额外注册任何东西 —— 测的就是真实装配结果，手工 addInterceptor 会掩盖装配错误。
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // ==================== 断言辅助 ====================

    /**
     * 把响应体解析成 JSON 树。
     *
     * <p>⚠️ 刻意<b>不用</b> {@code MockMvcResultMatchers.jsonPath}：
     * 它依赖 {@code com.jayway.jsonpath}，而本项目只引了 {@code spring-test}，
     * jsonpath 是它的<b>可选</b>依赖，并不在 classpath 上 —— 直接写
     * {@code jsonPath("$.code")} 会在运行期抛 {@code NoClassDefFoundError}
     * （编译期不报错，属于"看起来能编译、一跑就炸"）。
     * 这里改用项目本来就有的 Jackson，顺带能直接断言"某个键不存在"。
     */
    protected JsonNode body(MvcResult result) throws Exception {
        // ⚠️ 必须显式指定 UTF-8：Jackson 转换器写出的 Content-Type 不带 charset，
        //    getContentAsString() 会按 ISO-8859-1 解码，中文 message 全变问号。
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    /** 断言响应体是统一的 {@code Result} 结构：code / message / timestamp 必须存在。 */
    protected void assertResultShape(MvcResult result) throws Exception {
        JsonNode json = body(result);
        org.junit.Assert.assertTrue("统一返回体缺少 code 字段：" + json, json.has("code"));
        org.junit.Assert.assertTrue("统一返回体缺少 message 字段：" + json, json.has("message"));
        org.junit.Assert.assertTrue("统一返回体缺少 timestamp 字段：" + json, json.has("timestamp"));
        org.junit.Assert.assertTrue("code 应为整数：" + json, json.get("code").isInt());
        org.junit.Assert.assertTrue("message 应为字符串：" + json, json.get("message").isTextual());
        org.junit.Assert.assertTrue("timestamp 应为整数：" + json, json.get("timestamp").isNumber());
    }

    /**
     * 断言业务码。
     *
     * <p>⚠️ 先断言 {@code code} 这个键存在，再取它的值 ——
     * 否则一旦响应体不是统一结构（例如被 Spring 默认的 error JSON 接管），
     * 取到的是 {@code null}，用例会以 {@code NullPointerException} 的形式失败，
     * 报错信息里完全看不出"真正的问题是响应体形状不对"。
     */
    protected void assertCode(MvcResult result, int expectedCode) throws Exception {
        JsonNode json = body(result);
        org.junit.Assert.assertTrue("响应体里没有 code 字段，说明没走统一返回体：" + json, json.has("code"));
        org.junit.Assert.assertEquals("业务码不符：" + json, expectedCode, json.get("code").asInt());
    }

    // ==================== 数据构造辅助 ====================

    protected JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /**
     * 构造登录态，装配方式与生产登录完全一致（见类注释）。
     *
     * @param userId 演示账号 id
     * @return 可反复传给 {@code MockMvcRequestBuilders.*.session(...)} 的会话
     */
    protected MockHttpSession loginAs(Long userId) {
        // 用户基础信息也查库：避免"测试里写死的昵称"与种子数据不一致
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT username, nickname FROM sys_user WHERE id = ? AND deleted = 0", userId);

        Set<String> roles = splitToSet(sysUserMapper.selectRoleCodesByUserId(userId));
        Set<String> permissions = splitToSet(sysUserMapper.selectPermissionCodesByUserId(userId));

        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setUsername((String) row.get("username"));
        user.setNickname((String) row.get("nickname"));
        user.setRoles(roles);
        user.setPermissions(permissions);
        user.setSuperAdmin(roles.contains(SessionConstant.ROLE_ADMIN));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstant.LOGIN_USER, user);
        return session;
    }

    /** 与 {@code AuthServiceImpl#splitToSet} 同语义。 */
    private Set<String> splitToSet(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(Arrays.asList(commaSeparated.split(",")));
    }

    /** 造一个合法的保存入参（标题唯一，避免与种子数据或其他用例同名）。 */
    protected CmsArticleSaveDTO saveDto(String title) {
        CmsArticleSaveDTO dto = new CmsArticleSaveDTO();
        dto.setTitle(title);
        dto.setSummary("S19 接口层测试用的摘要");
        dto.setContent("S19 接口层测试用的正文");
        dto.setCategoryId(CATEGORY_ID);
        dto.setIsTop(0);
        return dto;
    }

    /** 造一个唯一的测试标题。 */
    protected String uniqueTitle(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 审核入参。
     *
     * @param auditAction 1 通过 / 2 驳回
     */
    protected ArticleAuditDTO auditDto(int auditAction, String opinion) {
        ArticleAuditDTO dto = new ArticleAuditDTO();
        dto.setAuditAction(auditAction);
        dto.setAuditOpinion(opinion);
        return dto;
    }

    /**
     * 编造一篇文章并把它推到目标状态（**通过 Service 调用做准备，不用 HTTP**）。
     *
     * <p>"准备数据"与"被测行为"用不同通道：准备走 Service（简单、失败信息直白），
     * 断言走 HTTP（被测的正是这一层）。若准备也走 HTTP，一旦接口退化，
     * 大量用例会以"准备阶段失败"的形式报错，把真正的问题埋掉。
     *
     * <p>⚠️ 审核人不能是作者本人（否则 11004），所以这里按归属挑一个不同的人。
     * <p>⚠️ 全仓只有一个 {@code article:audit} 权限码、环节由状态反推，
     * 因此<b>同一个审核人可以连审两次</b>从 PENDING_EDITOR 一路到 APPROVED
     * （这个"一键到底"缺口已登记，见 {@code docs/联调报告.md} 与 memory）。
     *
     * @param ownerId 文章作者（决定随后的水平越权场景）
     * @param target  期望终态
     * @return 文章 id
     */
    protected Long seedArticle(Long ownerId, ArticleStatus target) {
        Long auditorId = (AUTHOR_ID.equals(ownerId) || AUTHOR2_ID.equals(ownerId)) ? EDITOR_ID : ADMIN_ID;

        Long id = articleService.saveDraft(saveDto(uniqueTitle("S19-" + target.name())), ownerId);
        if (target == ArticleStatus.DRAFT) {
            return id;
        }

        articleService.submitForAudit(id, ownerId);
        if (target == ArticleStatus.PENDING_EDITOR) {
            return id;
        }

        if (target == ArticleStatus.REJECTED) {
            articleService.audit(id, auditDto(2, "S19 准备数据：驳回"), auditorId);
            return id;
        }

        articleService.audit(id, auditDto(1, "S19 准备数据：初审通过"), auditorId);
        if (target == ArticleStatus.PENDING_CHIEF) {
            return id;
        }

        articleService.audit(id, auditDto(1, "S19 准备数据：终审通过"), auditorId);
        if (target == ArticleStatus.APPROVED) {
            return id;
        }

        switch (target) {
            case PUBLISHED:
                articleService.publishNow(id, CHIEF_ID);
                return id;
            case OFFLINE:
                articleService.publishNow(id, CHIEF_ID);
                articleService.offline(id, CHIEF_ID, "S19 准备数据：下架");
                return id;
            case SCHEDULED:
                articleService.publishSchedule(id, publishDto(LocalDateTime.now().plusMinutes(30)), CHIEF_ID);
                return id;
            case WITHDRAWN:
                articleService.publishNow(id, CHIEF_ID);
                articleService.withdraw(id, ownerId);
                return id;
            case ARCHIVED:
                articleService.publishNow(id, CHIEF_ID);
                articleService.withdraw(id, ownerId);
                articleService.archive(id, CHIEF_ID);
                return id;
            default:
                throw new IllegalArgumentException("seedArticle 不支持的目标状态：" + target);
        }
    }

    /** 排期入参。 */
    protected CmsArticlePublishDTO publishDto(LocalDateTime scheduleTime) {
        CmsArticlePublishDTO dto = new CmsArticlePublishDTO();
        dto.setScheduleTime(scheduleTime);
        return dto;
    }

    /** 查一篇文章当前的状态码（直接用 JDBC，绕过 MP 缓存与逻辑删除包装）。 */
    protected int statusOf(Long articleId) {
        Integer status = jdbc().queryForObject(
                "SELECT status FROM cms_article WHERE id = ? AND deleted = 0", Integer.class, articleId);
        return status == null ? -1 : status;
    }

    /** 查一篇文章的 {@code create_by}。 */
    protected Long createByOf(Long articleId) {
        return auditColumnOf(articleId, "create_by");
    }

    /** 查一篇文章的 {@code update_by}。 */
    protected Long updateByOf(Long articleId) {
        return auditColumnOf(articleId, "update_by");
    }

    /**
     * 读审计列的数值。
     *
     * <p>⚠️ 不能直接用 {@code queryForObject(..., Long.class)}：
     * {@code cms_article.create_by} 在库里是 {@code BIGINT UNSIGNED}，
     * JDBC 驱动会把无符号大整数映射成 {@link java.math.BigInteger} 而不是 {@code Long}，
     * 于是 {@code assertEquals(Long.valueOf(0L), ...)} 会报
     * {@code expected: java.lang.Long<0> but was: java.math.BigInteger<0>} ——
     * 值明明相等，类型不等。这是"用包装类型做相等比较"的又一个变体，
     * 与代码里 {@code Integer} 不能用 {@code ==} 比是同一类坑。
     */
    private Long auditColumnOf(Long articleId, String column) {
        Object value = jdbc().queryForObject(
                "SELECT " + column + " FROM cms_article WHERE id = ?", Object.class, articleId);
        return value == null ? null : ((Number) value).longValue();
    }
}
