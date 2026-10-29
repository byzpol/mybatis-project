<!--
  文章详情（F06）
  ===========================================================================
  只读页。三块内容：
    ① 文章本身（标题、分类、状态、作者、时间、阅读量、摘要、正文）
    ② 审核流水（AuditTimeline）—— 数据源是 `/articles/{id}/audit-logs`
    ③ 版本列表（el-table）+ 每行「回滚」

  ---------------------------------------------------------------------------
  ⚠️ 为什么流水单独取一次，而不是复用 detail.auditLogs
  ---------------------------------------------------------------------------
  契约里详情 VO 也带 auditLogs，但 F06 的核查项是
  「列表页和详情页的审核流水能对上」——
  两处都用同一个独立接口，取到的必然是同一份数据；
  如果一处用 detail 内嵌、一处用独立接口，将来只改其中一个就会"对不上"，
  而那种不一致排查起来非常费劲（两边的排序、截断规则可能都不同）。

  ---------------------------------------------------------------------------
  ⚠️ 正文渲染用 {{ }} 不用 v-html
  ---------------------------------------------------------------------------
  正文是用户提交进来的内容。本项目没有富文本编辑器，正文按纯文本存，
  用 v-html 只会把"恰好长得像 HTML 的文本"当代码执行 —— 典型的 XSS 入口。
  现在没有危险只是"恰好没人这么填"，不是安全。
  要支持富文本时，正确做法是后端做白名单净化（如 OWASP Java HTML Sanitizer）
  之后再决定能否渲染，而不是前端直接 v-html。
-->
<template>
  <div class="article-detail" v-loading="loading">
    <div class="page-header">
      <div class="page-header__left">
        <h1 class="page-header__title">{{ detail?.title || '文章详情' }}</h1>
        <p class="page-header__desc">
          <StatusTag v-if="detail" :status="detail.status" />
          <span class="detail-id">ID：{{ articleId }}</span>
        </p>
      </div>
      <div class="page-header__actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button
          v-if="canEdit"
          type="primary"
          @click="router.push({ name: 'ArticleEdit', params: { id: articleId } })"
        >
          编辑
        </el-button>
      </div>
    </div>

    <!-- ---------------- 基本信息 ---------------- -->
    <div class="app-card">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="分类">{{ detail?.categoryName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="作者">{{ detail?.authorName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="浏览量">{{ detail?.viewCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="是否置顶">
          {{ Number(detail?.isTop) === 1 ? '是' : '否' }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(detail?.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="最后更新">{{ formatDateTime(detail?.updateTime) }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ formatDateTime(detail?.publishTime) }}</el-descriptions-item>
        <el-descriptions-item label="排期时间">{{ formatDateTime(detail?.scheduleTime) }}</el-descriptions-item>
        <el-descriptions-item label="发布人">{{ detail?.publisherName || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 被驳回过就把最近一次的意见顶到最显眼的位置，作者一眼就知道要改什么 -->
      <el-alert
        v-if="detail?.rejectReason"
        class="reject-alert"
        type="error"
        :closable="false"
        show-icon
        title="最近一次驳回意见"
        :description="detail.rejectReason"
      />
    </div>

    <!-- ---------------- 摘要与正文 ---------------- -->
    <div class="app-card">
      <h2 class="block-title">摘要</h2>
      <p class="summary-text">{{ detail?.summary || '（未填写摘要）' }}</p>

      <h2 class="block-title">正文</h2>
      <pre v-if="detail?.content" class="content-pre">{{ detail.content }}</pre>
      <el-empty v-else description="正文为空" :image-size="90" />
    </div>

    <!-- ---------------- 审核流水 ---------------- -->
    <div class="app-card">
      <h2 class="block-title">审核流水</h2>
      <div v-loading="logsLoading">
        <AuditTimeline :records="logs" :collapse-limit="5" />
      </div>
    </div>

    <!-- ---------------- 版本列表 ---------------- -->
    <div class="app-card">
      <h2 class="block-title">版本记录</h2>
      <el-table v-loading="versionsLoading" :data="versions" border stripe size="small">
        <el-table-column prop="versionNo" label="版本号" width="140" align="center">
          <template #default="{ row }">
            <span class="version-no">v{{ row.versionNo }}</span>
            <!--
              当前版本标记（S15）：后端 isCurrent = (versionNo == 该文章最大版本号)。
              一篇文章最多一条为 true。加上它是因为"版本号最大"这件事
              单看数字列表并不直观（用户不一定知道总共有几版），
              而"我现在是哪一版"是版本页最先要回答的问题。
              ⚠️ 注意 isCurrent 与文章状态无关：已发布/已归档的文章一样有当前版本。
            -->
            <el-tag v-if="row.isCurrent" type="success" effect="light" round size="small">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="titleSnapshot" label="标题快照" min-width="200" show-overflow-tooltip />
        <el-table-column prop="modifierName" label="修改人" width="120">
          <template #default="{ row }">{{ row.modifierName || row.modifierId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="modifyRemark" label="修改说明" min-width="160">
          <template #default="{ row }">{{ row.modifyRemark || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right" fixed="right">
          <template #default="{ row }">
            <!--
              回滚是"用旧内容新建一版"，不是"回到过去"——
              所以它同样需要二次确认：用户以为能把文章变回旧样子，
              实际是新产生一个版本，说明必须写清楚。
            -->
            <ConfirmAction
              title="回滚到该版本"
              :content="`将以 v${row.versionNo} 的内容新建一个版本（版本号继续递增，不会删掉后面的版本）。当前未提交的修改会丢失。`"
              confirm-text="回滚"
              @confirm="onRollback(row)"
            >
              <el-button link type="primary" size="small">回滚</el-button>
            </ConfirmAction>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import StatusTag from '@/components/StatusTag.vue'
import AuditTimeline from '@/components/AuditTimeline.vue'
import ConfirmAction from '@/components/ConfirmAction.vue'
import { useUserStore } from '@/store/user'
import { getArticleDetail, listAuditLogs, listVersions, rollbackVersion } from '@/api/modules/article'

defineOptions({ name: 'ArticleDetail' })

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const articleId = computed(() => Number(route.params.id))

const detail = ref(null)
const logs = ref([])
const versions = ref([])

const loading = ref(false)
const logsLoading = ref(false)
const versionsLoading = ref(false)

/** 详情页也要能进编辑：同样只对「草稿 / 已驳回」开放，并且要有 article:update */
const canEdit = computed(() => {
  if (!userStore.hasPermission('article:update')) return false
  const s = Number(detail.value?.status)
  return s === 0 || s === 4
})

onMounted(() => {
  loadDetail()
  loadLogs()
  loadVersions()
})

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await getArticleDetail(articleId.value)
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await listAuditLogs(articleId.value)
    logs.value = Array.isArray(res) ? res : res?.records ?? []
  } catch (e) {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

/**
 * 版本列表。
 * ⚠️ 按 versionNo 倒序显示（最新在上），但**不在这里改数组**：
 *    拿到的顺序由后端定，前端只做展示排序，避免"后端已排好、前端又排一遍"的双重排序。
 */
async function loadVersions() {
  versionsLoading.value = true
  try {
    const res = await listVersions(articleId.value)
    const list = Array.isArray(res) ? res : res?.records ?? []
    versions.value = [...list].sort((a, b) => Number(b.versionNo) - Number(a.versionNo))
  } catch (e) {
    versions.value = []
  } finally {
    versionsLoading.value = false
  }
}

async function onRollback(row) {
  try {
    await rollbackVersion(articleId.value, row.versionNo)
    ElMessage.success('已按该版本内容新建一版')
    loadDetail()
    loadLogs()
    loadVersions()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function goBack() {
  router.push({ name: 'ArticleList' })
}

function formatDateTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.detail-id {
  margin-left: 8px;
  font-size: 12px;
  color: var(--app-text-3);
}

.block-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
}

/* 摘要与正文之间留出层次：两个 block-title 相邻时不要挤在一起 */
.block-title + .block-title,
.summary-text + .block-title {
  margin-top: 20px;
}

.summary-text {
  margin: 0;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--app-text-2);
  background: var(--app-bg-page);
  border-radius: var(--app-radius-sm);
}

/*
  ⚠️ white-space: pre-wrap 是必须的。
  默认的 `pre` 不折行，正文里只要有一行长文本就会顶破容器 → 页面出现横向滚动条
  （这正是 F06 核查项里"1440 宽无横向滚动条"最容易挂的地方）。
*/
.content-pre {
  margin: 0;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.9;
  color: var(--app-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.version-no {
  font-family: 'Consolas', 'Menlo', monospace;
  font-size: 12px;
  color: var(--app-text-2);
}

.reject-alert {
  margin-top: 12px;
}
</style>
