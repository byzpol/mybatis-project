package com.lab.cms.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类。
 *
 * <p>所有实体类继承本类，自动获得：主键、审计四字段、逻辑删除标记。
 *
 * <h3>为什么用继承而不是每个实体各写一遍</h3>
 * <p>17 张表 × 6 个公共字段 = 102 行重复代码。继承后：
 * <ul>
 *   <li>字段定义只有一处，@TableField 填充策略也只有一处</li>
 *   <li>新增表时不会漏字段（17 张表全带审计字段是本项目的硬约束）</li>
 *   <li>将来改填充策略（如加个 tenant_id）只需改基类</li>
 * </ul>
 *
 * <h3>⚠️ 三个注解的坑（实验一实测踩过）</h3>
 *
 * <p><b>坑 1：{@code @TableLogic} 必须显式写 value 与 delval。</b>
 * MP 的默认值是 {@code value="0" / delval="1"}，
 * 语义是"0 = 未删除 / 1 = 已删除"。但很多业务习惯是"1 = 有效 / 0 = 无效"，
 * 两者方向相反。不显式声明时，读代码的人（和三个月后的自己）
 * 极易理解反 —— 把"已删除"当成"有效"。
 * 显式写出来，让语义在代码里直接可见。
 *
 * <p><b>坑 2：{@code @JsonIgnore} 加在 deleted 上。</b>
 * {@code deleted} 是内部字段，不应该出现在 API 响应里（前端不需要知道）。
 * 虽然本项目约定用 VO 出参（VO 里没有这个字段），但万一某处直接返回了 Entity，
 * {@code @JsonIgnore} 是最后一道防线。
 *
 * <p><b>坑 3：审计字段的 {@code FieldFill} 策略。</b>
 * {@code createBy/createTime} 用 {@code INSERT}（只在插入时填），
 * {@code updateBy/updateTime} 用 {@code INSERT_UPDATE}（插入和更新都填）。
 * 若把 createTime 也设成 INSERT_UPDATE，更新时会把创建时间覆盖成当前时间 —— 数据就错了。
 * 数据库层虽然设了 {@code DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP}，
 * 但那是"数据库自己填"，MP 的填充是"Java 侧填"，两者会打架。
 * 本项目的约定是：**以 MP 的 MetaObjectHandler 填充为准**（因为能拿到当前用户 ID），
 * 数据库的 DEFAULT 只作为"绕过 ORM 直接 insert 时"的兜底。
 */
@Data
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     *
     * <p>{@code IdType.AUTO} 表示用数据库自增（而非 MP 的雪花 ID）。
     * <p>⚠️ 不要用 {@code ASSIGN_ID}（雪花算法）：19 位 long 超出 JS Number 的安全范围
     * （15~16 位），前端会丢精度，id 变成 xxx000 这样的巧合值。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 创建人 ID。
     * <p>由 {@code MetaObjectHandler} 在 INSERT 时从当前登录用户填充。
     * <p>{@code FieldFill.INSERT}：只在插入时填。
     */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 创建时间。
     * <p>{@code FieldFill.INSERT}：只在插入时填，更新时**不覆盖**。
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新人 ID。
     * <p>{@code FieldFill.INSERT_UPDATE}：插入与更新都填。
     * <p>插入时也填的原因：让"创建"这件事也留下操作人痕迹，
     * 否则第一条记录的 update_by 是 null，审计时不好看。
     */
    @TableField(value = "update_by", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 更新时间。
     * <p>{@code FieldFill.INSERT_UPDATE}：插入与更新都填。
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：0 未删除 / 1 已删除。
     *
     * <p>⚠️ 必须显式写 value 与 delval（原因见类注释坑 1）。
     *
     * <p>加了本注解后，MP 会自动改写 SQL：
     * <ul>
     *   <li>{@code SELECT ... WHERE deleted = 0}</li>
     *   <li>{@code UPDATE ... SET deleted = 1 WHERE ...}（{@code deleteById} 变成逻辑删除）</li>
     * </ul>
     * <b>注意</b>：这意味着**手写的 Mapper XML 里的 SELECT 不会自动加这个条件** ——
     * XML 中的 SQL 是原样执行的。所以凡是手写 XML 查询，必须自己写
     * {@code AND deleted = 0}，否则会查出已删除的数据。
     * 这是 MP 逻辑删除最容易踩的坑，写 XML 时必须记住。
     */
    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    @JsonIgnore
    private Integer deleted;
}
