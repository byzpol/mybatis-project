import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

/**
 * 全局样式。
 *
 * ⚠️ 顺序有讲究：必须在 App.vue 之后 import。
 * F03 改成按需引入后，Element Plus 的组件样式是被各组件（含 App.vue 里的
 * ElConfigProvider）"顺带"引进来的，而 theme.css 里有一批
 * `--el-color-primary` 之类的变量覆盖 —— CSS 变量同优先级下**后加载的赢**。
 * 把 theme.css 放在 App.vue 之后，就能保证它排在 EP 的基础样式后面。
 * 反过来写的话，按钮/菜单会显示成 EP 默认的 #409EFF，主题就白改了。
 */
import './styles/theme.css'

const app = createApp(App)

/**
 * 顺序有讲究：pinia 必须在 router 之前注册。
 * 因为 router 的全局前置守卫里会 useUserStore()，守卫可能在首个路由解析时就执行，
 * 那时若 pinia 还没挂上，会报 "getActivePinia was called with no active Pinia"。
 */
app.use(createPinia())
app.use(router)
app.mount('#app')

/**
 * 关于 Element Plus 的注册方式（F03 变更）
 *
 * F01 用的是全量引入：
 *     import ElementPlus from 'element-plus'
 *     import 'element-plus/dist/index.css'
 *     app.use(ElementPlus, { locale: zhCn })
 * 好处是"组里谁都能直接用组件"，代价是首包偏大 —— 构建后 JS 约 1.0MB、CSS 约 361KB，
 * 而项目实际用到的组件不到 EP 的一半，其余全是死代码。
 *
 * F03 实测按需引入后：JS 约 426KB、CSS 约 114KB（全部 chunk 合计，含本项目的 theme.css）。
 *
 * F03 改为按需引入（配置在 vite.config.js，靠 unplugin-vue-components 解析模板里的 <el-xxx>）：
 *   · 不再需要 app.use(ElementPlus)，也不再 import 整包 CSS
 *   · 中文语言包改由 App.vue 的 <el-config-provider> 提供（按需模式下没有 app.use 这一步）
 *   · 模板里的 <el-xxx> 与 v-loading 由插件自动 import 并带上对应样式
 *   · **函数式调用的 ElMessage / ElLoading / ElMessageBox 插件管不到**，
 *     必须在使用处手工 import 它们的 style（见 api/request.js 与 layout/components/Navbar.vue）
 *
 * 说明详见 docs/ElementPlus按需引入说明.md。
 */
