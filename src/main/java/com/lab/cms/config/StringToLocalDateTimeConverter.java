package com.lab.cms.config;

import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * {@code String → LocalDateTime} 的 Web 层转换器（S19 新增）。
 *
 * <h2>为什么需要它（这是一个真实缺陷的修复）</h2>
 * <p>S19 补接口层测试时实测发现：
 * <b>{@code GET /api/v1/articles?beginTime=2026-10-14&endTime=2026-10-20} 直接返回 400</b>，
 * 提示是 {@code Failed to convert property value of type 'java.lang.String' to required type
 * 'java.time.LocalDateTime' for value [2016-10-15 20:34:58]}。
 *
 * <p>根因是项目里有<b>两条互不相干的"字符串 → 时间"转换链</b>：
 * <table border="1">
 *   <tr><th>入口</th><th>转换器</th><th>认什么格式</th></tr>
 *   <tr>
 *     <td>{@code @RequestBody} 的 JSON（排期时间等）</td>
 *     <td>{@code JacksonConfig} 注册的 {@code LocalDateTimeDeserializer}</td>
 *     <td><b>只认</b> {@code yyyy-MM-dd HH:mm:ss}（空格分隔）</td>
 *   </tr>
 *   <tr>
 *     <td>query string 绑定 DTO（列表筛选时间）</td>
 *     <td>Spring 的 {@code DateTimeFormatterRegistrar} 默认值</td>
 *     <td><b>只认</b> ISO-8601（{@code 2026-10-14T00:00:00}）</td>
 *   </tr>
 * </table>
 *
 * <p>于是同一个前端组件（{@code el-date-picker}）在两种场景下会得到不同的结果：
 * {@code ArticleEdit.vue} 的排期时间用 {@code value-format="YYYY-MM-DD HH:mm:ss"}（走 JSON，能用），
 * 而 {@code ArticleList.vue} 的创建时间范围用 {@code value-format="YYYY-MM-DD"} 拼进 query string
 * —— <b>一用筛选就 400</b>。
 *
 * <p>更隐蔽的是：这属于"跨模块真缺陷"，<b>只在真实 HTTP 请求下才暴露</b>。
 * S17 的 {@code ArticlePageQueryServiceTest} 直接 {@code new CmsArticleQueryDTO()} 设值，
 * 绕过了 Web 绑定；而 {@code PageQueryTest} 更是纯单元测试。
 * <b>S19 之前全仓 MockMvc 出现 0 次</b>，所以这条路径此前完全没有自动化覆盖。
 *
 * <h2>本转换器接受的三类写法（刻意不接受 ISO 的 {@code T}）</h2>
 * <ol>
 *   <li>{@code yyyy-MM-dd HH:mm:ss} —— 项目全局唯一格式，与
 *       {@link JacksonConfig#DATE_TIME_PATTERN} 同源；</li>
 *   <li>{@code yyyy-MM-dd HH:mm} —— 容忍少写秒（手工调用 / 脚本常用）；</li>
 *   <li>{@code yyyy-MM-dd} —— 纯日期，按当日 <b>00:00:00</b> 处理。
 *       日期范围筛选器的左边界就是这种写法。</li>
 * </ol>
 *
 * <p>⚠️ <b>刻意不支持</b> {@code 2026-10-14T00:00:00}。S13 已经定过口径：
 * "不要在某个接口单独做格式兼容，否则项目里会出现两套时间口径"。
 * 这里同理 —— 若再收下 {@code T}，前端就会重新分裂成"JSON 传空格、query 传 T"两种写法。
 * 统一到一种，比"两者都兼容"更有利于长期维护。
 *
 * <h2>⚠️ 纯日期按 00:00:00 处理 —— 右边界语义由调用方负责</h2>
 * <p>把 {@code endTime=2026-10-20} 解析成 {@code 2026-10-20 00:00:00} 之后，
 * SQL 里的 {@code create_time <= '2026-10-20 00:00:00'} 会<b>漏掉 10-20 当天</b>的数据。
 * 这是"日期区间右边界"的经典问题，<b>不能靠底层转换器猜</b>
 * （同一个转换器不知道自己是区间左端还是右端）。
 *
 * <p>正确做法是让"知道语义的那一层"补全：
 * <ul>
 *   <li>前端 {@code ArticleList.vue} 在 setter 里把左边界补 {@code 00:00:00}、
 *       右边界补 {@code 23:59:59}（已随本步一起改）；</li>
 *   <li>若调用方直接传纯日期，则得到"左闭右开"的保守语义 ——
 *       <b>不会 400</b>，只是右边界不含当天。这一点已写进
 *       {@code docs/接口契约.md} 的列表接口说明。</li>
 * </ul>
 */
public class StringToLocalDateTimeConverter implements Converter<String, LocalDateTime> {

    /** 项目全局格式：与 {@code JacksonConfig.DATE_TIME_PATTERN} 保持一致。 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN);

    /** 容忍少写秒。 */
    private static final DateTimeFormatter DATE_TIME_NO_SECOND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 纯日期：按当日 00:00:00 处理。 */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern(JacksonConfig.DATE_PATTERN);

    /**
     * 转换失败时抛出 {@link IllegalArgumentException} ——
     * Spring 会把它包装成 {@code ConversionFailedException}，
     * 再被 {@code BindException} 接住，最终由 {@code GlobalExceptionHandler#handleBindException}
     * 转成 <b>HTTP 400 + code 400</b>（不是 500）。这一点已被
     * {@code ArticleQueryTest} 的用例钉住。
     */
    @Override
    public LocalDateTime convert(String source) {
        if (source == null) {
            return null;
        }
        String text = source.trim();
        if (text.isEmpty()) {
            // 空串按"没传这个条件"处理（前端 request 里 delete 掉参数是主路径，
            // 但空串也应该被宽容掉，否则 ?beginTime= 会 400）
            return null;
        }

        for (DateTimeFormatter formatter : new DateTimeFormatter[]{DATE_TIME, DATE_TIME_NO_SECOND}) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式继续试
            }
        }

        try {
            // 纯日期 → 当日零点
            return LocalDateTime.of(LocalDate.parse(text, DATE), LocalTime.MIN);
        } catch (DateTimeParseException e) {            throw new IllegalArgumentException(
                    "时间格式非法：[" + source + "]。允许的写法："
                            + "yyyy-MM-dd HH:mm:ss、yyyy-MM-dd HH:mm、yyyy-MM-dd"
                            + "（不接受 ISO-8601 的 T 分隔写法）");
        }
    }
}
