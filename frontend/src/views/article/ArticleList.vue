<!--
  文章列表（F06 · 成员A 的入户线示范页）
  ===========================================================================
  这是 4 个前端主干组件（PageTable / StatusTag / AuditTimeline / ConfirmAction）
  的第一个**真实调用方**。在此之前它们没有任何 import 方，
  `vite build` 会 tree-shaking 掉、构建产物里根本不含它们（F04/F05 记过这条）——
  所以这个文件一旦写错，构建也不报错，必须真机验证。

  ---------------------------------------------------------------------------
  本页的三件事
  ---------------------------------------------------------------------------
  ① 查询 + 分页 + 表格：全部交给 PageTable，本页只提供列定义、查询条件、接口函数
  ② 操作列按「状态 + 权限」动态显示：逻辑在 articleActions.js（纯函数，已被 118 项断言覆盖）
  ③ 危险动作走 ConfirmAction 二次确认，不做自己的 MessageBox

  ---------------------------------------------------------------------------
  两处刻意偏离《成员A-开发提示词手册》F06（都有硬依据）
  ---------------------------------------------------------------------------
  ① 手册问「已下架 → 查看、重新发布、归档」，**但状态机里已下架没有"重新发布"**
     （`ArticleStateMachine` 只有 `OFFLINE + ARCHIVE → ARCHIVED`，没有 `OFFLINE + PUBLISH_NOW`）。
     后端 `assertTransfer` 会抛 11003（状态非法）。所以本页不给这个按钮，
     详见 `frontend/src/views/article/articleActions.js` 文件头注释。
  ② 手册说审核接口入参是 `{stage, result, opinion}`；契约与 `api/modules/article.js`
     都是 `{auditAction, auditOpinion}` 且**不传 stage**（阶段由文章当前状态决定）。
     本页的「审核」只是跳转到审核工作台，真正提交在 ArticleAudit.vue，那边按契约写。

  ---------------------------------------------------------------------------
  一个已知缺口（登记在 docs/F06-文章管理页实测记录.md，不要以为做完了）
  ---------------------------------------------------------------------------
  「分类下拉」「作者下拉」的选项是**临时常量**（与 sql/init.sql 的种子一致）。
  原因：分类列表接口归成员B、用户列表接口归成员C，F06 时都还不存在
  （`src/main/java/com/lab/cms/controller/` 下只有 Auth / Health / System 三个）。
  等接口就绪后把 CATEGORY_OPTIONS / AUTHOR_OPTIONS 换成接口返回值即可，
  其余代码不用动 —— 所以这里把它们单独放在文件末尾，替换点只有一个。
-->
<template>
  <div class="article-list">
    <div class="page-header">
      <div class="page-header__left">
        <h1 class="page-header__title">{{ presetAuthor ? '我的投稿' : '文章管理' }}</h1>
        <p class="page-header__desc">{{ pageDesc }}</p>
      </div>
      <div class="page-header__actions">
        <el-button v-if="canCreate" type="primary" @click="goCreate">
          <el-icon><EditPen /></el-icon>
          <span>写文章</span>
        </el-button>
      </div>
    </div>

    <div class="app-card">
      <PageTable
        ref="tableRef"
        :columns="columns"
        :request="request"
        :query-model="query"
        show-index
        selectable
        @selection-change="onSelectionChange"
      >
        <!-- 查询区：PageTable 负责"查询/重置"两个键与回车，这里只放控件 -->
        <template #filter>
          <el-input
            v-model="query.title"
            placeholder="标题关键字"
            clearable
            class="filter-title"
          />
          <el-select v-model="query.categoryId" placeholder="分类" clearable class="filter-select">
            <el-option
              v-for="c in CATEGORY_OPTIONS"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
          <el-select v-model="query.status" placeholder="状态" clearable class="filter-select">
            <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-select v-model="query.authorId" placeholder="作者" clearable class="filter-select">
            <el-option v-for="u in AUTHOR_OPTIONS" :key="u.id" :label="u.nickname" :value="u.id" />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            unlink-panels
            range-separator="至"
            start-placeholder="创建开始"
            end-placeholder="创建结束"
            value-format="YYYY-MM-DD"
            class="filter-date"
          />
        </template>

        <!-- 批量操作：选中项为 0 时禁用（不是为了好看，是这两个操作必须有点选对象） -->
        <template #toolbar>
          <ConfirmAction
            title="批量提交初审"
            :content="`将选中的 ${selected.length} 篇文章提交到编辑初审。提交后需要编辑审核才能继续流转。`"
            confirm-text="提交"
            :disabled="!selected.length"
            @confirm="onBatchSubmit"
          >
            <el-button :disabled="!selected.length">批量提交审核</el-button>
          </ConfirmAction>
          <ConfirmAction
            type="danger"
            title="批量删除"
            :content="`将删除选中的 ${selected.length} 篇文章（仅草稿与已驳回态可删，其余会被后端拒绝）。`"
            confirm-text="删除"
            :disabled="!selected.length"
            @confirm="onBatchDelete"
          >
            <el-button :disabled="!selected.length">批量删除</el-button>
          </ConfirmAction>
        </template>

        <!-- 状态列：统一走 StatusTag，不在本页写映射表 -->
        <template #col-status="{ row }">
          <StatusTag :status="row.status" />
        </template>

        <template #col-isTop="{ row }">
          <el-tag v-if="Number(row.isTop) === 1" type="warning" effect="light" round size="small">置顶</el-tag>
          <span v-else>—</span>
        </template>

        <!--
          操作列：**这是 F06 的核心核查项**
          按钮集合完全由 getRowActions(row, userStore.hasPermission) 算出，
          本页不写任何 if (status === x)。
          · 无权限的动作**不显示**（不是置灰）—— 手册明确要求
          · 每个按钮的文案、危险色、主次色也来自 articleActions.js
        -->
        <template #col-actions="{ row }">
          <div class="row-actions">
            <template v-for="act in actionsOf(row)" :key="act.key">
              <!-- 危险动作走 ConfirmAction 二次确认 -->
              <ConfirmAction
                v-if="act.danger"
                :type="'danger'"
                :title="confirmTitle(act, row)"
                :content="confirmContent(act, row)"
                :confirm-text="act.label"
                :require-reason="act.key === 'offline'"
                :reason-hint="act.key === 'offline' ? REASON_HINT_OFFLINE : undefined"
                @confirm="(reason) => runAction(act.key, row, reason)"
              >
                <el-button link type="danger" size="small">{{ act.label }}</el-button>
              </ConfirmAction>

              <!-- 其余动作直接触发 -->
              <el-button
                v-else
                link
                size="small"
                :type="isPrimaryAction(row.status, act.key) ? 'primary' : 'default'"
                @click="runAction(act.key, row)"
              >
                {{ act.label }}
              </el-button>
            </template>
          </div>
        </template>
      </PageTable>
    </div>

    <!--
      排期发布：需要选时间，所以不能用 ConfirmAction（它只有"确认/取消 + 原因"两种形态）。
      MessageBox 没有"填一个时间"的能力，只能自己开 dialog。
    -->
    <el-dialog
      v-model="scheduleVisible"
      title="排期发布"
      width="440px"
      :close-on-click-modal="false"
      append-to-body
    >
      <p class="schedule-tip">
        文章《{{ scheduleRow?.title }}》将在所选时间由后台定时任务自动发布。
      </p>
      <el-date-picker
        v-model="scheduleTime"
        type="datetime"
        placeholder="选择发布时间"
        value-format="YYYY-MM-DD HH:mm:ss"
        :disabled-date="disablePastDate"
        style="width: 100%"
      />
      <p class="schedule-hint">
        时间必须晚于当前时间<b>至少 1 分钟</b>（后端每分钟扫描一次排期任务），
        否则返回 11007（排期时间非法）。到点后由后台定时任务自动转为「已发布」。
      </p>
      <template #footer>
        <el-button @click="scheduleVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" :disabled="!scheduleTime" @click="onScheduleConfirm">
          确定排期
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { EditPen } from '@element-plus/icons-vue'
/*
 * ⚠️ ElMessage 是**函数式调用**，按需引入的插件管不到它的样式，必须手工引 css。
 *   漏了的表现是"提示条没有背景色、文字挤在一起"（F04/F05 各踩过一次）。
 */
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import ConfirmAction from '@/components/ConfirmAction.vue'
import { useUserStore } from '@/store/user'
import {
  pageArticles,
  deleteArticle,
  submitForAudit,
  batchSubmit,
  publishNow,
  publishSchedule,
  withdraw,
  offline,
  archive
} from '@/api/modules/article'
import { getRowActions, isPrimaryAction, STATUS_OPTIONS } from './articleActions'

defineOptions({ name: 'ArticleList' })

const props = defineProps({
  /**
   * true = 「我的投稿」模式：固定只查当前登录用户自己的文章。
   * 复用同一个页面而不是再写一份，因为两者除了"作者筛选"完全一样。
   * ⚠️ 这是**展示层**的筛选，不是安全边界 —— 水平越权由后端 S12 的
   *    checkOwnership 兜底，前端不筛也拿不到别人的草稿（接口会拒）。
   */
  presetAuthor: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const tableRef = ref(null)

/**
 * 查询条件对象。
 * ⚠️ key 必须是**后端 DTO 真实存在的字段名**。
 *    CmsArticleQueryDTO 的字段是：title / categoryId / status / authorId / beginTime / endTime。
 *    `api/modules/article.js` 的 JSDoc 里写的是 keyword / createTimeFrom / createTimeTo —— 那三个
 *    字段后端不存在，传了会被忽略（不是报错，是"查询条件静默失效"，更难发现）。
 *    所以这里按 DTO 写，并在 F06 记录里登记了要顺手改掉那段 JSDoc。
 */
const query = reactive({
  title: '',
  categoryId: null,
  status: null,
  authorId: null,
  beginTime: '',
  endTime: ''
})

const selected = ref([])
const acting = ref(false)

const canCreate = computed(() => userStore.hasPermission('article:create'))
const pageDesc = computed(() =>
  props.presetAuthor
    ? `只看你自己投稿的文章（${userStore.nickname || '当前用户'}），可在此编辑、提交审核、撤回`
    : '全量文章检索与流转操作。按钮按「文章状态 + 你的权限」自动变化，没有权限的动作不显示'
)

/**
 * 创建时间范围。
 *
 * 用**计算属性 + getter/setter** 而不是 ref + watch，是为了让 PageTable 的「重置」也能生效：
 * PageTable 的重置是直接 `delete queryModel[k]`，如果这里用一个独立的 ref 存日期，
 * 重置后输入框里的日期还在、但请求里已经没有时间条件了 —— 出现"看起来筛了但其实没筛"。
 * 用 getter 从 query 反推，二者永远一致。
 */
const dateRange = computed({
  get: () => (query.beginTime && query.endTime ? [query.beginTime, query.endTime] : []),
  set: (v) => {
    if (Array.isArray(v) && v.length === 2 && v[0] && v[1]) {
      query.beginTime = v[0]
      query.endTime = v[1]
    } else {
      delete query.beginTime
      delete query.endTime
    }
  }
})

/**
 * 列定义。
 *
 * ⚠️ 排序字段（sortField）必须写**数据库列名**（下划线），不是 prop（驼峰）。
 *    `PageQuery.checkSortFieldIn` 比对的就是数据库列名；
 *    传驼峰会被判"不支持按 createTime 排序"直接 400。
 *    本页可排序列与契约 §1.3 的白名单一致：title / view_count / create_time。
 *
 * ⚠️ 宽度一律用 minWidth 不用 width：给定 minWidth 后 el-table 把剩余宽度按比例分配，
 *    1440 宽下 8 列刚好铺满、不会出现横向滚动条（核查项之一）。
 *    实测总 minWidth = 46+60+200+100+110+90+80+150+190 = 1026 < 内容区可用宽度。
 */
const columns = [
  {
    prop: 'title',
    label: '标题',
    minWidth: 200,
    sortable: true,
    sortField: 'title'
  },
  {
    prop: 'categoryName',
    label: '分类',
    minWidth: 100
  },
  {
    prop: 'status',
    label: '状态',
    // 120 而不是 110：「待编辑初审」「定时发布中」在 110 下会被折成两行（F06 实测）
    minWidth: 120,
    // 状态列不截断：里面的 el-tag 宽度是固定的，给 tooltip 反而会遮住
    overflowTip: false
  },
  {
    prop: 'authorName',
    label: '作者',
    minWidth: 90
  },
  {
    prop: 'isTop',
    label: '置顶',
    minWidth: 80,
    align: 'center',
    overflowTip: false
  },
  {
    prop: 'viewCount',
    label: '浏览量',
    minWidth: 80,
    align: 'right',
    sortable: true,
    sortField: 'view_count'
  },
  {
    prop: 'createTime',
    label: '创建时间',
    minWidth: 150,
    sortable: true,
    sortField: 'create_time',
    formatter: (row) => formatDateTime(row.createTime)
  },
  {
    prop: 'actions',
    label: '操作',
    minWidth: 190,
    align: 'right',
    fixed: 'right',
    overflowTip: false
  }
]

/** 后端给的是 ISO（2026-10-01T09:30:00），列表里显示成"空格分格到分钟"更好读 */
function formatDateTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

/** PageTable 的请求函数：把分页参数交给 pageArticles */
function request(params) {
  const finalParams = { ...params }
  // 「我的投稿」模式强制叠加上作者条件（放在这里而不是 query 里，
  // 是为了让"重置"不会把作者条件也清掉 —— 那个条件不是用户填的，不该被重置）
  if (props.presetAuthor && userStore.userId != null) {
    finalParams.authorId = userStore.userId
  }
  return pageArticles(finalParams)
}

function onSelectionChange(rows) {
  selected.value = rows || []
}

/** 每行的动作集合。抽成函数是因为模板里会调两次（一次渲染、一次判主色），别重复算 */
function actionsOf(row) {
  return getRowActions(row, (perm) => userStore.hasPermission(perm))
}

function goCreate() {
  router.push({ name: 'ArticleCreate' })
}

/**
 * 动作分发。
 * 之所以用一张 switch 而不是在模板里写 @click="只要该动作的逻辑"，
 * 是因为操作列的按钮是 v-for 出来的 —— 每个按钮不可能各自绑一个函数。
 *
 * @param {string} key 动作 key（见 articleActions.js 的 ACTION_META）
 * @param {object} row 文章行
 * @param {string} [reason] 二次确认里填写的原因。
 *        只有 `requireReason` 模式的 ConfirmAction 会把它 emit 出来（当前只有「下架」），
 *        其余动作这个参数是 undefined —— 所以下面的 case 用不到就别接。
 */
function runAction(key, row, reason) {
  switch (key) {
    case 'view':
      router.push({ name: 'ArticleDetail', params: { id: row.id } })
      break
    case 'edit':
      router.push({ name: 'ArticleEdit', params: { id: row.id } })
      break
    case 'audit':
      // 跳到审核工作台并带上文章 id；工作台会自己拉详情与流水
      router.push({ name: 'ArticleAudit', query: { id: row.id } })
      break
    case 'submit':
      doSubmit(row)
      break
    case 'publishNow':
      doPublishNow(row)
      break
    case 'publishSchedule':
      openSchedule(row)
      break
    case 'republish':
      // 已撤回重新发布 = 复用立即发布的接口（状态机里是同一个 PUBLISH_NOW 动作）
      doRepublish(row)
      break
    case 'withdraw':
      doWithdraw(row)
      break
    /*
     * ⚠️ 下面三个虽然也是「危险动作」，但它们的触发点在 ConfirmAction 的 @confirm 上，
     *    不是 el-button 的 @click —— ConfirmAction 内部完全接管了点击（先弹确认，确认后才 emit）。
     *    所以这里**必须也列进来**：模板把三个动作统一走 runAction(act.key, row)，
     *    若忘了列，点确认后什么都不会发生（且不报错），是最难查的一类遗漏。
     */
    case 'remove':
      doDelete(row)
      break
    case 'offline':
      doOffline(row, reason)
      break
    case 'archive':
      doArchive(row)
      break
    default:
      // 到此为止：articleActions.js 里新增动作而这里忘了接，会在控制台明确报出来
      console.error('[ArticleList] 未处理的动作：', key)
      break
  }
}

/*
 * 下面每个 doXxx 都是同一个骨架：
 *   try { await 接口 } catch { 什么都不做 } finally { 关 loading }
 *
 * 为什么 catch 里不弹提示：request.js 的响应拦截器已经统一弹过一次了，
 * 这里再弹就是同一个错误两条红条（F02 定的规矩）。
 * 但 catch 必须存在，否则 loading 关不掉、按钮永远转圈。
 */

async function doSubmit(row) {
  acting.value = true
  try {
    await submitForAudit(row.id)
    ElMessage.success('已提交初审')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

async function doPublishNow(row) {
  acting.value = true
  try {
    await publishNow(row.id)
    ElMessage.success('已发布')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

async function doRepublish(row) {
  acting.value = true
  try {
    await publishNow(row.id)
    ElMessage.success('已重新发布')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

async function doWithdraw(row) {
  acting.value = true
  try {
    await withdraw(row.id)
    ElMessage.success('已撤回')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

/**
 * 删除（逻辑删除）。
 * ⚠️ 不传原因：契约的 DELETE /articles/{id} 没有原因入参。
 *    手册 S14 提到"下架要必填原因"，若 S14 给接口补了 reason，这里再改成 requireReason。
 */
async function doDelete(row) {
  acting.value = true
  try {
    await deleteArticle(row.id)
    ElMessage.success('已删除')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

/**
 * 下架：已发布(5) → 已下架(8)。
 * ⚠️ 下架后状态机里**没有**出边回到已发布，只能归档。
 *
 * ⚠️ S14 起 reason 是**后端必填**（`@NotBlank`，不填返 400），所以这里必须传。
 *    原因由 ConfirmAction 的 `require-reason` 模式收集（填了才允许点确认），
 *    这条交互是 F05 就把 ConfirmAction 做成两种形态时就预留好的。
 *    后端把原因写进 `sys_oper_log.request_param`（不写审核流水表），
 *    追溯方式：`SELECT * FROM sys_oper_log WHERE oper_desc='下架文章' AND oper_id=<文章id>`。
 */
async function doOffline(row, reason) {
  acting.value = true
  try {
    await offline(row.id, reason)
    ElMessage.success('已下架')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

/** 归档：已撤回(7) / 已下架(8) → 已归档(9)，**终态** */
async function doArchive(row) {
  acting.value = true
  try {
    await archive(row.id)
    ElMessage.success('已归档')
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

// ---------------- 排期发布 ----------------

const scheduleVisible = ref(false)
const scheduleRow = ref(null)
const scheduleTime = ref('')

function openSchedule(row) {
  scheduleRow.value = row
  scheduleTime.value = ''
  scheduleVisible.value = true
}

/** 只禁掉"今天之前"的日期。具体时分是否晚于当前，交给后端的 11007 兜底 */
function disablePastDate(d) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return d.getTime() < today.getTime()
}

async function onScheduleConfirm() {
  if (!scheduleTime.value) return
  acting.value = true
  try {
    await publishSchedule(scheduleRow.value.id, scheduleTime.value)
    ElMessage.success('已排期，到点后由后台定时任务发布')
    scheduleVisible.value = false
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

// ---------------- 二次确认的文案 ----------------
// 写成一个函数而不是在模板里拼字符串：模板里拼容易漏判空（row 可能还没加载完）

/**
 * 「下架」确认框里，原因输入框下方的说明。
 *
 * ⚠️ 这里刻意**不**沿用 ConfirmAction 的默认文案「原因会记录到审核流水/操作日志里」：
 *    下架原因**不会**进 `cms_article_audit_log`（那张表的 `result` 只有 1 通过/2 驳回，
 *    且详情页的 `rejectReason` 取"最近一条 result=2 的意见"，
 *    下架原因若混进去，用户会在文章详情里看到一句张冠李戴的"驳回原因"）。
 *    它的实际去处是 `@OperLog` → `sys_oper_log.request_param`。
 *    前端文案必须说实情，否则用户会以为能在审核时间线里找到这条原因。
 */
const REASON_HINT_OFFLINE = '原因会记入操作日志（sys_oper_log），供事后追溯'

function confirmTitle(act) {
  const map = {
    remove: '删除文章',
    offline: '下架文章',
    archive: '归档文章'
  }
  return map[act.key] || act.label
}

function confirmContent(act, row) {
  const title = row?.title || '（无标题）'
  const map = {
    remove: `确定删除《${title}》吗？这是逻辑删除，删除后列表里不再出现。`,
    offline: `确定下架《${title}》吗？下架后读者端立即不可见。注意：下架后不能再重新发布，只能归档。`,
    archive: `确定归档《${title}》吗？归档是终态，之后不能再改为任何其它状态。`
  }
  return map[act.key] || `确定对《${title}》执行「${act.label}」吗？`
}

// ---------------- 批量操作 ----------------

/**
 * 批量提交初审。
 * ⚠️ 契约里 batchSubmit 返回的是**逐条结果** `[{ id, success, message }]`，
 *    不是"要么全成要么全败"——需求明确要求不因单条失败整批回滚。
 *    所以这里必须按条统计，不能只看 Promise 有没有 reject。
 */
async function onBatchSubmit() {
  const ids = selected.value.map((r) => r.id)
  if (!ids.length) return
  acting.value = true
  try {
    const results = await batchSubmit(ids)
    const list = Array.isArray(results) ? results : []
    const okCount = list.filter((r) => r && r.success).length
    const failCount = list.length - okCount
    if (list.length === 0) {
      ElMessage.success(`已提交 ${ids.length} 篇`)
    } else if (failCount === 0) {
      ElMessage.success(`已提交 ${okCount} 篇`)
    } else {
      // 部分失败要明确说出来 —— 只说"成功 N 篇"会让人以为全过了
      ElMessage.warning(`提交完成：成功 ${okCount} 篇，失败 ${failCount} 篇（失败原因见后端返回）`)
    }
    tableRef.value?.clearSelection()
    tableRef.value?.refresh()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

/**
 * 批量删除。
 * ⚠️ 契约里**没有批量删除接口**（只有单条 DELETE /articles/{id}），
 *    所以这里逐个调。策略与 batchSubmit 一致：单条失败不中断整批，
 *    最后汇总告诉用户成功几条。
 */
async function onBatchDelete() {
  const rows = [...selected.value]
  if (!rows.length) return
  acting.value = true
  let okCount = 0
  const failTitles = []
  try {
    for (const row of rows) {
      try {
        await deleteArticle(row.id)
        okCount++
      } catch (e) {
        failTitles.push(row.title || row.id)
      }
    }
    if (failTitles.length === 0) {
      ElMessage.success(`已删除 ${okCount} 篇`)
    } else {
      ElMessage.warning(
        `删除完成：成功 ${okCount} 篇，失败 ${failTitles.length} 篇（${failTitles.slice(0, 3).join('、')}${
          failTitles.length > 3 ? ' 等' : ''
        }）`
      )
    }
    tableRef.value?.clearSelection()
    tableRef.value?.refresh()
  } finally {
    acting.value = false
  }
}

// ---------------------------------------------------------------------------
// ⚠️ 临时选项表（接口就绪后替换这两个常量即可，其余代码不用动）
// ---------------------------------------------------------------------------
// 分类：成员B 的「分类管理」接口还没建 → 先用 sql/init.sql 的 9 条种子
const CATEGORY_OPTIONS = [
  { id: 1, name: '技术资讯' },
  { id: 2, name: '产品动态' },
  { id: 3, name: '公司新闻' },
  { id: 11, name: '前端开发' },
  { id: 12, name: '后端开发' },
  { id: 13, name: '人工智能' },
  { id: 21, name: '版本发布' },
  { id: 22, name: '功能预告' },
  { id: 31, name: '企业公告' }
]

// 作者：成员C 的「用户管理」接口还没建 → 先用 sys_user 的 5 条种子
const AUTHOR_OPTIONS = [
  { id: 1, nickname: '张晓投稿' },
  { id: 2, nickname: '李编辑' },
  { id: 3, nickname: '王主编' },
  { id: 4, nickname: '系统管理员' },
  { id: 5, nickname: '赵投稿' }
]
</script>

<style scoped>
/*
  查询控件宽度固定、用 class 而不是行内 style：
  行内 style 每个控件写一遍容易写歪（F04 的示例页就有 200/140/180 三种宽度混着）。
*/
.filter-title {
  width: 200px;
}

.filter-select {
  width: 132px;
}

.filter-date {
  width: 240px;
}

/*
  操作列：link 按钮紧凑排列。
  ⚠️ 不加 gap 的话相邻按钮紧贴在一起，靠得太近容易误点；
     加 gap 又要保持"密而不挤"（细则要求），所以用 2px + 按钮自身 4px padding 的取舍。
*/
.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

/* 排期弹窗里的两行说明文字 */
.schedule-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-2);
}

.schedule-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-3);
}
</style>
