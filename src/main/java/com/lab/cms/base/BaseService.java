package com.lab.cms.base;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 业务 Service 接口基类。
 *
 * <p>本接口**故意不定义任何方法**，只做两件事：
 * <ol>
 *   <li>把 MP 的 {@link IService} 引入到项目自己的类型体系里</li>
 *   <li>给所有业务 Service 一个统一的"父接口"，将来要加公共方法时只改这里</li>
 * </ol>
 *
 * <h3>⚠️ 业务 Service 一律继承本接口，不要直接 implements IService</h3>
 * <p>两条理由：
 * <ul>
 *   <li><b>换实现的成本。</b>现在底层是 MP；若某天要换成 MyBatis 原生或 JPA，
 *       只需改 {@link BaseServiceImpl} 并重新设计本接口的方法，
 *       业务代码里 {@code XxxService extends BaseService<Xxx>} 这行不用动。
 *       直接 extends IService 的话，每个业务接口都要改。</li>
 *   <li><b>公共方法的落点。</b>比如"带租户过滤的查询""统一的分页查询封装"，
 *       有本接口作为落点，加一次就全项目生效；没有的话只能在每个 Service 里各写一份。</li>
 * </ul>
 *
 * <h3>为什么现在不加额外方法（如 {@code page(PageQuery)}）</h3>
 * <p>考虑过加一个泛型分页方法，但它的返回值类型依赖具体 VO/Entity，
 * 而 Java 的泛型擦除让"返回 PageResult&lt;Vo&gt;"很难在基类里表达干净
 * （需要在方法上重新声明泛型参数，调用处经常需要显式指定，比直接写反而啰嗦）。
 * 与其加一个用起来别扭的方法，不如先空着 —— 各域在自己接口里写
 * {@code PageResult<ArticleVO> page(ArticleQueryDTO query)}，
 * 语义清晰且 IDE 的跳转也准确。
 *
 * <p>等 S17 优化阶段如果发现确实有可复用的公共查询逻辑，再往这里加。
 *
 * @param <T> 实体类型（必须继承 {@link BaseEntity}）
 */
public interface BaseService<T extends BaseEntity> extends IService<T> {
}
