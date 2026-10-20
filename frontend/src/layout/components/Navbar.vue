<!--
  顶部导航（F03）
  左：折叠按钮 + 面包屑
  右：头像（昵称首字）+ 昵称 + 下拉（个人信息 / 退出登录）
  高 56px，白底 + 底部 1px 边框
-->
<template>
  <div class="navbar">
    <!-- ---------- 左侧 ---------- -->
    <div class="navbar__left">
      <el-button
        class="navbar__collapse"
        text
        :title="collapsed ? '展开菜单' : '收起菜单'"
        @click="emit('toggle')"
      >
        <el-icon :size="18">
          <Expand v-if="collapsed" />
          <Fold v-else />
        </el-icon>
      </el-button>

      <Breadcrumb />
    </div>

    <!-- ---------- 右侧 ---------- -->
    <div class="navbar__right">
      <el-dropdown trigger="click" @command="handleCommand">
        <div class="navbar__user">
          <!-- 头像用昵称首字：项目没有头像上传功能，文字头像比写死的默认图片更合适，
               也顺带满足"不引外链资源"。有 avatar 字段时优先用图片。 -->
          <el-avatar :size="30" :src="userStore.avatar || undefined" class="navbar__avatar">
            {{ userStore.avatarText }}
          </el-avatar>
          <span class="navbar__nickname">{{ userStore.nickname || '未登录' }}</span>
          <el-icon :size="12" class="navbar__caret"><ArrowDown /></el-icon>
        </div>

        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon>
              个人信息
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
// 按需引入模式下，模板里的 <el-xxx> 由 unplugin 自动带样式，
// 但 ElMessage / ElMessageBox 是**函数式调用**，解析器管不到，必须手工引样式，
// 否则弹窗会以"无样式"的裸 DOM 出现。谁在这里新增函数式组件就补一行。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { ArrowDown, Expand, Fold, SwitchButton, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import Breadcrumb from './Breadcrumb.vue'

defineProps({
  /** 侧边栏是否已折叠，只用来决定按钮图标是 Fold 还是 Expand */
  collapsed: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['toggle'])

const router = useRouter()
const userStore = useUserStore()

/**
 * 下拉菜单命令处理。
 * 用 command 而不是给每个 item 写 @click：el-dropdown-item 的 click 事件在
 * 某些版本上会被内部滚动条/键盘交互吞掉，command 是官方推荐的统一入口。
 */
async function handleCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
    return
  }

  if (command === 'logout') {
    // 先确认再登出：误点一下就被踢出去很恼人
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消'
      })
    } catch (e) {
      return // 用户点了取消
    }

    // logout() 内部是 try/finally：即使服务端登出失败，本地态也会被清干净
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}
</script>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
}

.navbar__left,
.navbar__right {
  display: flex;
  align-items: center;
}

.navbar__left {
  gap: 8px;
  min-width: 0; /* 允许面包屑在窄屏被压缩而不是把右侧用户区挤出去 */
}

.navbar__collapse {
  padding: 4px;
  color: var(--app-text-2);
}

.navbar__collapse:hover {
  color: var(--app-primary);
  background-color: transparent;
}

.navbar__user {
  display: flex;
  gap: 8px;
  align-items: center;
  height: 40px;
  padding: 0 8px;
  cursor: pointer;
  border-radius: var(--app-radius-sm);
  outline: none;
  transition: background-color var(--app-transition);
}

.navbar__user:hover {
  background-color: var(--app-bg-page);
}

.navbar__avatar {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background-color: var(--app-primary);
}

.navbar__nickname {
  max-width: 120px;
  overflow: hidden;
  font-size: 14px;
  color: var(--app-text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.navbar__caret {
  color: var(--app-text-3);
}

/* 窄屏（<768px）把昵称藏起来，只留头像 —— 52 字符的昵称会把面包屑挤没 */
@media (max-width: 768px) {
  .navbar__nickname {
    display: none;
  }
}
</style>
