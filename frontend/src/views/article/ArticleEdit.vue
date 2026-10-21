<!--
  文章编辑 / 新建（F06）
  ===========================================================================
  一个页面同时承担"新建草稿"和"编辑草稿"，靠路由参数区分：
      /articles/create       → 新建（无 id）
      /articles/edit/:id     → 编辑（带 id，先拉详情回填）

  ⚠️ 为什么新建和编辑共用一个页面，而不是两个：
     两者的表单字段、校验规则、布局完全一样，唯一差别是"提交到哪个接口"。
     拆成两个文件后每次改字段都要改两处，早晚有一处漏掉 ——
     而"漏掉的那一处"表现为"编辑页能存、新建页存进去字段丢了"，很难在自测时发现。

  ---------------------------------------------------------------------------
  正文编辑器：现在为什么是 textarea，将来怎么换
  ---------------------------------------------------------------------------
  用 `el-input type="textarea"` + `:rows="20"` 是**刻意的起步选择**：
  wangEditor / md-editor-v3 这类富文本库体积在 200KB~1MB 量级，
  而 F06 的全部诉求是"能把正文存进去、能读出来"。
  为了排版效果引一个大依赖，在 20 分的课程项目里性价比很低。

  将来要换（比如需要图文混排）时，**只需要改一个地方**：
      <el-input type="textarea" ... />   →   <RichEditor v-model="form.content" />
  下面 `<template #contentEditor>` 那一段就是替换点，
  其余代码（校验、组装 DTO、提交）一行都不用动 —— 因为 form.content 仍然是 HTML/字符串。

  ---------------------------------------------------------------------------
  与手册 F06 的两处偏离
  ---------------------------------------------------------------------------
  ① 手册要求侧栏有「标签多选」。**没做** —— 因为 `CmsArticleSaveDTO` 里没有 `tagIds`
     （实测字段只有 title/summary/coverUrl/content/categoryId/isTop/scheduleTime），
     后端 S09 也不接收。前端做了就是"选了一堆标签，保存后全没了"，
     比不做更糟。标签域归成员B，等 DTO 加上 tagIds 再补（已登记在 F06 记录里）。
  ② 手册没提"编辑态要先拉详情"。本页做了，因为从列表点「编辑」进来必须回填，
     否则用户会看到一张空表单、一保存就把原内容覆盖掉。
-->
<template>
  <div class="article-edit">
    <div class="page-header">
      <div class="page-header__left">
        <h1 class="page-header__title">{{ isEdit ? '编辑文章' : '写文章' }}</h1>
        <p class="page-header__desc">{{ pageDesc }}</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="goBack">返回列表</el-button>
      </div>
    </div>

    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      label-width="76px"
      label-position="top"
    >
      <div class="edit-layout">
        <!-- ---------------- 左侧主区 ---------------- -->
        <div class="edit-main app-card">
          <el-form-item label="标题" prop="title">
            <el-input
              v-model="form.title"
              maxlength="200"
              show-word-limit
              placeholder="请输入文章标题（不超过 200 字）"
            />
          </el-form-item>

          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="请选择分类" class="full-width">
              <el-option
                v-for="c in CATEGORY_OPTIONS"
                :key="c.id"
                :label="c.name"
                :value="c.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="摘要">
            <el-input
              v-model="form.summary"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="一两句话说明这篇讲什么（可选，最长 500 字）"
            />
          </el-form-item>

          <el-form-item label="正文">
            <!--
              ★ 富文本编辑器的替换点 ★
              换掉这一个控件即可，form.content 的类型不变（字符串）。
            -->
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="20"
              placeholder="支持纯文本 / HTML 片段。草稿允许正文为空，但提交初审前必须填写。"
            />
            <p class="field-hint">
              草稿允许只有标题；点「提交初审」时后端会校验正文与分类是否齐全。
            </p>
          </el-form-item>
        </div>

        <!-- ---------------- 右侧侧栏 ---------------- -->
        <aside class="edit-side app-card">
          <h2 class="edit-side__title">发布设置</h2>

          <el-form-item label="封面图 URL">
            <el-input v-model="form.coverUrl" maxlength="255" placeholder="https://..." />
          </el-form-item>

          <el-form-item label="是否置顶">
            <el-switch v-model="form.isTop" :active-value="1" :inactive-value="0" />
            <span class="field-hint field-hint--inline">置顶后在各列表里优先展示</span>
          </el-form-item>

          <el-form-item label="排期时间">
            <el-date-picker
              v-model="form.scheduleTime"
              type="datetime"
              placeholder="不填表示不排期"
              value-format="YYYY-MM-DDTHH:mm:ss"
              class="full-width"
            />
            <p class="field-hint">
              这里是"保存并登记排期意图"。真正让文章进入「定时发布中」的动作是
              列表页的「排期发布」，它只对**审核通过**的文章开放
              （状态机：审核通过(3) → 定时发布中(6)）。
            </p>
          </el-form-item>

          <!-- 回填后的只读信息，让人知道自己在改哪一篇 -->
          <template v-if="isEdit && loaded">
            <el-divider />
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="当前状态">
                <StatusTag :status="loaded.status" />
              </el-descriptions-item>
              <el-descriptions-item label="作者">
                {{ loaded.authorName || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">
                {{ formatDateTime(loaded.createTime) }}
              </el-descriptions-item>
            </el-descriptions>
            <el-alert
              v-if="loaded.rejectReason"
              class="reject-alert"
              type="error"
              :closable="false"
              show-icon
              title="这篇被驳回过"
              :description="loaded.rejectReason"
            />
          </template>
        </aside>
      </div>

      <!--
        底部操作条。
        用 `position: sticky; bottom: 0` 而不是 `position: fixed`：
        fixed 是相对视口定位的，会被侧边栏盖住、也要自己算左右偏移量，
        换个布局就得重算；sticky 只在"内容比视口高"时生效，天然跟着内容区走。
      -->
      <div class="edit-footer">
        <el-button @click="goBack">取消</el-button>
        <el-button :loading="saving" @click="onSaveDraft">保存草稿</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmitAudit">
          保存并提交初审
        </el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import StatusTag from '@/components/StatusTag.vue'
import { getArticleDetail, saveDraft, updateDraft, submitForAudit } from '@/api/modules/article'

defineOptions({ name: 'ArticleEdit' })

const route = useRoute()
const router = useRouter()

const formRef = ref(null)
const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)

/** 编辑态才有的"原稿"信息，用于回填与展示 */
const loaded = ref(null)

/** 有 id 就是编辑态。用 computed 而不是一次性的布尔，路由参数变了也能跟着变 */
const articleId = computed(() => {
  const id = route.params.id
  return id === undefined || id === null || id === '' ? null : Number(id)
})
const isEdit = computed(() => articleId.value !== null)

const pageDesc = computed(() =>
  isEdit.value
    ? '修改后点「保存草稿」只存不提交；点「保存并提交初审」会先保存再送审'
    : '新建的文章以草稿态保存。点「保存并提交初审」会直接送审，中途就不能再改了'
)

/**
 * ⚠️ 字段名必须是 `CmsArticleSaveDTO` 里真实存在的：
 *    title / summary / coverUrl / content / categoryId / isTop / scheduleTime
 *    （没有 tagIds —— 见文件头注释 ①）
 */
const form = reactive({
  title: '',
  summary: '',
  coverUrl: '',
  content: '',
  categoryId: null,
  isTop: 0,
  scheduleTime: ''
})

/**
 * 表单校验。
 * 只做"字段形态"校验（必填、长度），**不做"业务流程允不允许"的校验** ——
 * 与后端 `CmsArticleSaveDTO` 的注释一致：内容是"提交初审前必须有"，
 * 那不是表单校验该管的事，所以放在 onSubmitAudit 里显式判断。
 * 长度上限与库表列宽对齐（title 200 / summary 500 / coverUrl 255）。
 */
const rules = {
  title: [
    { required: true, message: '请输入文章标题', trigger: 'blur' },
    { max: 200, message: '标题不能超过 200 字', trigger: 'blur' }
  ],
  categoryId: [{ required: true, message: '请选择文章分类', trigger: 'change' }]
}

onMounted(() => {
  if (isEdit.value) {
    fetchDetail()
  }
})

/**
 * 拉详情回填。
 * ⚠️ 详情接口（GET /articles/{id}）由 S09/S11 实现，F06 阶段还不存在。
 *    所以这里必须能优雅失败：失败时提示并退回列表，
 *    而不是留一张空表单 —— 空表单 + 保存 = 把原内容覆盖成空白。
 */
async function fetchDetail() {
  loading.value = true
  try {
    const detail = await getArticleDetail(articleId.value)
    loaded.value = detail || null

    // 只有草稿(0) 与 已驳回(4) 可编辑，其余状态后端会拒；前端提前拦住，少一次无效请求
    const status = Number(detail?.status)
    if (status !== 0 && status !== 4) {
      ElMessage.warning('只有「草稿」和「已驳回」的文章可以编辑')
      goBack()
      return
    }

    form.title = detail?.title ?? ''
    form.summary = detail?.summary ?? ''
    form.coverUrl = detail?.coverUrl ?? ''
    form.content = detail?.content ?? ''
    form.categoryId = detail?.categoryId ?? null
    form.isTop = Number(detail?.isTop) === 1 ? 1 : 0
    // 后端给的是 LocalDateTime（序列化成 "2026-10-25T09:00:00"），
    // 而 el-date-picker 配了 value-format="YYYY-MM-DDTHH:mm:ss"，格式必须是这一个 ——
    // 直接把 "2026-10-25 09:00:00"（带空格）塞回去，picker 会解析不出来、显示成空
    form.scheduleTime = detail?.scheduleTime ? String(detail.scheduleTime).replace(' ', 'T').slice(0, 19) : ''
  } catch (e) {
    // 拦截器已提示过一次；这里只负责别把用户留在一张空表单上
    ElMessage.warning('没能读到这篇的原文，已返回列表（避免空表单覆盖原内容）')
    goBack()
  } finally {
    loading.value = false
  }
}

/** 组装后端要的 DTO。空字符串统一转成 null —— 后端把 null 当"不修改该字段"，空串是"改成空" */
function buildDto() {
  return {
    title: form.title.trim(),
    summary: form.summary?.trim() || null,
    coverUrl: form.coverUrl?.trim() || null,
    content: form.content ?? '',
    categoryId: form.categoryId,
    isTop: Number(form.isTop) === 1 ? 1 : 0,
    scheduleTime: form.scheduleTime || null
  }
}

/** 校验表单，返回是否通过 */
async function validate() {
  try {
    await formRef.value.validate()
    return true
  } catch (e) {
    return false
  }
}

/**
 * 保存草稿。
 * @returns {Promise<number|null>} 成功时返回文章 id（新建时用它做后续提交），失败返回 null
 *
 * ⚠️ 乐观锁：`cms_article` 有 version 列（MP @Version）。
 *    并发编辑同一篇时后提交者会失败，后端返回的是明确的业务错误，
 *    request.js 会把它弹出来，所以这里不需要额外处理 —— 只要不把失败当成功。
 */
async function saveDraftInternal() {
  if (!(await validate())) return null
  try {
    if (isEdit.value) {
      await updateDraft(articleId.value, buildDto())
      return articleId.value
    }
    const newId = await saveDraft(buildDto())
    return newId ?? null
  } catch (e) {
    return null
  }
}

/**
 * 点「保存草稿」。
 * ⚠️ 新建接口**不是幂等的**（没有做重复提交保护），所以按钮必须上 loading 锁，
 *    否则手快点两下就会建出两篇草稿。这也是 api/modules/article.js 里
 *    saveDraft 的 JSDoc 特地写明"前端按钮要自己防连点"的原因。
 */
async function onSaveDraft() {
  if (saving.value || submitting.value) return
  saving.value = true
  try {
    const id = await saveDraftInternal()
    if (id === null) return
    ElMessage.success('草稿已保存')
    if (!isEdit.value) {
      // 新建成功后切到编辑态：URL 换成 /articles/edit/{id}，
      // 用户再点一次「保存」走的就是 updateDraft，不会又建一篇
      router.replace({ name: 'ArticleEdit', params: { id } })
    }
  } finally {
    saving.value = false
  }
}

/**
 * 点「保存并提交初审」。
 * 两步：先保存（拿到 id），再调 submit。任何一步失败都中断，
 * 不出现"提交了但内容没保存"这种半成品状态。
 */
async function onSubmitAudit() {
  if (saving.value || submitting.value) return

  /*
   * 「提交流程」才有的业务校验。
   * ⚠️ 为什么不写成 el-form 的 rule：
   *    rules 是"字段级、与流程无关"的，而"正文必填"只在这一条流程上成立
   *    （存草稿时正文可以为空）。把它写成 rule 会让「保存草稿」也拦住用户。
   *    这与后端 CmsArticleSaveDTO 的注释是同一个取舍。
   */
  if (!form.content || !form.content.trim()) {
    ElMessage.warning('提交初审前请填写正文')
    return
  }

  submitting.value = true
  try {
    const id = await saveDraftInternal()
    if (id === null) return

    // 新建时 URL 还没换成编辑态，先换掉，避免提交失败后用户再点保存又建一篇
    if (!isEdit.value) {
      await router.replace({ name: 'ArticleEdit', params: { id } })
    }

    await submitForAudit(id)
    ElMessage.success('已提交初审')
    router.push({ name: 'ArticleList' })
  } catch (e) {
    // 提交失败：内容已经保存成功，留在当前页让用户能重试
  } finally {
    submitting.value = false
  }
}

function goBack() {
  router.push({ name: 'ArticleList' })
}

function formatDateTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

// ---------------------------------------------------------------------------
// ⚠️ 临时选项：与 ArticleList.vue 里的那份同源，等成员B 的分类接口就绪后删掉
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
/*
  左主右辅两栏。
  用 flex + `min-width: 0`：不给 min-width 时 flex 子项不会收缩到内容宽度以下，
  表格/长文本会把右侧栏挤出容器，进而把整个页面撑出横向滚动条。
*/
.edit-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.edit-main {
  flex: 1 1 auto;
  min-width: 0;
}

.edit-side {
  flex: 0 0 300px;
  width: 300px;
}

.edit-side__title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
}

.full-width {
  width: 100%;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-3);
}

.field-hint--inline {
  margin: 0 0 0 8px;
}

.reject-alert {
  margin-top: 12px;
}

.edit-footer {
  position: sticky;
  bottom: 0;
  z-index: 1;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  margin-top: 16px;
  background-color: var(--app-bg-card);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius);
  box-shadow: 0 -2px 8px rgba(16, 24, 40, 0.06);
}

/* 窄屏（<1100）时侧栏落到下方，避免把主区挤得没法写字 */
@media (max-width: 1100px) {
  .edit-layout {
    flex-direction: column;
  }

  .edit-side {
    flex: 0 0 auto;
    width: 100%;
  }
}
</style>
