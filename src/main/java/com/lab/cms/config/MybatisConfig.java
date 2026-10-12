package com.lab.cms.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lab.cms.base.AuditMetaObjectHandler;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 配置类。
 *
 * <p>职责（三件事）：
 * <ol>
 *   <li>构建 SqlSessionFactory（用 MP 的 MybatisSqlSessionFactoryBean，不是 MyBatis 原生的）</li>
 *   <li>装配 MP 拦截器链（分页 / 乐观锁 / 防全表更新）</li>
 *   <li>扫描 Mapper 接口</li>
 * </ol>
 *
 * <h3>⚠️ 本类最关键的三个坑（实验一已实测踩过）</h3>
 *
 * <p><b>坑 1：SqlSessionFactoryBean 必须用 MP 提供的那个。</b>
 * 如果用 MyBatis 原生的 {@code org.mybatis.spring.SqlSessionFactoryBean}，
 * 编译能过、启动也正常，但所有 BaseMapper 自带的方法（selectById / insert / updateById /
 * selectPage ...）在运行时会抛 "Invalid bound statement (not found)"。
 * 原因是原生工厂不会注册 MP 的注入器（DefaultSqlInjector），这些方法根本没被注入到配置里。
 * 必须用 {@code com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean}。
 *
 * <p><b>坑 2：Mapper XML 的扫描路径。</b>
 * 本项目的 XML 放在 {@code src/main/resources/com/lab/cms/mapper/} 下
 * （与接口的包路径 com.lab.cms.mapper 保持一致，这是实验一验证过的正确路径）。
 * MyBatis 的默认行为是"XML 与接口同包名则自动关联"，所以路径必须
 * {@code classpath*:com/lab/cms/mapper/*.xml}，写成 {@code classpath*:mapper/*.xml} 会找不到。
 *
 * <p><b>坑 3：拦截器添加顺序。</b>
 * MP 官方明确要求：多个 InnerInterceptor 时，**分页插件必须最后添加**。
 * 因为分页插件会改写 SQL（追加 LIMIT 并执行 count 查询），
 * 若它排在乐观锁/防攻击插件之前，改写后的 SQL 会跳过后续插件的处理逻辑，
 * 导致 count 语句丢失 WHERE 条件、或乐观锁版本号没有被追加到 UPDATE 语句中。
 * 症状是"分页查出来总数不对"或"乐观锁从不生效"，都很难排查。
 */
@Configuration
public class MybatisConfig {

    /**
     * MP 拦截器链。
     *
     * <p>顺序（严格按此顺序添加）：
     * <ol>
     *   <li>防全表更新删除插件</li>
     *   <li>乐观锁插件</li>
     *   <li>分页插件 ← 必须最后</li>
     * </ol>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ---- 1. 防全表更新/删除插件 ----
        // 拦截不带 WHERE 条件的 UPDATE / DELETE，直接抛异常。
        // 价值：防止"手滑写了个 updateById(null)"或"delete 忘了加条件"导致全表数据被清空。
        // 这是真实事故中排位极靠前的一类，成本极低收益极高。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        // ---- 2. 乐观锁插件 ----
        // 让实体上 @Version 标注的字段自动参与 UPDATE：
        //   UPDATE cms_article SET ..., version = version + 1 WHERE id = ? AND version = ?
        // ⚠️ 必须显式判 updateById 的返回值：不匹配时返回 0 而不是抛异常，
        //    静默失败是 MP 乐观锁最常见的坑（见 ArticleServiceImpl 的处理）。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // ---- 3. 分页插件（必须最后）----
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // setMaxLimit(100)：与 PageQuery.MAX_SIZE 保持一致，双重保险。
        // 即使前端绕过 DTO 校验直接传 size=100000，这里也会截断。
        pagination.setMaxLimit(100L);
        // setOverflow(false)：页码超出范围时返回空列表，而不是回到第一页。
        // 选 false 的理由：前端拿到空列表能明确知道"翻过头了"；
        // 若自动回第一页，用户会以为"翻回首页了"，心智模型混乱。
        pagination.setOverflow(false);
        // 不开启 count 优化（默认 false）：开启后会尝试把 select 列简化成 count(*)，
        // 但当 SQL 里有 distinct / group by 时优化可能出错。课程项目数据量小，稳定性优先。
        pagination.setOptimizeJoin(true);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }

    /**
     * SqlSessionFactory。
     *
     * <p>⚠️ 注意这里是 {@code MybatisSqlSessionFactoryBean}（MP 的），不是 MyBatis 原生的。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MybatisPlusInterceptor mybatisPlusInterceptor,
                                               AuditMetaObjectHandler auditMetaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();

        // ---- 数据源 ----
        factoryBean.setDataSource(dataSource);

        // ---- Mapper XML 位置（路径与接口包名一致）----
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:com/lab/cms/mapper/*.xml")
        );

        // ---- 别名包：XML 里可以直接写 Article 而不用写全限定名 ----
        factoryBean.setTypeAliasesPackage("com.lab.cms.entity");

        // ---- MyBatis 核心配置 ----
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 下划线转驼峰：author_id → authorId。本项目数据库全用下划线命名，必须开启。
        configuration.setMapUnderscoreToCamelCase(true);
        // 日志实现用 SLF4J（配合 logback.xml 里 com.lab.cms.mapper 的 DEBUG 级别打印 SQL）
        configuration.setLogImpl(Slf4jImpl.class);
        // 允许返回 null 的字段（默认就是 true，显式写出表明意图）
        configuration.setCallSettersOnNulls(true);
        // 不使用二级缓存：本项目无跨会话缓存需求，
        // 且二级缓存一旦在分布式/多实例场景下用错，会造成数据不一致。
        configuration.setCacheEnabled(false);
        // 装配 MP 拦截器链
        configuration.addInterceptor(mybatisPlusInterceptor);

        factoryBean.setConfiguration(configuration);

        // ---- MP 全局配置 ----
        factoryBean.setGlobalConfig(globalConfig(auditMetaObjectHandler));

        return factoryBean.getObject();
    }

    /**
     * MP 全局配置。
     *
     * <p>主要集中在三件事：主键策略、逻辑删除全局值、审计字段自动填充器。
     *
     * <p>⚠️ 必须把 {@code MetaObjectHandler} 显式塞进 {@code GlobalConfig} ——
     * 见 {@link #globalConfig(AuditMetaObjectHandler)} 的说明。
     */
    private GlobalConfig globalConfig(AuditMetaObjectHandler auditMetaObjectHandler) {
        GlobalConfig globalConfig = new GlobalConfig();

        // ---- 主键策略 ----
        // 本项目全部表用 AUTO_INCREMENT，故数据库主键策略为 AUTO。
        // ⚠️ 不要用 ASSIGN_ID（雪花算法）：雪花 ID 是 19 位 long，
        //    前端 JS 的 Number 只能安全表示 15~16 位，会丢精度导致 id 变成 xxx000。
        //    用自增主键天然规避这个问题（虽然本项目 id 不会超 15 位，但策略选择要正确）。
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(com.baomidou.mybatisplus.annotation.IdType.AUTO);

        // ---- 逻辑删除全局配置 ----
        // ⚠️ 这里设置的全局值必须与实体上 @TableLogic 的显式值一致。
        //    而实体上仍然要显式写 @TableLogic(value="0", delval="1")，原因见实体类注释：
        //    MP 默认 value="0"/delval="1" 与"1=有效/0=无效"这类业务语义相反，
        //    不显式声明，读代码的人（和未来的自己）极易理解错。
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicNotDeleteValue("0");
        dbConfig.setLogicDeleteValue("1");

        globalConfig.setDbConfig(dbConfig);

        // ---- ⚠️⚠️ 审计字段自动填充器（本方法最容易被漏掉、后果最严重的一行）----
        //
        // 【错误认知】"MetaObjectHandler 实现类加了 @Component 被扫描到就会自动生效。"
        //  —— 这是 Spring Boot 下 mybatis-plus-boot-starter 自动配置带来的错觉。
        //  在本项目的纯 SSM 环境（手动装配 MybatisSqlSessionFactoryBean、无 starter）下，
        //  MP **不会**去 Spring 容器里查找 MetaObjectHandler，
        //  不显式 set 就等于没写这个填充器。
        //
        // 【实测症状】填充器静默失效，实体里的 createTime/updateTime 保持 null，
        //  而 17 张表的这些列是 NOT NULL，于是所有 insert 都抛：
        //      Column 'create_time' cannot be null
        //  更坑的是 WebLogAspect 里用 e.getMessage() 打日志时拿到的是空串
        //  （DataIntegrityViolationException 经 MyBatis-Spring 翻译后 message 为 null），
        //  日志里只看到 "操作日志写库失败（已保留文件日志） | 原因="，
        //  原因字段是空的 —— 排查时极易误判为"表结构问题"。
        //
        // 【正确做法】把填充器实例显式交给 GlobalConfig（本行）。
        //  不依赖容器扫描的"魔法"，装配关系在代码里可见。
        globalConfig.setMetaObjectHandler(auditMetaObjectHandler);

        // banner 关掉（每次启动打印一大段 MP 版本信息，干扰日志阅读）
        globalConfig.setBanner(false);

        return globalConfig;
    }

    /**
     * SqlSessionTemplate。
     * <p>MyBatis-Spring 要求：Mapper 接口的代理需要它 。
     * <p>实际上 MapperScannerConfigurer 在找到 SqlSessionFactory 后会自动创建，
     * 这里显式声明是为了让依赖关系明确（也便于将来自定义执行器）。
     */
    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * Mapper 接口扫描。
     *
     * <p>用 {@code MapperScannerConfigurer} 而不是 {@code @MapperScan} 注解：
     * 两者的效果等价，但 MapperScannerConfigurer 是 BeanDefinitionRegistryPostProcessor，
     * 它在配置类解析的更早阶段运行，能避免"Mapper Bean 已创建但数据源还没准备好"这类时序问题。
     * 这在纯 SSM（无 Spring Boot 自动配置兜底）环境下更稳。
     *
     * <p>{@code sqlSessionFactoryBeanName} 用 String 而不是引用 SqlSessionFactory 对象：
     * 这是为了避免 MapperScannerConfigurer 过早实例化 SqlSessionFactory，
     * 从而绕开 @Value 注入尚未完成的时序问题（MyBatis-Spring 官方推荐的写法）。
     */
    @Bean
    public MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer configurer = new MapperScannerConfigurer();
        configurer.setBasePackage("com.lab.cms.mapper");
        configurer.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return configurer;
    }
}
