<!--
  状态标签组件（F04）
  ===========================================================================
  把"状态码 → 文案 + 标签颜色"这件事收口到一个组件里。

  ⚠️ 为什么必须收口：如果每个页面各写一套 v-if 链，
     后端加一个状态就要改 N 个页面，且大概率漏掉一两个 ——
     漏掉的那个表现为"状态列空白"或"显示了码值 7"，很难在自测时发现。

  ---------------------------------------------------------------------------
  映射表的来源（有个不一致，说清楚免得后人困惑）
  ---------------------------------------------------------------------------
  article 映射与后端 `com.lab.cms.common.enums.ArticleStatus` 的
  `description` + `color` 两个字段**逐条一致**。后端那个枚举的类注释明确写了
  "把展示语义放在后端，前端只管渲染"，`CmsArticleVO` 也同时给了
  statusDesc / statusColor 两个字段。

  既然如此，为什么前端还要存一份？
    · 因为 StatusTag 要能**脱离接口单独使用**（比如审核页里只知道状态码，
      不想为了一个标签再去取详情）；
    · 而且列表页一次性拿 20 行，用 StatusTag 直接按码渲染比每行去读 VO 字段干净。
  代价是"两边要保持同步"。所以这里写明：**改映射时以后端枚举为准，两边一起改**。

  ⚠️ 与《成员A-开发提示词手册》F04 的 1 处差异：
     手册写 `3 已通过待发布 → primary（蓝）`，但后端枚举与 `docs/接口契约.md`（第 1118 行）
     都写的是 `APPROVED(3, "审核通过", "success")`。
     按项目既定口径（**库表 / 契约 / 代码 > 手册**）取 success。
     副作用：状态 3（审核通过）与 5（已发布）标签同为绿色，靠文案区分。
     如果确实想让两者在视觉上分开，正确做法是改后端枚举的 color（一处生效），
     而不是只在前端偷偷改一份 —— 那会变成两份真相。
-->
<template>
  <el-tag :type="tagType" effect="light" round size="small" class="status-tag">
    <el-icon v-if="showIcon" class="status-tag__icon"><Clock /></el-icon>
    {{ label }}
  </el-tag>
</template>

<script>
/**
 * ⚠️ 这个 <script> 块与下面的 <script setup> 并存是刻意的（Vue 支持双 script）。
 * 它的唯一职责是提供**静态方法** `getStatusOptions()`，
 * 让使用方可以 `StatusTag.getStatusOptions()`（手册 F04 要求的调用方式，
 * 查询表单的状态下拉直接用），也可以按需具名导入。
 * 组件本身的逻辑仍在 <script setup> 里，不混在一起。
 */

/**
 * 状态映射表。
 * key 是映射表名（对应 props.type），value 是 `状态码 → { label, type, icon }`。
 *
 * · type：Element Plus `<el-tag type="...">` 的取值
 * · icon：仅"定时发布中"用时钟图标 —— 它是唯一"等时间到"的状态，
 *         加个图标能让运营一眼看出"这篇在等时间，不是没处理"
 */
export const STATUS_MAPS = {
  /** 文章状态（0~9），对齐后端 ArticleStatus 的 description + color */
  article: {
    0: { label: '草稿', type: 'info' },
    1: { label: '待编辑初审', type: 'warning' },
    2: { label: '待主编终审', type: 'warning' },
    3: { label: '审核通过', type: 'success' },
    4: { label: '已驳回', type: 'danger' },
    5: { label: '已发布', type: 'success' },
    6: { label: '定时发布中', type: 'primary', icon: 'Clock' },
    7: { label: '已撤回', type: 'info' },
    8: { label: '已下架', type: 'info' },
    9: { label: '已归档', type: 'info' }
  },

  /**
   * 分类审批状态，对齐 sql/init.sql 第 186 行的列注释
   * `审核状态：0=待审批 1=已生效 2=已驳回`
   * （注意与文章状态不是一回事，所以用独立的映射表，不共用码值）
   */
  category: {
    0: { label: '待审批', type: 'warning' },
    1: { label: '已生效', type: 'success' },
    2: { label: '已驳回', type: 'danger' }
  },

  /** 审核结论，对齐后端 AuditResultEnum（1 通过 / 2 驳回） */
  approval: {
    1: { label: '通过', type: 'success' },
    2: { label: '驳回', type: 'danger' }
  }
}

/** 未知状态码的统一文案。刻意不用空白 —— 空白会让人以为是没查到数据 */
const UNKNOWN = { label: '未知状态', type: 'info' }

/**
 * 取某个状态码的展示定义。
 *
 * @param {number|string} status  状态码
 * @param {string} mapName        映射表名，默认 'article'
 * @returns {{label: string, type: string, icon?: string}}
 */
export function getStatusMeta(status, mapName = 'article') {
  const map = STATUS_MAPS[mapName] || STATUS_MAPS.article
  // 用 String() 归一：后端 JSON 里是数字，但 URL query / 手写测试里可能是 "3"，
  // 对象取值时数字 3 与字符串 '3' 不相等 —— 这里统一成字符串再查，少一类诡异问题
  return map[String(status)] || UNKNOWN
}

/**
 * 状态下拉选项，供查询表单直接使用：
 *   <el-option v-for="o in StatusTag.getStatusOptions()" :key="o.value" :label="o.label" :value="o.value" />
 *
 * @param {string} mapName 映射表名，默认 'article'
 * @returns {Array<{label: string, value: number}>} 按状态码升序
 */
export function getStatusOptions(mapName = 'article') {
  const map = STATUS_MAPS[mapName] || STATUS_MAPS.article
  return Object.keys(map)
    .map((k) => Number(k))
    .sort((a, b) => a - b)
    .map((code) => ({ label: map[String(code)].label, value: code }))
}

export default {
  name: 'StatusTag',
  // 挂成组件选项，满足 `StatusTag.getStatusOptions()` 的调用方式
  getStatusOptions,
  getStatusMeta
}
</script>

<script setup>
import { computed } from 'vue'
import { Clock } from '@element-plus/icons-vue'
// ⚠️ 不要写 `import { getStatusMeta } from './StatusTag.vue'`（自己 import 自己）。
// <script> 与 <script setup> 编译后同处一个模块作用域，
// 上面 <script> 里 export 的函数在这里**直接可见**，再 import 一次会构成自引用循环模块。
// 这条在 F04 构建时验证过：直接引用可用，自引用 import 属于多余且危险的写法。

const props = defineProps({
  /** 状态码 */
  status: {
    type: [Number, String],
    default: null
  },
  /** 用哪张映射表：'article'（默认）| 'category' | 'approval' */
  type: {
    type: String,
    default: 'article'
  },
  /** 是否显示图标（目前只有「定时发布中」配了图标） */
  showIcon: {
    type: Boolean,
    default: true
  }
})

const meta = computed(() => getStatusMeta(props.status, props.type))
const label = computed(() => meta.value.label)
const tagType = computed(() => meta.value.type)
/** 只有映射里声明了 icon 的状态才显示图标 */
const showIcon = computed(() => props.showIcon && meta.value.icon === 'Clock')
</script>

<style scoped>
.status-tag {
  /* 12px 比 EP 默认的 size 更小一档：状态列通常很窄，字号大了会把列撑开 */
  font-size: 12px;
  line-height: 18px;
  /*
   * ⚠️ nowrap 必须加（F06 实测补的）：
   *    「待编辑初审」「定时发布中」都是 5 个字，在 110px 宽的状态列里
   *    默认会被折成两行 —— 于是表格里出现"有的行高 1 行、有的行高 2 行"，
   *    整张表看起来很毛糙，而且折行后的两行文字贴着标签边框，像样式坏了。
   *    状态标签的文案长度是后端定的，前端能做的就是"不许它换行"，
   *    列宽给够（各页的状态列都用 minWidth ≥ 120）。
   */
  white-space: nowrap;
}

.status-tag__icon {
  margin-right: 2px;
  vertical-align: -2px;
}
</style>
