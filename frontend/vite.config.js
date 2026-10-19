import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
    vue()
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
      // 开发期把 /api 透传给 Tomcat，浏览器看到的是同源请求，
      // 于是 JSESSIONID 这个 Cookie 能正常携带（跨域 + 第三方 Cookie 会被浏览器拦掉）。
      // 注意：不做 rewrite —— 后端 URL 本身就带 /api/v1 前缀，原样转发即可。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
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
