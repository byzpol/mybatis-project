package com.lab.cms.common.result;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一分页入参基类。
 *
 * <p>各业务域的查询 DTO 继承本类，如 {@code ArticleQueryDTO extends PageQuery}。
 * 字段名固定为 {@code page / size / sortField / sortOrder}，前端统一传。
 *
 * <h3>为什么把分页参数抽成基类而不是每域各写一份</h3>
 * <p>四个业务域各写一份的话，"size 上限"这个约束就会有四份实现，
 * 且新人接手时不知道该看哪份。抽成基类后：
 * <ul>
 *   <li>{@code normalize()} 只有一处实现，上限规则天然一致</li>
 *   <li>前端可以写一个通用的分页请求构造器</li>
 *   <li>新增业务域时不会漏掉分页保护</li>
 * </ul>
 *
 * <h3>⚠️ 本类的最重要一条：{@code toMpPage()} 绝不用字符串拼 ORDER BY</h3>
 * <p>MyBatis 的 {@code #{}} 占位符不能用于列名（会被当成字符串字面量），
 * 所以"用 ${} 拼排序字段"是很自然的想法 —— 但那样就有 SQL 注入面。
 *
 * <p>本类的做法是不拼字符串，而是走 MP 的 {@link Page#addOrder(OrderItem)}：
 * <ul>
 *   <li>列名由 MP 内部处理，不进 SQL 文本拼接流程</li>
 *   <li>排序方向用枚举 {@code OrderItem.asc()/desc()}，不是字符串</li>
 *   <li>再叠加一层 {@link #SAFE_IDENTIFIER} 正则白名单，
 *       把 "create_time; drop table" 这类输入直接拒掉</li>
 * </ul>
 * 三重防线都比单纯写个正则更稳，也更不容易在后续重构中被绕过。
 */
@Data
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 每页最大条数。防止前端传 size=999999 把数据库拖死。 */
    public static final int MAX_SIZE = 100;

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 10;

    /**
     * 排序字段的安全白名单正则：只允许字母、数字、下划线，且必须以字母或下划线开头。
     *
     * <p>为什么必须有这道防线：
     * <pre>
     * 前端传：sortField = "create_time; drop table cms_article"
     * 若直接拼进 ORDER BY，这条 SQL 就变成了两句
     * </pre>
     * <p>正则 {@code ^[A-Za-z_][A-Za-z0-9_]*$} 的约束：
     * <ul>
     *   <li>首位不允许数字 —— 挡住 "1=1" 这类表达式</li>
     *   <li>只允许标识符字符 —— 挡住分号、空格、括号、单引号、反引号</li>
     *   <li>长度上限 64（MySQL 标识符上限）—— 挡住超长输入撑爆日志</li>
     * </ul>
     *
     * <p>⚠️ 注意这道防线是"格式白名单"，不保证该列真实存在。
     * 传 {@code sortField="not_a_column"} 会通过这里，然后在数据库层报错。
     * 这是可接受的：报错比注入安全得多，而且列名合法性属于各域知识
     * （Service 可用 {@link #requireSortFieldIn} 做精确白名单）。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** 页码，从 1 开始。 */
    private Integer page = 1;

    /** 每页条数，默认 10，上限 {@link #MAX_SIZE}。 */
    private Integer size = DEFAULT_SIZE;

    /** 排序字段。必须通过 {@link #SAFE_IDENTIFIER} 格式校验后才能使用。 */
    private String sortField;

    /** 排序方向：asc / desc（不区分大小写），默认 desc。 */
    private String sortOrder = "desc";

    /** 合法的排序方向集合（用 Set 便于 O(1) 判断与 hasText 式校验）。 */
    private static final Set<String> VALID_ORDERS = new HashSet<>(Arrays.asList("asc", "desc"));

    /**
     * 归一化：修正非法 page/size/sortOrder。
     *
     * <p>⚠️ 每个 Service 的分页查询方法开头都**必须**调用一次。
     * 不调用的后果：
     * <ul>
     *   <li>{@code page=-1} 会传给 SQL，MySQL 报语法错误或返回意外结果</li>
     *   <li>{@code size=100000} 会真的去查十万条，直接把演示现场搞卡</li>
     *   <li>{@code sortOrder="'; DROP TABLE"} 这种会拼进 SQL —— 虽然下面
     *       只判断是否等于 asc/desc 就能挡住，但不调用 normalize 就没这道防线</li>
     * </ul>
     */
    public void normalize() {
        if (page == null || page < 1) {
            page = 1;
        }
        if (size == null || size < 1) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        if (sortOrder == null || !VALID_ORDERS.contains(sortOrder.toLowerCase())) {
            sortOrder = "desc";
        } else {
            // 统一成小写，避免 asc 与 ASC 生成不同 SQL 导致 MyBatis 的 SQL 缓存失效
            sortOrder = sortOrder.toLowerCase();
        }
    }

    /**
     * 判断是否有有效的排序字段。
     * <p>Service 里用它决定"要不要加 ORDER BY"，避免拼出 {@code ORDER BY null}。
     */
    public boolean hasSortField() {
        return sortField != null && !sortField.trim().isEmpty();
    }

    /**
     * 校验带 {@code _} 的数据库列名能安全使用。
     *
     * <p>⚠️ 任何要把 sortField 拼进 ORDER BY 的地方都**必须**先过这个方法。
     * 不做的话，{@code sortField="id; drop table sys_user"} 会直接进 SQL。
     *
     * @throws BusinessException 参数非法时抛出（错误码 10400），
     *         由 {@code GlobalExceptionHandler} 转成 400 响应。
     *         这里抛业务异常而不是返回 boolean，是因为"非法排序字段"属于
     *         客户端传参错误，应当明确告知调用方，而不是静默降级成默认排序 ——
     *         静默降级会让前端以为排序生效了，排查起来更费时间。
     */
    public void checkSortField() {
        if (!hasSortField()) {
            return;
        }

        // ⚠️ 顺序很重要：**先剔除两端普通空白，再用正则校验，且正则必须对整串生效**。
        //
        // 这里的坑：Java 的 String.trim() 会去掉所有码点 <= U+0020 的字符，
        // 其中包含 NUL(0x00)、TAB(0x09) 等控制符。
        // 若用 trim() 处理输入 "id\0"，末尾的 NUL 被吃掉、得到 "id" 从而通过校验；
        // 但原始字符串 "id\0" 在某些 MySQL 配置下会被当成与 "id" 不同的标识符 ——
        // 这是真实存在的 NUL 字节截断绕过手法。
        //
        // 所以这里只剔除**普通空格**（0x20），不剔除控制字符；
        // 控制字符留在串里，下面正则就会因为不在 [A-Za-z0-9_] 内而拒绝它。
        String f = stripSpaces(sortField);
        if (!SAFE_IDENTIFIER.matcher(f).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "排序字段非法：" + describe(sortField) + "（只允许字母、数字、下划线，且不能以数字开头）");
        }
        this.sortField = f;
    }

    /**
     * 只剔除两端的普通空格（U+0020）。
     *
     * <p>特意不用 {@code String.trim()} —— 它会连 NUL、TAB 这类控制符一起去掉，
     * 从而给 {@code "id\0"} 这类输入"洗白"的机会（详见 {@link #checkSortField()} 注释）。
     * 这里手写循环，语义精确可控。
     */
    private static String stripSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? null : "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 把非法输入渲染成"人能看懂"的形式。
     *
     * <p>为什么不直接 {@code "…" + raw + "…"}：非法输入里经常含控制字符
     * （NUL、换行、退格），直接拼进日志会让日志错行、甚至污染终端。
     * 这里把控制字符转成 {@code \\xNN} 形式，保证报错信息是一行、可读、可复制。
     *
     * <p>同时截断到 40 字符 —— 防止有人传 100KB 的字符串把日志刷爆。
     */
    private static String describe(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(s.length(), 40);
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append(String.format("\\x%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        if (s.length() > limit) {
            sb.append("…(共 ").append(s.length()).append(" 字符)");
        }
        return sb.toString();
    }

    /**
     * 精确白名单校验：只允许传入列表中的字段。
     *
     * <p>比 {@link #checkSortField()} 更严格 —— 那个只校验"格式像标识符"，
     * 这个方法校验"白名单里有"。各业务域知道自己的可排序列，
     * 在 Service 里调用本方法即可：
     * <pre>
     * query.checkSortFieldIn("create_time", "publish_time", "view_count");
     * </pre>
     *
     * <p>为什么把白名单交给各域而不是基类写死：
     * 基类不知道有哪些列（文章按 publish_time，日志按 oper_time），
     * 写死会变成"所有域共用一张大表"的坏设计。
     *
     * <p>另外注意：白名单里写的是**数据库列名**（下划线），不是 Java 属性名。
     * 因为 MP 的 {@code OrderItem} 最终拼的是 SQL 列名。
     *
     * @param allowedFields 允许排序的列名，至少传一个
     */
    public void checkSortFieldIn(String... allowedFields) {
        checkSortField();
        if (!hasSortField()) {
            return;
        }
        for (String allowed : allowedFields) {
            if (allowed.equalsIgnoreCase(sortField)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR,
                "不支持按 " + sortField + " 排序，可选：" + String.join("、", allowedFields));
    }

    /**
     * 转成 MP 的分页对象。
     *
     * <p>用法：
     * <pre>
     * Page&lt;Article&gt; mp = query.toMpPage();
     * return PageResult.of(articleMapper.selectPage(mp, wrapper));
     * </pre>
     *
     * <p>⚠️ 调用前**必须**先调 {@link #normalize()}，否则 page/size 可能是
     * -1 或 999999。本方法出于防御式编程只做了兜底（负值归默认），
     * 但不负责把 size 截到 100 —— 那个规则在 normalize() 里，
     * 两处都截会让人困惑"到底哪个说了算"。
     *
     * <p>排序：{@code sortField} 非空时先过 {@link #checkSortField()} 格式校验，
     * 再用 {@code OrderItem.asc/desc} 传给 MP，不拼字符串。
     * 想要更严格的列名白名单，用 {@link #toMpPage(String...)}。
     *
     * @param <T> 分页记录类型
     * @return 已设置好分页与排序的 Page 对象
     */
    public <T> Page<T> toMpPage() {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_SIZE : size;

        Page<T> mpPage = new Page<>(p, s);

        if (hasSortField()) {
            // 先校验再使用：非法输入在这里就被拦下，不会流到 SQL
            checkSortField();
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            mpPage.addOrder(asc ? OrderItem.asc(sortField) : OrderItem.desc(sortField));
        }

        return mpPage;
    }

    /**
     * 转成 MP 分页对象，并带上精确白名单校验（推荐用法）。
     *
     * <p>比裸的 {@link #toMpPage()} 更严格：传了白名单外的列名会得到明确的
     * 400 响应（"不支持按 xxx 排序"），而不是数据库层的语法错误。
     *
     * <pre>
     * Page&lt;Article&gt; mp = query.toMpPage("create_time", "publish_time", "view_count");
     * </pre>
     *
     * <p>注意白名单传的是**数据库列名**（下划线），不是 Java 属性名 ——
     * MP 的 OrderItem 最终拼进 SQL 的就是列名。
     *
     * @param allowedFields 允许排序的列名；不传则退化为只做格式校验
     */
    public <T> Page<T> toMpPage(String... allowedFields) {
        if (allowedFields != null && allowedFields.length > 0) {
            checkSortFieldIn(allowedFields);
        }
        return toMpPage();
    }

    // ==================== 显式 getter ====================
    // 虽然 @Data 已生成，但显式写出可以让"字段被意外改名"在编译期就暴露
    // （否则字段名改了，getter 名也跟着变，调用方不会报错）

    public Integer getPage() {
        return page;
    }

    public Integer getSize() {
        return size;
    }

    public String getSortField() {
        return sortField;
    }

    public String getSortOrder() {
        return sortOrder;
    }
}
