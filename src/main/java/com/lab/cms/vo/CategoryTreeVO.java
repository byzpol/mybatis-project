package com.lab.cms.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 分类树节点 VO（<b>继承</b> {@link CategoryVO}）。
 *
 * <h3>⚠️ 为什么是继承而不是"包一层 node 字段"</h3>
 * <p>两种写法的 JSON 形状差很多，选错了前端代码会很难写：
 * <pre>
 * 继承（本类）:              {"id":1, "categoryName":"技术资讯", "children":[...]}
 * 组合:                     {"node":{"id":1, "categoryName":"技术资讯"}, "children":[...]}
 * </pre>
 * <p>前端渲染树时，节点上要读的是 {@code id} / {@code categoryName} / {@code status} ——
 * 继承让它和列表接口的字段路径<b>完全一致</b>（都是 {@code item.categoryName}），
 * 同一个 {@code CategoryNode} 组件既能渲染列表行也能渲染树节点。
 * 组合写法则到处要多一层 {@code .node}，且树的展示组件（如 ElTree）
 * 默认从数据对象上直接取字段，多一层就得写自定义渲染函数。
 *
 * <p>语义上也说得通：<b>树节点"是一种"分类展示</b>，不是"拥有一个分类"。
 *
 * <h3>⚠️ 叶子节点的 {@code children} 是 {@code null}，不是空数组</h3>
 * <p>Jackson 配的是 {@code NON_NULL} 策略，所以叶子节点在 JSON 里
 * <b>根本没有 children 字段</b>。好处是前端一个判断就能区分"叶子"与"有子级"：
 * <pre>
 *   if (node.children) { /* 有子级 *\/ } else { /* 叶子 *\/ }
 * </pre>
 * <p>若返回空数组，判断就得写成 {@code children.length > 0}，
 * 而且 ElTree 之类的组件会把"空 children"当成父节点、渲染出一个点不开的展开箭头。
 *
 * <p>⚠️ 副作用：所有内层 {@code children} 都是 {@code null} 的节点
 * （即所有叶子）在 JSON 里形状相同，反序列化时无法区分
 * "这个节点没有子级" 与 "这个字段没查" —— 本接口两种情况不会同时出现，可接受。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CategoryTreeVO extends CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 子节点；叶子节点为 {@code null}（见类注释）。 */
    private List<CategoryTreeVO> children;
}
