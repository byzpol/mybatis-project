/**
 * F05 组件（AuditTimeline / ConfirmAction）的浏览器自动化验证
 *
 * 验证对象：真实部署在 Tomcat 9 上的 war（http://localhost:8080/cms-mis/），
 *   被验证页面是临时的 /dev/f05-demo（frontend/src/views/dev/F05Demo.vue）。
 *
 * 为什么必须真机跑（不是"看代码觉得没问题"）：
 *   ① AuditTimeline 的倒序、折叠阈值、意见块显隐、圆点颜色，都是**渲染结果**，
 *      只有在浏览器里数 DOM 才算数。
 *   ② ConfirmAction 有两个"看起来像能用、其实最容易漏"的点：
 *      · `ElMessageBox` 是函数式调用，**样式要手工 import** —— 漏了不会报错，
 *        只会弹出没有遮罩、按钮是原生样式的框。这条只能靠"量遮罩的背景色"来证。
 *      · `'danger'` 不是 MessageBox 支持的 type，要映射成 warning + 红色按钮类。
 *        只传 type:'danger' 的话图标会静默消失。
 *   ③ 取消路径必须吃掉 promise rejection，否则控制台会冒出一串 Uncaught (in promise)。
 *
 * ⚠️ 已知限制（同 F03/F04）：SPA 用 createWebHistory，Tomcat 没有"未匹配路径回退
 *   index.html"，所以不能整页加载 /cms-mis/dev/f05-demo，要先开 /cms-mis/ 再 pushState 进去。
 *
 * 跑法：node f05-verify.mjs
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9337
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f05-check'

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
    `--user-data-dir=${OUT_DIR}/chrome-profile-f05`, 'about:blank'
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

function connect(wsUrl) {
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
    } else if (m.method) events.push(m)
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

/** 页面内公共小工具，注入一次后各断言复用 */
const HELPERS = `
window.__h = {
  visibleDialog(titlePart) {
    return Array.from(document.querySelectorAll('.el-dialog')).find(d => {
      const t = (d.querySelector('.el-dialog__title') || {}).textContent || ''
      const ov = d.closest('.el-overlay')
      return t.includes(titlePart) && ov && getComputedStyle(ov).display !== 'none'
    }) || null
  },
  visibleMessageBox(titlePart) {
    return Array.from(document.querySelectorAll('.el-message-box')).find(b => {
      const t = (b.querySelector('.el-message-box__title') || {}).textContent || ''
      const ov = b.closest('.el-overlay')
      return t.includes(titlePart) && ov && getComputedStyle(ov).display !== 'none'
    }) || null
  },
  boxButtons(box) {
    return Array.from(box.querySelectorAll('.el-message-box__btns button'))
  },
  bg(el) { return el ? getComputedStyle(el).backgroundColor : '' },
  rgb(s) {
    const m = String(s).match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/)
    return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null
  },
  setTextarea(el, v) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }
};
true
`

async function main() {
  const version = await waitChrome()
  console.log('Chrome:', version.Browser, '| 目标:', BASE)
  note(`Chrome: ${version.Browser}`)
  note(`目标: ${BASE}`)

  const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const cdp = connect(t.webSocketDebuggerUrl)
  await cdp.ready
  const { send, events } = cdp
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error('页面内异常: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text))
    }
    return r.result.value
  }
  const waitFor = async (expr, timeout = 20000) => {
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
  const emits = () => evalJs('JSON.parse(JSON.stringify(window.__f05emits || []))')
  const itemCount = (sel) => evalJs(`document.querySelectorAll('${sel} .el-timeline-item').length`)
  const firstStamp = (sel) =>
    evalJs(`(() => { const e = document.querySelector('${sel} .el-timeline-item .el-timeline-item__timestamp'); return e ? e.textContent.trim() : null })()`)

  /* ============================ 0. 打开并进入演示页 ============================ */
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: `${BASE}/` })
  ok('0.1 应用成功挂载', await waitFor(`!!document.querySelector('#app') && document.querySelector('#app').children.length > 0`, 30000))
  await evalJs(`history.pushState({}, '', '/cms-mis/dev/f05-demo'); dispatchEvent(new PopStateEvent('popstate')); true`)
  ok('0.2 SPA 内导航到 /dev/f05-demo 成功', await waitFor(`!!document.querySelector('#tl-10')`, 20000))
  await evalJs(HELPERS)

  /* ============================ 核查① 时间线三种数据 ============================ */
  // --- 0 条 ---
  const emptyInfo = await evalJs(`(() => {
    const box = document.querySelector('#tl-empty')
    const e = box.querySelector('.el-empty')
    return {
      hasEmpty: !!e,
      desc: e ? (e.querySelector('.el-empty__description') || {}).textContent || '' : '',
      timelineCount: box.querySelectorAll('.el-timeline').length
    }
  })()`)
  note(`  0 条: ${JSON.stringify(emptyInfo)}`)
  ok('① 0 条：渲染 el-empty', emptyInfo.hasEmpty)
  ok('① 0 条：空态文案为「暂无审核记录」', emptyInfo.desc.includes('暂无审核记录'), JSON.stringify(emptyInfo.desc))
  ok('① 0 条：不渲染时间线（不留一条孤零零的竖线）', emptyInfo.timelineCount === 0)

  // --- 3 条 ---
  const c3 = await itemCount('#tl-3')
  ok('① 3 条：渲染 3 个节点', c3 === 3, `count=${c3}`)
  const stamp3 = await firstStamp('#tl-3')
  ok('① 3 条：自己按时间倒序（数组给的是 09-28/10-02/10-01，首条应为最新的 10-02）',
    stamp3 === '2026-10-02 10:00', JSON.stringify(stamp3))
  const stamp3List = await evalJs(`Array.from(document.querySelectorAll('#tl-3 .el-timeline-item__timestamp')).map(e => e.textContent.trim())`)
  ok('① 3 条：三个时间戳都格式化为 YYYY-MM-DD HH:mm，且严格递减',
    JSON.stringify(stamp3List) === JSON.stringify(['2026-10-02 10:00', '2026-10-01 09:30', '2026-09-28 15:30']),
    JSON.stringify(stamp3List))
  ok('① 3 条：没有出现「展开全部」按钮（未超过折叠阈值）',
    (await evalJs(`document.querySelectorAll('#tl-3 .audit-timeline__more button').length`)) === 0)

  const nodes3 = await evalJs(`Array.from(document.querySelectorAll('#tl-3 .audit-node')).map(n => ({
    stage: (n.querySelector('.audit-node__stage') || {}).textContent || '',
    // ⚠️ 必须 trim：模板里 {{ label }} 前面的换行/缩进会变成文本节点，
    //    不 trim 拿到的是 " 通过" 而不是 "通过"（F05 第一轮就栽在这上面）
    tag: ((n.querySelector('.el-tag') || {}).textContent || '').trim(),
    tagCls: Array.from((n.querySelector('.el-tag') || { classList: [] }).classList).find(c => c.startsWith('el-tag--')) || '',
    auditor: ((n.querySelector('.audit-node__auditor') || {}).textContent || '').trim(),
    version: ((n.querySelector('.audit-node__version') || {}).textContent || '').trim(),
    hasOpinion: !!n.querySelector('.audit-node__opinion'),
    opinionReject: !!n.querySelector('.audit-node__opinion--reject'),
    dotColor: (() => {
      const item = n.closest('.el-timeline-item')
      const dot = item ? item.querySelector('.el-timeline-item__node') : null
      return dot ? getComputedStyle(dot).backgroundColor : ''
    })()
  }))`)
  note('  3 条节点实测:')
  nodes3.forEach((n, i) => note(`    #${i + 1} ${JSON.stringify(n)}`))
  note('')

  ok('① 3 条：环节名显示中文（编辑初审 / 主编终审），不是码值也不是「未知环节」',
    nodes3.every((n) => ['编辑初审', '主编终审'].includes(n.stage)),
    JSON.stringify(nodes3.map((n) => n.stage)))
  ok('① 3 条：结论标签为 通过(success) / 驳回(danger)',
    nodes3[0].tag === '通过' && nodes3[0].tagCls === 'el-tag--success' &&
    nodes3[2].tag === '驳回' && nodes3[2].tagCls === 'el-tag--danger',
    JSON.stringify(nodes3.map((n) => [n.tag, n.tagCls])))
  ok('① 3 条：审核人昵称与版本号都渲染了', nodes3[0].auditor === '王主编' && nodes3[0].version === 'v2',
    JSON.stringify([nodes3[0].auditor, nodes3[0].version]))
  ok('① 3 条：通过且意见为空 → 不显示意见块', nodes3[0].hasOpinion === false)
  ok('① 3 条：通过且有意见 → 显示意见块（浅色，非驳回样式）', nodes3[1].hasOpinion === true && nodes3[1].opinionReject === false)
  ok('① 3 条：驳回 → 意见块带 --reject（浅红底）', nodes3[2].hasOpinion === true && nodes3[2].opinionReject === true)

  const rejectStyle = await evalJs(`(() => {
    const el = document.querySelector('#tl-3 .audit-node__opinion--reject')
    const cs = getComputedStyle(el)
    const r = window.__h.rgb(cs.backgroundColor)
    const before = getComputedStyle(el, '::before').content
    const after = getComputedStyle(el, '::after').content
    return { bg: cs.backgroundColor, r, borderLeft: cs.borderLeftColor, before, after, color: cs.color }
  })()`)
  note(`  驳回意见块样式: ${JSON.stringify(rejectStyle)}`)
  ok('① 3 条：驳回意见块是**浅红底**（红 > 绿，且不刺眼）',
    rejectStyle.r && rejectStyle.r.r > 200 && rejectStyle.r.g < rejectStyle.r.r && rejectStyle.r.g > 100,
    JSON.stringify(rejectStyle.bg))
  /*
   * ⚠️ 不要在这里写死 EP 默认的 #F56C6C（=rgb(245,108,108)）。
   * F05 实测拿到的是 rgb(214, 69, 69) —— 那是本项目主题把 --el-color-danger
   * 换成了自己的红。断言写默认值就会把"主题生效"误判成"样式没起效"。
   * 所以判据只认"红"这个事实：R 明显大于 G。
   */
  const borderRgb = JSON.parse(await evalJs(`JSON.stringify(window.__h.rgb(${JSON.stringify(rejectStyle.borderLeft)}))`))
  ok('① 3 条：驳回意见块左侧有红色竖线',
    borderRgb && borderRgb.r > borderRgb.g + 60, JSON.stringify(borderRgb))
  ok('① 3 条：驳回意见带**引号**（伪元素实现，而不是写进文本里）',
    rejectStyle.before.includes('\\u201C') || rejectStyle.before.includes('\u201c') || rejectStyle.before.includes('"') ||
    /u201c/i.test(rejectStyle.before),
    JSON.stringify([rejectStyle.before, rejectStyle.after]))

  const dotColors = await evalJs(`(() => {
    const items = document.querySelectorAll('#tl-3 .el-timeline-item')
    return Array.from(items).map(it => {
      const d = it.querySelector('.el-timeline-item__node')
      return d ? getComputedStyle(d).backgroundColor : null
    })
  })()`)
  note(`  圆点颜色: ${JSON.stringify(dotColors)}`)
  ok('① 3 条：通过节点与驳回节点的圆点颜色不同',
    !!dotColors[0] && !!dotColors[2] && dotColors[0] !== dotColors[2], JSON.stringify(dotColors))
  const parsed = await Promise.all(
    dotColors.map(async (c) => JSON.parse(await evalJs(`JSON.stringify(window.__h.rgb(${JSON.stringify(c)}))`)))
  )
  ok('① 3 条：通过节点圆点是绿的（G 明显大于 R）',
    parsed[0] && parsed[0].g > parsed[0].r + 40, JSON.stringify(parsed[0]))
  ok('① 3 条：驳回节点圆点是红的（R 明显大于 G）',
    parsed[2] && parsed[2].r > parsed[2].g + 40, JSON.stringify(parsed[2]))

  await shot('01-timeline-3')

  // --- 10 条（折叠） ---
  ok('① 10 条：默认只显示最近 3 条', (await itemCount('#tl-10')) === 3, `count=${await itemCount('#tl-10')}`)
  ok('① 10 条：首条是最新的 09-19', (await firstStamp('#tl-10')) === '2026-09-19 10:00', await firstStamp('#tl-10'))
  const moreText = await evalJs(`(() => { const b = document.querySelector('#tl-10 .audit-timeline__more button'); return b ? b.textContent.trim() : null })()`)
  ok('① 10 条：出现「展开全部（共 10 条）」', moreText === '展开全部（共 10 条）', JSON.stringify(moreText))

  await evalJs(`document.querySelector('#tl-10 .audit-timeline__more button').click(); true`)
  await sleep(300)
  ok('① 10 条：点展开 → 渲染全部 10 条', (await itemCount('#tl-10')) === 10, `count=${await itemCount('#tl-10')}`)
  const moreText2 = await evalJs(`(() => { const b = document.querySelector('#tl-10 .audit-timeline__more button'); return b ? b.textContent.trim() : null })()`)
  ok('① 10 条：展开后按钮变为「收起」', moreText2 === '收起', JSON.stringify(moreText2))
  ok('① 10 条：展开后首条仍是最新的 09-19（排序未被破坏）',
    (await firstStamp('#tl-10')) === '2026-09-19 10:00', await firstStamp('#tl-10'))

  await shot('02-timeline-10-expanded')

  await evalJs(`document.querySelector('#tl-10 .audit-timeline__more button').click(); true`)
  await sleep(300)
  ok('① 10 条：再点一次 → 收起回 3 条', (await itemCount('#tl-10')) === 3, `count=${await itemCount('#tl-10')}`)

  const allStages = await evalJs(`Array.from(document.querySelectorAll('#tl-10 .audit-node__stage')).map(e => e.textContent.trim())`)
  ok('① 10 条：所有环节名都识别出来了（无「未知环节」）',
    allStages.length > 0 && allStages.every((s) => ['编辑初审', '主编终审'].includes(s)),
    JSON.stringify(Array.from(new Set(allStages))))

  /* ============================ 核查② requireReason 模式 ============================ */
  await evalJs(`document.querySelector('#ca-reason-btn').click(); true`)
  ok('② requireReason：点击触发元素弹出 el-dialog', await waitFor(`!!window.__h.visibleDialog('驳回理由')`, 5000))

  const dlg1 = await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    if (!d) return null
    const btns = Array.from(d.querySelectorAll('.el-dialog__footer button'))
    const ta = d.querySelector('textarea')
    const hint = d.querySelector('.confirm-action__hint')
    return {
      title: (d.querySelector('.el-dialog__title') || {}).textContent || '',
      btnTexts: btns.map(b => b.textContent.trim()),
      confirmDisabled: btns[btns.length - 1] ? btns[btns.length - 1].disabled : null,
      hasTextarea: !!ta,
      maxlength: ta ? ta.getAttribute('maxlength') : null,
      hintText: hint ? hint.textContent.trim() : '',
      hintIsError: hint ? hint.classList.contains('is-error') : null,
      overlayBg: window.__h.bg(d.closest('.el-overlay'))
    }
  })()`)
  note(`  requireReason 弹窗: ${JSON.stringify(dlg1)}`)
  ok('② requireReason：弹窗标题正确', !!dlg1 && dlg1.title.includes('驳回理由'), JSON.stringify(dlg1?.title))
  ok('② requireReason：有 textarea 且带 maxlength', !!dlg1 && dlg1.hasTextarea && dlg1.maxlength === '200', JSON.stringify(dlg1?.maxlength))
  ok('② requireReason：**未填写原因时确认按钮是 disabled**', dlg1?.confirmDisabled === true, `disabled=${dlg1?.confirmDisabled}`)
  ok('② requireReason：旁边提示「请填写原因」', !!dlg1 && dlg1.hintText.includes('请填写原因'), JSON.stringify(dlg1?.hintText))
  /*
   * ⚠️ 这条断言是 F05 改过组件之后才成立的，记录一下原委：
   *    第一版有个 `touched` 标记（"点了确认却没填"才转红），实测发现**永远到不了** ——
   *    确认键是 :disabled 的，而浏览器**不会给 disabled 的按钮派发 click**，
   *    处理函数压根不执行。于是"按钮置灰"和"点击后报错"两套设计同时存在 = 一段死代码。
   *    现在统一成一种：未填写时提示就是「请填写原因」+ 危险色，与置灰的按钮配对。
   */
  ok('② requireReason：未填写时提示即为「请填写原因」且用危险色（与置灰按钮配对）', dlg1?.hintIsError === true)
  ok('② requireReason：弹窗遮罩已生效（说明 dialog 样式正常）',
    !!dlg1 && (await evalJs(`(() => { const r = window.__h.rgb(${JSON.stringify(dlg1.overlayBg)}); return !!r && r.a > 0 })()`)),
    JSON.stringify(dlg1?.overlayBg))

  // 空原因硬点确认 → 不应 emit
  await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    const btns = Array.from(d.querySelectorAll('.el-dialog__footer button'))
    btns[btns.length - 1].click()
    return true
  })()`)
  await sleep(300)
  const emitsAfterEmptyClick = await emits()
  ok('② requireReason：尝试点确认但**按钮是 disabled**，浏览器不派发 click → 不产生任何 emit',
    emitsAfterEmptyClick.length === 0, JSON.stringify(emitsAfterEmptyClick))
  ok('② requireReason：空原因时弹窗仍然开着（没被误关掉）',
    (await evalJs(`!!window.__h.visibleDialog('驳回理由')`)) === true)
  note('  说明：disabled 的按钮不会派发 click —— 这正是"点击后报错"那种校验方式在这里行不通的原因')

  // 只填空白 → 仍不可提交
  await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    window.__h.setTextarea(d.querySelector('textarea'), '    ')
    return true
  })()`)
  await sleep(300)
  ok('② requireReason：只输入空白（trim 后为空）→ 确认键仍 disabled',
    (await evalJs(`(() => {
      const d = window.__h.visibleDialog('驳回理由')
      const btns = Array.from(d.querySelectorAll('.el-dialog__footer button'))
      return btns[btns.length - 1].disabled
    })()`)) === true)

  // 填有效原因 → 可提交
  await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    window.__h.setTextarea(d.querySelector('textarea'), '缺少实测数据支撑，请补充执行计划对比截图。')
    return true
  })()`)
  await sleep(300)
  const dlg2 = await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    const btns = Array.from(d.querySelectorAll('.el-dialog__footer button'))
    const hint = d.querySelector('.confirm-action__hint')
    const cb = btns[btns.length - 1]
    return {
      confirmDisabled: cb.disabled,
      confirmBg: window.__h.bg(cb),
      confirmCls: Array.from(cb.classList),
      hintText: hint ? hint.textContent.trim() : '',
      hintIsError: hint ? hint.classList.contains('is-error') : null
    }
  })()`)
  note(`  填了原因之后: ${JSON.stringify(dlg2)}`)
  ok('② requireReason：填了原因 → 确认键可用', dlg2.confirmDisabled === false)
  ok('② requireReason：危险操作确认键是**红色**（el-button--danger）',
    dlg2.confirmCls.includes('el-button--danger'), JSON.stringify(dlg2.confirmCls))
  const cbRgb = JSON.parse(await evalJs(`JSON.stringify(window.__h.rgb(${JSON.stringify(dlg2.confirmBg)}))`))
  ok('② requireReason：确认键背景确实是红的（R 明显大于 G）', cbRgb && cbRgb.r > cbRgb.g + 60, JSON.stringify(cbRgb))
  ok('② requireReason：原因填好后提示不再显示错误文案', dlg2.hintIsError === false, JSON.stringify(dlg2.hintText))

  await shot('03-confirm-reason-dialog')

  await evalJs(`(() => {
    const d = window.__h.visibleDialog('驳回理由')
    const btns = Array.from(d.querySelectorAll('.el-dialog__footer button'))
    btns[btns.length - 1].click()
    return true
  })()`)
  await sleep(500)
  const emitsAfterConfirm = await emits()
  ok('② requireReason：确认后 emit 了 confirm 且**带上了原因**',
    emitsAfterConfirm.length === 1 && emitsAfterConfirm[0].type === 'confirm' &&
    emitsAfterConfirm[0].reason === '缺少实测数据支撑，请补充执行计划对比截图。',
    JSON.stringify(emitsAfterConfirm))
  ok('② requireReason：确认后弹窗关闭', (await evalJs(`!window.__h.visibleDialog('驳回理由')`)) === true)

  /* ============================ 核查② 轻量确认（ElMessageBox） ============================ */
  await evalJs(`document.querySelector('#ca-plain-btn').click(); true`)
  ok('② 轻量模式：点击弹出 ElMessageBox', await waitFor(`!!window.__h.visibleMessageBox('确认下架')`, 5000))

  const mb = await evalJs(`(() => {
    const b = window.__h.visibleMessageBox('确认下架')
    if (!b) return null
    const btns = window.__h.boxButtons(b)
    const confirm = btns[btns.length - 1]
    const cancel = btns[0]
    const icon = b.querySelector('.el-message-box__status')
    return {
      title: (b.querySelector('.el-message-box__title') || {}).textContent || '',
      msg: (b.querySelector('.el-message-box__message') || {}).textContent || '',
      btnTexts: btns.map(x => x.textContent.trim()),
      confirmCls: Array.from(confirm.classList),
      confirmBg: window.__h.bg(confirm),
      confirmRadius: getComputedStyle(confirm).borderRadius,
      cancelCls: Array.from(cancel.classList),
      hasIcon: !!icon,
      iconCls: icon ? Array.from(icon.classList) : [],
      overlayCls: Array.from(b.closest('.el-overlay').classList),
      overlayBg: window.__h.bg(b.closest('.el-overlay')),
      boxRadius: getComputedStyle(b).borderRadius,
      boxWidth: Math.round(b.getBoundingClientRect().width)
    }
  })()`)
  note(`  ElMessageBox 实测: ${JSON.stringify(mb)}`)
  ok('② 轻量模式：标题与正文正确',
    !!mb && mb.title.includes('确认下架') && mb.msg.includes('前台立即不可见'), JSON.stringify([mb?.title, mb?.msg]))
  ok('② 轻量模式：按钮文案来自 props（确认下架 / 取消）',
    !!mb && mb.btnTexts[0] === '取消' && mb.btnTexts[1] === '确认下架', JSON.stringify(mb?.btnTexts))
  ok('② 轻量模式：确认键带上了 el-button--danger（danger 映射生效）',
    !!mb && mb.confirmCls.includes('el-button--danger'), JSON.stringify(mb?.confirmCls))
  const mbRgb = JSON.parse(await evalJs(`JSON.stringify(window.__h.rgb(${JSON.stringify(mb?.confirmBg)}))`))
  ok('② 轻量模式：确认键背景是红的（R 明显大于 G）', mbRgb && mbRgb.r > mbRgb.g + 60, JSON.stringify(mbRgb))
  ok('② 轻量模式：有警示图标（说明 danger→warning 的 type 映射生效，没被静默丢掉）',
    !!mb && mb.hasIcon && mb.iconCls.some((c) => c.includes('warning')), JSON.stringify(mb?.iconCls))
  const overlayRgb = JSON.parse(await evalJs(`JSON.stringify(window.__h.rgb(${JSON.stringify(mb?.overlayBg)}))`))
  ok('② 轻量模式：**遮罩有半透明背景色** —— 这条专门用来证明 message-box 的样式真的加载了',
    overlayRgb && overlayRgb.a > 0.2, JSON.stringify(overlayRgb))
  ok('② 轻量模式：弹框是圆角的（同样是"样式已加载"的旁证，未加载时 border-radius 是 0px）',
    !!mb && mb.boxRadius !== '0px' && mb.boxRadius !== '', JSON.stringify(mb?.boxRadius))

  await shot('04-messagebox')

  // 取消路径
  await evalJs(`(() => { const b = window.__h.visibleMessageBox('确认下架'); window.__h.boxButtons(b)[0].click(); return true })()`)
  await sleep(400)
  const emitsAfterCancel = await emits()
  ok('② 轻量模式：点取消 → emit cancel，且**不产生未捕获异常**',
    emitsAfterCancel.length === 2 && emitsAfterCancel[1].type === 'cancel',
    JSON.stringify(emitsAfterCancel.slice(1)))
  ok('② 轻量模式：取消后弹框关闭', (await evalJs(`!window.__h.visibleMessageBox('确认下架')`)) === true)

  // 确认路径
  await evalJs(`document.querySelector('#ca-plain-btn').click(); true`)
  await waitFor(`!!window.__h.visibleMessageBox('确认下架')`, 5000)
  await evalJs(`(() => { const b = window.__h.visibleMessageBox('确认下架'); const bs = window.__h.boxButtons(b); bs[bs.length - 1].click(); return true })()`)
  await sleep(400)
  const emitsFinal = await emits()
  ok('② 轻量模式：点确认 → emit confirm，且 reason 为 null（没填原因的模式不带参数）',
    emitsFinal.length === 3 && emitsFinal[2].type === 'confirm' && emitsFinal[2].reason === null,
    JSON.stringify(emitsFinal.slice(2)))

  /* ============================ 全程无异常 ============================ */
  const thrown = events.filter((e) => e.method === 'Runtime.exceptionThrown')
  const reasons = thrown.map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text || '')
  ok('③ 全程无未捕获异常（含 MessageBox 取消路径）', thrown.length === 0, JSON.stringify(reasons).slice(0, 300))

  const consoleErrors = events
    .filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
    .map((e) => (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '))
  ok('③ 全程无 console.error', consoleErrors.length === 0, JSON.stringify(consoleErrors).slice(0, 300))

  note('')
  note('  emit 记录:')
  ;(await emits()).forEach((e, i) => note(`    #${i + 1} ${JSON.stringify(e)}`))
  note('')

  /* ============================ 收尾 ============================ */
  writeFileSync(`${OUT_DIR}/f05-run.txt`, log.join('\n'), 'utf8')
  const passed = results.filter((r) => r.startsWith('PASS')).length
  const failed = results.filter((r) => r.startsWith('FAIL')).length
  console.log('\n================ 结果 ================')
  console.log(results.join('\n'))
  console.log(`\n通过 ${passed} / 共 ${passed + failed}`)
  writeFileSync(`${OUT_DIR}/f05-summary.txt`, `通过 ${passed} / 共 ${passed + failed}\n\n` + results.join('\n'), 'utf8')

  cdp.ws.close()
  chrome.kill()
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('验证脚本异常:', e)
  writeFileSync(
    `${OUT_DIR}/f05-error.txt`,
    `${String(e.stack || e)}\n\n---- 异常前已完成的断言 ----\n` + results.join('\n') + '\n\n---- 过程日志 ----\n' + log.join('\n'),
    'utf8'
  )
  console.error('\n---- 异常前已完成的断言 ----\n' + results.join('\n'))
  try {
    chrome.kill()
  } catch (_) {}
  process.exit(2)
})
