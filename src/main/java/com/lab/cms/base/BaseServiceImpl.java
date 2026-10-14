package com.lab.cms.base;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * 业务 Service 实现基类。
 *
 * <p>业务实现类一律继承本类：
 * <pre>
 * {@code @Service}
 * public class ArticleServiceImpl
 *         extends BaseServiceImpl&lt;ArticleMapper, Article&gt;
 *         implements ArticleService {
 * }
 * </pre>
 *
 * <h3>为什么要有这一层（明明只是 extends ServiceImpl）</h3>
 * <p>看着像纯粹的"空壳转发"，但它解决三个实际问题：
 *
 * <ol>
 *   <li><b>锁死 Mapper 与 Entity 的配对关系。</b>
 *       {@code BaseServiceImpl<M extends BaseMapper<T>, T extends BaseEntity>}
 *       这个泛型约束保证 {@code ArticleMapper} 只能配 {@code Article}，
 *       写错配对（比如 {@code BaseServiceImpl<ArticleMapper, Tag>}）编译期就报错。
 *       直接 extends {@code ServiceImpl} 时，泛型参数写成两个下划线，
 *       出错要等到运行期才能发现。</li>
 *
 *   <li><b>公共能力有落点。</b>后续要加"当前登录用户校验归属权"
 *       （水平越权防护，S12 要用）之类的公共逻辑，加在这里一次，全项目生效。
 *       这也是本项目"主干 + 入户线"架构的体现 ——
 *       主干只加不改，入户线只管自己那一段。</li>
 *
 *   <li><b>把 {@code BaseEntity} 约束传下去。</b>继承本类后，
 *       子类拿到的 {@code T} 一定带 {@code id / createBy / deleted} 等字段，
 *       所以基类里写 {@code entity.getId()} 是编译期安全的。</li>
 * </ol>
 *
 * <h3>使用注意</h3>
 * <ul>
 *   <li>业务实现类只需 {@code extends BaseServiceImpl<XxxMapper, Xxx>}，
 *       不要自己再定义 {@code @Autowired XxxMapper mapper} ——
 *       父类的 {@code baseMapper} 已经是它，重复注入容易造成
 *       "两个字段指向同一个 Bean 但其中之一被代理"的困惑。</li>
 *   <li>⚠️ MP 的 {@code updateById} 与乐观锁 {@code @Version} 配合时，
 *       版本不匹配**返回 0 而不抛异常**。用乐观锁的地方必须显式判返回值，
 *       这一点在 {@code ArticleServiceImpl} 里有示例。</li>
 *   <li>本类不覆盖 MP 的任何方法。若某业务需要定制，在**子类**覆盖即可，
 *       不要改基类 —— 基类一改影响全项目。</li>
 * </ul>
 *
 * @param <M> Mapper 类型（必须继承 {@link BaseMapper}）
 * @param <T> 实体类型（必须继承 {@link BaseEntity}）
 */
public abstract class BaseServiceImpl<M extends BaseMapper<T>, T extends BaseEntity>
        extends ServiceImpl<M, T> implements BaseService<T> {
}
