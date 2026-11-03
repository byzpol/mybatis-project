package com.lab.cms.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量提交初审的结果。
 *
 * <h2>为什么批量接口的返回值必须能表达"部分成功"</h2>
 *
 * <p>这个 VO 是本项目 S09 里唯一一处<b>主动修改了 S07 已定接口签名</b>的地方，
 * 因此把来龙去脉写清楚。
 *
 * <p>S07 建骨架时 {@code ArticleService#batchSubmit} 的返回值是 {@code void}，
 * 接口注释里已经写明这是"临时占位"，并注明"S09 实现时应改为返回逐条结果集合"。
 * 现在落实这个改动。
 *
 * <h3>为什么不选"全成功或全回滚"</h3>
 * <p>作者一次写完几篇草稿要一起提交，10 篇里有 2 篇因为状态不对（比如其中一篇
 * 已经被编辑退回了）而失败，两种设计的结果差别很大：
 * <table border="1">
 *   <tr><th></th><th>全成功或全回滚</th><th>逐条容错（本方案）</th></tr>
 *   <tr><td>结果</td><td>8 篇也不提交</td><td>8 篇提交成功，2 篇失败</td></tr>
 *   <tr><td>用户感受</td><td>"点了没反应，还得自己去猜哪篇有问题"</td><td>"这 2 篇有问题，其余已经交上去了"</td></tr>
 *   <tr><td>重试成本</td><td>用户要先找出坏的那篇、去掉、再整批重来</td>
 *       <td>直接修那 2 篇即可</td></tr>
 * </table>
 * <p>而且"回滚全部"在实现上反而是错的：一次请求里如果有一篇失败就整批回滚，
 * 那用户永远无法通过"再点一次"来成功 —— 失败的那篇还在，下次还是整批失败，
 * 形成死锁式的重试循环。
 *
 * <h3>⚠️ 一个后果必须讲清楚：HTTP 状态码是 200</h3>
 * <p>即使 10 篇里有 9 篇失败，本接口返回的仍然是 {@code code=200} +
 * 一个"成功 1 条、失败 9 条"的结果体。
 * <p>原因是：从"这次请求"的视角看它确实被正常处理了 ——
 * 请求格式合法、权限足够、服务端也没有出错。失败是<b>逐条业务规则</b>的判定结果，
 * 而不是"这次调用失败"。这属于"聚合操作"的固有语义，
 * 前端必须读 {@code data.failList} 而不是只看 HTTP 状态码。
 * <p>反过来说，两种情况仍然返回非 200：
 * <ul>
 *   <li>整体性的参数错误（ids 为空、超过 100 条）→ 400 {@code PARAM_ERROR}</li>
 *   <li>未登录 / 无权限 → 401 / 403（拦截器与切面在进 Service 前就拦下了）</li>
 * </ul>
 * 这个区分很重要：**"这批请求本身不合法"与"其中某几条业务上不通过"是两件事。**
 */
@Data
public class BatchSubmitResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实际处理的条数（**去重之后**的数量）。
     *
     * <p>⚠️ 注意是去重后的值，不是请求里 {@code ids.size()}。
     * 前端传 {@code [3,3,3]} 时这里是 1，因为同一篇文章提交两次第二次必然失败
     * （状态已变成待初审），把它算成一条"失败"是误导 ——
     * 用户会以为自己操作错了，而实际上是重复传参。
     * 去重规则见 {@code ArticleServiceImpl#batchSubmit}。
     */
    private Integer total;

    /** 成功条数。 */
    private Integer successCount;

    /**
     * 失败条数。
     *
     * <p>冗余字段（等于 {@code total - successCount}）也要给出：
     * 前端展示"成功 8 条，失败 2 条"时不必自己做减法，
     * 更重要的是避免前端**各写一遍减法**导致两边口径不一致。
     */
    private Integer failCount;

    /**
     * 失败明细。全部成功时是**空列表而不是 null**。
     *
     * <p>⚠️ 这条约定很重要：前端写 {@code data.failList.length} 或
     * {@code v-for} 时不必判 null。返回 null 会让调用方在空集合这一最常见的情况下
     * 拿到 null，属于"最省事的地方留了个坑"。
     */
    private List<FailItem> failList = new ArrayList<>();

    /**
     * 单条失败明细。
     *
     * <p>只给 {@code id} 与 {@code reason} 两个字段，**不给标题**。
     * 理由：要拿标题就得再查一次库（失败的那些常常是状态不对，
     * 我们本来就已经查过一次了，只是没把它带出来）。
     * 权衡后认为不划算 —— 前端已经把当前列表渲染在页面上了，
     * 拿到 id 就足以定位是哪一行。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 失败的文章 ID。可能是 null —— 请求里传了 null 元素时。 */
        private Long id;

        /**
         * 失败原因，**直接取业务异常的 message**，是可展示的中文。
         *
         * <p>例如"文章当前状态为「已发布」，不允许执行「提交初审」"、
         * "只能操作自己创建的文章"。这些 message 在 {@code ErrorCode} 与
         * {@code ArticleStateMachine} 里就是按"给人看"的标准写的，
         * 这里直接透传，不做二次加工 —— 二次加工必然导致同一件事有两套文案。
         */
        private String reason;
    }
}
