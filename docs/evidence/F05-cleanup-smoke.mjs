/**
 * F05 收尾冒烟：删掉临时示例页之后，确认应用本身没被带坏
 *
 * 与 F04 那次同一套路，只是把"检查有没有 F04Demo 残留"扩成"任何 /dev/ 临时页的残留"。
 * 后面 F06、S09…继续做页面时，这个脚本可以直接改 PORT 复用。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9338
const BASE = 'http://localhost:8080/cms-mis'
const OUT_DIR = 'D:/mybatis-demo/project1/target/f05-check'

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
const loginForm = await evalJs(`(() => ({
  inputCount: document.querySelectorAll('input').length,
  buttons: Array.from(document.querySelectorAll('button')).map(b => b.textContent.trim())
}))()`)
ok('③ 登录表单渲染（至少 2 个输入框）', loginForm.inputCount >= 2, `inputs=${loginForm.inputCount}`)
// ⚠️ el-button 默认 autoInsertSpace：两个汉字中间会被塞空格（"登录" 在 DOM 里是 "登 录"）
ok('③ 有登录按钮', loginForm.buttons.some((b) => b.replace(/\s/g, '').includes('登录')), JSON.stringify(loginForm.buttons))

ok('④ 无未捕获异常', events.filter((e) => e.method === 'Runtime.exceptionThrown').length === 0)
ok('④ 无 console.error', events.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error').length === 0)

const reqs = events.filter((e) => e.method === 'Network.requestWillBeSent').map((e) => e.params.request.url)
ok('⑤ 没有任何请求打到临时 /dev/ 页面', reqs.filter((u) => u.includes('/dev/')).length === 0)
ok('⑤ 页面确实加载了前端产物', reqs.filter((u) => /\.js$|\.css$/.test(u)).length > 0, `js/css=${reqs.filter((u) => /\.js$|\.css$/.test(u)).length}`)
const jsList = reqs.filter((u) => /\.js$/.test(u)).map((u) => u.split('/').pop())
ok('⑤ 加载的 js 里不含任何 Demo chunk', !jsList.some((n) => /Demo/i.test(n)), JSON.stringify(jsList))

writeFileSync(`${OUT_DIR}/smoke-run.txt`, results.join('\n'), 'utf8')
console.log('\n' + results.join('\n'))
const failed = results.filter((r) => r.startsWith('FAIL')).length
console.log(`\n通过 ${results.length - failed} / 共 ${results.length}`)
cdp.ws.close()
chrome.kill()
process.exit(failed === 0 ? 0 : 1)
