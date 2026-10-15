package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章保存入参（新建草稿 / 修改草稿共用）。
 *
 * <h3>为什么本类不继承 {@code PageQuery}</h3>
 * <p>{@code PageQuery} 承载的是分页与排序入参（{@code page / size / sortField / sortOrder}）。
 * 而"保存一篇文章"是<b>写操作</b>，与分页无关 ——
 * 继承它会让前端误以为可以传 {@code page=2}，后端也会白白多出四个无意义字段。
 * <b>判据：入参里出现"要不要分页"这个问题本身就不合理，就不该继承 PageQuery。</b>
 * 需要分页的是查询类 DTO（见 {@link CmsArticleQueryDTO}）。
 *
 * <h3>校验规则的设计取舍</h3>
 * <p>用的是 JSR-303 分组校验还是"宽松校验 + Service 层按状态补校验"？
 * 本项目选择<b>后者（宽松）</b>，理由是"草稿"这个状态的特殊性：
 * <ul>
 *   <li><b>草稿允许只填标题</b>：投稿人经常先存个标题占位，正文稍后补。
 *       所以 {@link #content} <b>不加 {@code @NotBlank}</b>。</li>
 *   <li><b>标题、分类必填</b>：连标题都没有的文章无法在任何列表里识别，
 *       分类决定文章归属，两者在保存阶段就要求填。</li>
 *   <li>真正严格的"提交审核前必须完整"的校验放在 {@code submitForAudit}
 *       的 Service 逻辑里（S09 实现），因为那里才知道"这是要提交了"。
 *       <b>入参校验只管"这个字段本身的形态对不对"，不管"业务流程允不允许"。</b></li>
 * </ul>
 *
 * <h3>⚠️ 为什么没有 id 字段</h3>
 * <p>"修改草稿"的 id 通过路径参数传入（{@code PUT /api/v1/articles/{id}/draft}），
 * 不从请求体读。原因：避免"请求体里的 id 与路径里的 id 不一致"这种歧义
 * —— 若两者都允许，就需要写"以哪个为准"的规则，而规则本身是 bug 温床。
 * 路径参数是资源定位符，id 天然属于路径。
 */
@Data
public class CmsArticleSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文章标题，必填，最长 200（与库表 {@code VARCHAR(200)} 对齐）。
     * <p>⚠️ 长度用 {@code max = 200} 与库表一致 —— 若这里放宽到 300，
     * 提交时数据库会报 {@code Data too long for column 'title'}，
     * 错误发生在 Service 层深处，前端只能看到 500，体验差。
     * <b>入参长度上限必须 ≤ 库表列宽</b>，是硬规矩。
     */
    @NotBlank(message = "文章标题不能为空")
    @Size(max = 200, message = "文章标题长度不能超过 200")
    private String title;

    /** 摘要，可选，最长 500（与库表 {@code VARCHAR(500)} 对齐）。 */
    @Size(max = 500, message = "文章摘要长度不能超过 500")
    private String summary;

    /** 封面图 URL，可选，最长 255（与库表对齐）。 */
    @Size(max = 255, message = "封面图地址长度不能超过 255")
    private String coverUrl;

    /**
     * 正文内容，<b>可选</b>。
     * <p>不加长度校验：库表是 {@code LONGTEXT}（约 4GB），
     * 在入参上再设一个"看起来安全"的上限反而会挡住合理的长文。
     * 真正的上限由 {@code spring.servlet} 的请求体大小限制与
     * MySQL 的 {@code max_allowed_packet} 控制，不在这一层。
     */
    private String content;

    /**
     * 分类 ID，必填。
     * <p>用 {@code @NotNull} 而不是 {@code @NotBlank}：
     * {@code @NotBlank} 只能用在 CharSequence 上，套在 Long 上会抛
     * {@code UnexpectedTypeException}（校验期直接 500，不是 400）。
     * 这个坑很常见：把 {@code Integer/Long} 字段也标 {@code @NotBlank}。
     */
    @NotNull(message = "请选择文章分类")
    private Long categoryId;

    /**
     * 是否置顶：{@code 0 否 / 1 是}，可选，默认由 Service 补 0。
     * <p>不做 {@code @Min/@Max} 校验到这一层，因为前端是开关组件，
     * 只会传 0/1；但 Service 落库前仍应归一化（非 1 一律当 0），
     * 防止有人手工构造请求传 {@code isTop=999}。
     */
    private Integer isTop;

    /**
     * 排期发布时间，可选。
     * <p>只有"保存并排期"场景会带值。
     * <p>⚠️ 这里<b>不做"必须晚于当前时间"的校验</b>：
     * 那是业务规则（对应 {@code ARTICLE_SCHEDULE_TIME_INVALID}），
     * 且"当前时间"在参数校验那一刻与真正落库那一刻不同。
     * 放在 Service 的排期逻辑里判断才准确。
     */
    private LocalDateTime scheduleTime;
}
