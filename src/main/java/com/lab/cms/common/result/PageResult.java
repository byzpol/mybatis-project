package com.lab.cms.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 统一分页响应体。
 *
 * <p>前端 {@code PageTable} 组件直接消费这五个字段，字段名不允许改动。
 *
 * <h3>为什么不直接返回 MP 的 IPage</h3>
 * <p>{@code IPage} 的序列化结果包含十几个字段（records / total / size / current /
 * pages / orders / optimizeCountSql / searchCount / maxLimit / countId ...），
 * 其中大部分是 MP 内部的查询元数据，对前端毫无意义。直接返回会导致：
 * <ul>
 *   <li>前端契约含糊（"total 到底是总条数还是总页数？"）</li>
 *   <li>MP 升级时字段变动会直接冲击前端</li>
 * </ul>
 * 固定成五个字段后，契约就锁死了。
 *
 * <h3>为什么字段用包装类型 Long 而不是 long</h3>
 * <p>与 Jackson 的 {@code NON_NULL} 序列化策略配合：若某字段为 null 则不出现在 JSON 中。
 * 用基本类型时，null 会被序列化成 0，前端无法区分"真的是 0"还是"没查到"。
 * （本类的构造器已保证不会出现 null，但保持包装类型让语义更明确。）
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页记录 */
    private List<T> records;

    /** 总记录数 */
    private Long total;

    /** 当前页码（从 1 开始） */
    private Long current;

    /** 每页条数 */
    private Long size;

    /** 总页数（由 total/size 向上取整得出，前端 Pagination 组件需要） */
    private Long pages;

    /**
     * 无参构造：Jackson 反序列化（如测试、或作为上游服务的响应）需要。
     */
    public PageResult() {
    }

    /**
     * 全参构造。
     *
     * <p>⚠️ 所有入参做空值兜底，保证构造出的对象一定是"前端可安全消费"的：
     * records 至少是空列表（前端可无脑 forEach），total 至少是 0，
     * pages 由 total 与 size 现算，避免调用方传错。
     */
    public PageResult(List<T> records, Long total, Long current, Long size) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = total == null ? 0L : total;
        this.current = current == null ? 1L : current;
        this.size = size == null || size <= 0 ? 10L : size;
        this.pages = computePages(this.total, this.size);
    }

    /**
     * 从 MP 的 {@link IPage} 转换。
     * <p>这是 Service 层的标准用法：{@code PageResult.of(articleMapper.selectPage(...))}
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        if (page == null) {
            return empty(1L, 10L);
        }
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 空分页。用于"前置校验发现查不到数据"时直接返回，避免再发一次 count 查询。 */
    public static <T> PageResult<T> empty(Long current, Long size) {
        return new PageResult<>(Collections.emptyList(), 0L, current, size);
    }

    /**
     * 总页数计算：向上取整。
     *
     * <p>{@code (total + size - 1) / size} 是整数向上取整的标准写法，
     * 比 {@code Math.ceil((double) total / size)} 更安全 ——
     * 后者在 total 或 size 极大时会有 double 精度丢失风险。
     */
    private static Long computePages(Long total, Long size) {
        if (total == null || total <= 0 || size == null || size <= 0) {
            return 0L;
        }
        return (total + size - 1) / size;
    }
}
