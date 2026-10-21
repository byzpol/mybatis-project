<!--
  审核工作台（F06）
  ===========================================================================
  一个页面同时服务「编辑初审」和「主编终审」两个环节 ——
  它们的差别只有两处：① 看哪个状态的待审队列 ② 通过后流向哪个状态，
  其余（只读预览、流水时间线、意见必填规则）完全一样。

  ---------------------------------------------------------------------------
  ⚠️ 提交给后端的入参**不带 stage**（这是本项目最容易搞错的一处）
  ---------------------------------------------------------------------------
  《成员A-开发提示词手册》F06 写的是 `audit(id, {stage, result, opinion})`，
  但契约与 `api/modules/article.js` 都是：
      { auditAction: 1通过 / 2驳回, auditOpinion: '...' }
  并且**由文章当前状态决定是初审还是终审**：
      status=1（待编辑初审）→ 走初审
      status=2（待主编终审）→ 走终审
  为什么不让前端传 stage：前端传的 stage 与库里真实状态可能不一致
  （比如两个人同时打开页面，一个已经审完了），后端还得写"以谁为准"的规则。
  状态是唯一真相，阶段从状态推出来就不会打架。
  所以本页的「环节」只用来筛选列表与显示标题，绝不进请求体。

  ---------------------------------------------------------------------------
  「驳回必须填意见」用置灰按钮实现，不用"点了再报错"
  ---------------------------------------------------------------------------
  F05 的实测结论：**浏览器不会给 disabled 的按钮派发 click 事件**，
  所以"按钮置灰"和"点击后报错"是两种互斥的校验设计，同时做必然得到死代码。
  这里选"置灰 + 旁边写明为什么"，判据也很直白：
  按钮上有 `disabled` 属性，自动化脚本可以直接断言。
-->
<template>
  <div class="article-audit">
    <div class="page-header">
      <div class="page-header__left">
        <h1 class="page-header__title">待我审核</h1>
        <p class="page-header__desc">
          审核阶段由文章当前状态决定：待编辑初审 → 初审，待主编终审 → 终审。驳回必须填写意见。
        </p>
      </div>
      <div class="page-header__actions">
        <!-- 同时具备初审与终审权限的角色（管理员）才需要这个切换 -->
        <el-radio-group v-if="canFirst && canFinal" v-model="stage" size="default">
          <el-radio-button :value="1">编辑初审</el-radio-button>
          <el-radio-button :value="2">主编终审</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- ---------------- 待审队列 ---------------- -->
    <div class="app-card">
      <PageTable
        ref="tableRef"
        :columns="columns"
        :request="request"
        :query-model="query"
        show-index
      >
        <template #filter>
          <el-input v-model="query.title" placeholder="标题关键字" clearable class="filter-title" />
          <el-select v-model="query.categoryId" placeholder="分类" clearable class="filter-select">
            <el-option v-for="c in CATEGORY_OPTIONS" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <span class="queue-count">当前环节：{{ stageName }}</span>
        </template>

        <template #col-status="{ row }">
          <StatusTag :status="row.status" />
        </template>

        <template #col-actions="{ row }">
          <el-button link type="primary" size="small" @click="selectArticle(row)">
            {{ isCurrent(row) ? '正在审核' : '去审核' }}
          </el-button>
        </template>
      </PageTable>
    </div>

    <!-- ---------------- 审核面板 ---------------- -->
    <div v-if="currentId" class="app-card audit-panel">
      <div class="audit-panel__grid">
        <!-- 左：只读预览 -->
        <section class="audit-preview" v-loading="previewLoading">
          <h2 class="audit-preview__title">{{ detail?.title || '（加载中…）' }}</h2>

          <div class="audit-preview__meta">
            <StatusTag :status="detail?.status" />
            <span>分类：{{ detail?.categoryName || '-' }}</span>
            <span>作者：{{ detail?.authorName || '-' }}</span>
            <span>创建：{{ formatDateTime(detail?.createTime) }}</span>
            <span>最近更新：{{ formatDateTime(detail?.updateTime) }}</span>
          </div>

          <p v-if="detail?.summary" class="audit-preview__summary">{{ detail.summary }}</p>

          <div class="audit-preview__content">
            <!-- 正文一律按纯文本渲染（v-text 语义），不 v-html —— 避免把提交上来的 HTML 当代码执行 -->
            <pre v-if="detail?.content" class="audit-preview__pre">{{ detail.content }}</pre>
            <el-empty v-else description="正文为空" :image-size="80" />
          </div>
        </section>

        <!-- 右：审核表单 + 流水 -->
        <aside class="audit-side">
          <h2 class="audit-side__title">审核结论</h2>

          <el-radio-group v-model="form.result" class="audit-side__result">
            <el-radio :value="1">通过</el-radio>
            <el-radio :value="2">驳回</el-radio>
          </el-radio-group>

          <el-input
            v-model="form.opinion"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="form.result === 2 ? '请说明驳回原因（必填）' : '可填写审核意见（选填）'"
          />

          <p class="audit-side__hint" :class="{ 'is-error': form.result === 2 && !opinionFilled }">
            {{ hintText }}
          </p>

          <div class="audit-side__actions">
            <el-button @click="clearCurrent">取消</el-button>
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="!canSubmit"
              @click="onSubmit"
            >
              提交审核
            </el-button>
          </div>

          <el-divider />

          <h2 class="audit-side__title">审核流水</h2>
          <div v-loading="logsLoading">
            <AuditTimeline :records="logs" :collapse-limit="3" />
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import AuditTimeline from '@/components/AuditTimeline.vue'
import { useUserStore } from '@/store/user'
import { pageArticles, getArticleDetail, listAuditLogs, audit } from '@/api/modules/article'

defineOptions({ name: 'ArticleAudit' })

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const tableRef = ref(null)

/*
 * 环节选择。
 * 默认值不是拍脑袋：只有「主编 / 管理员」能终审（库表里 article:publish 只授予了这两个角色），
 * 所以有 publish 权限的人默认落在终审，编辑默认落在初审。
 * 同时具备两者（管理员）时顶部会出现切换按钮。
 */
const canFirst = computed(() => userStore.hasPermission('article:audit'))
const canFinal = computed(() => userStore.hasPermission('article:publish'))
// 主编 / 管理员（有 article:publish）默认落在终审；编辑默认落在初审。
// 两者都有的人（管理员）可以直接用顶部的切换按钮换环节。
const stage = ref(canFinal.value ? 2 : 1)
const stageName = computed(() => (stage.value === 2 ? '主编终审' : '编辑初审'))

const query = reactive({ title: '', categoryId: null })

/** 待审队列的请求：状态固定为当前环节对应的状态，不受用户输入影响 */
function request(params) {
  return pageArticles({ ...params, status: stage.value === 2 ? 2 : 1 })
}

const columns = [
  { prop: 'title', label: '标题', minWidth: 220 },
  { prop: 'categoryName', label: '分类', minWidth: 110 },
  { prop: 'status', label: '状态', minWidth: 120, overflowTip: false },
  { prop: 'authorName', label: '作者', minWidth: 90 },
  {
    prop: 'createTime',
    label: '提交时间',
    minWidth: 150,
    formatter: (row) => formatDateTime(row.createTime)
  },
  { prop: 'actions', label: '操作', minWidth: 90, align: 'right', fixed: 'right', overflowTip: false }
]

// ---------------------------------------------------------------------------
// 当前正在审核的文章
// ---------------------------------------------------------------------------
const currentId = ref(null)
const detail = ref(null)
const logs = ref([])
const previewLoading = ref(false)
const logsLoading = ref(false)
const submitting = ref(false)

const form = reactive({ result: null, opinion: '' })

const opinionFilled = computed(() => !!form.opinion && !!form.opinion.trim())

/**
 * 能不能提交。
 *  · 没选结论 → 不能
 *  · 选驳回但没写意见 → 不能（后端也会拒，见 ARTICLE_AUDIT_OPINION_REQUIRED）
 *  · 选通过 → 可以（意见选填）
 */
const canSubmit = computed(() => {
  if (form.result !== 1 && form.result !== 2) return false
  if (form.result === 2) return opinionFilled.value
  return true
})

const hintText = computed(() => {
  if (form.result === null) return '请先选择审核结论'
  if (form.result === 2 && !opinionFilled.value) return '驳回时必须填写审核意见'
  if (form.result === 2) return '驳回后文章回到「已驳回」，作者可修改后重新提交'
  return stage.value === 2 ? '通过后文章进入「审核通过」，等待发布' : '通过后文章进入「待主编终审」'
})

function isCurrent(row) {
  return currentId.value !== null && Number(row.id) === Number(currentId.value)
}

onMounted(() => {
  // 从列表页点「审核」会带 ?id=xx 过来，直接进入这一篇的审核面板
  const id = route.query.id
  if (id !== undefined && id !== null && id !== '') {
    selectArticle({ id: Number(id) })
  }
})

function selectArticle(row) {
  currentId.value = Number(row.id)
  // 换一篇就把上一次的结论清干净 —— 否则"上一篇填了驳回原因"会带到这一篇上，
  // 一不留神就把新文章给驳回了
  form.result = null
  form.opinion = ''
  // 地址栏同步带上 id：刷新页面还在这一篇上，也方便把这个地址发给别人
  router.replace({ name: 'ArticleAudit', query: { id: String(currentId.value) } })
  loadDetail()
  loadLogs()
}

function clearCurrent() {
  currentId.value = null
  detail.value = null
  logs.value = []
  form.result = null
  form.opinion = ''
  router.replace({ name: 'ArticleAudit' })
}

async function loadDetail() {
  previewLoading.value = true
  try {
    detail.value = await getArticleDetail(currentId.value)
  } catch (e) {
    // 拦截器已提示
    detail.value = null
  } finally {
    previewLoading.value = false
  }
}

/**
 * 审核流水。
 * ⚠️ 单独走 `/articles/{id}/audit-logs` 而不是复用 detail.auditLogs：
 *    需求里明确要"列表页和详情页的审核流水能对上"，
 *    用同一个独立接口才能保证两处取到的是同一份数据、也不会因为详情 VO 字段调整而失效。
 */
async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await listAuditLogs(currentId.value)
    logs.value = Array.isArray(res) ? res : res?.records ?? []
  } catch (e) {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await audit(currentId.value, {
      // ⚠️ 只有这两个字段：auditAction / auditOpinion。没有 stage（见文件头注释）
      auditAction: form.result,
      auditOpinion: form.opinion.trim()
    })
    ElMessage.success(form.result === 1 ? '审核通过' : '已驳回')
    // 审完这一篇就不再是最新的待审项了：清空面板 + 回第 1 页刷新队列
    clearCurrent()
    tableRef.value?.reload()
  } catch (e) {
    // 拦截器已提示；保留面板让用户能改意见后重试
  } finally {
    submitting.value = false
  }
}

function formatDateTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

// ---------------------------------------------------------------------------
// ⚠️ 临时选项：与 ArticleList.vue 同源，等成员B 的分类接口就绪后删掉
// ---------------------------------------------------------------------------
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
</script>

<style scoped>
.filter-title {
  width: 200px;
}

.filter-select {
  width: 132px;
}

/* 队列提示：不是控件，跟在下拉后面做一个轻量说明 */
.queue-count {
  font-size: 12px;
  color: var(--app-text-3);
}

/*
  审核面板：左预览右表单。
  min-width: 0 是必须的 —— 没有它，左侧 <pre> 里的一长行会把右栏挤出去，
  页面就会出现横向滚动条。
*/
.audit-panel__grid {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.audit-preview {
  flex: 1 1 auto;
  min-width: 0;
}

.audit-side {
  flex: 0 0 320px;
  width: 320px;
}

.audit-preview__title {
  margin: 0 0 10px;
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
  color: var(--app-text-1);
}

.audit-preview__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--app-text-3);
}

.audit-preview__summary {
  margin: 12px 0 0;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-2);
  background: var(--app-bg-page);
  border-radius: var(--app-radius-sm);
}

.audit-preview__content {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border);
}

/*
  正文用 <pre> 保留换行，但**必须**允许在容器内换行（white-space: pre-wrap）。
  默认 white-space: pre 时，一行很长的文字不会折行，直接顶出容器 → 页面横向滚动条。
*/
.audit-preview__pre {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  color: var(--app-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.audit-side__title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
}

.audit-side__result {
  margin-bottom: 12px;
}

.audit-side__hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-3);
}

/* 只有"该填却没填"时才转危险色，与置灰的提交按钮配对（F05 的结论） */
.audit-side__hint.is-error {
  color: var(--el-color-danger);
}

.audit-side__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

@media (max-width: 1200px) {
  .audit-panel__grid {
    flex-direction: column;
  }

  .audit-side {
    flex: 0 0 auto;
    width: 100%;
  }
}
</style>
