package com.lab.cms.controller;

import com.lab.cms.annotation.OperLog;
import com.lab.cms.annotation.RequirePermission;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * S12「审核鉴权与防越权拦截」的<b>静态核查</b>：把"权限注解有没有漏"变成可重复判据。
 *
 * <h2>为什么这条要写成测试，而不是在报告里人工数一遍</h2>
 * <p>手册 S12 的核查项是"权限注解覆盖全部接口，没有一个写接口漏掉"。
 * 人工数一遍能得到当时的结果，但<b>下一次加接口时没人会再数</b>——
 * 而"新加了个写接口、忘了加 {@code @RequirePermission}"正是最典型的越权成因：
 * 编译通过、功能正常、只有安全检查缺失，测试不红就永远发现不了。
 *
 * <p>所以这里用<b>反射遍历</b>控制器，把规则写成断言：
 * <ol>
 *   <li>文章模块的<b>每一个</b>接口都必须有 {@code @RequirePermission}；</li>
 *   <li>注解里的权限码必须<b>真实存在于 {@code sys_permission} 表</b>
 *       （拼错不会报错，只会在运行时变成"谁都没权限"或"谁都有权限"）；</li>
 *   <li><b>写接口必须有 {@code @OperLog}、读接口必须没有</b>
 *       （查询不记操作日志是 S11 定下的口径）；</li>
 *   <li>本项目其余控制器里"没有权限注解"的方法，必须正好等于一份<b>写死的豁免清单</b>
 *       —— 将来有人新加一个没有权限注解的接口，这条会立刻红，逼他
 *       要么加上注解、要么显式把方法名加进豁免清单并说明理由。</li>
 * </ol>
 *
 * <h2>⚠️ 豁免清单为什么写在测试里而不是注解里</h2>
 * <p>豁免的三种"正当理由"是：<b>探针</b>（健康检查要被监控系统匿名调用）、
 * <b>认证入口</b>（登录时还没有身份，无从鉴权）、<b>主干自测</b>（S05/S06 留的验证接口，
 * 只依赖"必须登录"，将来要删）。这三种理由互相不同，没法用注解表达；
 * 而写在测试里，<b>加一行就要改一次测试</b>，改动本身会出现在 code review 里。
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = com.lab.cms.config.SpringConfig.class)
public class ApiPermissionCoverageTest {

    /** 文章模块控制器：这里的接口<b>不允许</b>任何豁免。 */
    private static final Class<?> ARTICLE_CONTROLLER = ArticleController.class;

    /** 文章域权限码前缀。 */
    private static final String ARTICLE_DOMAIN = "article:";

    /**
     * 全项目"刻意不加 {@code @RequirePermission} 的接口"清单。
     *
     * <p>格式 {@code 控制器简单类名#方法名}。每一条都写明为什么可以不加。
     * <p>⚠️ 这个清单<b>只允许因为上面三类理由增长</b>；业务接口一律不许进来。
     */
    private static final Set<String> EXEMPT_METHODS = new LinkedHashSet<>(Arrays.asList(
            // —— 认证入口：登录时还没有身份，无从鉴权；登出与 /me 只要求"已登录"，
            //    由 LoginInterceptor 负责，不需要功能权限。
            "AuthController#login",
            "AuthController#logout",
            "AuthController#me",
            // —— 探针：健康检查与回环验证要被监控/部署脚本匿名调用。
            //    ⚠️ HealthController 里带了 S06 的自测端点（page-echo / require-param /
            //    boom / db-roundtrip），它们同样是"验证基座行为"的探针，不承载业务数据。
            "HealthController#ping",
            "HealthController#pageEcho",
            "HealthController#requireParam",
            "HealthController#boom",
            "HealthController#dbRoundtrip",
            // —— 主干自测：S05/S06 留给主干验证用的，只要求登录，不要求功能权限。
            //    如将来删除这些自测接口，本清单同步删。
            "SystemController#health",
            "SystemController#selfTestOperLog",
            "SystemController#selfTestException"
    ));

    /** 需要 Spring 上下文里的数据源来核对权限码（拼错注解不会报错，必须查库）。 */
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    // ==================== 工具 ====================

    /** 是否带任一 Spring MVC 请求映射注解。 */
    private static boolean isMapped(Method m) {
        return m.isAnnotationPresent(RequestMapping.class)
                || m.isAnnotationPresent(GetMapping.class)
                || m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class)
                || m.isAnnotationPresent(PatchMapping.class);
    }

    /** 该类里所有"接口方法"（带映射注解的，按方法名排序，保证输出稳定）。 */
    private static List<Method> mappedMethods(Class<?> clazz) {
        List<Method> list = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (isMapped(m)) {
                list.add(m);
            }
        }
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    /** 是否写接口（POST / PUT / DELETE / PATCH）。GET 一律算读接口。 */
    private static boolean isWrite(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) {
            return false;
        }
        if (m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class)
                || m.isAnnotationPresent(PatchMapping.class)) {
            return true;
        }
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        if (rm != null) {
            for (RequestMethod method : rm.method()) {
                if (method != RequestMethod.GET && method != RequestMethod.HEAD) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** 人类可读的 HTTP 动词，用于打印清单。 */
    private static String verb(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) return "GET";
        if (m.isAnnotationPresent(PostMapping.class)) return "POST";
        if (m.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (m.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (m.isAnnotationPresent(PatchMapping.class)) return "PATCH";
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        return rm == null ? "?" : Arrays.toString(rm.method());
    }

    /** 库表里真实存在的全部权限码。 */
    private Set<String> permissionCodesInDb() {
        List<String> codes = jdbc().queryForList(
                "SELECT permission_code FROM sys_permission WHERE deleted = 0", String.class);
        return new HashSet<>(codes);
    }

    // ==================== 1. 文章模块：零豁免 ====================

    /**
     * 文章模块每个接口都必须有 {@code @RequirePermission}，且权限码在库表里存在、属于 {@code article:} 域。
     *
     * <p>这条同时防两类事：漏加注解（安全缺口），和写了库里不存在的权限码
     * （{@code PermissionAspect} 会认为"任何人都缺这个权限"，于是连 admin 都被 403 ——
     * 本项目在契约 §1.3 的 {@code article:edit} 上踩过这个坑）。
     */
    @Test
    public void testEveryArticleEndpointHasExistingPermissionCode() {
        Set<String> codesInDb = permissionCodesInDb();
        assertFalse("sys_permission 表里一条权限码都没有，环境不对", codesInDb.isEmpty());

        List<Method> methods = mappedMethods(ARTICLE_CONTROLLER);
        assertTrue("ArticleController 里一个接口都没反射到，测试本身有问题", methods.size() >= 7);

        StringBuilder inventory = new StringBuilder();
        for (Method m : methods) {
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            assertNotNull("⚠️ " + m.getName() + " 没有 @RequirePermission —— 文章模块不允许有豁免接口",
                    rp);
            assertTrue("@" + m.getName() + " 的权限码数组为空", rp.value().length > 0);

            for (String code : rp.value()) {
                assertFalse(m.getName() + " 的权限码是空白串", code == null || code.trim().isEmpty());
                assertTrue("⚠️ " + m.getName() + " 用了库里不存在的权限码「" + code
                                + "」—— PermissionAspect 会认为所有角色都缺它，连 admin 都会被 403。"
                                + "库表实际有：" + new TreeSet<>(codesInDb),
                        codesInDb.contains(code));
                assertTrue(m.getName() + " 的权限码「" + code + "」不在 article: 域内",
                        code.startsWith(ARTICLE_DOMAIN));
            }
            inventory.append(String.format("  %-5s %-22s → %s%n",
                    verb(m), m.getName(), Arrays.toString(rp.value())));
        }

        System.out.println("[S12] 文章模块接口权限清单（共 " + methods.size() + " 个，零豁免）：");
        System.out.print(inventory);
    }

    /**
     * 写接口必须有 {@code @OperLog}，读接口必须<b>没有</b>。
     *
     * <p>两个方向都要断言：只查"写接口有没有日志"会漏掉反向错误
     * （给 GET 也加上 {@code @OperLog}，把 {@code sys_oper_log} 迅速撑爆）。
     * S11 已经把这条口径写进注释，这里把它变成可执行的判据。
     */
    @Test
    public void testWriteEndpointsLogAndReadEndpointsDoNot() {
        List<Method> methods = mappedMethods(ARTICLE_CONTROLLER);
        int writes = 0;
        int reads = 0;

        for (Method m : methods) {
            boolean write = isWrite(m);
            boolean hasOperLog = m.isAnnotationPresent(OperLog.class);
            if (write) {
                writes++;
                assertTrue("⚠️ 写接口 " + m.getName() + " 没有 @OperLog —— 谁改了数据将无法追责",
                        hasOperLog);
                OperLog ol = m.getAnnotation(OperLog.class);
                assertNotNull(ol);
                assertFalse("写接口 " + m.getName() + " 的 @OperLog.module 为空",
                        ol.module() == null || ol.module().trim().isEmpty());
                assertNotNull(m.getName() + " 的 @OperLog.type 为空", ol.type());
            } else {
                reads++;
                assertFalse("⚠️ 读接口 " + m.getName() + " 不该有 @OperLog —— "
                                + "列表页一点就是几十条，会把 sys_oper_log 迅速撑爆",
                        hasOperLog);
            }
        }

        assertTrue("写接口一个都没反射到，测试本身有问题", writes >= 5);
        assertTrue("读接口一个都没反射到，测试本身有问题", reads >= 2);
        System.out.println("[S12] 文章模块：写接口 " + writes + " 个（都有 @OperLog），读接口 "
                + reads + " 个（都没有 @OperLog）");
    }

    // ==================== 2. 全项目：没有权限注解的接口＝豁免清单 ====================

    /**
     * 遍历全部控制器，断言"没有 {@code @RequirePermission} 的接口"正好等于 {@link #EXEMPT_METHODS}。
     *
     * <p>这条是防"新加写接口时忘了加权限注解"的兜底：新接口一旦漏加，
     * 它就会出现在"实际无注解集合"里而不在豁免清单里，断言立刻失败。
     */
    @Test
    public void testUnannotatedEndpointsEqualToExemptionList() {
        Class<?>[] controllers = {ArticleController.class, AuthController.class,
                HealthController.class, SystemController.class};

        Set<String> actuallyUnannotated = new TreeSet<>();
        Set<String> annotated = new TreeSet<>();

        for (Class<?> clazz : controllers) {
            for (Method m : mappedMethods(clazz)) {
                String key = clazz.getSimpleName() + "#" + m.getName();
                if (m.isAnnotationPresent(RequirePermission.class)) {
                    annotated.add(key);
                } else {
                    actuallyUnannotated.add(key);
                }
            }
        }

        // 豁免清单与实际集合必须完全一致（双向比对：多了说明该加注解，少了说明清单过期）
        assertEquals("⚠️ 无权限注解的接口与豁免清单不一致。多的说明该加 @RequirePermission，"
                        + "少的说明清单该删（接口已不再存在或已加注解）",
                new TreeSet<>(EXEMPT_METHODS), actuallyUnannotated);

        // 豁免清单里不许出现"其实有注解"的项（写错了或注解后来补上了）
        for (String e : EXEMPT_METHODS) {
            assertFalse("豁免清单里的 " + e + " 其实已经带了 @RequirePermission，应从清单删除",
                    annotated.contains(e));
        }

        System.out.println("[S12] 全项目控制器接口：有权限注解 " + annotated.size()
                + " 个，刻意豁免 " + actuallyUnannotated.size() + " 个");
        System.out.println("[S12] 豁免明细（必须都是探针 / 认证入口 / 主干自测）：");
        for (String e : actuallyUnannotated) {
            System.out.println("       " + e);
        }
    }

    /**
     * 豁免清单里的名字必须都能反射到真实方法 —— 防止"接口删了清单没删"导致清单虚胖。
     */
    @Test
    public void testExemptionListEntriesStillExist() {
        Set<String> allMapped = new TreeSet<>();
        Class<?>[] controllers = {ArticleController.class, AuthController.class,
                HealthController.class, SystemController.class};
        for (Class<?> clazz : controllers) {
            for (Method m : mappedMethods(clazz)) {
                allMapped.add(clazz.getSimpleName() + "#" + m.getName());
            }
        }
        for (String e : EXEMPT_METHODS) {
            assertTrue("豁免清单里的 " + e + " 反射不到对应方法（接口已删或改名？）",
                    allMapped.contains(e));
        }
        System.out.println("[S12] 豁免清单 " + EXEMPT_METHODS.size() + " 项全部对应到真实接口");
    }

    // ==================== 3. 文章域权限码是否被用满（登记，不强制） ====================

    /**
     * 打印文章域权限码的使用情况，<b>不</b>断言"必须全部用上"。
     *
     * <p>理由：{@code article:publish/withdraw/manage/delete} 对应的接口
     * 分别在 S13/S14/S15 才实现，现在断言"用满"必然红。
     * 这条的价值是<b>把差距显式打出来</b>——S12 结束时它应该恰好剩这几个。
     * 等 S15 做完，可以把这条改成 {@code assertEquals} 收紧。
     */
    @Test
    public void testReportUnusedArticlePermissionCodes() {
        Set<String> inDb = new TreeSet<>(permissionCodesInDb());
        Set<String> expectedArticleDomain = new TreeSet<>(Arrays.asList(
                "article:view", "article:create", "article:update", "article:submit",
                "article:audit", "article:publish", "article:withdraw",
                "article:manage", "article:delete"));

        Set<String> articleCodesInDb = new TreeSet<>();
        for (String c : inDb) {
            if (c.startsWith(ARTICLE_DOMAIN)) {
                articleCodesInDb.add(c);
            }
        }
        assertEquals("库表 article: 域权限码与预期不符（加了/删了权限码？请同步本测试与契约）",
                expectedArticleDomain, articleCodesInDb);

        Set<String> used = new HashSet<>();
        for (Method m : mappedMethods(ARTICLE_CONTROLLER)) {
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            if (rp != null) {
                Collections.addAll(used, rp.value());
            }
        }

        Set<String> unused = new TreeSet<>(expectedArticleDomain);
        unused.removeAll(used);

        System.out.println("[S12] article: 域权限码共 " + expectedArticleDomain.size()
                + " 个，已被接口使用 " + used.size() + " 个");
        System.out.println("[S12] 尚未使用的（对应接口还没做，S13/S14/S15 逐步补上）：" + unused);

        // 只断言"至少用上了 S12 已实现的那些"，不锁死未实现的
        assertTrue("article:create 没被任何接口使用", used.contains("article:create"));
        assertTrue("article:update 没被任何接口使用", used.contains("article:update"));
        assertTrue("article:submit 没被任何接口使用", used.contains("article:submit"));
        assertTrue("article:audit 没被任何接口使用", used.contains("article:audit"));
        assertTrue("article:view 没被任何接口使用", used.contains("article:view"));
    }
}
