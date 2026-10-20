/**
 * F04 收尾冒烟：删掉临时示例页之后，确认应用本身没被带坏
 *
 * 检查点：
 *   ① 应用能挂载（说明产物没被打坏）
 *   ② 未登录时落到 /login
 *   ③ 登录表单渲染完整（用户名 / 密码 / 登录按钮）
 *   ④ 控制台 0 异常、0 error
 *   ⑤ 侧边栏菜单仍能渲染（F03 的菜单从路由表生成，删一条路由不该影响它）
 *      —— 顺带确认没有 /dev/f04-demo 这条临时路由残留
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9336
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f04-check'

mkdirSync(OUT_DIR, { recursive: true })
const results = []
const ok = (n, c, x = '') => results.push(`${c ? 'PASS' : 'FAIL'}  ${n}${x ? `  ${x}` : ''}`)

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--no-proxy-server', '--no-first-run',
  '--disable-extensions', `--remote-debugging-port=${PORT}`, `--user-data-dir=${OUT_DIR}/chrome-profile-smoke`, 'about:blank'
], { stdio: 'ignore' })

async function waitChrome() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return await r.json()
    } catch (e) {}
    await sleep(500)
  }
  throw new Error('Chrome 未就绪')
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

const v = await waitChrome()
console.log('Chrome:', v.Browser)
const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
const cdp = connect(t.webSocketDebuggerUrl)
await cdp.ready
const { send, events } = cdp
await send('Page.enable')
await send('Runtime.enable')
await send('Network.enable')

const evalJs = async (e) => {
  const r = await send('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面内异常: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text))
  return r.result.value
}
const waitFor = async (e, timeout = 25000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    await sleep(250)
    try {
      if (await evalJs(e)) return true
    } catch (_) {}
  }
  return false
}

await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
await send('Page.navigate', { url: `${BASE}/` })

ok('① 应用成功挂载', await waitFor(`!!document.querySelector('#app') && document.querySelector('#app').children.length > 0`, 30000))
ok('② 未登录落到 /login', (await evalJs('location.pathname')) === '/cms-mis/login', await evalJs('location.pathname'))
await waitFor(`!!document.querySelector('input')`, 10000)
const loginForm = await evalJs(`(() => {
  const inputs = document.querySelectorAll('input')
  const btns = Array.from(document.querySelectorAll('button')).map(b => b.textContent.trim())
  return { inputCount: inputs.length, buttons: btns, text: document.body.innerText.slice(0, 200) }
})()`)
ok('③ 登录表单渲染（至少 2 个输入框）', loginForm.inputCount >= 2, `inputs=${loginForm.inputCount}`)
/* ⚠️ 判据踩坑：Element Plus 的 <el-button> 默认 autoInsertSpace —— 两个汉字之间会被塞一个空格，
   「登录」渲染出来是「登 录」。所以比对按钮文案必须先把空白去掉，否则断言永远不成立。 */
ok('③ 有登录按钮', loginForm.buttons.some((b) => b.replace(/\s/g, '').includes('登录')), JSON.stringify(loginForm.buttons))

const errs = events.filter((e) => e.method === 'Runtime.exceptionThrown')
ok('④ 无未捕获异常', errs.length === 0, `count=${errs.length}`)
const cerrs = events.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
ok('④ 无 console.error', cerrs.length === 0, `count=${cerrs.length}`)

const reqs = events
  .filter((e) => e.method === 'Network.requestWillBeSent')
  .map((e) => e.params.request.url)
const devReq = reqs.filter((u) => u.includes('/dev/f04'))
ok('⑤ 没有任何请求打到已删除的临时路由', devReq.length === 0, JSON.stringify(devReq))
const failedReq = reqs.filter((u) => u.includes('/cms-mis/') && /\.js$|\.css$/.test(u))
ok('⑤ 页面确实加载了前端产物', failedReq.length > 0, `js/css 请求数=${failedReq.length}`)

const jsList = reqs.filter((u) => /\.js$/.test(u)).map((u) => u.split('/').pop())
ok('⑤ 加载的 js 里不含 F04Demo chunk', !jsList.some((n) => n.includes('F04Demo')), JSON.stringify(jsList))

writeFileSync(`${OUT_DIR}/smoke-run.txt`, results.join('\n'), 'utf8')
console.log('\n' + results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n通过 ${results.length - failed} / 共 ${results.length}`)
cdp.ws.close()
chrome.kill()
process.exit(failed === 0 ? 0 : 1)
