package com.lab.cms.common.util;

/**
 * SQL {@code LIKE} 通配符转义工具。
 *
 * <h3>本类为什么存在（它是主干的一个"新增"，不是"修改"）</h3>
 * <p>主干（{@code config / common / base / annotation / aspect / interceptor / util}）
 * 自 {@code trunk-v1.0} 起冻结，规则是<b>"只许加方法，不许改签名"</b>。
 * 本类是<b>新增文件</b>：没有改动任何既有类的签名，也没有改动任何既有方法的行为，
 * 因此不违反冻结约定。
 *
 * <h3>⚠️ 为什么必须转义：{@code like} 会把用户的输入当模式</h3>
 * <p>用户在搜索框里输入 {@code %}，若直接拼成 {@code LIKE '%' + 输入 + '%'}，
 * 查询就从"搜索含百分号的标题"悄悄变成了"匹配所有记录" ——
 * 输入 {@code _} 同理（匹配任意单字符）。
 *
 * <p>这不是 SQL 注入（参数仍然走 PreparedStatement 绑定，没有注入面），
 * 而是<b>语义被用户输入改变</b>。后果是：搜索框输入一个 {@code %} 就会全表扫描，
 * 既查不到用户想要的东西，又给数据库压力。
 *
 * <h3>⚠️ 转义顺序不能颠倒：先转义反斜杠本身</h3>
 * <p>必须<b>先</b>把 {@code \} → {@code \\}，<b>再</b>处理 {@code %} 与 {@code _}。
 * 反过来的话：{@code "%"} → {@code "\\%"} → 回过头再转义反斜杠 → {@code "\\\\%"}，
 * 结果是"匹配一个反斜杠 + 任意串"，与用户意图完全不符。
 *
 * <h3>⚠️ 依赖 MySQL 的默认 {@code ESCAPE '\\'}</h3>
 * <p>本实现用的是反斜杠转义，它依赖 MySQL 默认的转义字符。
 * 已核实本机 {@code sql_mode} <b>不含</b> {@code NO_BACKSLASH_ESCAPES}。
 * 若哪天打开这个 mode，转义会<b>静默失效</b>（不报错，只是行为变回"通配"）——
 * 所以这条依赖必须写在注释里，改数据库配置时要连带检查。
 *
 * <h3>⚠️ 与 {@code ArticleServiceImpl#likePattern} 的关系</h3>
 * <p>文章域在那个私有方法里有一份<b>等价实现</b>（S17 写的，早于本类）。
 * 本类的逻辑与它逐行相同，只是抽成了共享工具，供本次新增的三个域
 * （分类 / 标签 / 日志）复用，避免同一段转义逻辑复制三份。
 * <p><b>S24 未改动 {@code ArticleServiceImpl}</b>：那是一个已被回归测试
 * 覆盖（496 条断言）的热点类，为一次"纯搬移"去动它、再重跑全套回归，
 * 收益与风险不成比例。把"两处逻辑相同"这件事写清楚即可 ——
 * 真正危险的是<b>两份实现悄悄分了叉而没人知道</b>，不是重复本身。
 */
public final class SqlLikeUtil {

    /** 工具类，禁止实例化。 */
    private SqlLikeUtil() {
    }

    /**
     * 把用户输入的关键字转成"包含匹配"的 {@code LIKE} 模式串。
     *
     * <p>返回的形式是 {@code %关键字%}，且关键字里的
     * {@code \} / {@code %} / {@code _} 都已被转义。
     *
     * <h3>⚠️ 返回值不能直接拼进 SQL 文本</h3>
     * <p>它必须作为<b>参数</b>绑定（{@code #{...}} 或在 MP Wrapper 里用
     * {@code apply("title LIKE {0}", pattern)}）。用 {@code ${}} 拼字符串
     * 会把转义好的内容又暴露成注入点 —— 转义解决的是"通配符语义"，
     * 不解决"注入"，两者是两件事，不能互相替代。
     *
     * @param keyword 用户输入的关键字；为 {@code null} 时返回 {@code null}
     *                （调用方应当先判空再决定要不要加这个条件，而不是拿
     *                {@code null} 当"匹配所有"用）
     */
    public static String containsPattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String escaped = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
