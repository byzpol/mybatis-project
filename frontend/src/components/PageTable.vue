<!--
  通用列表页组件（F04）
  ===========================================================================
  「查询条件 + 表格 + 分页」一体化封装，供四个业务域的所有列表页复用。

  ⚠️ 本组件**不含任何业务字段**：不出现 article / cms_article / status 这类词，
     所有列、所有查询条件、所有按钮都由使用方通过 props 和插槽传进来。
     判据很简单：如果某段逻辑只有文章列表用得上，它就不该在这里。

  ---------------------------------------------------------------------------
  用法（文章列表页的例子，F06 的 ArticleList.vue 就是这么用的）
  ---------------------------------------------------------------------------
    <PageTable
      ref="tableRef"
      :columns="columns"
      :request="request"
      :query-model="query"
      show-index
      selectable
      @selection-change="onSelectionChange"
    >
      <template #filter>
        <el-input v-model="query.title" placeholder="标题关键字" clearable style="width: 200px" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px">
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </template>

      <template #toolbar>
        <el-button type="primary" @click="goCreate">写文章</el-button>
        <el-button :disabled="!selected.length" @click="onBatchSubmit">批量提交审核</el-button>
      </template>

      <template #col-status="{ row }">
        <StatusTag :status="row.status" />
      </template>
    </PageTable>

  ---------------------------------------------------------------------------
  关于排序（容易踩的一个坑，写在这里省得每个页面各踩一次）
  ---------------------------------------------------------------------------
    columns 里写 sortable: true 表示**服务端排序**，组件会把 sortField 传给后端。
    ⚠️ 后端 PageQuery.checkSortFieldIn 比对的是**数据库列名（下划线）**，
       而列的 prop 通常是 Java 属性名（驼峰）。所以排序字段要用列上的
       sortField 显式指定数据库列名，例如：
           { prop: 'createTime', label: '创建时间', sortable: true, sortField: 'create_time' }
       不写 sortField 时会退回用 prop —— 只在这两者恰好同名时才正确（如 title / view_count 之外的基本没有）。
       后端只接受白名单里的列，传别的会返回 400，这是刻意的（防 SQL 注入）。
-->
<template>
  <div class="page-table">
    <!--
      查询区：由使用方通过 #filter 插槽放入真正的输入控件。
      查询/重置按钮由本组件提供 —— 保证所有列表页"回车查询、按钮位置、重置语义"一致。
    -->
    <div v-if="$slots.filter" class="filter-bar">
      <slot name="filter" />
      <div class="filter-bar__actions">
        <el-button type="primary" :loading="loading" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>
    </div>

    <!-- 工具栏：新增/批量操作按钮，由使用方提供 -->
    <div v-if="$slots.toolbar" class="table-toolbar">
      <div class="table-toolbar__left">
        <slot name="toolbar" />
      </div>
      <div class="table-toolbar__right">
        <slot name="toolbar-right" />
      </div>
    </div>

    <!--
      ⚠️ request 缺失时不白屏：给一个能看懂的提示。
         「组件挂了但页面一片空白、控制台只有一行 TypeError」是最难排查的一类问题。
    -->
    <el-alert
      v-if="requestMissing"
      type="error"
      :closable="false"
      show-icon
      title="PageTable 使用错误：未传入 request"
      description="request 是必填项，形如 (params) => Promise<PageResult>。请检查页面里是否漏写了 :request。"
    />

    <el-table
      v-else
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      :row-key="rowKey"
      border
      stripe
      :default-sort="defaultSort"
      @sort-change="onSortChange"
      @selection-change="onSelectionChange"
    >
      <!-- 多选列 -->
      <el-table-column v-if="selectable" type="selection" width="46" align="center" reserve-selection />

      <!-- 序号列：按当前页偏移计算，翻页后序号是连续的 -->
      <el-table-column v-if="showIndex" type="index" label="序号" width="64" align="center" :index="indexMethod" />

      <el-table-column
        v-for="col in columns"
        :key="col.prop || col.label"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
        :fixed="col.fixed || false"
        :sortable="col.sortable ? 'custom' : false"
        :show-overflow-tooltip="col.overflowTip !== false"
      >
        <template #default="scope">
          <!--
            自定义单元格：#col-{prop} 或列上显式指定的 slotName。
            ⚠️ 这里用 <slot :name="..."> 动态传递插槽名 ——
               它让"哪些列需要自定义"完全由使用方的模板决定，组件不需要知道列的语义。
          -->
          <slot
            v-if="$slots[cellSlotName(col)]"
            :name="cellSlotName(col)"
            :row="scope.row"
            :column="col"
            :index="scope.$index"
          />
          <span v-else>{{ formatCell(col, scope.row) }}</span>
        </template>
      </el-table-column>

      <!-- 空态：不用 el-table 自带的 empty-text，因为要放插槽（使用方可放"去创建"按钮） -->
      <template #empty>
        <slot name="empty">
          <el-empty description="暂无数据" :image-size="100" />
        </slot>
      </template>
    </el-table>

    <!--
      分页：始终渲染（即使 0 条），让"总数 0"这件事对用户可见。
      sizes 固定 [10,20,50,100] —— 与后端 PageQuery.MAX_SIZE = 100 对齐，
      不给用户选到会被后端截断的值（选了 200 实际只返回 100 会让人困惑）。
    -->
    <div v-if="!requestMissing" class="page-table__pager">
      <el-pagination
        v-model:current-page="current"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'

defineOptions({ name: 'PageTable' })

const props = defineProps({
  /**
   * 列定义。每项：
   *   prop      字段名（也是默认插槽名 #col-{prop} 的来源）
   *   label     列标题
   *   width     固定宽度（数字）；与 minWidth 二选一
   *   minWidth  最小宽度（数字）—— ⚠️ 表格要不横向溢出就该用它而不是 width
   *   align     'left' | 'center' | 'right'
   *   sortable  是否服务端排序
   *   sortField 排序时传给后端的**数据库列名**（如 'create_time'）；不写则用 prop
   *   slotName  自定义插槽名；不写则用 `col-{prop}`
   *   formatter (row) => string，默认渲染逻辑；有插槽时以插槽为准
   *   fixed     'left' | 'right' | true —— 列固定（F06 加的，操作列用它）。
   *             ⚠️ 只有"表格比容器宽"时才看得出效果；列都用 minWidth 且总宽不超标时，
   *                固定列与普通列长得一模一样（这是正常的，不是没生效）
   *   overflowTip 设为 false 可关掉该列的"内容超长显示 tooltip"（F06 加的）。
   *             操作列必须关掉：一排 link 按钮被当成长文本截断时会很难看
   */
  columns: {
    type: Array,
    default: () => []
  },
  /**
   * 分页请求函数，(params) => Promise<PageResult>。
   * PageResult 形如 { records, total, current, size, pages }（后端契约 §2.2）。
   */
  request: {
    type: Function,
    default: null
  },
  /** 查询条件对象。组件只负责"重置时清空"和"查询时合并进参数"，不关心里面有什么字段 */
  queryModel: {
    type: Object,
    default: () => ({})
  },
  /** 是否显示序号列 */
  showIndex: {
    type: Boolean,
    default: false
  },
  /** 行主键字段名 */
  rowKey: {
    type: String,
    default: 'id'
  },
  /** 是否显示多选列 */
  selectable: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['selection-change'])

const tableRef = ref(null)
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)

/**
 * 排序状态。空表示不传 sortField（后端用自己的默认排序）。
 * 用 ref 而不是塞进 queryModel：排序属于组件的内部状态，
 * 混进业务查询对象会让"重置"变得难以界定（重置要不要清排序？）。
 */
const sortField = ref('')
const sortOrder = ref('')

const requestMissing = computed(() => typeof props.request !== 'function')
const defaultSort = computed(() => {
  if (!sortField.value) return undefined
  return { prop: sortField.value, order: sortOrder.value === 'asc' ? 'ascending' : 'descending' }
})

onMounted(() => {
  if (requestMissing.value) {
    // 开发期立刻报出来，别等到用户看到一片空白
    console.error('[PageTable] request 是必填的，(params) => Promise<PageResult>')
    return
  }
  fetchData()
})

function cellSlotName(col) {
  return col.slotName || `col-${col.prop}`
}

/**
 * 默认单元格渲染。
 * 传了 formatter 就用它；否则把 null / undefined 渲染成 '-' 而不是空白 ——
 * 表格里一片空白会让人分不清"没数据"和"数据是空的"。
 */
function formatCell(col, row) {
  const raw = row?.[col.prop]
  if (typeof col.formatter === 'function') {
    return col.formatter(row)
  }
  return raw === null || raw === undefined || raw === '' ? '-' : raw
}

/** 序号连续：第 2 页第 1 行的序号是 11（而不是从 1 重新开始） */
function indexMethod(index) {
  return (current.value - 1) * size.value + index + 1
}

/**
 * 组装请求参数。
 *
 * ⚠️ 三个细节：
 *  ① 空字符串 / null / undefined 的条件**不传** —— 后端把 null 当"该条件不参与过滤"，
 *     但传空串会给 MySQL 拼出 `title LIKE '%%'`，语义就变成了"必须有标题"，与预期不符。
 *  ② 数组要传引用副本：axios 序列化后不再引用原对象，但保守起见仍然拷贝。
 *  ③ sortField 只在有值时传，避免拼出 `ORDER BY null`。
 */
function buildParams() {
  const params = { page: current.value, size: size.value }
  const q = props.queryModel || {}
  Object.keys(q).forEach((k) => {
    const v = q[k]
    if (v === null || v === undefined || v === '') return
    if (Array.isArray(v) && v.length === 0) return
    params[k] = v
  })
  if (sortField.value) {
    params.sortField = sortField.value
    params.sortOrder = sortOrder.value || 'desc'
  }
  return params
}

/** 发起请求。既用于挂载时，也用于查询/重置/翻页/改每页条数 */
async function fetchData() {
  if (requestMissing.value) return
  loading.value = true
  try {
    const res = await props.request(buildParams())
    rows.value = res?.records ?? []
    total.value = Number(res?.total ?? 0)
    // 以服务端返回的 current/size 为准：万一分页被后端归一化（size 超 100 被截到 100），
    // 分页器显示的才是真实生效的值
    if (res?.current) current.value = Number(res.current)
    if (res?.size) size.value = Number(res.size)
  } catch (e) {
    /*
     * 刻意不在这里弹提示：request.js 的响应拦截器已经统一弹过一次了，
     * 这里再弹就是两条一模一样的报错。
     * 之所以还 catch，是为了保证 loading 一定收回（否则一次失败就永久转圈）。
     */
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询：回到第 1 页。不重置页码的话，用户在第 5 页改条件会看到"查不到数据"（其实只是没第 5 页） */
function onSearch() {
  current.value = 1
  // 手动 setCurrentPage 不会触发 change 事件（值本来就是 1 的时候），所以直接调
  fetchData()
}

/** 重置：清空查询条件 + 清排序 + 回第 1 页 */
function onReset() {
  const q = props.queryModel || {}
  Object.keys(q).forEach((k) => {
    delete q[k]
  })
  sortField.value = ''
  sortOrder.value = ''
  current.value = 1
  size.value = 10
  fetchData()
}

/**
 * 翻页。⚠️ v-model 已经把 current 改好了，这里只需要重新请求。
 * 不能再赋值 current —— 那会造成"点一次翻两页"。
 */
function onPageChange() {
  fetchData()
}

/** 改每页条数：同样要回第 1 页（否则 第 5 页 + 每页 100 条 会请求一个不存在的页） */
function onSizeChange() {
  current.value = 1
  fetchData()
}

/**
 * 服务端排序。
 * el-table 的 order 是 'ascending' / 'descending' / null（点了第三次会取消）。
 * prop 给的是列的 prop，需要回到 columns 里找 sortField（数据库列名）。
 */
function onSortChange({ prop, order }) {
  if (!order) {
    sortField.value = ''
    sortOrder.value = ''
  } else {
    const col = props.columns.find((c) => c.prop === prop)
    sortField.value = col?.sortField || prop
    sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  }
  current.value = 1
  fetchData()
}

function onSelectionChange(selection) {
  emit('selection-change', selection)
}

/**
 * 对外暴露的方法。
 * refresh 保持当前页 / reload 回第 1 页 —— 分开是因为使用场景不同：
 *  · 编辑完某一行回来 → refresh()（用户希望还停在原来那页）
 *  · 新增了一条 → reload()（新数据在最后，回第 1 页更合理）
 */
function refresh() {
  return fetchData()
}

function reload() {
  current.value = 1
  return fetchData()
}

function getSelection() {
  return tableRef.value?.getSelectionRows?.() ?? []
}

function clearSelection() {
  tableRef.value?.clearSelection?.()
}

defineExpose({ refresh, reload, getSelection, clearSelection })
</script>

<style scoped>
/* 分页器：右对齐，与上方表格右边界对齐 */
.page-table__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/*
  表格宽度：撑满容器。
  el-table 内部自带横向滚动容器，所以"表格比容器宽"只会让**表格内部**出现滚动条，
  不会把整个页面撑出横向滚动条。前提是这里限制成 100%，不要让表格按内容宽度外溢。

  ⚠️ 真正的省事手段还是列上用 minWidth 而不是 width：
     给定 minWidth 后 el-table 会把剩余宽度分配给各列，
     容器变窄时列等比例收窄，大多数情况下压根到不了"需要滚动"那一步。
*/
.page-table :deep(.el-table) {
  width: 100%;
}
</style>
