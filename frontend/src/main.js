import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
// Element Plus 官方中文语言包（分页器、日期选择器、表格空数据文案等都会变成中文）
// 若该路径在安装后报解析失败，备用路径为 'element-plus/dist/locale/zh-cn.mjs'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// Element Plus 样式必须先于业务样式引入，业务样式才能覆盖它
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// 顺序有讲究：pinia 必须在 router 之前注册。
// 因为 router 的全局前置守卫里会 useUserStore()，守卫可能在首个路由解析时就执行，
// 那时若 pinia 还没挂上，会报 "getActivePinia was called with no active Pinia"。
app.use(createPinia())
app.use(router)
// 全量注册 Element Plus（含中文语言包）。
// 本项目页面以表格/表单/弹窗为主，全量引入换来"组里谁都能直接用组件"，
// 代价是首包偏大 —— 对课程项目是划算的取舍。
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
