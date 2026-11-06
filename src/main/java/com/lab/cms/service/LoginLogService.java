package com.lab.cms.service;

/**
 * 登录日志写入服务。
 *
 * <h3>为什么要单独抽一个服务，而不是在 {@code AuthServiceImpl} 里直接 insert</h3>
 * <p>因为<b>登录日志必须与登录业务分属两个事务</b>。
 *
 * <p>S20 真机回归实测发现：{@code AuthServiceImpl#login} 上有
 * {@code @Transactional(rollbackFor = Exception.class)}，而失败路径是
 * "先写登录日志、再 {@code throw new BusinessException(PASSWORD_ERROR)}"。
 * 异常一抛，Spring 就把整个事务回滚了 —— <b>刚插进去的那条失败日志也一起没了</b>。
 * 结果：库里只剩成功记录，运维想查"谁在爆破密码"什么都查不到。
 * 实测判据：用错误密码登录一次，{@code sys_login_log} 总行数 121 → 121，
 * 而 {@code init.sql} 里那条 {@code login_status=2} 的失败样本是种子数据，不是运行时写的。
 *
 * <p>修法：把写入动作放到<b>独立 Bean</b>上并标 {@code REQUIRES_NEW}，
 * 让它在外层业务事务之外另开一个事务并立即提交。
 * <p>⚠️ 必须经<b>外部 Bean</b>调用：{@code @Transactional} 靠代理生效，
 * 同类自调用（{@code this.xxx()}）绕不过代理，写多少遍注解都不生效 ——
 * 这是本项目已经踩过的坑，见 {@code ArticleServiceImpl#batchSubmit} 的注释。
 *
 * <h3>为什么审计日志天生该独立于业务事务</h3>
 * <p>审计记录回答的是"当时到底发生了什么"。业务失败（密码错、账号停用）
 * 恰恰是审计最关心的一类事件；如果它随业务事务一起回滚，
 * 审计表就只剩"顺利的事情"，失去了它存在的意义。
 *
 * <h3>{@code loginStatus} 的取值口径</h3>
 * <p>以<b>库表</b>为准：{@code 1=成功 2=失败}（见 {@code sql/init.sql} 中
 * {@code sys_login_log.login_status} 的列注释与种子数据）。
 * <p>⚠️ 历史上这里存在三方不一致：库表写 {@code 1/2}、种子数据用 {@code 2}，
 * 而代码传的是 {@code 0}、实体注释也写 {@code 0 失败}。
 * 同一列两套口径，按注释查"失败记录"会一条都查不到。本次统一到 {@code 1/2}。
 */
public interface LoginLogService {

    /** 登录结果：成功 */
    int STATUS_SUCCESS = 1;

    /** 登录结果：失败 */
    int STATUS_FAIL = 2;

    /**
     * 记录一条登录日志（独立事务，绝不影响调用方的登录流程）。
     *
     * <p>⚠️ 本方法<b>不接收密码参数</b> —— 从方法签名上就杜绝了密码流入日志表的可能。
     * <p>⚠️ 内部自行吞掉写库异常并只打 WARN：日志写不进去不该让用户看到 500。
     *
     * @param username    登录用户名（失败时该用户可能并不存在）
     * @param userId      用户 ID，登录成功 / 密码错误时才有值，用户不存在时为 null
     * @param ip          客户端 IP
     * @param userAgent   浏览器 UA
     * @param loginStatus {@link #STATUS_SUCCESS} 或 {@link #STATUS_FAIL}
     * @param message     结果说明，失败原因写在这里（如"用户名或密码错误"）
     */
    void record(String username, Long userId, String ip, String userAgent,
                int loginStatus, String message);
}
