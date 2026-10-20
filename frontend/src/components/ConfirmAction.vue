<!--
  危险操作二次确认（F05）
  ===========================================================================
  「撤回 / 下架 / 归档 / 驳回」这类动作的共同点：
    · 点错了要改回来很麻烦（甚至改不回来）
    · 有的还必须说清"为什么"（驳回必须给意见、下架应当说明原因）

  封装成一个组件的理由就是"让这四个动作的交互一致"——
  如果每个业务域各写一套确认框，很快就会出现"撤回是 MessageBox、
  下架是个自制弹窗、归档问一句、驳回要填两行"这种五花八门的形态，
  用户每次都要重新判断"这个框点哪个键才是确认"。

  ---------------------------------------------------------------------------
  两种形态（由 requireReason 决定）
  ---------------------------------------------------------------------------
    requireReason = false  → `ElMessageBox.confirm`：
        轻量、居中、只有一个输入焦点。适合"不需要解释"的动作。
    requireReason = true   → `el-dialog` + `textarea`：
        为什么不用 MessageBox？因为 MessageBox **没有"禁用确认按钮"的能力**，
        只能等用户点了再弹一句"请填写原因"再关掉 —— 用户要被打断两次。
        换成 dialog 才能做到"原因没填时确认键直接是灰的、旁边写着为什么"。

  ---------------------------------------------------------------------------
  两个 Element Plus 的细节（都是踩过才知道的）
  ---------------------------------------------------------------------------
  ① `ElMessageBox.confirm` 的 `type` 只认 'warning' | 'info' | 'success' | 'error'，
     **没有 'danger'**。所以这里把 danger 映射成 'warning'（拿那个橙色感叹号图标），
     再用 `confirmButtonClass: 'el-button--danger'` 把确认按钮刷成红色。
     只传 `type: 'danger'` 的话图标会静默消失。
  ② `ElMessageBox` 是**函数式调用**，`unplugin-vue-components` 管不到它的样式，
     必须在使用处手工 import 样式（见下面 import 那一行）。
     漏了的表现是"弹出框没有背景遮罩、按钮也是原生样式"，看着像是自己写坏的。
-->
<template>
  <span class="confirm-action" :class="{ 'is-disabled': disabled }" @click="onTrigger">
    <slot />
  </span>

  <!-- requireReason 模式：用 dialog 而不是 MessageBox（原因见文件头注释） -->
  <el-dialog
    v-model="dialogVisible"
    :title="title"
    width="480px"
    :close-on-click-modal="false"
    append-to-body
    @closed="onDialogClosed"
  >
    <div class="confirm-action__body">
      <p v-if="content" class="confirm-action__content">{{ content }}</p>
      <el-input
        v-model="reason"
        type="textarea"
        :rows="3"
        :maxlength="reasonMaxLength"
        show-word-limit
        :placeholder="reasonPlaceholder"
      />
      <p class="confirm-action__hint" :class="{ 'is-error': reasonInvalid }">
        {{ reasonInvalid ? '请填写原因' : reasonHint }}
      </p>
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ cancelText }}</el-button>
      <el-button
        :type="type === 'danger' ? 'danger' : 'primary'"
        :disabled="reasonInvalid"
        @click="onDialogConfirm"
      >
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
/*
 * ⚠️ `ElMessageBox` 显式 import，**不依赖** vite 里的 AutoImport（ElementPlusResolver）。
 *   理由：它在这条路径上是"点下去就要弹框"的关键逻辑，依赖自动导入的话，
 *   万一解析不到只会是运行时 ReferenceError，属于最难查的一类。
 *   显式写出来还有另一个好处：看 import 就知道这个文件依赖了 Element Plus 的什么。
 *
 * ⚠️ 样式必须手工引：`ElMessageBox` 是**函数式调用**，按需引入的插件管不到它的样式。
 *   漏了的表现是"弹框没有背景遮罩、按钮是原生样式"，看着像是自己写坏的（F03 实测踩到）。
 *   即便插件也注入了同样的样式也没关系 —— CSS 模块导入是幂等的，不会重复生效。
 */
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

defineOptions({ name: 'ConfirmAction' })

const props = defineProps({
  /** 确认框标题 */
  title: {
    type: String,
    default: '确认操作'
  },
  /** 确认框正文（说明后果）。requireReason 模式下显示在输入框上方 */
  content: {
    type: String,
    default: ''
  },
  /** 'warning' 普通警示 / 'danger' 危险操作（确认按钮变红） */
  type: {
    type: String,
    default: 'warning',
    validator: (v) => ['warning', 'danger'].includes(v)
  },
  /** 确认按钮文案 */
  confirmText: {
    type: String,
    default: '确定'
  },
  /** 取消按钮文案 */
  cancelText: {
    type: String,
    default: '取消'
  },
  /** 是否强制填写原因（填了才允许确认） */
  requireReason: {
    type: Boolean,
    default: false
  },
  /** 原因输入框的提示语 */
  reasonPlaceholder: {
    type: String,
    default: '请填写原因，这条记录会留痕'
  },
  /** 原因不长于该长度时视为未填写 */
  reasonMinLength: {
    type: Number,
    default: 1
  },
  /** 原因最大长度 */
  reasonMaxLength: {
    type: Number,
    default: 200
  },
  /** 原因下方的辅助说明 */
  reasonHint: {
    type: String,
    default: '原因会记录到审核流水/操作日志里'
  },
  /** 触发元素整体禁用（比如没有权限时） */
  disabled: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['confirm', 'cancel'])

const dialogVisible = ref(false)
const reason = ref('')

const trimmedReason = computed(() => reason.value.trim())
const reasonInvalid = computed(
  () => props.requireReason && trimmedReason.value.length < props.reasonMinLength
)

function onTrigger() {
  if (props.disabled) return

  if (props.requireReason) {
    reason.value = ''
    dialogVisible.value = true
    return
  }

  openMessageBox()
}

/** 轻量确认路径：ElMessageBox.confirm 会返回 Promise，取消时 reject，必须接住 */
function openMessageBox() {
  ElMessageBox
    .confirm(props.content, props.title, {
      // 'danger' 不是 MessageBox 支持的 type，映射成 'warning' + 红色按钮（见文件头注释 ①）
      type: props.type === 'danger' ? 'warning' : props.type,
      confirmButtonText: props.confirmText,
      cancelButtonText: props.cancelText,
      confirmButtonClass: props.type === 'danger' ? 'el-button--danger' : '',
      closeOnClickModal: false
    })
    .then(() => {
      emit('confirm')
    })
    .catch(() => {
      // 取消 / 点遮罩关闭：ElMessageBox 会 reject，这里必须吃掉，
      // 否则控制台会出现一串「Uncaught (in promise) cancel」—— 看起来像报错，其实不是。
      emit('cancel')
    })
}

function onDialogConfirm() {
  if (reasonInvalid.value) return
  dialogVisible.value = false
  emit('confirm', trimmedReason.value)
}

function onDialogClosed() {
  reason.value = ''
}

/*
 * ⚠️ 这里曾经有一个 `touched` 标记，想让"点了确认却没填"才把提示转成危险色
 *    （即"别一上来就红字指责用户"）。**实测证明这个状态永远到达不了**：
 *    确认按钮是 :disabled 的，而**浏览器不会给 disabled 的按钮派发 click 事件**，
 *    所以 onDialogConfirm 根本不会被调用，touched 永远是 false —— 一段死代码。
 *    这条由 F05 的浏览器自动化断言抓出来（"尝试提交后提示转为危险色" 一直 FAIL）。
 *
 *    现在的做法：未填写时提示就是「请填写原因」+ 危险色，与旁边置灰的确认键配对。
 *    用户的动作只有两个 —— 要么填原因让按钮亮起来，要么点取消。
 *    不需要第三个中间状态，也就不需要 touched。
 *    教训：**"按钮置灰"和"点击后报错"是两种互斥的设计**，想同时做就会得到死代码。
 */
</script>

<style scoped>
.confirm-action {
  /* 不改变使用方原有布局：插槽里通常是 el-button，行内块即可 */
  display: inline-flex;
  align-items: center;
}

.confirm-action.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.confirm-action__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.confirm-action__content {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-regular, #606266);
}

.confirm-action__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary, #909399);
}

/* 未填写时的提示用危险色，但不加粗、不放大 —— 它只是提示，不是报错弹窗 */
.confirm-action__hint.is-error {
  color: var(--el-color-danger, #f56c6c);
}
</style>
