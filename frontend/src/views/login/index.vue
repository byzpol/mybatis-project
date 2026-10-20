<!--
  登录页（F03）
  左右分栏：左侧品牌区（渐变主色），右侧登录表单卡片。

  ⚠️ 校验规则以**后端 LoginDTO 为准**，不是照抄手册：
     后端 LoginDTO：username @NotBlank + @Size(max=50)；password @NotBlank + @Size(min=6,max=64)
     手册 F03 写的是"用户名密码长度 4~20"，与后端不一致。
     前端校验比后端松 → 用户提交才 400；比后端严 → 明明能登录却不让提交。
     两边必须是同一套数，所以这里严格对齐后端。
-->
<template>
  <div class="login">
    <!-- ---------------- 左：品牌区 ---------------- -->
    <div class="login__brand">
      <div class="login__brand-inner">
        <div class="login__logo" aria-hidden="true">C</div>
        <h1 class="login__brand-title">CMS 内容管理系统</h1>
        <p class="login__brand-desc">
          内容创作 · 两级审核 · 定时发布 · 全链路操作留痕
        </p>
        <ul class="login__features">
          <li>投稿人 → 编辑初审 → 主编终审，状态机驱动</li>
          <li>细粒度权限到接口级，前后端双重校验</li>
          <li>版本快照与回滚，操作日志可追溯</li>
        </ul>
      </div>
    </div>

    <!-- ---------------- 右：表单区 ---------------- -->
    <div class="login__panel">
      <div class="login__form-wrap">
        <h2 class="login__title">账号登录</h2>
        <p class="login__subtitle">请使用管理员分配的账号登录</p>

        <!-- 用 el-form 的 validate 而不是自己写 if：统一走 Element Plus 的错误提示样式 -->
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model.trim="form.username"
              placeholder="请输入用户名"
              autocomplete="username"
              :prefix-icon="User"
              clearable
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              :prefix-icon="Lock"
              show-password
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <div class="login__row">
            <el-checkbox v-model="form.remember">记住用户名</el-checkbox>
          </div>

          <el-button
            type="primary"
            class="login__submit"
            size="large"
            :loading="loading"
            @click="handleSubmit"
          >
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form>

        <!-- 演示账号：种子数据里的 4 个身份，方便答辩时切换角色看不同菜单。
             ⚠️ 这是课程演示用的，生产环境必须删掉这一块。 -->
        <el-alert type="info" :closable="false" class="login__demo">
          <template #title>
            <span class="login__demo-title">演示账号（密码均为 123456）</span>
          </template>
          <div class="login__demo-list">
            <span
              v-for="acc in demoAccounts"
              :key="acc.username"
              class="login__demo-item"
              @click="fillDemo(acc.username)"
            >
              {{ acc.username }}（{{ acc.label }}）
            </span>
          </div>
        </el-alert>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)

/** 记住用户名的 localStorage key（加前缀，避免同域下别的项目撞键） */
const REMEMBER_KEY = 'cms-mis:login:username'

const form = reactive({
  username: '',
  password: '',
  remember: false
})

/**
 * 校验规则 —— 数值与后端 LoginDTO 的注解逐条对齐（见文件头注释）。
 * 密码不做"必须含大小写数字符号"的强度校验：那是后端密码策略的事，
 * 登录时只该校验"格式合法、别让后端白跑一次 BCrypt"。
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名长度不能超过 50', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度应在 6~64 之间', trigger: 'blur' }
  ]
}

const demoAccounts = [
  { username: 'author', label: '投稿人' },
  { username: 'editor', label: '编辑' },
  { username: 'chief', label: '主编' },
  { username: 'admin', label: '管理员' }
]

/** 点演示账号：只填用户名，密码留空由用户（或演示时）自己输 */
function fillDemo(username) {
  form.username = username
}

function handleSubmit() {
  if (loading.value) return // 防连点：登录请求不是幂等的，重复提交会多写一条登录日志
  formRef.value?.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      await userStore.login({ username: form.username, password: form.password })

      // 记住用户名。⚠️ 只记用户名，不记密码 ——
      // 本项目是 Session 认证，"记住我"无法延长服务端会话（后端 30 分钟超时），
      // 它的价值只是"下次不用重输用户名"。把密码存 localStorage 是明确的安全问题。
      if (form.remember) {
        localStorage.setItem(REMEMBER_KEY, form.username)
      } else {
        localStorage.removeItem(REMEMBER_KEY)
      }

      // 跳回被拦下的原页面；没有就回首页
      const redirect = route.query.redirect
      await router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
    } catch (e) {
      // ⚠️ 这里**不弹提示**：axios 响应拦截器（F02）已经统一弹过一次了。
      //    再弹一次就是同一个错误两条红条 —— 那正是 F02 明确要避免的。
      //    这里只需收掉 loading，让用户能改密码重试。
    } finally {
      loading.value = false
    }
  })
}

onMounted(() => {
  // 回填上次记住的用户名
  const remembered = localStorage.getItem(REMEMBER_KEY)
  if (remembered) {
    form.username = remembered
    form.remember = true
  }
})
</script>

<style scoped>
.login {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ---------------- 左：品牌区 ---------------- */
.login__brand {
  position: relative;
  display: flex;
  flex: 1 1 55%;
  align-items: center;
  justify-content: center;
  padding: 40px;
  overflow: hidden;
  /* 渐变主色 + 两个径向光斑：纯 CSS，不引图片 —— 满足"无外链资源"要求 */
  background:
    radial-gradient(circle at 15% 20%, rgba(255, 255, 255, 0.16) 0%, transparent 45%),
    radial-gradient(circle at 85% 80%, rgba(255, 255, 255, 0.12) 0%, transparent 40%),
    linear-gradient(135deg, #2f6fed 0%, #1b4fbf 55%, #16307a 100%);
}

.login__brand-inner {
  max-width: 460px;
  color: #fff;
}

.login__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  margin-bottom: 28px;
  font-size: 30px;
  font-weight: 700;
  color: var(--app-primary);
  background-color: #fff;
  border-radius: 14px;
}

.login__brand-title {
  margin: 0 0 12px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 1px;
}

.login__brand-desc {
  margin: 0 0 32px;
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.85);
}

.login__features {
  padding: 0;
  margin: 0;
  list-style: none;
}

.login__features li {
  position: relative;
  padding-left: 22px;
  margin-bottom: 12px;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.78);
}

.login__features li::before {
  position: absolute;
  top: 8px;
  left: 4px;
  width: 6px;
  height: 6px;
  content: '';
  background-color: rgba(255, 255, 255, 0.7);
  border-radius: 50%;
}

/* ---------------- 右：表单区 ---------------- */
.login__panel {
  display: flex;
  flex: 1 1 45%;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background-color: var(--app-bg-card);
}

.login__form-wrap {
  width: 360px;
  max-width: 100%;
}

.login__title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: var(--app-text-1);
}

.login__subtitle {
  margin: 0 0 28px;
  font-size: 13px;
  color: var(--app-text-3);
}

.login__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.login__submit {
  width: 100%;
  font-size: 15px;
  letter-spacing: 2px;
}

.login__demo {
  margin-top: 24px;
}

.login__demo-title {
  font-size: 13px;
  font-weight: 600;
}

.login__demo-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 6px;
}

.login__demo-item {
  font-size: 12px;
  color: var(--app-text-2);
  cursor: pointer;
  transition: color var(--app-transition);
}

.login__demo-item:hover {
  color: var(--app-primary);
  text-decoration: underline;
}

/* ---------------- 窄屏：收起品牌区，只留表单 ---------------- */
@media (max-width: 900px) {
  .login__brand {
    display: none;
  }

  .login__panel {
    flex-basis: 100%;
  }
}
</style>
