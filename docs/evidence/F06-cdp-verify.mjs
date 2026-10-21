/**
 * F06 文章管理页 · 浏览器自动化验证
 * ===========================================================================
 * 验证对象：真实部署在 Tomcat 9 上的 war（http://localhost:8080/cms-mis/）
 *
 * ---------------------------------------------------------------------------
 * 为什么必须真机跑，而不是"构建通过 + 看代码觉得没问题"
 * ---------------------------------------------------------------------------
 *  ① F04/F05 留下的 4 个组件（PageTable / StatusTag / AuditTimeline / ConfirmAction）
 *     **在 F06 之前没有任何调用方**，会被 tree-shaking 跳过、根本不进构建产物。
 *     这一轮它们第一次有了真实调用方，必须证明"真的译进来了、真的能渲染"。
 *  ② 「操作列按钮随状态与权限正确变化」是 F06 的头号核查项。
 *     articleActions.js 已经用 40 组合 × 118 项断言覆盖了**逻辑**，
 *     但"逻辑对"不等于"模板把 11 个动作都接到了、按钮真的渲染出来了、点了真会发请求"——
 *     那是模板层的东西，只有浏览器里数 DOM 才算数。
 *  ③ 「1440 宽无横向滚动条」是渲染结果，代码里看不出。
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 关键手法：用 CDP 的 Fetch 域拦截并伪造列表接口
 * ---------------------------------------------------------------------------
 * F06 阶段后端 `ArticleController` 还没建（S09 才写），
 * `GET /api/v1/articles` 是 404，列表页**一行数据都没有** ——
 * 那样就没法在真机上验证操作列。
 *
 * 所以这里用 `Fetch.enable` + `Fetch.fulfillRequest` 在**协议层**把该接口的响应
 * 换成一份 10 个状态各一条的假数据。这样做的好处：
 *   · 不改一行业务代码（不像 F04/F05 那样要先插一个临时演示页再删掉）
 *   · 走的是**真实的 ArticleList.vue 模板**，验证的不是"复刻版"
 *   · 顺带把"状态列 StatusTag 渲染对了没"也一起验了
 * 代价：只伪造了**读取**接口。写操作（提交/审核/发布…）仍然会 404 ——
 *      所以"全流程走一遍"这条核查项本轮无法完成，如实记在报告里（S09~S14 之后补）。
 *
 * 跑法：
 *   "C:/Users/admin/.workbuddy/binaries/node/versions/22.22.2-6/node.exe" \
 *     docs/evidence/F06-cdp-verify.mjs
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9338
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f06-check'

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
    '--headless=new', '--disable-gpu', '--no-sandbox', '--no-proxy-server', '--no-first-run',
    '--disable-extensions', `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${OUT_DIR}/chrome-profile-f06`, 'about:blank'
  ],
  { stdio: 'ignore' }
)

async function waitChrome() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return await r.json()
    } catch (e) {}
    await sleep(500)
  }
  throw new Error('Chrome DevTools 端口未就绪')
}

function connect(wsUrl, onEvent) {
  const ws = new WebSocket(wsUrl)
  let id = 0
  const pending = new Map()
  const events = []
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id != null && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id)
      pending.delete(m.id)
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
    } else if (m.method) {
      events.push(m)
      if (onEvent) {
        try {
          onEvent(m)
        } catch (e) {
          /* 事件回调里出错不能影响主流程 */
        }
      }
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

// ---------------------------------------------------------------------------
// 伪造数据（10 个状态各一条，顺序固定，断言按顺序取行）
// ---------------------------------------------------------------------------
const STATUS_TITLES = [
  '草稿样本', '待编辑初审样本', '待主编终审样本', '审核通过样本', '已驳回样本',
  '已发布样本', '定时发布中样本', '已撤回样本', '已下架样本', '已归档样本'
]

function mockRows() {
  const now = '2026-10-21T10:00:00'
  return STATUS_TITLES.map((t, i) => ({
    id: i + 1,
    title: `【状态${i}】${t}`,
    summary: `这是 ${t} 的摘要`,
    coverUrl: null,
    categoryId: 1,
    categoryName: '技术资讯',
    status: i,
    statusDesc: t,
    statusColor: 'info',
    authorId: 1,
    authorName: '张晓投稿',
    publisherId: null,
    publishTime: null,
    scheduleTime: null,
    isTop: i === 5 ? 1 : 0,
    viewCount: 100 + i,
    createTime: now,
    updateTime: now
  }))
}

/** 统一的 Result 信封：{code,message,data,timestamp}，成功 message 固定「操作成功」 */
function envelope(data) {
  return { code: 200, message: '操作成功', data, timestamp: Date.now() }
}

const MOCK_DETAIL = {
  ...mockRows()[1],
  content: '这是正文第一行\n这是正文第二行，用来验证 <pre> 的 white-space: pre-wrap 是否生效。\n第三行',
  rejectReason: '摘要太短，请补充到 80 字以上',
  publisherName: '王主编'
}

const MOCK_LOGS = [
  { auditStage: 1, auditorName: '李编辑', result: 2, opinion: '摘要太短，请补充', auditTime: '2026-10-20T09:30:00', articleVersion: 2 },
  { auditStage: 1, auditorName: '李编辑', result: 1, opinion: '', auditTime: '2026-10-21T09:30:00', articleVersion: 3 }
]

const MOCK_VERSIONS = [
  { versionNo: 1, titleSnapshot: '【状态1】待编辑初审样本', modifierId: 1, modifierName: '张晓投稿', modifyRemark: '创建草稿', createTime: '2026-10-19T10:00:00' },
  { versionNo: 2, titleSnapshot: '【状态1】待编辑初审样本（改）', modifierId: 1, modifierName: '张晓投稿', modifyRemark: '更新草稿', createTime: '2026-10-20T10:00:00' }
]

// ---------------------------------------------------------------------------
// 期望矩阵（与 F06-actions-matrix.mjs 里那份**同一份手写期望**，不是从实现里读的）
// ---------------------------------------------------------------------------
const ROLE_PERMS = {
  author: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'category:view'],
  editor: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'article:audit', 'category:view', 'category:manage'],
  chief: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'article:audit', 'article:manage', 'article:publish', 'category:view', 'category:manage', 'report:view']
}

const ACTION_PERM = {
  view: 'article:view', edit: 'article:update', submit: 'article:submit', audit: 'article:audit',
  publishNow: 'article:publish', publishSchedule: 'article:publish', republish: 'article:publish',
  withdraw: 'article:withdraw', offline: 'article:manage', archive: 'article:manage', remove: 'article:delete'
}
const ACTION_LABEL = {
  view: '查看', edit: '编辑', submit: '提交初审', audit: '审核', publishNow: '立即发布',
  publishSchedule: '排期发布', republish: '重新发布', withdraw: '撤回', offline: '下架',
  archive: '归档', remove: '删除'
}
// 状态 → 动作 key 顺序（手写，依据 ArticleStateMachine 的 14 条迁移）
const RULES_EXPECTED = {
  0: ['edit', 'submit', 'remove'],
  1: ['view', 'audit'],
  2: ['view', 'audit'],
  3: ['view', 'publishNow', 'publishSchedule'],
  4: ['edit', 'submit', 'remove'],
  5: ['view', 'withdraw', 'offline'],
  6: ['view'],
  7: ['view', 'republish', 'archive'],
  8: ['view', 'archive'], // ⚠️ 已下架没有 republish（状态机无 OFFLINE→PUBLISH_NOW）
  9: ['view']
}

function expectedLabels(status, role) {
  const perms = ROLE_PERMS[role]
  return RULES_EXPECTED[status]
    .filter((k) => perms.includes(ACTION_PERM[k]))
    .map((k) => ACTION_LABEL[k])
}

async function main() {
  const version = await waitChrome()
  console.log('Chrome:', version.Browser, '| 目标:', BASE)
  note(`Chrome: ${version.Browser}`)
  note(`目标: ${BASE}`)

  const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()

  let sendRef = null
  const intercepted = []
  const cdp = connect(t.webSocketDebuggerUrl, (m) => {
    if (m.method !== 'Fetch.requestPaused' || !sendRef) return
    const { requestId, request } = m.params
    const url = request.url
    const path = url.replace(BASE, '').split('?')[0]
    let payload = null

    if (path === '/api/v1/articles') {
      payload = envelope({ records: mockRows(), total: 10, current: 1, size: 10, pages: 1 })
      intercepted.push(`GET ${path} → 伪造 10 行`)
    } else if (/^\/api\/v1\/articles\/\d+$/.test(path)) {
      payload = envelope({ ...MOCK_DETAIL, id: Number(path.split('/').pop()) })
      intercepted.push(`GET ${path} → 伪造详情`)
    } else if (/^\/api\/v1\/articles\/\d+\/audit-logs$/.test(path)) {
      payload = envelope(MOCK_LOGS)
      intercepted.push(`GET ${path} → 伪造流水`)
    } else if (/^\/api\/v1\/articles\/\d+\/versions$/.test(path)) {
      payload = envelope(MOCK_VERSIONS)
      intercepted.push(`GET ${path} → 伪造版本`)
    }

    if (payload) {
      sendRef('Fetch.fulfillRequest', {
        requestId,
        responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json;charset=UTF-8' }],
        body: Buffer.from(JSON.stringify(payload), 'utf8').toString('base64')
      }).catch(() => {})
    } else {
      // 非目标接口一律放行（登录、健康检查等都走这里）
      sendRef('Fetch.continueRequest', { requestId }).catch(() => {})
    }
  })

  await cdp.ready
  const { send, events } = cdp
  sendRef = send

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Fetch.enable', {
    patterns: [{ urlPattern: '*localhost:8080/cms-mis/api/v1/articles*', requestStage: 'Request' }]
  })

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error('页面内异常: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text))
    }
    return r.result.value
  }
  const waitFor = async (expr, timeout = 25000) => {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      await sleep(250)
      try {
        if (await evalJs(expr)) return true
      } catch (_) {}
    }
    return false
  }
  const shot = async (name) => {
    const r = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/${name}.png`, Buffer.from(r.data, 'base64'))
  }
  const spaPush = async (path) => {
    await evalJs(`(() => {
      history.pushState({}, '', ${JSON.stringify(BASE + path)})
      window.dispatchEvent(new PopStateEvent('popstate'))
      return true
    })()`)
    await sleep(400)
  }
  const setViewport = (w, h) =>
    send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })

  /** 垂直/水平滚动条判据（F03/F04 口径） */
  const scrollInfo = () =>
    evalJs(`(() => { const de = document.documentElement; return {
      sw: de.scrollWidth, cw: de.clientWidth, sh: de.scrollHeight, ch: de.clientHeight } })()`)

  /** 读列表里每一行的操作列按钮（按行序）。⚠️ 按钮文案必须去空白：「查看」在 DOM 里是「查 看」 */
  const readRowActionButtons = () =>
    evalJs(`(() => {
      const rows = document.querySelectorAll('.el-table__body-wrapper tbody tr')
      return Array.from(rows).map(tr => {
        const tds = tr.querySelectorAll('td')
        const last = tds[tds.length - 1]
        const btns = last ? Array.from(last.querySelectorAll('button')) : []
        return btns.map(b => ({
          text: (b.textContent || '').replace(/\\s+/g, ''),
          primary: b.classList.contains('el-button--primary'),
          danger: b.classList.contains('el-button--danger')
        }))
      })
    })()`)

  /** 每行的状态标签文案（验证 StatusTag 真在渲染） */
  const readRowStatusTags = () =>
    evalJs(`(() => {
      const rows = document.querySelectorAll('.el-table__body-wrapper tbody tr')
      return Array.from(rows).map(tr => {
        const tag = tr.querySelector('.status-tag')
        return tag ? (tag.textContent || '').replace(/\\s+/g, '') : null
      })
    })()`)

  /**
   * 状态标签的高度与整行高度。
   * ⚠️ 加这条是因为第一轮截图里**肉眼**发现「待编辑初审」被折成了两行，
   *    导致 10 行里有 3 行比别的行高一截，整张表看着毛糙。
   *    自动化断言里原本没有这一项，只能靠人看截图发现 —— 补上。
   *    判据：标签高度 ≤ 26px（单行 small tag 约 22px），且 10 个标签高度一致。
   */
  const readStatusTagHeights = () =>
    evalJs(`(() => {
      const rows = document.querySelectorAll('.el-table__body-wrapper tbody tr')
      const tagH = [], rowH = []
      rows.forEach(tr => {
        const tag = tr.querySelector('.status-tag')
        tagH.push(tag ? Math.round(tag.getBoundingClientRect().height) : null)
        rowH.push(Math.round(tr.getBoundingClientRect().height))
      })
      return { tagH, rowH }
    })()`)

  const login = async (user, pass) => {
    await send('Network.clearBrowserCookies')
    await send('Page.navigate', { url: `${BASE}/` })
    const atLogin = await waitFor(`location.pathname.endsWith('/login')`, 25000)
    if (!atLogin) return false
    await evalJs(`(() => {
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
    await sleep(250)
    await evalJs(`document.querySelector('.login__submit').click()`)
    return await waitFor(`location.pathname.endsWith('/articles/list')`, 25000)
  }

  const EXC_MARK = () => events.filter((e) => e.method === 'Runtime.exceptionThrown').length
  const CONSOLE_ERR = () =>
    events
      .filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
      .map((e) => (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '))

  // =========================================================================
  // 会话一：作者（author / 张晓投稿）—— 无 article:audit 的账号
  // =========================================================================
  await setViewport(1440, 900)
  const loginAuthor = await login('author', '123456')
  ok('会话一 作者登录成功并落到 /articles/list', loginAuthor)

  // 等 10 行渲染出来（证明伪造响应 + PageTable + 真实模板串起来了）
  const rowsReady = await waitFor(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 10`, 20000)
  ok('会话一 列表渲染出 10 行（CDP 伪造响应已被真实模板消费）', rowsReady,
    String(await evalJs(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length`)))

  const headerTitle = await evalJs(`(() => { const e = document.querySelector('.page-header__title'); return e ? e.textContent.trim() : null })()`)
  ok('会话一 页面标题为「文章管理」', headerTitle === '文章管理', JSON.stringify(headerTitle))

  // ---- 状态列 ----
  const tags = await readRowStatusTags()
  note(`  状态列实测: ${JSON.stringify(tags)}`)
  ok('会话一 10 行的状态标签都渲染了（StatusTag 生效）', tags.length === 10 && tags.every((x) => !!x), JSON.stringify(tags))
  ok('会话一 状态 6 显示「定时发布中」（含图标时 textContent 仍含该词）',
    !!tags[6] && tags[6].includes('定时发布中'), JSON.stringify(tags[6]))
  ok('会话一 状态 8 显示「已下架」', !!tags[8] && tags[8] === '已下架', JSON.stringify(tags[8]))

  // ---- 状态标签不许折行（否则 10 行里会有几行高出来，整张表看着毛糙） ----
  const tagH = await readStatusTagHeights()
  note(`  状态标签高度: ${JSON.stringify(tagH.tagH)}`)
  note(`  每行高度:     ${JSON.stringify(tagH.rowH)}`)
  ok('★ 会话一 状态标签都是单行（高度 ≤ 26px，没有折成两行）',
    tagH.tagH.every((h) => h !== null && h <= 26), JSON.stringify(tagH.tagH))
  ok('★ 会话一 10 个状态标签高度完全一致',
    new Set(tagH.tagH).size === 1, JSON.stringify([...new Set(tagH.tagH)]))
  ok('★ 会话一 10 行行高完全一致（没有因为某个标签折行而高出一截）',
    new Set(tagH.rowH).size === 1, JSON.stringify([...new Set(tagH.rowH)]))

  // ---- ★ 本步头号核查项：操作列随状态与权限变化（作者） ----
  const authorBtns = await readRowActionButtons()
  note('  作者操作列实测:')
  authorBtns.forEach((b, i) => note(`    状态${i} → ${JSON.stringify(b.map((x) => x.text))}`))
  note('')
  let authorActionPass = 0
  for (let s = 0; s <= 9; s++) {
    const got = (authorBtns[s] || []).map((b) => b.text)
    const exp = expectedLabels(s, 'author')
    const good = JSON.stringify(got) === JSON.stringify(exp)
    if (good) authorActionPass++
    ok(`★ 作者 · 状态${s} 操作列 = [${exp.join('/')}]`, good, `实得 [${got.join('/')}]`)
  }
  note(`  作者 10 行操作列全对：${authorActionPass}/10`)

  // 手册原文要求："用一个无 article:audit 权限的账号验证" —— 单独留一条显式断言
  ok('★ 作者（无 article:audit）在「待编辑初审」行看不到「审核」按钮',
    !(authorBtns[1] || []).some((b) => b.text === '审核'), JSON.stringify((authorBtns[1] || []).map((b) => b.text)))
  ok('★ 作者（无 article:audit）在「待主编终审」行看不到「审核」按钮',
    !(authorBtns[2] || []).some((b) => b.text === '审核'), JSON.stringify((authorBtns[2] || []).map((b) => b.text)))
  ok('★ 作者（无 article:publish）在「审核通过」行看不到发布按钮',
    !(authorBtns[3] || []).some((b) => b.text === '立即发布' || b.text === '排期发布'),
    JSON.stringify((authorBtns[3] || []).map((b) => b.text)))
  ok('★ 作者能撤回自己已发布的文章（状态5 有「撤回」）',
    (authorBtns[5] || []).some((b) => b.text === '撤回'))
  ok('★ 作者（无 article:manage）看不到「下架」', !(authorBtns[5] || []).some((b) => b.text === '下架'))
  ok('★ 作者（无 article:manage）看不到「归档」（状态7/8 都没有）',
    !(authorBtns[7] || []).some((b) => b.text === '归档') && !(authorBtns[8] || []).some((b) => b.text === '归档'))

  // ---- 危险动作的红 / 主色的 primary ----
  const dangerChecks = [
    [0, '删除'],
    [4, '删除']
  ]
  for (const [s, label] of dangerChecks) {
    const btn = (authorBtns[s] || []).find((b) => b.text === label)
    ok(`★ 作者 · 状态${s} 的「${label}」按钮是危险色（el-button--danger）`, !!btn && btn.danger === true, JSON.stringify(btn))
  }
  const primarySubmit = (authorBtns[0] || []).find((b) => b.text === '提交初审')
  ok('★ 作者 · 状态0 的「提交初审」是主色（状态 0 的主色动作）', !!primarySubmit && primarySubmit.primary === true, JSON.stringify(primarySubmit))

  // ---- 查询区控件 ----
  const filterInfo = await evalJs(`(() => {
    const bar = document.querySelector('.filter-bar')
    if (!bar) return null
    return {
      inputs: bar.querySelectorAll('.el-input').length,
      selects: bar.querySelectorAll('.el-select').length,
      dates: bar.querySelectorAll('.el-date-editor').length,
      buttons: Array.from(bar.querySelectorAll('.filter-bar__actions button')).map(b => b.textContent.replace(/\\s+/g,''))
    }
  })()`)
  note(`  查询区实测: ${JSON.stringify(filterInfo)}`)
  ok('会话一 查询区包含标题输入框', !!filterInfo && filterInfo.inputs >= 1)
  ok('会话一 查询区包含 3 个下拉（分类/状态/作者）', !!filterInfo && filterInfo.selects === 3, JSON.stringify(filterInfo?.selects))
  ok('会话一 查询区包含日期范围选择器', !!filterInfo && filterInfo.dates === 1, JSON.stringify(filterInfo?.dates))
  ok('会话一 查询区有「查询」「重置」两个按钮',
    !!filterInfo && filterInfo.buttons.join(',') === '查询,重置', JSON.stringify(filterInfo?.buttons))

  // ---- 状态下拉的 10 个选项（证明用的是 STATUS_OPTIONS） ----
  // 按 placeholder 文本定位「状态」那个下拉，而不是按下标 —— 下标会随以后加控件而错位
  await evalJs(`(() => {
    const sel = Array.from(document.querySelectorAll('.filter-bar .el-select'))
      .find(s => (s.textContent || '').includes('状态'))
    if (!sel) return false
    const hit = sel.querySelector('.el-select__wrapper') || sel
    hit.click()
    return true
  })()`)
  await sleep(600)
  /*
   * ⚠️ 不能直接取 `.el-select-dropdown__item` ——
   *    页面上**同时存在 4 个下拉的全部选项**（分类 9 条 + 状态 10 条 + 作者 5 条 + 每页条数 4 条 = 28 条），
   *    因为 el-select 的选项是随组件一起渲染进 DOM 的，只是被外层 popper 藏起来了。
   *    第一轮就是这么拿到 28 条的（那不是组件的问题，是断言选错了范围）。
   *    判据用 offsetParent：display:none 的祖先会让 offsetParent 变 null，
   *    所以"offsetParent 不为 null"就等于"这个选项真的可见"。
   */
  const statusOptions = await evalJs(`Array.from(document.querySelectorAll('.el-select-dropdown__item'))
    .filter(e => e.offsetParent !== null)
    .map(e => e.textContent.trim()).filter(t => t)`)
  note(`  状态下拉选项: ${JSON.stringify(statusOptions)}`)
  ok('会话一 状态下拉有 10 个选项', statusOptions.length === 10, String(statusOptions.length))
  ok('会话一 状态下拉文案与 STATUS_OPTIONS 一致（含「审核通过」而非手册的「已通过待发布」）',
    statusOptions.join(',') === '草稿,待编辑初审,待主编终审,审核通过,已驳回,已发布,定时发布中,已撤回,已下架,已归档',
    JSON.stringify(statusOptions))
  await evalJs(`document.body.click(); true`)
  await sleep(300)

  // ---- 工具栏 ----
  const toolbarInfo = await evalJs(`(() => {
    const bar = document.querySelector('.table-toolbar')
    if (!bar) return null
    return Array.from(bar.querySelectorAll('button')).map(b => ({
      text: b.textContent.replace(/\\s+/g,''), disabled: b.disabled
    }))
  })()`)
  note(`  工具栏实测: ${JSON.stringify(toolbarInfo)}`)
  ok('会话一 工具栏有「批量提交审核」「批量删除」', !!toolbarInfo && toolbarInfo.length === 2, JSON.stringify(toolbarInfo))
  ok('会话一 未选中任何行时两个批量按钮都禁用',
    !!toolbarInfo && toolbarInfo.every((b) => b.disabled === true), JSON.stringify(toolbarInfo))

  // ---- 分页器（PageTable 内置） ----
  const pagerTotal = await evalJs(`(() => { const e = document.querySelector('.el-pagination__total'); return e ? e.textContent.trim() : null })()`)
  ok('会话一 PageTable 内置分页器渲染且总数为 10', !!pagerTotal && pagerTotal.includes('10'), JSON.stringify(pagerTotal))

  await shot('F06-01-author-list-1440')

  // ---- 1440 无横向滚动条 ----
  let sc = await scrollInfo()
  note(`  /articles/list 1440 滚动量: ${JSON.stringify(sc)}`)
  ok('★ 会话一 /articles/list 在 1440 宽下无横向滚动条', sc.sw <= sc.cw, `${sc.sw}/${sc.cw}`)

  // ---- 表格自身有没有把内容顶出容器 ----
  const tableFit = await evalJs(`(() => {
    const card = document.querySelector('.app-card')
    const table = card.querySelector('.el-table')
    const wrap = card.querySelector('.el-scrollbar__wrap')
    return {
      cardW: card.clientWidth,
      // ⚠️ clientWidth **不含 border**（只含 padding + 内容）。
      //    .app-card 的 padding 是 20px*2 = 40px，所以卡片内容宽 = clientWidth - 40。
      //    第一轮按 F04 时期对"计算宽（getBoundingClientRect 含 border）"的 42px 算，
      //    少了 2px，把一个正好铺满的表格误判成"超出 2px"。
      //    教训同 F03/F04：**先确认自己量的是哪个盒子**，再比大小。
      cardInner: card.clientWidth - 40,
      tableW: table ? table.getBoundingClientRect().width : 0,
      wrapScrollW: wrap ? wrap.scrollWidth : 0,
      wrapClientW: wrap ? wrap.clientWidth : 0
    }
  })()`)
  note(`  表格适配实测: ${JSON.stringify(tableFit)}`)
  ok('会话一 表格宽度不超过卡片内容宽度（8 列 minWidth 总和没超标）',
    tableFit.tableW <= tableFit.cardInner + 1, `${tableFit.tableW} / ${tableFit.cardInner}`)
  ok('会话一 表格内部也没有出现横向滚动（列宽刚好铺满）',
    tableFit.wrapScrollW <= tableFit.wrapClientW + 1, `${tableFit.wrapScrollW}/${tableFit.wrapClientW}`)

  // ---- 我的投稿 ----
  await spaPush('/articles/mine')
  await waitFor(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 10`, 15000)
  const mineTitle = await evalJs(`(() => { const e = document.querySelector('.page-header__title'); return e ? e.textContent.trim() : null })()`)
  ok('会话一 SPA 导航到「我的投稿」页并渲染', mineTitle === '我的投稿', JSON.stringify(mineTitle))
  sc = await scrollInfo()
  ok('会话一 /articles/mine 在 1440 宽下无横向滚动条', sc.sw <= sc.cw, `${sc.sw}/${sc.cw}`)
  await shot('F06-02-author-mine-1440')

  // ---- 作者访问审核页应被权限守卫拦到 /403 ----
  await spaPush('/articles/audit')
  const at403 = await waitFor(`location.pathname.endsWith('/403')`, 12000)
  ok('会话一 作者（无 article:audit）访问 /articles/audit 被守卫拦到 /403', at403,
    String(await evalJs(`location.pathname`)))

  const excA = EXC_MARK()
  const errA = CONSOLE_ERR()
  ok('会话一 全程无未捕获异常', excA === 0, String(excA))
  ok('会话一 无 JS 层面的 console.error（TypeError/ReferenceError 这类）',
    !errA.some((m) => /TypeError|ReferenceError|is not a function|Cannot read/.test(m)),
    JSON.stringify(errA).slice(0, 300))

  // =========================================================================
  // 会话二：编辑（editor / 李编辑）—— 有初审权，无发布权
  // =========================================================================
  const loginEditor = await login('editor', '123456')
  ok('会话二 编辑登录成功', loginEditor)
  await spaPush('/articles/audit')
  const auditReady = await waitFor(`!!document.querySelector('.page-header__title') && document.querySelector('.page-header__title').textContent.includes('待我审核')`, 15000)
  ok('会话二 审核工作台渲染（标题「待我审核」）', auditReady,
    JSON.stringify(await evalJs(`(() => { const e = document.querySelector('.page-header__title'); return e ? e.textContent.trim() : null })()`)))

  const stageSwitch = await evalJs(`document.querySelectorAll('.page-header__actions .el-radio-group').length`)
  ok('会话二 编辑无 article:publish → 不出现「初审/终审」切换按钮', stageSwitch === 0, String(stageSwitch))

  const auditQueueRows = await evalJs(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length`)
  ok('会话二 待审队列有数据（伪造的 10 行里按 status=1 过滤后仍有行）', auditQueueRows >= 1, String(auditQueueRows))

  // ---- 进入某一篇的审核面板（用 ?id=1 直达，绕过队列点击） ----
  await spaPush('/articles/audit?id=1')
  const panelReady = await waitFor(`!!document.querySelector('.audit-panel')`, 15000)
  ok('会话二 带 ?id=1 能直接打开审核面板', panelReady)

  const previewInfo = await evalJs(`(() => {
    const p = document.querySelector('.audit-preview')
    const pre = p ? p.querySelector('.audit-preview__pre') : null
    const meta = p ? p.querySelector('.audit-preview__meta') : null
    const tag = p ? p.querySelector('.status-tag') : null
    return {
      title: p ? (p.querySelector('.audit-preview__title')||{}).textContent : null,
      hasContent: !!pre,
      contentWhiteSpace: pre ? getComputedStyle(pre).whiteSpace : null,
      metaText: meta ? meta.textContent.replace(/\\s+/g,' ') : null,
      tagText: tag ? tag.textContent.replace(/\\s+/g,'') : null,
      timelineItems: document.querySelectorAll('.audit-side .el-timeline-item').length,
      timelineEmpty: !!document.querySelector('.audit-side .el-empty')
    }
  })()`)
  note(`  审核面板实测: ${JSON.stringify(previewInfo)}`)
  ok('会话二 只读预览渲染了标题与正文', !!previewInfo.title && previewInfo.hasContent, JSON.stringify(previewInfo.title))
  ok('会话二 正文容器是 pre-wrap（长行会折行，不会顶破页面）', previewInfo.contentWhiteSpace === 'pre-wrap', String(previewInfo.contentWhiteSpace))
  ok('会话二 预览里有状态标签与作者', !!previewInfo.tagText && /张晓投稿/.test(previewInfo.metaText || ''), JSON.stringify(previewInfo.metaText))

  /*
   * ★ 核查项：审核页驳回时不填意见无法提交
   * 判据用"按钮上的 disabled 属性"——F05 的结论是「按钮置灰」与「点击后报错」
   * 是两种互斥设计，本页选了置灰，所以断言就看 disabled。
   */
  const auditBtnState = () =>
    evalJs(`(() => {
      const btns = Array.from(document.querySelectorAll('.audit-side__actions button'))
      const submit = btns.find(b => b.textContent.replace(/\\s+/g,'') === '提交审核')
      const hint = document.querySelector('.audit-side__hint')
      return {
        submitExists: !!submit,
        disabled: submit ? submit.disabled : null,
        hint: hint ? hint.textContent.trim() : null,
        hintIsError: hint ? hint.classList.contains('is-error') : null,
        opacities: btns.map(b => getComputedStyle(b).opacity)
      }
    })()`)

  let bs = await auditBtnState()
  note(`  未选结论时: ${JSON.stringify(bs)}`)
  ok('★ 会话二 未选结论时「提交审核」禁用', bs.submitExists && bs.disabled === true, JSON.stringify(bs.disabled))
  ok('会话二 未选结论时提示为「请先选择审核结论」', bs.hint === '请先选择审核结论', JSON.stringify(bs.hint))

  // 点「驳回」：意见为空 → 仍然禁用
  await evalJs(`(() => {
    const radios = document.querySelectorAll('.audit-side__result .el-radio')
    radios[1].click(); return true
  })()`)
  await sleep(400)
  bs = await auditBtnState()
  note(`  选驳回但意见为空时: ${JSON.stringify(bs)}`)
  ok('★ 会话二 选「驳回」但不填意见 → 「提交审核」仍然禁用', bs.disabled === true, JSON.stringify(bs.disabled))
  ok('★ 会话二 选「驳回」不填意见时提示转危险色并写明原因',
    bs.hint === '驳回时必须填写审核意见' && bs.hintIsError === true, JSON.stringify([bs.hint, bs.hintIsError]))
  await shot('F06-03-audit-reject-blocked')

  // 填意见 → 可提交
  await evalJs(`(() => {
    const ta = document.querySelector('.audit-side textarea')
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(ta, '内容结构不清晰，请补充小标题')
    ta.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(400)
  bs = await auditBtnState()
  note(`  选驳回且填了意见时: ${JSON.stringify(bs)}`)
  ok('★ 会话二 驳回 + 填了意见 → 「提交审核」解禁', bs.disabled === false, JSON.stringify(bs.disabled))

  // 切回「通过」，把意见清空 → 仍可提交（通过时意见选填）
  await evalJs(`(() => {
    const radios = document.querySelectorAll('.audit-side__result .el-radio')
    radios[0].click(); return true
  })()`)
  await sleep(300)
  await evalJs(`(() => {
    const ta = document.querySelector('.audit-side textarea')
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(ta, '')
    ta.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(400)
  bs = await auditBtnState()
  note(`  选通过且意见为空时: ${JSON.stringify(bs)}`)
  ok('★ 会话二 选「通过」且意见为空 → 可以提交（通过时意见选填）', bs.disabled === false, JSON.stringify(bs.disabled))
  ok('会话二 选「通过」时提示不再是危险色', bs.hintIsError === false, JSON.stringify(bs.hintIsError))

  // ---- 审核流水时间线（伪造的 2 条：驳回 + 通过，倒序后通过在上） ----
  ok('会话二 审核流水渲染了 2 个节点', previewInfo.timelineItems === 2, String(previewInfo.timelineItems))
  const logNodes = await evalJs(`Array.from(document.querySelectorAll('.audit-side .audit-node')).map(n => ({
    stage: (n.querySelector('.audit-node__stage')||{}).textContent,
    tag: ((n.querySelector('.el-tag')||{}).textContent||'').trim(),
    hasOpinion: !!n.querySelector('.audit-node__opinion')
  }))`)
  note(`  审核流水实测: ${JSON.stringify(logNodes)}`)
  ok('会话二 流水按时间倒序（最新的「通过」在上）', logNodes[0]?.tag === '通过', JSON.stringify(logNodes.map((n) => n.tag)))
  ok('会话二 驳回节点显示意见块、通过节点（无意见）不显示',
    logNodes[1]?.tag === '驳回' && logNodes[1]?.hasOpinion === true && logNodes[0]?.hasOpinion === false,
    JSON.stringify(logNodes.map((n) => n.hasOpinion)))

  sc = await scrollInfo()
  note(`  /articles/audit 1440 滚动量: ${JSON.stringify(sc)}`)
  ok('★ 会话二 /articles/audit 在 1440 宽下无横向滚动条', sc.sw <= sc.cw, `${sc.sw}/${sc.cw}`)

  const excB = EXC_MARK() - excA
  const errB = CONSOLE_ERR().slice(errA.length)
  ok('会话二 全程无未捕获异常', excB === 0, String(excB))
  ok('会话二 无 JS 层面的 console.error',
    !errB.some((m) => /TypeError|ReferenceError|is not a function|Cannot read/.test(m)),
    JSON.stringify(errB).slice(0, 300))

  // =========================================================================
  // 会话三：主编（chief / 王主编）—— 终审 + 发布权
  // =========================================================================
  const loginChief = await login('chief', '123456')
  ok('会话三 主编登录成功', loginChief)

  await waitFor(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 10`, 15000)
  const chiefBtns = await readRowActionButtons()
  note('  主编操作列实测:')
  chiefBtns.forEach((b, i) => note(`    状态${i} → ${JSON.stringify(b.map((x) => x.text))}`))
  note('')
  for (let s = 0; s <= 9; s++) {
    const got = (chiefBtns[s] || []).map((b) => b.text)
    const exp = expectedLabels(s, 'chief')
    ok(`★ 主编 · 状态${s} 操作列 = [${exp.join('/')}]`, JSON.stringify(got) === JSON.stringify(exp), `实得 [${got.join('/')}]`)
  }
  ok('★ 主编在「已下架」行**看不到**「重新发布」（状态机没有 OFFLINE→PUBLISH_NOW）',
    !(chiefBtns[8] || []).some((b) => b.text === '重新发布'),
    JSON.stringify((chiefBtns[8] || []).map((b) => b.text)))
  ok('★ 主编在「已撤回」行**能**看到「重新发布」（对照，说明 7 与 8 确实不同）',
    (chiefBtns[7] || []).some((b) => b.text === '重新发布'))
  ok('★ 主编能看到「立即发布」「排期发布」（状态3）',
    (chiefBtns[3] || []).some((b) => b.text === '立即发布') && (chiefBtns[3] || []).some((b) => b.text === '排期发布'))
  await shot('F06-04-chief-list-1440')

  // ---- 主编进审核页：应出现环节切换，默认终审 ----
  await spaPush('/articles/audit')
  await waitFor(`!!document.querySelector('.page-header__title') && document.querySelector('.page-header__title').textContent.includes('待我审核')`, 15000)
  const stageInfo = await evalJs(`(() => {
    const g = document.querySelector('.page-header__actions .el-radio-group')
    if (!g) return null
    const labels = Array.from(g.querySelectorAll('.el-radio-button')).map(b => b.textContent.trim())
    const active = Array.from(g.querySelectorAll('.el-radio-button')).findIndex(b => b.classList.contains('is-active'))
    const q = document.querySelector('.queue-count')
    return { labels, active, queue: q ? q.textContent.trim() : null }
  })()`)
  note(`  环节切换实测: ${JSON.stringify(stageInfo)}`)
  ok('会话三 主编同时具备初审与终审权 → 出现环节切换', !!stageInfo, JSON.stringify(stageInfo))
  ok('会话三 环节切换的两个按钮是「编辑初审」「主编终审」',
    !!stageInfo && stageInfo.labels.join(',') === '编辑初审,主编终审', JSON.stringify(stageInfo?.labels))
  ok('会话三 默认选中「主编终审」（主编有 article:publish）', !!stageInfo && stageInfo.active === 1, String(stageInfo?.active))
  ok('会话三 队列提示显示当前环节为「主编终审」', !!stageInfo && /主编终审/.test(stageInfo.queue || ''), JSON.stringify(stageInfo?.queue))
  await shot('F06-05-chief-audit-switch')

  // ---- 详情页：审核流水 + 版本列表 ----
  await spaPush('/articles/detail/1')
  const detailReady = await waitFor(`!!document.querySelector('.article-detail .el-descriptions')`, 15000)
  ok('会话三 详情页渲染（基本信息 descriptions 存在）', detailReady)

  const detailInfo = await evalJs(`(() => {
    const root = document.querySelector('.article-detail')
    const cards = root.querySelectorAll('.app-card').length
    const titles = Array.from(root.querySelectorAll('.block-title')).map(e => e.textContent.trim())
    const times = root.querySelectorAll('.audit-timeline .el-timeline-item').length
    const versionRows = root.querySelectorAll('.el-table__body-wrapper tbody tr').length
    const pre = root.querySelector('.content-pre')
    const rejectAlert = root.querySelector('.reject-alert')
    return {
      cards, titles, timelineItems: times, versionRows,
      hasContent: !!pre,
      contentWhiteSpace: pre ? getComputedStyle(pre).whiteSpace : null,
      rejectText: rejectAlert ? rejectAlert.textContent.replace(/\\s+/g,'') : null,
      rollbackButtons: Array.from(root.querySelectorAll('.el-table__body-wrapper tbody button')).map(b => b.textContent.replace(/\\s+/g,''))
    }
  })()`)
  note(`  详情页实测: ${JSON.stringify(detailInfo)}`)
  ok('会话三 详情页四个区块齐全（基本信息/摘要正文/审核流水/版本记录）',
    detailInfo.titles.join(',') === '摘要,正文,审核流水,版本记录', JSON.stringify(detailInfo.titles))
  ok('会话三 详情页有审核流水时间线（2 条，与审核页同一份数据）', detailInfo.timelineItems === 2, String(detailInfo.timelineItems))
  ok('会话三 详情页版本列表渲染了 2 行', detailInfo.versionRows === 2, String(detailInfo.versionRows))
  ok('会话三 每行版本都有「回滚」按钮', detailInfo.rollbackButtons.filter((t) => t === '回滚').length === 2,
    JSON.stringify(detailInfo.rollbackButtons))
  ok('会话三 详情页正文用 pre-wrap（长行折行）', detailInfo.contentWhiteSpace === 'pre-wrap', String(detailInfo.contentWhiteSpace))
  ok('会话三 详情页显示了驳回意见（有被驳回过的痕迹）',
    !!detailInfo.rejectText && detailInfo.rejectText.includes('摘要太短'), JSON.stringify(detailInfo.rejectText))

  sc = await scrollInfo()
  note(`  /articles/detail/1 1440 滚动量: ${JSON.stringify(sc)}`)
  ok('★ 会话三 /articles/detail/1 在 1440 宽下无横向滚动条', sc.sw <= sc.cw, `${sc.sw}/${sc.cw}`)
  await shot('F06-06-chief-detail-1440')

  // ---- 窄屏（1024）也不该出现横向滚动条 ----
  await setViewport(1024, 800)
  await sleep(600)
  sc = await scrollInfo()
  note(`  窄屏 1024 滚动量: ${JSON.stringify(sc)}`)
  ok('会话三 窄屏 1024 详情页仍无横向滚动条（不是只有 1440 能看）', sc.sw <= sc.cw, `${sc.sw}/${sc.cw}`)
  await setViewport(1440, 900)
  await sleep(400)

  const excC = EXC_MARK() - excA - excB
  const errC = CONSOLE_ERR().slice(errA.length + errB.length)
  ok('会话三 全程无未捕获异常', excC === 0, String(excC))
  ok('会话三 无 JS 层面的 console.error',
    !errC.some((m) => /TypeError|ReferenceError|is not a function|Cannot read/.test(m)),
    JSON.stringify(errC).slice(0, 300))

  // =========================================================================
  // 收尾
  // =========================================================================
  note('')
  note('  被拦截并伪造的请求:')
  intercepted.slice(0, 20).forEach((x) => note(`    ${x}`))
  note('')

  writeFileSync(`${OUT_DIR}/f06-run.txt`, log.join('\n'), 'utf8')
  const passed = results.filter((r) => r.startsWith('PASS')).length
  const failed = results.filter((r) => r.startsWith('FAIL')).length
  console.log('\n================ 结果 ================')
  console.log(results.join('\n'))
  console.log(`\n通过 ${passed} / 共 ${passed + failed}`)
  writeFileSync(`${OUT_DIR}/f06-summary.txt`, `通过 ${passed} / 共 ${passed + failed}\n\n` + results.join('\n'), 'utf8')

  cdp.ws.close()
  chrome.kill()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('验证脚本异常:', e)
  writeFileSync(
    `${OUT_DIR}/f06-error.txt`,
    `${String(e.stack || e)}\n\n---- 异常前已完成的断言 ----\n` + results.join('\n') + '\n\n---- 过程日志 ----\n' + log.join('\n'),
    'utf8'
  )
  console.error('\n---- 异常前已完成的断言 ----\n' + results.join('\n'))
  try {
    chrome.kill()
  } catch (_) {}
  process.exit(2)
})
