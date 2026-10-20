<!--
  审核流水时间线（F05）
  ===========================================================================
  把一串审核流水渲染成"最新在上"的时间线。四个业务域里凡是需要展示
  "这篇内容被谁在哪一步怎么处理过"的地方都用它（目前是文章域）。

  ---------------------------------------------------------------------------
  数据形状（props.records 里每一项）
  ---------------------------------------------------------------------------
    {
      auditStage:     1 | 2,          // 环节：1=编辑初审 2=主编终审（见 AuditStageEnum）
      auditorName:    '李编辑',        // 审核人昵称（后端拼好，前端不做二次查询）
      result:         1 | 2,          // 结论：1=通过 2=驳回（见 AuditResultEnum）
      opinion:        '内容结构清晰',   // 审核意见，可空
      auditTime:      '2026-10-01 09:30:00',
      articleVersion: 2               // 审核针对的版本号（可空）
    }

  ⚠️ 环境字段（环节名、结论标签）的口径都以**后端枚举为准**：
     · 环节名：`com.lab.cms.common.enums.AuditStageEnum`（1 编辑初审 / 2 主编终审），
       查不到时后端 `getDesc()` 也会兜底成「未知环节」——前端保持同一个兜底文案。
     · 结论标签：直接复用 `StatusTag` 的 `approval` 映射表（1 通过 / 2 驳回），
       **不在这里再写一份**。理由：两处维护就会有两处不一致，
       而"审核结论"这种只有两个值的映射没有任何理由分家。
       圆点颜色也从同一张映射表的 `type` 派生（success→绿 / danger→红），
       于是"标签色"和"圆点色"永远一致。

  ---------------------------------------------------------------------------
  两个需要解释的处理
  ---------------------------------------------------------------------------
  ① 倒序是**自己排**的，没用 el-timeline 的 reverse
     因为"默认只显示最近 3 条"这件事本身就要求先按时间排好再截取。
     既然要排，就别让排序一会儿在组件内部一会儿在 CSS 方向里。
     排序用**归一化后的字符串比较**而不是 `new Date()`：
     后端给的是 `2026-10-01 09:30:00`，这种"空格分隔"的格式在
     `new Date()` 里属于实现相关的宽容解析（Safari 一类会直接 Invalid Date）。
     字符串比较只要格式一致就是稳定且正确的，也不需要处理时区。

  ② 折叠只隐藏"渲染"，不隐藏"数据"
     展开/收起通过 `visibleRecords` 计算，records 本身不动 ——
     使用方拿到的还是完整数组，不会被组件改掉。
-->
<template>
  <div class="audit-timeline">
    <!-- 空态：不用 el-timeline 渲染空列表（那会留下一条孤零零的竖线） -->
    <el-empty v-if="!records || records.length === 0" description="暂无审核记录" :image-size="90" />

    <template v-else>
      <el-timeline>
        <el-timeline-item
          v-for="(item, index) in visibleRecords"
          :key="index"
          :timestamp="formatTime(item.auditTime)"
          placement="top"
          :color="dotColor(item.result)"
        >
          <div class="audit-node">
            <div class="audit-node__head">
              <span class="audit-node__stage">{{ stageName(item.auditStage) }}</span>
              <StatusTag :status="item.result" type="approval" />
              <span class="audit-node__auditor">{{ item.auditorName || '—' }}</span>
              <span v-if="item.articleVersion != null" class="audit-node__version">
                v{{ item.articleVersion }}
              </span>
            </div>

            <!--
              意见块：只在这两种情况下显示
                · 驳回了 —— 不管有没有写意见都要显示（没写也要让人看见"这次驳回没说原因"）
                · 通过了但确实写了意见 —— 空的不显示，避免一排空框
            -->
            <blockquote
              v-if="shouldShowOpinion(item)"
              class="audit-node__opinion"
              :class="{ 'audit-node__opinion--reject': isReject(item.result) }"
            >
              {{ item.opinion || '（未填写审核意见）' }}
            </blockquote>
          </div>
        </el-timeline-item>
      </el-timeline>

      <!-- 折叠开关：记录不超过阈值时整个按钮不出现 -->
      <div v-if="records.length > collapseLimit" class="audit-timeline__more">
        <el-button link type="primary" size="small" @click="expanded = !expanded">
          {{ expanded ? '收起' : `展开全部（共 ${records.length} 条）` }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import StatusTag, { getStatusMeta } from './StatusTag.vue'

defineOptions({ name: 'AuditTimeline' })

const props = defineProps({
  /** 审核流水数组（顺序随意，组件内部会按时间倒序） */
  records: {
    type: Array,
    default: () => []
  },
  /** 默认只显示最近几条；设为 0 或负数表示不折叠 */
  collapseLimit: {
    type: Number,
    default: 3
  }
})

const emit = defineEmits(['expand-change'])

const expanded = ref(false)

/** 环节名：与后端 AuditStageEnum.getDesc 的兜底文案保持一致 */
const STAGE_NAMES = { 1: '编辑初审', 2: '主编终审' }
function stageName(stage) {
  return STAGE_NAMES[String(stage)] || '未知环节'
}

/**
 * 圆点颜色。从 StatusTag 的 approval 映射表取出 `type`，再翻译成主题变量 ——
 * 这样改结论配色只需要改 StatusTag 一处，圆点自动跟着变。
 * 用 `var(--el-color-xxx, 兜底色)` 的形式：主题变量没定义时还能退回到 EP 默认色。
 */
const DOT_COLORS = {
  success: 'var(--el-color-success, #67c23a)',
  danger: 'var(--el-color-danger, #f56c6c)',
  warning: 'var(--el-color-warning, #e6a23c)',
  primary: 'var(--el-color-primary, #409eff)',
  info: 'var(--el-color-info, #909399)'
}
function dotColor(result) {
  const meta = getStatusMeta(result, 'approval')
  return DOT_COLORS[meta.type] || DOT_COLORS.info
}

function isReject(result) {
  return String(result) === '2'
}

function shouldShowOpinion(item) {
  if (isReject(item.result)) return true
  return !!(item.opinion && String(item.opinion).trim())
}

/**
 * 时间归一：把 `YYYY-MM-DD HH:mm:ss` 与 ISO 的 `YYYY-MM-DDTHH:mm:ss` 统一成
 * `YYYY-MM-DD HH:mm`。用切片而不是 Date 解析 —— 见文件头注释 ①。
 */
function normalizeTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').trim().slice(0, 16)
}

function formatTime(t) {
  return normalizeTime(t) || '时间未知'
}

/**
 * 按时间倒序排（最新在上），再按折叠状态截取。
 * 排序键用归一化后的字符串：格式一致时字典序 == 时间序。
 */
const sortedRecords = computed(() => {
  return [...(props.records || [])].sort((a, b) => {
    const ta = normalizeTime(a?.auditTime)
    const tb = normalizeTime(b?.auditTime)
    return tb.localeCompare(ta)
  })
})

const visibleRecords = computed(() => {
  const limit = Number(props.collapseLimit)
  if (expanded.value || !limit || limit <= 0) return sortedRecords.value
  return sortedRecords.value.slice(0, limit)
})

/** 折叠开关联动，方便使用方做埋点或统计 */
function toggleExpand() {
  expanded.value = !expanded.value
  emit('expand-change', expanded.value)
}

defineExpose({ toggleExpand })
</script>

<style scoped>
.audit-timeline {
  padding: 2px 0;
}

.audit-node__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  line-height: 20px;
}

.audit-node__stage {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-primary, #303133);
}

.audit-node__auditor {
  font-size: 12px;
  color: var(--app-text-regular, #606266);
}

.audit-node__version {
  font-size: 12px;
  padding: 0 6px;
  border-radius: 2px;
  color: var(--app-text-secondary, #909399);
  background: var(--app-bg-page, #f5f7fa);
}

/*
  驳回意见：浅红底 + 左侧竖线 + 引号。
  用浅底而不是加粗红字 —— 意见往往是一整段话，红字段落看久了很刺眼，
  浅红底既能让人一眼定位到"这条是驳回"，又不影响读完整段文字。
*/
.audit-node__opinion {
  margin: 8px 0 0;
  padding: 6px 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-regular, #606266);
  background: var(--app-bg-page, #f5f7fa);
  border-left: 3px solid var(--el-color-info, #909399);
  border-radius: 2px;
}

.audit-node__opinion--reject {
  color: var(--el-color-danger, #f56c6c);
  background: var(--el-color-danger-light-9, #fef0f0);
  border-left-color: var(--el-color-danger, #f56c6c);
}

/* 引号靠两个伪元素加在首尾，比把引号写进文本里干净（复制出去不会有引号） */
.audit-node__opinion--reject::before {
  content: '\201C';
}

.audit-node__opinion--reject::after {
  content: '\201D';
}

.audit-timeline__more {
  text-align: center;
  padding-bottom: 4px;
}
</style>
