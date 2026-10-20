# Element Plus 按需引入说明

> 手册 F03 第四节要求写这份说明。本文回答三个问题：
> 为什么用 `unplugin` 而不是全量引入、怎么装怎么配、以及按需模式下**必须自己动手补的三件事**。
>
> 实测数据与踩坑过程见 `docs/F03-后台布局与主题实测记录.md` 第二节。

---

## 一、为什么不直接用全量引入

全量引入只有三行，最省事：

```js
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
app.use(ElementPlus, { locale: zhCn })
```

代价是**把整个组件库塞进产物**，而项目实际用到的组件不到一半，其余全是死代码。
实测（本项目 F01 → F03）：

| 指标 | 全量（F01） | 按需（F03） | 变化 |
|---|---|---|---|
| JS（全部 chunk 合计） | 约 1020 KB | **426 KB** | −58% |
| CSS（全部 chunk 合计） | 约 361 KB | **114 KB** | −68% |

对一个 4 人课程项目来说，这个差距还没到"不能用"的程度 —— 但改造成本只有**一段配置 + 三处补丁**，
而且早改比晚改便宜。**留到后面再改的话，已经写好的页面越多，越容易漏掉某个组件的样式。**

如果只是个人练手的小 demo，全量引入完全够用，不必为了"显得专业"去改。

---

## 二、怎么装、怎么配

### 2.1 装两个开发依赖

```bash
cd frontend
npm i -D unplugin-auto-import unplugin-vue-components
```

本项目实际装到的版本：`unplugin-auto-import@^21.1.0`、`unplugin-vue-components@^32.1.0`。

### 2.2 在 `vite.config.js` 里加两个插件

```js
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),

    AutoImport({
      dts: false,                        // 纯 JS 项目，不生成 auto-imports.d.ts
      imports: [],                       // 不自动导入 vue / vue-router / pinia
      resolvers: [ElementPlusResolver()]
    }),

    Components({
      dts: false,
      dirs: [],                          // 本项目自己的组件也显式 import
      resolvers: [ElementPlusResolver()]
    })
  ]
})
```

**两个插件的分工一定要分清**（这是最容易踩坑的地方）：

| 插件 | 管什么 | 例子 |
|---|---|---|
| `Components` | **模板里写出来的标签** | `<el-button>`、`<el-table>`、`v-loading` 指令 |
| `AutoImport` | **JS 里函数式调用的 API** | `ElMessage.success()`、`ElMessageBox.confirm()` |

### 2.3 三个"故意留空/关掉"的配置，以及为什么

- **`dts: false`** —— 本项目不用 TypeScript。默认会生成 `auto-imports.d.ts` / `components.d.ts`，
  那会给编辑器引入 TS 语义，和"纯 JS"的约定冲突，也会在仓库里多出两个生成物。
- **`imports: []`（不自动导入 vue / vue-router / pinia）** —— `ref` / `computed` / `useRouter`
  仍然要老老实实写 `import`。原因很实际：**组里有人是第一次写 Vue3**。
  把 import 藏起来会让"这个东西从哪来的"变成需要靠记忆回答的问题。
  想开启的话，在 `imports` 里加 `'vue', 'vue-router', 'pinia'` 三行即可。
- **`dirs: []`（不自动注册本项目组件）** —— 默认值是 `['src/components']`，
  会自动注册该目录下的组件。F04 会往这里放 `PageTable.vue` / `StatusTag.vue`，
  我们选择**显式 import**，这样打开一个页面就能一眼看出它依赖了哪些共享组件。
  自动注册会让这变成隐式知识。

这三条都是**可选项**，不是按需引入必需的。想省事就把它们去掉，功能不会受影响。

---

## 三、按需模式下必须自己动手补的三件事 ⚠️

### 3.1 函数式调用的组件，样式要手工引

`ElMessage` / `ElMessageBox` / `ElLoading` 是"用 JS 调出来的"，**模板里根本没有对应标签**，
所以 `Components` 的解析器没有机会介入，样式不会被带进来。

**症状**：调用能成功、文字也在，但**完全没有样式** —— 一个透明的提示浮在页面上。
很容易误判成"CSS 没加载"或"主题变量写错了"。

**解法**：在使用处显式引样式。本项目已在这两处写死：

```js
// src/api/request.js（统一错误提示）
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/loading/style/css'

// src/layout/components/Navbar.vue（退出登录二次确认）
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
```

### 3.2 中文语言包得换挂载点

全量模式下是这样挂的：

```js
app.use(ElementPlus, { locale: zhCn })
```

按需模式下**没有 `app.use(ElementPlus)` 这一步**，语言包就没地方挂了。
改用官方推荐的 `ElConfigProvider`，在 `App.vue` 包一层：

```vue
<template>
  <el-config-provider :locale="zhCn" :z-index="2000">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
```

**不配它的后果**：分页器显示 "Go to"、表格空数据显示 "No Data"、
日期选择器全是英文 —— 中文界面里混着英文，一眼能看出没做完。

> 本步（F03）**没有构造到 EP 自带文案的场景**（用到的 `el-descriptions` / `el-form` /
> `el-dropdown` / `el-menu` 文案都来自我们自己的模板），所以"分页器是中文"这件事
> **待 F04 的 `PageTable` 接入分页组件后再实测确认**。配置已按官方写法挂好。

### 3.3 `theme.css` 必须放在 `App.vue` 之后 import

按需引入后，EP 的样式是被各个组件"顺带"引进来的，**加载顺序不再可控**；
而 `theme.css` 里有一批 `--el-color-primary` 之类的变量覆盖。
**CSS 变量在同优先级下是后加载的赢** ——

```js
// src/main.js
import App from './App.vue'
import './styles/theme.css'   // ← 必须在 App.vue 之后
```

放反了的表现是：按钮和菜单显示成 EP 默认的 `#409EFF`，主题整个白改。
本项目已在验证脚本里加了断言防回归：

```
PASS  主题已覆盖 EP 主色（--el-color-primary=#2f6fed）  #2f6fed
PASS  EP 派生色同时被覆盖（light-3=#6d9af2，防 hover 变回默认蓝）  #6d9af2
```

---

## 四、以后新增页面怎么写

**什么都不用做。** 直接在模板里写 `<el-xxx>`，插件会自动 import 组件和它的样式：

```vue
<template>
  <el-table :data="rows">...</el-table>
  <el-pagination ... />
</template>
<!-- 不需要写任何 import -->
```

只有两种情况需要动手：

1. 用到 `ElMessage` / `ElMessageBox` / `ElLoading` / `ElNotification` ——
   在**该文件顶部**加一行对应的 `import 'element-plus/es/components/xxx/style/css'`。
2. 用到本项目的共享组件（如 F04 的 `PageTable`）—— 显式 `import PageTable from '@/components/PageTable.vue'`。

---

## 五、怎么确认配置还在生效

改完配置后重新构建，看产物大小就知道：

```bash
cd frontend && npm run build
ls -l ../src/main/webapp/assets/*.css | awk '{s+=$5} END {print s " 字节"}'
```

- 数值在 **110~130 KB** 附近 → 按需引入生效。
- 跳到 **350 KB 以上** → 多半是配置丢了，或有地方又 `import 'element-plus/dist/index.css'` 了。

另外可以直接搜有没有漏网的整包引入：

```bash
grep -rn "element-plus/dist" frontend/src frontend/vite.config.js
```

**预期只有一条命中，且必须是注释**：

```
frontend/src/main.js:35: *     import 'element-plus/dist/index.css'
```

那一行在 `main.js` 的块注释里，是**记录 F01 当初的全量写法**（作为"为什么改"的对照）。
判定方法：看行首有没有 `*` —— 有就是注释，不算真引用。
真引用会出现在代码行上（行首是 `import`），那才是要清理的。

**更省事的判据是直接看构建产物的 CSS 总量**（上一段那条命令）：
只要在 110~130 KB 区间，就说明没有整包样式混进来。
`main.js` 注释里也确实写了"实测按需引入后 CSS 约 114KB"，两者互相印证。
