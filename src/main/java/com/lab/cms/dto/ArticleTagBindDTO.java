package com.lab.cms.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

/**
 * 文章打标入参（<b>覆盖式</b>）。
 *
 * <h3>⚠️ 这是"整体替换"语义，不是"追加"</h3>
 * <p>{@code PUT /api/v1/tags/articles/{articleId}} 带上
 * {@code {"tagIds": [1, 2, 3]}} 的含义是：<b>这篇文章最终的标签就是 1、2、3</b>。
 * 原有而本次没出现的标签会被取消。
 *
 * <p>为什么选覆盖式而不是"追加/移除"两个动作：
 * <ul>
 *   <li><b>幂等</b>。同一个请求重发多少次，结果都一样 —— 而"追加"天然不幂等
 *       （重发一次就多一次记录，网络重试场景下会静默出错）。</li>
 *   <li><b>与前端交互天然对齐</b>。打标界面上是一个多选框，
 *       用户点完确定时拿到的就是"最终选中集合"，不需要前端自己算差集。</li>
 *   <li><b>一次请求一个事务</b>。追加/移除要发两次请求才能达成的效果，
 *       中间失败会留下"加了一半"的态。</li>
 * </ul>
 *
 * <h3>⚠️ 为什么用 {@code @NotNull} 而不是 {@code @NotEmpty}</h3>
 * <p>空数组 {@code {"tagIds": []}} 是<b>合法且有意义的</b> —— 它的意思是
 * "清空这篇文章的所有标签"。用 {@code @NotEmpty} 会把"清空"这个操作堵死，
 * 前端只能靠"逐个取消"绕过。
 * <p>相反，{@code null}（不传字段）是非法请求：它和"传空数组"语义不同，
 * 不该被当成"清空"来处理 —— 那种宽容会让"前端忘了传字段"变成一个静默的数据破坏动作。
 *
 * <h3>{@code @Size(max = 20)} 是资源保护</h3>
 * <p>上限 20 个标签：一张文章挂 200 个标签在业务上没有意义，
 * 却要让服务端发 200 次 INSERT。
 * <p>⚠️ 与 {@code BatchSubmitDTO#ids} 一样，这个上限在 Service 里<b>再检查一次</b> ——
 * DTO 只拦得住 HTTP 入口，拦不住"别的 Service 或定时任务直接调 Service"的调用方。
 */
@Data
public class ArticleTagBindDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标标签 ID 集合。<b>空数组 = 清空所有标签</b>，{@code null} = 非法。
     */
    @NotNull(message = "tagIds 不能为 null（清空标签请传空数组）")
    @Size(max = 20, message = "一篇文章最多打 20 个标签")
    private List<Long> tagIds;
}
