package com.lab.cms.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置。
 *
 * <h3>⚠️ 三个必须解决的问题（纯 SSM 环境，没有 Spring Boot 自动配置兜底）</h3>
 *
 * <p><b>问题 1：LocalDateTime 序列化成数组。</b>
 * 不注册 {@code JavaTimeModule} 时，{@code LocalDateTime} 默认被当成 POJO 拆解，
 * 输出成 {@code [2026,10,8,17,25,42]} 这样的数组 —— 前端完全无法使用。
 * 这是从 Spring Boot 转到纯 SSM 时最常踩的坑（Boot 里被自动配置掩盖了）。
 *
 * <p><b>问题 2：null 字段占位。</b>
 * 默认输出 {@code "summary": null}，前端要额外判空，且响应体体积变大。
 * 设成 {@code NON_NULL} 后 null 字段直接不出现。
 * ⚠️ 注意空集合**不要**也被过滤掉 —— 前端对空列表做 {@code forEach} 是安全的，
 * 但字段缺失会导致 {@code list.map()} 报错。Jackson 的 NON_NULL 只过滤 null，
 * 不过滤空集合（要过滤空集合得用 {@code NON_EMPTY}）—— 所以我们用 NON_NULL，
 * 正是为了避免"空集合被删掉导致前端报错"。
 *
 * <p><b>问题 3：时间格式不统一。</b>
 * 不指定格式时会输出 ISO-8601（{@code 2026-10-08T17:25:42}），
 * 带个 T 且可能有纳秒。前端展示需要再格式化。
 * 统一成 {@code yyyy-MM-dd HH:mm:ss} 后，前端可以直接显示。
 *
 * <h3>为什么不用 @JsonFormat 逐个字段标注</h3>
 * <p>每个实体的每个时间字段都写 {@code @JsonFormat(pattern = "...")}：
 * <ul>
 *   <li>重复（20 个字段写 20 遍）</li>
 *   <li>容易漏（新加字段忘了标，格式就不一致）</li>
 *   <li>改格式要改几十处</li>
 * </ul>
 * 全局配置一次搞定，是更正确的做法。{@code @JsonFormat} 只保留给"个别字段需要不同格式"
 * 的例外场景。
 */
@Configuration
public class JacksonConfig {

    /** 日期时间格式，与前端约定一致 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 日期格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 时间格式 */
    public static final String TIME_PATTERN = "HH:mm:ss";

    /**
     * 自定义 ObjectMapper。
     *
     * <p>做成 Bean 而不是每次 new：
     * <ul>
     *   <li>拦截器（{@code LoginInterceptor}）需要用它把 {@code Result} 序列化成 JSON 写响应</li>
     *   <li>ObjectMapper 是线程安全的，全局单例即可（这也是它能被复用为 Bean 的前提）</li>
     *   <li>多处 new 会导致配置不一致（某处忘了注册 JavaTimeModule 就出 bug）</li>
     * </ul>
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // ---------- 1. JSR-310 时间模块 ----------
        JavaTimeModule timeModule = new JavaTimeModule();

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN);

        // LocalDateTime：2026-10-08 17:25:42
        timeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        timeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));

        // LocalDate：2026-10-08
        timeModule.addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter));
        timeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(dateFormatter));

        // LocalTime：17:25:42
        timeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(timeFormatter));
        timeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

        mapper.registerModule(timeModule);

        // ---------- 2. null 字段不输出 ----------
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // ---------- 3. 兼容性开关 ----------

        // 前端多传了未定义的字段时不报错（否则前端加个字段后端就 400）
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 空字符串转 null（" " 这类空白字符串在业务上等价于"没填"）
        // ⚠️ 本项目不开启这个：因为"标题设为空字符串"和"不修改标题"在语义上不同，
        //    开启后无法区分。保持默认（不转换）。

        // 时间不序列化成时间戳数字（保持上面配置的字符串格式）
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // 枚举按 name() 输出（默认行为，显式写出表明意图）
        // 与 sys_oper_log.oper_type 存枚举名保持一致
        mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, false);

        // 不输出空 Bean 的异常（避免 "No serializer found for class ..." 这类报错）
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        return mapper;
    }

    /**
     * 基于自定义 ObjectMapper 的 HTTP 消息转换器。
     *
     * <p>⚠️ 必须显式创建这个 Bean，并在 {@code SpringMvcConfig#extendMessageConverters}
     * 中把它 add 到索引 0。
     *
     * <p>为什么需要两步：{@code @EnableWebMvc} 会注册一套**默认的**
     * MappingJackson2HttpMessageConverter（用的是默认 ObjectMapper，会输出时间数组）。
     * 只创建一个 ObjectMapper Bean 是**不够的** —— SpringMVC 不会自动用你的
     * ObjectMapper 替换默认转换器里的那个（除非用 Spring Boot 的自动配置）。
     * 必须手动替换转换器本身。
     */
    @Bean
    public MappingJackson2HttpMessageConverter jacksonMessageConverter() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper());
        return converter;
    }
}
