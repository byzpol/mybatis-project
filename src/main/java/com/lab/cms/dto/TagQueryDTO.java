package com.lab.cms.dto;

import com.lab.cms.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 标签列表查询入参（分页）。
 *
 * <p>与 {@link CategoryQueryDTO} 同口径：字段全可选、{@code null} 表示条件不生效。
 * <p>可排序列白名单由 Service 按"库表列名"给出（{@code use_count} / {@code create_time} / {@code id}），
 * 因为"按热度排序"是标签列表最常用的排序方式。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TagQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签名称关键字，模糊匹配。 */
    private String tagName;

    /** 状态：0 停用 / 1 启用。 */
    private Integer status;
}
