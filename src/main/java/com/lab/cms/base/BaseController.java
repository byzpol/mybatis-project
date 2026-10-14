package com.lab.cms.base;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lab.cms.common.constant.LoginUser;
import com.lab.cms.common.exception.BusinessException;
import com.lab.cms.common.exception.ErrorCode;
import com.lab.cms.common.result.PageResult;
import com.lab.cms.config.CurrentUserArgumentResolver;

/**
 * Controller 基类。
 *
 * <p>**不继承任何框架类**（不 extends 也不 implements 任何 Spring MVC 的类型），
 * 只提供两个 {@code protected} 工具方法给子类用。
 *
 * <h3>为什么刻意不继承任何东西</h3>
 * <p>见过不少项目让 Controller 继承一个 {@code AbstractController} 或
 * {@code BaseController implements Controller}，然后覆写一堆无关的回调。
 * 那样做的代价是：Controller 与框架强耦合，单元测试时很难脱离容器实例化，
 * 且父类一旦有状态（成员变量）就会在多线程下出事。
 *
 * <p>本类的定位是"**静态工具方法的容器**"，本身无状态、无依赖注入、可自由 new。
 * 继承它纯粹是为了少写几行 import 和类名前缀。
 *
 * <h3>⚠️ 本类最重要的一条：Controller 只做四件事</h3>
 * <p>收参 → 校验 → 调 Service → 封装返回。**禁止在 Controller 里写业务逻辑。**
 *
 * <p>判断标准很简单：Controller 的方法体里**不应该出现 if 判断业务规则**。
 * 例外只有两类：
 * <ul>
 *   <li>参数合法性判断（如"id 不能为空"）—— 更好的做法是用 {@code @Valid} + 注解校验，
 *       让 {@code GlobalExceptionHandler} 统一处理</li>
 *   <li>把 Service 的返回值塞进 {@code Result.ok(...)}</li>
 * </ul>
 *
 * <p>为什么强调这一点：把所有逻辑堆在 Controller 是学生项目最常见的结构问题。
 * 后果是 —— 同一个业务规则（如"只有文章作者才能改"）在多个接口里各写一遍，
 * 改一处忘一处，越权漏洞就是这么来的。逻辑集中在 Service 才可能改一次全生效。
 *
 * <h3>关于事务</h3>
 * <p>⚠️ **不要在 Controller 上加 {@code @Transactional}。**
 * 事务应该由 Service 层开启：Controller 只管一次请求的编排，
 * 而"哪些操作必须原子"是业务知识，属于 Service。
 * 在 Controller 加事务还会导致：事务范围过大，
 * 把参数校验、甚至序列化都包进去了，无谓地拖长持锁时间。
 */
public abstract class BaseController {

    /**
     * 取当前登录用户 ID。
     *
     * <p>实现上复用 {@link CurrentUserArgumentResolver#getCurrentUser()}，
     * 它内部走 {@code RequestContextHolder} → 当前请求的 Session。
     * 所以本方法**只能在处理 HTTP 请求的线程里调用**。
     *
     * <p>⚠️ 取不到时**返回 null 而不是抛异常**。原因：有些接口允许匿名访问
     * （如文章列表、健康检查），它们也需要读一下当前用户来判断
     * "要不要显示草稿"。若这里直接抛 401，那些接口就没法公用了。
     * 需要"必须登录"语义的接口，应当用 {@code @RequirePermission} 注解
     * 或显式调 {@link #requireCurrentUserId()}。
     *
     * @return 登录用户 ID；未登录或非 HTTP 上下文时返回 null
     */
    protected Long currentUserId() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getUserId();
    }

    /**
     * 取当前登录用户 ID，未登录直接抛 401。
     *
     * <p>与 {@link #currentUserId()} 的区别是明确表达了"这个接口必须有登录用户"
     * 的意图。推荐在大多数需要登录的接口里用它 ——
     * 免得下游某个 {@code userId} 为 null 时在 Service 深处才炸，
     * 报错信息离现场很远，排查困难。
     *
     * @throws BusinessException 错误码 {@link ErrorCode#UNAUTHORIZED}（401）
     */
    protected Long requireCurrentUserId() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    /**
     * 取当前登录用户对象（含用户名、角色、权限集）。
     *
     * <p>需要判断"是不是管理员"这类场景时用它。
     * 例如：管理员能看所有文章，普通作者只能看自己的。
     *
     * @return 登录用户；未登录时返回 null
     */
    protected LoginUser getLoginUser() {
        return CurrentUserArgumentResolver.getCurrentUser();
    }

    /**
     * MP 的 {@link IPage} → 项目的 {@link PageResult}。
     *
     * <p>存在的意义：让 Service 层可以直接返回 MP 的分页对象（写起来自然），
     * 转换这一步在 Controller 统一做掉，Service 不需要知道外部契约长什么样。
     *
     * <p>⚠️ 注意这是**浅转换**：{@code records} 里的元素类型不变。
     * 也就是说本方法只适合"Entity 直接出参"的简单场景。
     * 本项目要求出参用 VO（避免把 {@code deleted} 这类内部字段暴出去），
     * 所以更常见的写法是：
     * <pre>
     * PageResult&lt;ArticleVO&gt; voPage = PageResult.of(
     *         articleService.pageArticle(query));   // Service 内部已做 Entity → VO
     * return Result.ok(voPage);
     * </pre>
     * 本方法保留给确实不需要 VO 的场景（如日志查询，字段本身就是给运营看的）。
     *
     * @param page MP 分页对象；为 null 时返回空分页而非 NPE
     * @param <T>  记录类型
     */
    protected <T> PageResult<T> toPageResult(IPage<T> page) {
        return PageResult.of(page);
    }
}
