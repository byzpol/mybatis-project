/**
 * F04 组件（PageTable / StatusTag）的浏览器自动化验证
 *
 * 验证对象：真实部署在 Tomcat 9 上的 **war**（http://localhost:8080/cms-mis/），
 *   被验证页面是临时的 /dev/f04-demo（frontend/src/views/dev/F04Demo.vue）。
 *
 * 为什么要用真机而不是"看代码觉得没问题"：
 *   ① StatusTag.vue 里 <script setup> 直接引用了 <script> 块中 export 的 getStatusMeta
 *      （刻意不用 `import { getStatusMeta } from './StatusTag.vue'` 自引用）。
 *      这件事**构建不会报错** —— JS 里未声明的标识符只有运行时求值才炸。
 *      也就是说 vite build 成功完全不能证明它对，必须真跑一次。
 *      对应断言：③「StatusTag 正常渲染」—— 如果那个引用解析不了，标签一个都渲染不出来。
 *   ② 四条核查项里"参数正确""不横向溢出"这类，只有在浏览器里量真实 DOM 才算数。
 *
 * ⚠️ 已知限制（与 F03 相同）：SPA 用 createWebHistory，Tomcat 没有"未匹配路径
 *   回退 index.html"的配置 → **不能直接整页加载 /cms-mis/dev/f04-demo**（会 404）。
 *   所以脚本先加载 /cms-mis/，再用 pushState + popstate 在 SPA 内部导航过去。
 *
 * 跑法：node f04-verify.mjs
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9335
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f04-check'
const PROFILE = `${OUT_DIR}/chrome-profile-f04`

mkdirSync(OUT_DIR, { recursive: true })

const results = []
const log = []
function ok(name, cond, extra = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? `  ${extra}` : ''}`
  results.push(line)
  log.push(line)
}
function note(s) {
  log.push(s)
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

/* 断言基准：来自 src/main/java/com/lab/cms/common/enums/ArticleStatus.java
   （0~9 的 description 与 color，F04 期间已用 grep 逐行核对过，不是凭记忆写的） */
const EXPECT_LABELS = ['草稿', '待编辑初审', '待主编终审', '审核通过', '已驳回', '已发布', '定时发布中', '已撤回', '已下架', '已归档']
const EXPECT_TYPES = ['info', 'warning', 'warning', 'success', 'danger', 'success', 'primary', 'info', 'info', 'info']

async function main() {
  const version = await waitChrome()
  console.log('Chrome:', version.Browser, '| 目标:', BASE)
  note(`Chrome: ${version.Browser}`)
  note(`目标: ${BASE}`)

  const t = await (
    await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })
  ).json()
  const cdp = connect(t.webSocketDebuggerUrl)
  await cdp.ready
  const { send, events } = cdp

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Log.enable')

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error('页面内异常: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text))
    }
    return r.result.value
  }
  const setViewport = (width, height) =>
    send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
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
    const r = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/${name}.png`, Buffer.from(r.data, 'base64'))
  }
  /** SPA 内导航：真实页面用 createWebHistory，pushState + popstate 即可让 router 响应 */
  const spaGo = async (path) => {
    await evalJs(`history.pushState({}, '', '${path}'); dispatchEvent(new PopStateEvent('popstate')); true`)
  }
  const calls = () => evalJs('JSON.parse(JSON.stringify(window.__f04calls))')
  const rowCount = () => evalJs(`document.querySelectorAll('#table-real .el-table__body tbody tr').length`)

  /* ============================ 0. 打开应用并进入演示页 ============================ */
  await setViewport(1440, 900)
  await send('Page.navigate', { url: `${BASE}/` })
  const mounted = await waitFor(`!!document.querySelector('#app') && document.querySelector('#app').children.length > 0`, 30000)
  ok('0.1 应用成功挂载（vite 产物在 war 里能跑起来）', mounted)
  note(`  当前落地路由: ${await evalJs('location.pathname')}`)

  await spaGo('/cms-mis/dev/f04-demo')
  const demoReady = await waitFor(`!!document.querySelector('#case-norequest')`, 20000)
  ok('0.2 SPA 内导航到 /dev/f04-demo 成功', demoReady)
  note(`  当前路由: ${await evalJs('location.pathname')}`)
  await waitFor(`window.__f04calls && window.__f04calls.length >= 1`, 10000)

  /* ============================ 核查① 未传 request ============================ */
  const alertInfo = await evalJs(`(() => {
    const box = document.querySelector('#case-norequest')
    if (!box) return null
    const a = box.querySelector('.el-alert')
    return {
      hasAlert: !!a,
      title: a ? (a.querySelector('.el-alert__title')?.textContent || '').trim() : '',
      desc: a ? (a.querySelector('.el-alert__description')?.textContent || '').trim() : '',
      tableCount: box.querySelectorAll('.el-table').length,
      pagerCount: box.querySelectorAll('.el-pagination').length
    }
  })()`)
  ok('① 未传 request → 渲染 el-alert 而不是白屏', !!alertInfo && alertInfo.hasAlert)
  ok('① 提示文案点明原因（含「未传入 request」）', !!alertInfo && alertInfo.title.includes('未传入 request'), JSON.stringify(alertInfo?.title))
  ok('① 提示里给出了正确用法', !!alertInfo && alertInfo.desc.includes('Promise<PageResult>'))
  ok('① 未传 request 时不渲染表格（避免看到一个空壳表格）', !!alertInfo && alertInfo.tableCount === 0)
  ok('① 未传 request 时不渲染分页器', !!alertInfo && alertInfo.pagerCount === 0)

  const consoleErrors = events
    .filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
    .map((e) => (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '))
  ok('① 同时打了 console.error（开发期第一时间能看见）', consoleErrors.some((s) => s.includes('PageTable')), JSON.stringify(consoleErrors))
  const pageErrors = events.filter((e) => e.method === 'Runtime.exceptionThrown')
  ok('① 页面内无未捕获异常', pageErrors.length === 0, `exceptionThrown=${pageErrors.length}`)
  const bodyLen = await evalJs(`document.body.innerText.trim().length`)
  ok('① 页面不是白屏（正文有内容）', bodyLen > 200, `innerText=${bodyLen}`)

  /* ============================ 核查③ StatusTag ============================ */
  const tagState = await evalJs(`(() => {
    const pick = (el) => ({
      text: el.textContent.trim(),
      cls: Array.from(el.classList).find((c) => c.startsWith('el-tag--')) || '',
      hasIcon: !!el.querySelector('.el-icon')
    })
    const grab = (sel) => Array.from(document.querySelectorAll(sel)).map(pick)
    const one = (sel) => { const e = document.querySelector(sel); return e ? pick(e) : null }
    return {
      article: grab('#row-article .el-tag'),
      others: grab('#row-others .el-tag'),
      unknown: one('#tag-unknown'),
      str: one('#tag-string'),
      nul: one('#tag-null')
    }
  })()`)

  note('')
  note('  StatusTag 实测渲染:')
  note('    文章态 0~9: ' + JSON.stringify(tagState?.article))
  note('    其它映射表: ' + JSON.stringify(tagState?.others))
  note('    99   → ' + JSON.stringify(tagState?.unknown))
  note('    "3"  → ' + JSON.stringify(tagState?.str))
  note('    null → ' + JSON.stringify(tagState?.nul))
  note('')

  // 这一条同时证明了"<script setup> 直接引用 <script> 的导出"这件事成立
  ok('③ StatusTag 正常渲染（若 getStatusMeta 引用解析失败这里会是 0 个）',
    (tagState?.article?.length ?? 0) === 10, `渲染出的标签数=${tagState?.article?.length}`)

  const labels = (tagState?.article || []).map((x) => x.text)
  ok('③ 文章态 0~9 文案与后端 ArticleStatus.description 逐条一致',
    JSON.stringify(labels) === JSON.stringify(EXPECT_LABELS), JSON.stringify(labels))

  const types = (tagState?.article || []).map((x) => x.cls.replace('el-tag--', ''))
  ok('③ 文章态 0~9 颜色类与后端 color 逐条一致（APPROVED 取 success 而非手册的 primary）',
    JSON.stringify(types) === JSON.stringify(EXPECT_TYPES), JSON.stringify(types))

  ok('③ 传 99 显示「未知状态」，不空白也不报错', tagState?.unknown?.text === '未知状态', JSON.stringify(tagState?.unknown))
  ok('③ 传字符串 "3" 也能正确归一（数字/字符串都查得到）', tagState?.str?.text === '审核通过', JSON.stringify(tagState?.str))
  ok('③ 传 null 显示「未知状态」', tagState?.nul?.text === '未知状态', JSON.stringify(tagState?.nul))
  ok('③ 定时发布中(6) 带时钟图标，其余状态不带',
    tagState?.article?.[6]?.hasIcon === true && tagState?.article?.[0]?.hasIcon === false,
    JSON.stringify((tagState?.article || []).map((x) => x.hasIcon)))
  ok('③ category 映射表三档正确（待审批/已生效/已驳回）',
    JSON.stringify((tagState?.others || []).slice(0, 3).map((x) => x.text)) === JSON.stringify(['待审批', '已生效', '已驳回']),
    JSON.stringify(tagState?.others?.map((x) => x.text)))
  ok('③ approval 映射表两档正确（通过/驳回）',
    JSON.stringify((tagState?.others || []).slice(3, 5).map((x) => x.text)) === JSON.stringify(['通过', '驳回']))

  await shot('01-status-tag')

  /* ============================ 核查② 四个动作都重新请求且参数正确 ============================ */
  /* ⚠️ 判据踩坑记录：el-input 是 inheritAttrs:false，属性被透传到**内部原生 input** 上，
     所以 `#f-title` 指向的就是 <input> 本身，不能再 .querySelector('input')。
     统一走 helper，两种落点都能处理。 */
  const setFieldValue = async (sel, val) => evalJs(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el) return 'missing'
    const inp = el.tagName === 'INPUT' ? el : el.querySelector('input')
    if (!inp) return 'no-input'
    inp.value = ${JSON.stringify(val)}
    inp.dispatchEvent(new Event('input', { bubbles: true }))
    return 'ok'
  })()`)
  const getFieldValue = async (sel) => evalJs(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el) return null
    const inp = el.tagName === 'INPUT' ? el : el.querySelector('input')
    return inp ? inp.value : null
  })()`)
  const clickFilterBtn = (i) =>
    evalJs(`document.querySelectorAll('.filter-bar__actions button')[${i}].click(); true`)
  const clickHeader = (text) => evalJs(`(() => {
    const th = Array.from(document.querySelectorAll('#table-real .el-table__header th')).find(e => e.textContent.includes(${JSON.stringify(text)}))
    if (!th) return false
    th.click()
    return true
  })()`)
  const clickPager = (text) => evalJs(`(() => {
    const li = Array.from(document.querySelectorAll('#table-real .el-pager li')).find(e => e.textContent.trim() === ${JSON.stringify(text)})
    if (!li) return false
    li.click()
    return true
  })()`)
  const changePageSize = async (n) => {
    await evalJs(`(() => {
      const w = document.querySelector('#table-real .el-pagination .el-select__wrapper') || document.querySelector('#table-real .el-pagination .el-select')
      if (w) w.click()
      return !!w
    })()`)
    await sleep(400)
    const r = await evalJs(`(() => {
      const items = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      const target = items.find(e => e.textContent.replace(/\\s/g, '').startsWith(${JSON.stringify(String(n))}))
      if (!target) return 'no-option:' + items.map(e => e.textContent.trim()).join('|')
      target.click()
      return 'ok'
    })()`)
    await sleep(500)
    return r
  }

  // 1) 挂载即请求第一页
  const c0 = await calls()
  ok('② 挂载时自动请求第 1 页，参数为 {page:1,size:10}',
    c0.length === 1 && c0[0].page === 1 && c0[0].size === 10 && Object.keys(c0[0]).length === 2,
    JSON.stringify(c0))
  ok('② 表格渲染出第 1 页数据行', (await rowCount()) === 10, `rows=${await rowCount()}`)

  // 2) 查询
  const setRes = await setFieldValue('#f-title', '标题 1')
  ok('② 能定位到标题输入框（el-input 把 id 透传到了原生 input）', setRes === 'ok', String(setRes))
  await sleep(150)
  let before = (await calls()).length
  await clickFilterBtn(0)
  await sleep(400)
  let c = await calls()
  let last = c[c.length - 1]
  ok('② 点「查询」触发了一次新请求', c.length === before + 1, `${before} → ${c.length}`)
  ok('② 查询参数带上了标题条件', last.title === '标题 1', JSON.stringify(last))
  ok('② 查询回到第 1 页', last.page === 1, JSON.stringify(last))
  ok('② 值为空的条件不传（status 未选，参数里不应出现 status）', !('status' in last), JSON.stringify(last))
  // 假数据里标题含「标题 1」的是：标题 1、标题 10~19 —— 共 11 条。
  // ⚠️ 别用"渲染行数"去证筛选生效：每页 10 条时第 1 页永远是 10 行。
  //   要看的是分页器上的**总数**。
  const rowsAfterSearch = await rowCount()
  const totalText = await evalJs(`(document.querySelector('#table-real .el-pagination__total') || {}).textContent || ''`)
  note(`  筛选后分页器总数文案: ${JSON.stringify(totalText)}`)
  ok('② 筛选真的作用到了结果集（分页器显示命中 11 条：标题 1 / 标题 10~19）',
    totalText.includes('11'), JSON.stringify(totalText))
  ok('② 筛选后第 1 页按每页 10 条渲染 10 行', rowsAfterSearch === 10, `rows=${rowsAfterSearch}`)

  // 3) 翻页
  before = (await calls()).length
  await clickPager('2')
  await sleep(400)
  c = await calls()
  last = c[c.length - 1]
  ok('② 点第 2 页触发新请求', c.length === before + 1, `${before} → ${c.length}`)
  ok('② 翻页参数 page=2 且保留查询条件', last.page === 2 && last.title === '标题 1', JSON.stringify(last))
  const rowsPage2 = await rowCount()
  ok('② 第 2 页只渲染剩下的 1 行（11 条命中 / 每页 10 条）', rowsPage2 === 1, `rows=${rowsPage2}`)
  const idxCell = await evalJs(`(() => {
    const td = document.querySelector('#table-real .el-table__body tbody tr td:nth-child(2) .cell')
    return td ? td.textContent.trim() : null
  })()`)
  ok('② 翻页后序号连续（第 2 页首行序号 = 11，不是从 1 重新开始）', idxCell === '11', JSON.stringify(idxCell))

  // 4) 改每页条数
  before = (await calls()).length
  const sizeRes = await changePageSize(20)
  c = await calls()
  last = c[c.length - 1]
  note(`  改条数：选项点击=${sizeRes}`)
  ok('② 改每页条数触发新请求', c.length === before + 1, `${before} → ${c.length}`)
  ok('② 改条数后 size=20 且回到第 1 页', last.size === 20 && last.page === 1, JSON.stringify(last))
  ok('② 每页 20 时把 11 条命中一次性渲染完', (await rowCount()) === 11, `rows=${await rowCount()}`)

  // 5) 排序
  before = (await calls()).length
  await clickHeader('创建时间')
  await sleep(400)
  c = await calls()
  last = c[c.length - 1]
  ok('② 点可排序表头触发新请求', c.length === before + 1, `${before} → ${c.length}`)
  ok('② 排序传的是数据库列名 sortField=create_time（而不是驼峰 prop）',
    last.sortField === 'create_time' && last.sortOrder === 'asc', JSON.stringify(last))
  ok('② 排序后回到第 1 页', last.page === 1, JSON.stringify(last))

  // 6) 重置
  before = (await calls()).length
  await clickFilterBtn(1)
  await sleep(400)
  c = await calls()
  last = c[c.length - 1]
  ok('② 点「重置」触发新请求', c.length === before + 1, `${before} → ${c.length}`)
  ok('② 重置清空了查询条件', !('title' in last) && !('status' in last), JSON.stringify(last))
  ok('② 重置清空了排序', !('sortField' in last), JSON.stringify(last))
  ok('② 重置回到第 1 页且条数复位为 10', last.page === 1 && last.size === 10, JSON.stringify(last))
  const inputAfterReset = await getFieldValue('#f-title')
  ok('② 重置把输入框也清空了（v-model 双向生效）', inputAfterReset === '', JSON.stringify(inputAfterReset))

  // 7) 无筛选条件下再改一次条数，验证 20 条真的渲染 20 行
  await changePageSize(20)
  ok('② 无条件时每页 20 条渲染满 20 行', (await rowCount()) === 20, `rows=${await rowCount()}`)

  note('')
  note('  request 收到的全部参数（按调用顺序）:')
  ;(await calls()).forEach((x, i) => note(`    #${i + 1} ${JSON.stringify(x)}`))
  note('')

  await shot('02-realtable-1440')

  /* ============================ 核查④ 窄屏不横向溢出 ============================ */
  /*
   * 量法说明（F03 的教训："元素存在" ≠ "元素可见"）：
   *  · 页面是否横向溢出 → 直接比 documentElement 的 scrollWidth / clientWidth，别绕。
   *  · 表格内部是否在滚 → 量**真正的滚动容器** .el-scrollbar__wrap，
   *    而不是 .el-table__body-wrapper（后者 overflow:hidden，量出来恒为 0）。
   *  · "列用的是 minWidth 而不是固定 width" 怎么证 → 看**第一个数据列的实际渲染宽度**：
   *      minWidth 的列在容器变宽时会被拉伸（> minWidth），容器变窄时回落到 minWidth；
   *      固定 width 的列则永远等于那个值。这个差别是可直接量出来的。
   */
  const TITLE_MIN_WIDTH = 220
  const measure = async () => evalJs(`(() => {
    const de = document.documentElement
    const t = document.querySelector('#table-real')
    const wrap = t.querySelector('.el-table__body-wrapper .el-scrollbar__wrap') || t.querySelector('.el-table__body-wrapper')
    const tbl = t.querySelector('.el-table__body')
    const ths = t.querySelectorAll('.el-table__header th')
    // 表头顺序：selection / index / 标题 / 分类 / 作者 / 状态 / 阅读量 / 创建时间
    const titleTh = Array.from(ths).find(e => e.textContent.includes('标题'))
    return {
      viewport: de.clientWidth,
      overflowPx: de.scrollWidth - de.clientWidth,
      tableW: Math.round(t.getBoundingClientRect().width),
      innerTableW: tbl ? Math.round(tbl.getBoundingClientRect().width) : 0,
      wrapOverflowPx: wrap ? wrap.scrollWidth - wrap.clientWidth : -1,
      titleColW: titleTh ? Math.round(titleTh.getBoundingClientRect().width) : -1
    }
  })()`)

  const wide = await measure()
  note(`  1440 视口: ${JSON.stringify(wide)}`)
  ok('④ 1440 宽：页面无横向溢出', wide.overflowPx <= 1, `overflow=${wide.overflowPx}px`)
  ok('④ 1440 宽：表格宽度 = 容器内容宽度（撑满，无留白）',
    Math.abs(wide.tableW - (wide.viewport - 42)) <= 2, `table=${wide.tableW} 期望≈${wide.viewport - 42}（视口-滚动条8px-.app-card 左右各20px内边距与1px边框）`)
  ok('④ 1440 宽：标题列被拉伸到明显大于 minWidth，说明是 minWidth 而非固定 width',
    wide.titleColW > TITLE_MIN_WIDTH + 20, `标题列=${wide.titleColW} minWidth=${TITLE_MIN_WIDTH}`)
  ok('④ 1440 宽：表格内部不需要横向滚动', wide.wrapOverflowPx <= 1, `wrapOverflow=${wide.wrapOverflowPx}px`)

  await setViewport(1024, 800)
  await sleep(400)
  const mid = await measure()
  note(`  1024 视口: ${JSON.stringify(mid)}`)
  ok('④ 1024 宽：页面无横向溢出', mid.overflowPx <= 1, `overflow=${mid.overflowPx}px`)
  ok('④ 1024 宽：表格仍撑满容器', Math.abs(mid.tableW - (mid.viewport - 42)) <= 2, `table=${mid.tableW}`)
  ok('④ 1024 宽：标题列仍大于 minWidth（列在跟着容器等比收窄）', mid.titleColW > TITLE_MIN_WIDTH, `标题列=${mid.titleColW}`)

  await setViewport(800, 800)
  await sleep(400)
  const narrow = await measure()
  note(`   800 视口: ${JSON.stringify(narrow)}`)
  ok('④ 800 宽（比各列最小宽之和 850 还窄）：页面仍无横向溢出', narrow.overflowPx <= 1, `overflow=${narrow.overflowPx}px`)
  ok('④ 800 宽：表格比容器宽（说明列没有继续压缩，守住最小宽）',
    narrow.innerTableW > narrow.tableW, `innerTable=${narrow.innerTableW} 容器=${narrow.tableW}`)
  ok('④ 800 宽：多出来的宽度由 el-table 自己的滚动容器承载',
    narrow.wrapOverflowPx > 0, `wrapOverflow=${narrow.wrapOverflowPx}px`)
  ok('④ 800 宽：标题列回落到 minWidth 附近', narrow.titleColW <= TITLE_MIN_WIDTH + 5, `标题列=${narrow.titleColW}`)
  await shot('03-narrow-800')

  await setViewport(600, 800)
  await sleep(400)
  const tiny = await measure()
  note(`   600 视口: ${JSON.stringify(tiny)}`)
  ok('④ 600 宽：页面仍无横向溢出', tiny.overflowPx <= 1, `overflow=${tiny.overflowPx}px`)

  await setViewport(1440, 900)
  await sleep(300)

  /* ============================ 收尾 ============================ */
  writeFileSync(`${OUT_DIR}/f04-run.txt`, log.join('\n'), 'utf8')
  const passed = results.filter((r) => r.startsWith('PASS')).length
  const failed = results.filter((r) => r.startsWith('FAIL')).length
  console.log('\n================ 结果 ================')
  console.log(results.join('\n'))
  console.log(`\n通过 ${passed} / 共 ${passed + failed}`)
  writeFileSync(`${OUT_DIR}/f04-summary.txt`, `通过 ${passed} / 共 ${passed + failed}\n\n` + results.join('\n'), 'utf8')

  cdp.ws.close()
  chrome.kill()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('验证脚本异常:', e)
  // 异常也要把"已经跑完的那部分"留下，否则只知道炸了、不知道前面几条过没过
  writeFileSync(
    `${OUT_DIR}/f04-error.txt`,
    `${String(e.stack || e)}\n\n---- 异常前已完成的断言 ----\n` + results.join('\n') + '\n\n---- 过程日志 ----\n' + log.join('\n'),
    'utf8'
  )
  console.error('\n---- 异常前已完成的断言 ----\n' + results.join('\n'))
  try {
    chrome.kill()
  } catch (_) {}
  process.exit(2)
})
