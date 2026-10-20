/**
 * F03 布局与主题的浏览器自动化验证
 *
 * 【归档说明】本文件原是 F03 期间写在 `target/f03-check/` 下的一次性脚本，
 * 验证通过后按"证据留档"移到这里（F03 原始输出见同目录
 * `F03-浏览器自动化验证原始输出.md`）。脚本里 OUT_DIR / Chrome 路径是当时的
 * 绝对路径，直接搬到别的机器跑不起来；F04~F06 要用时改这两处即可。
 * 它仍值得留存的原因：CDP 直驱 Chrome 的这套封装（WebSocket 说 DevTools Protocol、
 * SPA 内 pushState+popstate 导航、只等不导航的 waitFor）后面几步还要反复用。
 *
 * 验证对象：**Tomcat 9 上部署的 war**（http://localhost:8080/cms-mis/），
 *   而不是 vite dev server。两个原因：
 *   ① 这才是交付物的真实运行形态，证据更有说服力；
 *   ② 本环境下 vite dev server 不稳定 —— 它的依赖预构建要批量删除
 *      node_modules/.vite/deps_temp_*（80 个文件），会被 CLI 的批量删除保护
 *      （SAFE_DELETE_BULK_CONFIRM_REQUIRED，阈值 50）打断进程，服务会在几十秒后猝死。
 *      `vite build` 不走依赖预构建，所以构建一直是正常的。
 *
 * ⚠️ 已知限制（S22 才修）：SPA 用 createWebHistory，Tomcat 没有"未匹配路径回退 index.html"
 *   的配置，所以**直接刷新/直开子路由会 404**。因此本脚本里所有路由切换都靠
 *   「加载 /cms-mis/ → 页面内点击菜单/程序化导航」完成，不做深层路径的整页加载。
 *   最后会专门断言一次这个 404，作为交给 S22 的证据。
 *
 * 跑法：node cdp-verify.mjs
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9334
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f03-check'
const PROFILE = `${OUT_DIR}/chrome-profile-war`

mkdirSync(OUT_DIR, { recursive: true })

const results = []
function ok(name, cond, extra = '') {
  results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? `  ${extra}` : ''}`)
}

const chrome = spawn(
  CHROME,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--no-proxy-server',
    '--no-first-run',
    '--disable-extensions',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    'about:blank'
  ],
  { stdio: 'ignore' }
)

async function waitChrome() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return await r.json()
    } catch (e) {
      /* not ready */
    }
    await sleep(500)
  }
  throw new Error('Chrome DevTools 端口未就绪')
}

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let id = 0
  const pending = new Map()
  const events = []
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id != null && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      if (msg.error) reject(new Error(JSON.stringify(msg.error)))
      else resolve(msg.result)
    } else if (msg.method) {
      events.push(msg)
    }
  })
  const ready = new Promise((res, rej) => {
    ws.addEventListener('open', res)
    ws.addEventListener('error', rej)
  })
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const myId = ++id
      pending.set(myId, { resolve, reject })
      ws.send(JSON.stringify({ id: myId, method, params }))
    })
  return { ws, send, ready, events }
}

async function main() {
  const version = await waitChrome()
  console.log('Chrome:', version.Browser, '| 目标:', BASE)

  const t = await (
    await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })
  ).json()
  const cdp = connect(t.webSocketDebuggerUrl)
  await cdp.ready
  const { send } = cdp

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error('页面内异常: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text))
    return r.result.value
  }
  const setViewport = (width, height) =>
    send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  const goto = async (url, waitExpr, timeout = 20000) => {
    await send('Page.navigate', { url })
    return waitFor(waitExpr, timeout)
  }
  /** 只等待、不导航。用于"点击后页面自己会走"的场景 —— 此时再 Page.navigate 会把请求打断 */
  const waitFor = async (waitExpr, timeout = 20000) => {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      await sleep(250)
      try {
        if (await evalJs(waitExpr)) return true
      } catch (e) {
        /* 导航中忽略 */
      }
    }
    return false
  }
  const shot = async (name) => {
    const { data } = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/${name}.png`, Buffer.from(data, 'base64'))
  }
  /** SPA 内程序化导航：vue-router 监听 popstate，所以 pushState + 派发 popstate 就能驱动它 */
  const spaPush = async (path) => {
    await evalJs(`(() => {
      history.pushState({}, '', ${JSON.stringify(BASE + path)})
      window.dispatchEvent(new PopStateEvent('popstate'))
      return true
    })()`)
  }
  const fillForm = (user, pass) =>
    evalJs(`(() => {
      const setVal = (el, val) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(el, val)
        el.dispatchEvent(new Event('input', { bubbles: true }))
      }
      const inputs = document.querySelectorAll('.login__form-wrap .el-input__inner')
      setVal(inputs[0], ${JSON.stringify(user)})
      setVal(inputs[1], ${JSON.stringify(pass)})
      return inputs.length
    })()`)
  const clickLogin = async () => {
    await sleep(250)
    await evalJs(`document.querySelector('.login__submit').click()`)
  }

  // =========================================================================
  // 0. 先在无登录态下走"加载根路径 → 守卫踢到 /login"
  //    （不能直开 /cms-mis/login —— 那是客户端路由，整页加载会 404，见文件头说明）
  // =========================================================================
  await setViewport(1366, 768)
  const reachedLogin = await goto(`${BASE}/`, `location.pathname.endsWith('/login')`, 25000)
  ok('未登录加载根路径后被守卫踢到 /login', reachedLogin)

  // =========================================================================
  // 1. 登录页：两种分辨率下的滚动条与主题色
  // =========================================================================
  for (const [w, h] of [
    [1366, 768],
    [1920, 1080]
  ]) {
    await setViewport(w, h)
    await sleep(500)
    const m = await evalJs(`(() => {
      const de = document.documentElement
      const brand = document.querySelector('.login__brand')
      const btn = document.querySelector('.login__submit')
      return {
        scrollW: de.scrollWidth, clientW: de.clientWidth,
        scrollH: de.scrollHeight, clientH: de.clientHeight,
        brandVisible: !!brand && getComputedStyle(brand).display !== 'none',
        btnBg: btn ? getComputedStyle(btn).backgroundColor : null,
        title: document.title
      }
    })()`)
    ok(`登录页 ${w}x${h} 无横向滚动条`, m.scrollW <= m.clientW, `${m.scrollW}/${m.clientW}`)
    ok(`登录页 ${w}x${h} 无纵向滚动条`, m.scrollH <= m.clientH, `${m.scrollH}/${m.clientH}`)
    ok(`登录页 ${w}x${h} 品牌区可见`, m.brandVisible)
    ok(`登录页 ${w}x${h} 主按钮是主题主色 #2f6fed`, m.btnBg === 'rgb(47, 111, 237)', String(m.btnBg))
    await shot(`login-${w}x${h}`)
  }

  // =========================================================================
  // 2. 登录（真实后端）：主编身份
  // =========================================================================
  await setViewport(1440, 900)
  await sleep(300)
  const inputCount = await fillForm('chief', '123456')
  ok('登录表单定位到 2 个输入框', inputCount === 2, String(inputCount))
  await clickLogin()
  // ⚠️ 这里只能"等"，不能再 Page.navigate —— 整页导航会把还没落地的登录 XHR 直接掐断，
  //    表现成"点了登录没反应、也没报错"，我第一次就是这么被骗过去的。
  const loggedIn = await waitFor(`!!document.querySelector('.sidebar')`, 25000)
  ok('主编登录后进入后台（侧边栏已渲染）', loggedIn)
  if (!loggedIn) {
    const net = cdp.events
      .filter((e) => e.method === 'Network.requestWillBeSent' || e.method === 'Network.responseReceived')
      .map((e) => {
        const p = e.params
        const url = p.request ? p.request.url : p.response.url
        return `${e.method === 'Network.requestWillBeSent' ? '→' : '←'} ${p.request ? p.request.method : p.response.status} ${url}`
      })
      .filter((s) => s.includes('/auth/') || s.includes('/api/'))
    const detail = await evalJs(`(() => {
      const btn = document.querySelector('.login__submit')
      return {
        path: location.pathname,
        messages: [...document.querySelectorAll('.el-message')].map(e => e.innerText),
        formErrors: [...document.querySelectorAll('.el-form-item__error')].map(e => e.innerText),
        inputs: [...document.querySelectorAll('.login__form-wrap .el-input__inner')].map(e => e.value),
        btnText: btn ? btn.innerText : null,
        btnClass: btn ? btn.className : null,
        btnDisabled: btn ? btn.disabled : null,
        overlay: !!document.querySelector('.el-loading-mask')
      }
    })()`)
    console.log('诊断(页面):', JSON.stringify(detail))
    console.log('诊断(网络):', JSON.stringify(net, null, 1))
  }

  const layout = await evalJs(`(() => {
    const root = document.documentElement
    const aside = document.querySelector('.app-layout__aside')
    const active = document.querySelector('.sidebar .el-menu-item.is-active')
    const before = active ? getComputedStyle(active, '::before') : null
    const header = document.querySelector('.app-layout__header')
    return {
      path: location.pathname,
      asideW: aside ? Math.round(aside.getBoundingClientRect().width) : 0,
      sidebarBg: getComputedStyle(document.querySelector('.sidebar')).backgroundColor,
      items: [...document.querySelectorAll('.sidebar .el-menu-item')].map(e => e.innerText.trim()),
      subs: [...document.querySelectorAll('.sidebar .el-sub-menu__title')].map(e => e.innerText.trim()),
      activeText: active ? active.innerText.trim() : null,
      beforeW: before ? before.width : null,
      beforeColor: before ? before.backgroundColor : null,
      headerH: header ? Math.round(header.getBoundingClientRect().height) : 0,
      headerBg: header ? getComputedStyle(header).backgroundColor : null,
      mainBg: getComputedStyle(document.querySelector('.app-layout__main')).backgroundColor,
      nickname: (document.querySelector('.navbar__nickname') || {}).innerText,
      avatarText: (document.querySelector('.navbar__avatar') || {}).innerText,
      crumb: [...document.querySelectorAll('.breadcrumb .el-breadcrumb__inner')].map(e => e.innerText.trim()),
      elPrimary: getComputedStyle(root).getPropertyValue('--el-color-primary').trim(),
      elPrimaryLight3: getComputedStyle(root).getPropertyValue('--el-color-primary-light-3').trim(),
      title: document.title,
      scrollW: root.scrollWidth, clientW: root.clientWidth,
      sidebarOverflowX: (() => { const w = document.querySelector('.sidebar .el-scrollbar__wrap'); return w ? w.scrollWidth - w.clientWidth : null })(),
      // ⚠️ 判"滚动条是否可见"必须看 computed display，不能看元素是否存在 ——
      //    Element Plus 的 Scrollbar 两个 bar 元素**永远**渲染在 DOM 里，靠 v-show/display 控制显隐。
      //    实测：折叠态 .el-scrollbar__bar.is-horizontal 存在但 display=none、offsetW=0。
      anyHBar: [...document.querySelectorAll('.sidebar .el-scrollbar__bar.is-horizontal')].some((e) => getComputedStyle(e).display !== 'none')
    }
  })()`)

  ok('登录后落在 /cms-mis/articles/list', layout.path === '/cms-mis/articles/list', layout.path)
  ok('侧边栏展开宽度 220px', layout.asideW === 220, String(layout.asideW))
  ok('侧边栏深色底 #1f2733', layout.sidebarBg === 'rgb(31, 39, 51)', layout.sidebarBg)
  ok('顶栏高 56px 且白底', layout.headerH === 56 && layout.headerBg === 'rgb(255, 255, 255)', `${layout.headerH} / ${layout.headerBg}`)
  ok('内容区底色 #f5f7fa', layout.mainBg === 'rgb(245, 247, 250)', layout.mainBg)
  ok('主题已覆盖 EP 主色（--el-color-primary=#2f6fed）', layout.elPrimary === '#2f6fed', layout.elPrimary)
  ok('EP 派生色同时被覆盖（light-3=#6d9af2，防 hover 变回默认蓝）', layout.elPrimaryLight3 === '#6d9af2', layout.elPrimaryLight3)
  ok('选中项左侧 3px 主色高亮条', layout.beforeW === '3px' && layout.beforeColor === 'rgb(47, 111, 237)', `${layout.beforeW} / ${layout.beforeColor}`)
  ok('面包屑 = 首页 / 文章管理 / 文章列表', layout.crumb.join('/') === '首页/文章管理/文章列表', layout.crumb.join(' / '))
  ok('浏览器标签 = 文章列表 · CMS 内容管理系统', layout.title === '文章列表 · CMS 内容管理系统', layout.title)
  ok('右上角昵称与文字头像正确', layout.nickname === '王主编' && layout.avatarText === '王', `${layout.nickname} / ${layout.avatarText}`)
  ok('大屏无横向滚动条', layout.scrollW <= layout.clientW, `${layout.scrollW}/${layout.clientW}`)
  console.log('主编可见分组:', JSON.stringify(layout.subs), '菜单项:', JSON.stringify(layout.items))
  ok('菜单按权限过滤：主编看不到「用户与权限」', !layout.subs.includes('用户与权限'), layout.subs.join(','))
  ok('菜单按权限过滤：主编看得到「文章管理」', layout.subs.includes('文章管理'), layout.subs.join(','))
  await shot('layout-chief-1440x900')

  // =========================================================================
  // 3. 面包屑随路由变化（点菜单）
  // =========================================================================
  await spaPush('/articles/audit')
  await sleep(900)
  const c2 = await evalJs(`({
    path: location.pathname,
    crumb: [...document.querySelectorAll('.breadcrumb .el-breadcrumb__inner')].map(e => e.innerText.trim()),
    title: document.title,
    active: (document.querySelector('.sidebar .el-menu-item.is-active') || {}).innerText
  })`)
  ok('跳到 /cms-mis/articles/audit', c2.path === '/cms-mis/articles/audit', c2.path)
  ok('面包屑变成 首页 / 文章管理 / 待我审核', c2.crumb.join('/') === '首页/文章管理/待我审核', c2.crumb.join(' / '))
  ok('菜单选中态跟着变', String(c2.active).trim() === '待我审核', String(c2.active))
  ok('浏览器标签跟着变', c2.title === '待我审核 · CMS 内容管理系统', c2.title)

  await spaPush('/profile')
  await sleep(700)
  const c3 = await evalJs(`({
    crumb: [...document.querySelectorAll('.breadcrumb .el-breadcrumb__inner')].map(e => e.innerText.trim()),
    activeCount: document.querySelectorAll('.sidebar .el-menu-item.is-active').length
  })`)
  ok('无分组的页面面包屑 = 首页 / 个人信息', c3.crumb.join('/') === '首页/个人信息', c3.crumb.join(' / '))
  ok('/profile 不在菜单里（hidden）→ 无菜单高亮项', c3.activeCount === 0, String(c3.activeCount))

  // =========================================================================
  // 4. 前端权限拦截：主编直接访问 /users → /403
  // =========================================================================
  await spaPush('/articles/list')
  await sleep(700)
  await spaPush('/users')
  await sleep(900)
  const fb = await evalJs(`({ path: location.pathname, has403: !!document.querySelector('.forbidden'), title: document.title })`)
  ok('主编直接访问 /users 被前端守卫拦到 /403', fb.path === '/cms-mis/403' && fb.has403, fb.path)
  await shot('forbidden-403')

  // =========================================================================
  // 5. 响应式：<992px 自动折叠，恢复宽度自动展开，手动按钮可用
  // =========================================================================
  await spaPush('/articles/list')
  await sleep(700)
  await setViewport(900, 700)
  await sleep(700)
  const narrow = await evalJs(`(() => {
    const a = document.querySelector('.app-layout__aside')
    const logoTitle = document.querySelector('.sidebar__title')
    const wrap = document.querySelector('.sidebar .el-scrollbar__wrap')
    // ⚠️ 只能取"可见"的图标：折叠态下 el-sub-menu 的箭头图标 (.el-sub-menu__icon-arrow)
    //    被 EP 设成 display:none，getBoundingClientRect() 会返回全 0，中心算出来是 0，
    //    会误判成"没居中"。实测真图标 centerX=32（64px 侧边栏正中），箭头是 0。
    const icons = [...document.querySelectorAll('.sidebar .el-sub-menu__title > .el-icon:not(.el-sub-menu__icon-arrow)')]
      .filter((e) => e.getBoundingClientRect().width > 0)
    const centers = icons.map(e => {
      const r = e.getBoundingClientRect()
      return Math.round(r.left + r.width / 2)
    })
    const anyHBar = [...document.querySelectorAll('.sidebar .el-scrollbar__bar.is-horizontal')].some((e) => getComputedStyle(e).display !== 'none')
    return {
      asideW: Math.round(a.getBoundingClientRect().width),
      menuCollapsed: !!document.querySelector('.sidebar .el-menu--collapse'),
      logoTitleShown: logoTitle ? getComputedStyle(logoTitle).display !== 'none' : null,
      scrollW: document.documentElement.scrollWidth,
      clientW: document.documentElement.clientWidth,
      nicknameDisplay: getComputedStyle(document.querySelector('.navbar__nickname')).display,
      iconCenters: centers,
      sidebarOverflowX: wrap ? wrap.scrollWidth - wrap.clientWidth : null,
      anyHBar
    }
  })()`)
  ok('900px 宽侧边栏自动折叠到 64px', narrow.asideW === 64, String(narrow.asideW))
  ok('900px 宽 el-menu 进入折叠模式', narrow.menuCollapsed)
  ok('900px 宽系统名文字隐藏', narrow.logoTitleShown === false, String(narrow.logoTitleShown))
  ok('900px 宽无横向滚动条（布局不塌）', narrow.scrollW <= narrow.clientW, `${narrow.scrollW}/${narrow.clientW}`)
  ok('900px 宽昵称仍显示（>768px 断点）', narrow.nicknameDisplay !== 'none', narrow.nicknameDisplay)
  ok(
    '折叠态菜单图标水平居中（中心 ≈ 32px）',
    narrow.iconCenters.length > 0 && narrow.iconCenters.every((c) => Math.abs(c - 32) <= 4),
    JSON.stringify(narrow.iconCenters)
  )
  ok(
    '侧边栏内无横向溢出内容',
    narrow.sidebarOverflowX === 0 && narrow.anyHBar === false,
    `overflowX=${narrow.sidebarOverflowX} hbar=${narrow.anyHBar}`
  )
  await shot('layout-narrow-900x700')

  await setViewport(1440, 900)
  await sleep(700)
  const wide = await evalJs(`(() => {
    const a = document.querySelector('.app-layout__aside')
    return { asideW: Math.round(a.getBoundingClientRect().width), scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth }
  })()`)
  ok('拖回 1440px 侧边栏自动展开回 220px', wide.asideW === 220, String(wide.asideW))
  ok('1440px 无横向滚动条', wide.scrollW <= wide.clientW, `${wide.scrollW}/${wide.clientW}`)

  await evalJs(`document.querySelector('.navbar__collapse').click()`)
  await sleep(600)
  const manual = await evalJs(`Math.round(document.querySelector('.app-layout__aside').getBoundingClientRect().width)`)
  ok('点顶栏按钮可手动折叠到 64px', manual === 64, String(manual))
  await evalJs(`document.querySelector('.navbar__collapse').click()`)
  await sleep(600)
  const manual2 = await evalJs(`Math.round(document.querySelector('.app-layout__aside').getBoundingClientRect().width)`)
  ok('再点一次展开回 220px', manual2 === 220, String(manual2))

  // =========================================================================
  // 6. 整页加载根路径 → 用 Session 恢复登录态（等价于"在首页按 F5"）
  // =========================================================================
  const restored = await goto(`${BASE}/`, `!!document.querySelector('.sidebar')`, 25000)
  ok('带 Session 整页加载 /cms-mis/ 后仍是登录态（/auth/me 恢复成功）', restored)
  const afterReload = await evalJs(`({ path: location.pathname, nickname: (document.querySelector('.navbar__nickname') || {}).innerText })`)
  ok('恢复后仍在文章列表且昵称还在', afterReload.path === '/cms-mis/articles/list' && afterReload.nickname === '王主编', `${afterReload.path} / ${afterReload.nickname}`)

  const external = await evalJs(`(() => {
    const host = location.host
    return [...new Set(performance.getEntriesByType('resource').map(r => r.name)
      .filter(u => u.startsWith('http') && new URL(u).host !== host))]
  })()`)
  ok('页面加载的资源全部同源（无外链 CDN / 网络字体）', external.length === 0, JSON.stringify(external.slice(0, 5)))

  // =========================================================================
  // 7. 换角色：编辑登录 → 菜单不同
  // =========================================================================
  await evalJs(`fetch('${BASE}/api/v1/auth/logout', { method: 'POST' })`).catch(() => {})
  await sleep(300)
  await goto(`${BASE}/`, `!!document.querySelector('.login__submit')`, 25000)
  await fillForm('editor', '123456')
  await clickLogin()
  const editorIn = await waitFor(`!!document.querySelector('.sidebar')`, 25000)
  ok('编辑登录后进入后台', editorIn)
  const ed = await evalJs(`({
    nickname: (document.querySelector('.navbar__nickname') || {}).innerText,
    items: [...document.querySelectorAll('.sidebar .el-menu-item')].map(e => e.innerText.trim()),
    subs: [...document.querySelectorAll('.sidebar .el-sub-menu__title')].map(e => e.innerText.trim())
  })`)
  console.log('编辑可见分组:', JSON.stringify(ed.subs), '菜单项:', JSON.stringify(ed.items))
  ok('编辑登录成功（昵称=李编辑）', ed.nickname === '李编辑', ed.nickname)
  ok('菜单随角色变化：编辑看得到「待我审核」', ed.items.includes('待我审核'), ed.items.join(','))
  ok('菜单随角色变化：编辑看不到「用户管理」', !ed.items.includes('用户管理'), ed.items.join(','))
  ok('编辑看得到「分类管理」但看不到「角色权限」', ed.items.includes('分类管理') && !ed.items.includes('角色权限'), ed.items.join(','))
  await shot('layout-editor-1440x900')

  // =========================================================================
  // 8. 已知限制：SPA 子路由整页加载会 404（S22 修）
  // =========================================================================
  const deep = await fetch(`${BASE}/articles/list`, { redirect: 'manual' })
  ok(
    '已知限制确认：整页加载 /cms-mis/articles/list 返回 404（S22 需配 index.html fallback）',
    deep.status === 404,
    `HTTP ${deep.status}`
  )

  console.log('\n================ RESULT ================')
  console.log(results.join('\n'))
  const fails = results.filter((r) => r.startsWith('FAIL')).length
  console.log(`\nTOTAL pass=${results.length - fails} fail=${fails}`)
  if (fails) process.exitCode = 1

  await send('Browser.close').catch(() => {})
  cdp.ws.close()
}

main()
  .catch((e) => {
    console.error('脚本异常:', e && e.stack ? e.stack : e)
    if (results.length) {
      console.log('\n================ 已完成的断言 ================')
      console.log(results.join('\n'))
    }
    process.exitCode = 1
  })
  .finally(() => {
    try {
      chrome.kill()
    } catch (e) {
      /* ignore */
    }
  })
