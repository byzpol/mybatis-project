import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
// Element Plus 按需引入（F03 起）
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Vite 配置 —— 企业级 CMS 内容管理系统（前端主干）
 *
 * 后端：Spring 5.3.31 + SpringMVC，部署在 Tomcat 9，context path = /cms-mis
 * 接口：统一前缀 /api/v1
 *
 * 用函数式配置（({ command })）而不是对象式，是因为「开发」与「构建」需要两套 base：
 *   · 开发（vite dev，command === 'serve'） → base = '/'，浏览器直接访问 http://localhost:5173/
 *   · 构建（vite build，command === 'build'）→ base = '/cms-mis/'
 * 如果构建也用 '/'，打包进 war 后页面会去请求 http://host:8080/assets/xxx.js，
 * 而 war 实际部署在 /cms-mis/ 下，全部 404 —— 这是纯 SSM（非 Spring Boot）项目最典型的踩坑点。
 * router 那边用 createWebHistory(import.meta.env.BASE_URL) 配合，两边自动一致。
 */
export default defineConfig(({ command }) => ({
  // ① 部署基路径：开发用根路径，构建时对齐 Tomcat 的 context path
  base: command === 'build' ? '/cms-mis/' : '/',

  plugins: [
    // 让 .vue 单文件组件能被 Vite 处理
    vue(),

    /**
     * Element Plus 按需引入之一：解析"函数式调用"的 API。
     *
     * 负责把代码里直接使用的 ElMessage / ElMessageBox / ElNotification / ElLoading
     * （模板里写 <el-xxx> 的组件不归它管，归下面的 Components）
     * 自动补上 import 语句，并连带补上该组件的样式。
     *
     * ⚠️ 本项目**没有**开启 vue / vue-router / pinia 的 API 自动导入（imports 留空），
     *    也就是说 `ref` / `computed` / `useRouter` 仍然要老老实实写 import。
     *    原因：组里有人是第一次写 Vue3，把 import 藏起来会让"这个东西从哪来的"
     *    变成一个需要靠记忆回答的问题；显式 import 对学习者更友好。
     *    想开启的话在 imports 里加 'vue', 'vue-router', 'pinia' 三行即可。
     */
    AutoImport({
      // 纯 JS 项目，不要生成 auto-imports.d.ts（那会给编辑器引入 TS 语义，与"不用 TS"的约定冲突）
      dts: false,
      imports: [],
      resolvers: [ElementPlusResolver()]
    }),

    /**
     * Element Plus 按需引入之二：解析模板里写出来的组件。
     *
     * 把 <el-button> 这类标签编译成"按需 import 对应组件 + 该组件样式"。
     * 实测这一步把构建后的 CSS 从约 361KB（F01 全量引入时）降到约 114KB
     * （F03 全部 css chunk 合计，见 docs/F03-后台布局与主题实测记录.md 第二节）。
     *
     * dirs 留空（默认是 ['src/components']）：本项目自己的组件一律显式 import。
     * 理由同上 —— 自动注册会让"这个 <PageTable> 从哪来的"变成隐式知识，
     * 而且 F04 会往 src/components 放共享组件，显式 import 才能一眼看出页面依赖了什么。
     * 指令（如 v-loading）由解析器兜住，不需要额外配置。
     */
    Components({
      dts: false,
      dirs: [],
      resolvers: [ElementPlusResolver()]
    })
  ],

  resolve: {
    alias: {
      // '@' → frontend/src，避免出现 '../../../' 这种深层相对路径
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // 不设 strictPort：端口被占用时 Vite 自动顺延到 5174/5175，
    // 后端的 CORS 配置已按 http://localhost:[*] 放开，顺延也能联调
    proxy: {
      /**
       * 开发期把 /api 透传给 Tomcat，浏览器看到的是同源请求，
       * 于是 JSESSIONID 这个 Cookie 能正常携带（跨域 + 第三方 Cookie 会被浏览器拦掉）。
       *
       * ⚠️ rewrite 不能省（F02 实测踩到）：后端用 Java 配置类部署在 Tomcat 的
       *    context path = /cms-mis 下，接口真身是
       *        http://localhost:8080/cms-mis/api/v1/health/ping
       *    而开发页地址是 http://localhost:5173/，前端只写 '/api/v1/...'。
       *    若原样转发 → 请求打到 http://localhost:8080/api/v1/...，
       *    Tomcat 里根本没有 /api 这个上下文，直接回它自己的 HTML 404 页
       *    （不是后端的统一 JSON，前端拿到的是 <!doctype html>，排查时极具迷惑性）。
       *    所以必须把 context path 补回去。
       *
       *    注意这和 base('/cms-mis/') 是两件事：base 管静态资源 URL，
       *    这里管接口转发，构建产物不经过 proxy，所以生产环境不需要这段（同源）。
       */
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => `/cms-mis${path}`,

        /**
         * ⚠️ 必须把 Set-Cookie 里的 Path 改回来（F03 实测踩到，症状极具迷惑性）
         *
         * 后端会话 Cookie 长这样：
         *     Set-Cookie: JSESSIONID=xxx; Path=/cms-mis; HttpOnly
         * 这个 Path 是**按后端真实上下文**写的，对生产环境完全正确
         * （只在 /cms-mis 下发送，范围更小更安全）。
         *
         * 但开发期浏览器的视角不一样：页面在 http://localhost:5173/，
         * 发出去的请求是 /api/v1/auth/login，浏览器**按这个路径**去匹配 Cookie 的 Path。
         * /cms-mis 匹配不上 /api/v1/...，于是这个 Cookie 被直接丢掉、根本不存。
         *
         * 后果：登录接口返回 200、响应里也有 Set-Cookie，看起来一切正常，
         *       但下一个请求（/auth/me）就是 401 —— 典型的"登录成功却一直是未登录"。
         *       而且因为响应体是成功 JSON，排查方向很容易被引到"后端 Session 配置错了"。
         *
         * 修法：代理层把 Path=/cms-mis 改写成 Path=/。
         * 只影响开发期（生产不经过 proxy，仍是后端原本的 Path=/cms-mis）。
         */
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const cookies = proxyRes.headers['set-cookie']
            if (!cookies) return
            proxyRes.headers['set-cookie'] = cookies.map((cookie) =>
              cookie.replace(/Path=\/cms-mis/gi, 'Path=/')
            )
          })
        }
      }
    }
  },

  build: {
    // ② 产物直接落进 war 的源码目录，这样 mvn package 出来的 war 自带前端
    outDir: '../src/main/webapp',
    // ③ 构建前清空该目录，避免删路由/删页面后旧 chunk 残留。
    //    ⚠️ 因此 src/main/webapp/ 里只能放构建产物，绝不能手工往里塞文件
    //    （本项目用 Java 配置类代替 web.xml，webapp 下没有需要保留的东西）。
    //    该目录已被 .gitignore 排除（构建产物不入库，同 target/）。
    emptyOutDir: true,
    // 生产包不开 sourcemap：体积小、不泄露源码；需要排查时临时改成 true
    sourcemap: false,
    // Element Plus 整包引入后单 chunk 会超默认 500KB 告警线，抬高阈值避免刷屏
    chunkSizeWarningLimit: 1500
  }
}))
