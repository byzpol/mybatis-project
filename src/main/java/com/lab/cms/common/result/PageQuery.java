package com.lab.cms.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
 * <h3>⚠️ sortField 本类不做校验，必须由各域白名单校验</h3>
 * <p>MyBatis 的 {@code #{} } 占位符**不能用于列名**（会被当成字符串字面量），
 * 所以排序字段只能用 {@code ${}} 拼接。既然必须拼接，就必须防注入 ——
 * 但白名单是**业务相关**的（文章按 createTime 排，日志按 operTime 排），
 * 基类不知道有哪些合法字段。因此设计成：基类提供 {@code normalize()} 处理数值归一化，
 * 各域在 Service 里显式校验 sortField（见 {@code ArticleServiceImpl#checkSortField}）。
 */
@Data
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 每页最大条数。防止前端传 size=999999 把数据库拖死。 */
    public static final int MAX_SIZE = 100;

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 10;

    /** 页码，从 1 开始。 */
    private Integer page = 1;

    /** 每页条数，默认 10，上限 {@link #MAX_SIZE}。 */
    private Integer size = DEFAULT_SIZE;

    /** 排序字段。必须由各域白名单校验后才能拼入 SQL。 */
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
     *
     * <p>注意：本方法**不校验 sortField**（原因见类注释）。
     * 调用方在 normalize() 之后必须自行做 sortField 白名单校验。
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
