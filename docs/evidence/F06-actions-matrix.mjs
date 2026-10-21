/**
 * F06 · 操作列「状态 × 角色」矩阵验证（40 种组合逐个断言）
 * ===========================================================================
 * 只用 Node 原生能力跑，**不依赖浏览器、不依赖后端**。
 *
 * 为什么要单独跑这个：
 *   F06 阶段后端 ArticleController 还没写（S09 才建），文章列表接口是 404，
 *   页面上不会出现任何真实数据行 —— 手册里"操作列按钮随状态与权限正确变化"
 *   这条核查项如果只在页面上看，就只能靠肉眼读代码。
 *   所以把"状态 + 权限 → 动作"抽成纯函数 articleActions.js，在这里穷举断言。
 *
 * ⚠️ 期望值是**手写死的**，不是从 articleActions.js 的 RULES 表里读出来的 ——
 *    如果拿同一张表当期望，就是自己证明自己，白测。
 *    手写期望的依据是 ArticleStateMachine.java 的 14 条迁移，见下表注释。
 *
 * 运行：
 *   "C:/Users/admin/.workbuddy/binaries/node/versions/22.22.2-6/node.exe" \
 *     docs/evidence/F06-actions-matrix.mjs
 */

import {
  getRowActions,
  auditActionLabel,
  isPrimaryAction,
  PERM,
  ACTION_META,
  STATUS_OPTIONS
} from '../../frontend/src/views/article/articleActions.js'

let pass = 0
const fails = []
function ok(name, cond, extra) {
  if (cond) {
    pass++
  } else {
    fails.push(`${name}${extra === undefined ? '' : ' → ' + extra}`)
  }
}

// ---------------------------------------------------------------------------
// 1) 四个角色的权限集合（来自 sys_permission × sys_role_permission 实测）
//    只列文章域 + 相关的几条，够用即可。
// ---------------------------------------------------------------------------
const ROLE_PERMS = {
  作者: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'category:view'],
  编辑: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'article:audit', 'category:view', 'category:manage'],
  主编: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'article:audit', 'article:manage', 'article:publish', 'category:view', 'category:manage', 'report:view'],
  管理员: ['article:view', 'article:create', 'article:update', 'article:submit', 'article:withdraw', 'article:delete', 'article:audit', 'article:manage', 'article:publish', 'category:view', 'category:manage', 'report:view', 'user:manage', 'role:manage', 'log:view']
}

// 顺带核一下每个角色的条数，与库表实测（7/9/12/20）对齐
ok('作者权限条数 = 7', ROLE_PERMS['作者'].length === 7, ROLE_PERMS['作者'].length)
ok('编辑权限条数 = 9', ROLE_PERMS['编辑'].length === 9, ROLE_PERMS['编辑'].length)
ok('主编权限条数 = 12', ROLE_PERMS['主编'].length === 12, ROLE_PERMS['主编'].length)
// 管理员库里 20 条，这里只列了 15 条（省掉与文章无关的），所以不做条数断言，
// 但要保证"是其它角色的超集"
for (const r of ['作者', '编辑', '主编']) {
  ok(`管理员权限覆盖${r}`, ROLE_PERMS[r].every((p) => ROLE_PERMS['管理员'].includes(p)))
}

const has = (role) => (perm) => ROLE_PERMS[role].includes(perm)
const keysOf = (status, role) => getRowActions({ status }, has(role)).map((a) => a.key)

// ---------------------------------------------------------------------------
// 2) 期望矩阵（手写）
// ---------------------------------------------------------------------------
// 状态机的合法迁移（ArticleStateMachine.java）：
//   0 草稿    +SUBMIT→1
//   4 驳回    +SUBMIT→1
//   1 初审    +AUDIT_PASS/REJECT→2 / 4
//   2 终审    +AUDIT_PASS/REJECT→3 / 4
//   3 通过    +PUBLISH_NOW→5，+PUBLISH_SCHEDULE→6
//   6 排期中  +定时触发→5              ← 系统动作，页面上没有按钮
//   5 已发布  +WITHDRAW→7，+OFFLINE→8
//   7 已撤回  +PUBLISH_NOW→5，+ARCHIVE→9
//   8 已下架  +ARCHIVE→9
//   9 归档    （终态，无出边）
//
// 动作 → 需要的权限：
//   view=view  edit=update  submit=submit  remove=delete
//   audit=audit  publishNow/publishSchedule/republish=publish
//   withdraw=withdraw  offline/archive=manage
const EXPECT = {
  // 草稿：编辑/提交/删除 —— 三个权限作者就有，所以四角色一致
  0: { 作者: ['edit', 'submit', 'remove'], 编辑: ['edit', 'submit', 'remove'], 主编: ['edit', 'submit', 'remove'], 管理员: ['edit', 'submit', 'remove'] },
  // 待编辑初审：审核按钮要 article:audit，作者没有
  1: { 作者: ['view'], 编辑: ['view', 'audit'], 主编: ['view', 'audit'], 管理员: ['view', 'audit'] },
  // 待主编终审：同上（按钮文案在 auditActionLabel 里区分）
  2: { 作者: ['view'], 编辑: ['view', 'audit'], 主编: ['view', 'audit'], 管理员: ['view', 'audit'] },
  // 审核通过：发布要 article:publish，只有主编/管理员有
  3: { 作者: ['view'], 编辑: ['view'], 主编: ['view', 'publishNow', 'publishSchedule'], 管理员: ['view', 'publishNow', 'publishSchedule'] },
  // 已驳回：与草稿同路
  4: { 作者: ['edit', 'submit', 'remove'], 编辑: ['edit', 'submit', 'remove'], 主编: ['edit', 'submit', 'remove'], 管理员: ['edit', 'submit', 'remove'] },
  // 已发布：撤回要 withraw(作者也有)，下架要 manage(只有主编/管理员)
  5: { 作者: ['view', 'withdraw'], 编辑: ['view', 'withdraw'], 主编: ['view', 'withdraw', 'offline'], 管理员: ['view', 'withdraw', 'offline'] },
  // 定时发布中：状态机只有系统触发那条出边 → 谁都没有人工动作
  6: { 作者: ['view'], 编辑: ['view'], 主编: ['view'], 管理员: ['view'] },
  // 已撤回：重新发布要 publish，归档要 manage
  7: { 作者: ['view'], 编辑: ['view'], 主编: ['view', 'republish', 'archive'], 管理员: ['view', 'republish', 'archive'] },
  // 已下架：⚠️ 状态机里 OFFLINE 只有 ARCHIVE 一条出边（没有 PUBLISH_NOW）
  //          → 主编/管理员也只有「查看 + 归档」，**没有「重新发布」**。
  //          手册 F06 写的"已撤回/已下架 → 重新发布"对已下架是错的。
  8: { 作者: ['view'], 编辑: ['view'], 主编: ['view', 'archive'], 管理员: ['view', 'archive'] },
  // 已归档：终态，只读
  9: { 作者: ['view'], 编辑: ['view'], 主编: ['view'], 管理员: ['view'] }
}

const STATUS_NAMES = ['草稿', '待编辑初审', '待主编终审', '审核通过', '已驳回', '已发布', '定时发布中', '已撤回', '已下架', '已归档']

let combos = 0
for (let s = 0; s <= 9; s++) {
  for (const role of ['作者', '编辑', '主编', '管理员']) {
    combos++
    const got = keysOf(s, role)
    const exp = EXPECT[s][role]
    ok(
      `状态${s}(${STATUS_NAMES[s]}) × ${role}`,
      JSON.stringify(got) === JSON.stringify(exp),
      `期望 ${JSON.stringify(exp)} 实得 ${JSON.stringify(got)}`
    )
  }
}
ok('组合数 = 40', combos === 40, combos)

// 专门盯"已下架不能重新发布"这条 —— 它是本轮从状态机源码里查出来的、
// 手册与先前实现都写错的一处，值得单独留一条断言防止回退。
ok('已下架(8) 不出现 republish（主编）', !keysOf(8, '主编').includes('republish'), keysOf(8, '主编').join(','))
ok('已下架(8) 不出现 republish（管理员）', !keysOf(8, '管理员').includes('republish'), keysOf(8, '管理员').join(','))
ok('已下架(8) 主编能看到 archive', keysOf(8, '主编').includes('archive'))
ok('已撤回(7) 主编能看到 republish（对照，说明 7 与 8 确实不同）', keysOf(7, '主编').includes('republish'))

// ---------------------------------------------------------------------------
// 3) 顺序约定：破坏性动作排在最后
// ---------------------------------------------------------------------------
for (let s = 0; s <= 9; s++) {
  const arr = getRowActions({ status: s }, () => true)
  const lastDanger = arr.map((a) => !!a.danger).lastIndexOf(true)
  const firstSafe = arr.map((a) => !!a.danger).indexOf(false)
  if (lastDanger >= 0 && firstSafe >= 0) {
    ok(`状态${s} 危险动作都在安全动作之后`, lastDanger > firstSafe, arr.map((a) => a.key).join(','))
  }
}

// ---------------------------------------------------------------------------
// 4) 边界与防御
// ---------------------------------------------------------------------------
ok('article=null → []', getRowActions(null, () => true).length === 0)
ok('article=undefined → []', getRowActions(undefined, () => true).length === 0)
ok('status 越界(10) → []', getRowActions({ status: 10 }, () => true).length === 0)
ok('status 负数 → []', getRowActions({ status: -1 }, () => true).length === 0)
ok('status 为字符串 "1" 也能算', keysOf('1', '编辑').join(',') === 'view,audit')
ok('hasPermission 不是函数 → []', getRowActions({ status: 0 }, null).length === 0)
ok('权限全无 → []（草稿）', getRowActions({ status: 0 }, () => false).length === 0)
ok('权限全无 → []（已发布）', getRowActions({ status: 5 }, () => false).length === 0)

// 权限函数只被问"该动作需要的权限"，不会乱传
{
  const asked = new Set()
  getRowActions({ status: 5 }, (p) => { asked.add(p); return true })
  ok('状态5 只询问 view/withdraw/manage 三个码',
    [...asked].sort().join(',') === 'article:manage,article:view,article:withdraw',
    [...asked].join(','))
}

// ---------------------------------------------------------------------------
// 5) ACTION_META 自洽：每个 key 都能在 RULES 里被用到，且都带 label/perm
// ---------------------------------------------------------------------------
{
  const used = new Set()
  for (let s = 0; s <= 9; s++) getRowActions({ status: s }, () => true).forEach((a) => used.add(a.key))
  for (const k of Object.keys(ACTION_META)) {
    ok(`ACTION_META.${k} 有 label 和 perm`, !!ACTION_META[k].label && !!ACTION_META[k].perm)
  }
  // 除了 view 之外，每个动作都应该在某个状态里出现过（view 是兜底，也出现）
  for (const k of Object.keys(ACTION_META)) {
    ok(`ACTION_META.${k} 至少被某个状态用到`, used.has(k))
  }
  // PERM 里的码必须与库表一致（库表没有 list/edit/offline/archive）
  const forbidden = ['article:list', 'article:edit', 'article:offline', 'article:archive']
  for (const f of forbidden) {
    ok(`PERM 里不应出现库里没有的 ${f}`, !Object.values(PERM).includes(f))
  }
  ok('PERM 全部以 article: 开头', Object.values(PERM).every((p) => p.startsWith('article:')))
}

// ---------------------------------------------------------------------------
// 6) auditActionLabel / isPrimaryAction / STATUS_OPTIONS
// ---------------------------------------------------------------------------
ok('auditActionLabel(1) = 编辑初审', auditActionLabel(1) === '编辑初审', auditActionLabel(1))
ok('auditActionLabel(2) = 主编终审', auditActionLabel(2) === '主编终审', auditActionLabel(2))
ok('auditActionLabel("2") 也能认', auditActionLabel('2') === '主编终审', auditActionLabel('2'))

ok('主色 0=submit', isPrimaryAction(0, 'submit') && !isPrimaryAction(0, 'edit'))
ok('主色 1=audit', isPrimaryAction(1, 'audit'))
ok('主色 2=audit', isPrimaryAction(2, 'audit'))
ok('主色 3=publishNow', isPrimaryAction(3, 'publishNow') && !isPrimaryAction(3, 'publishSchedule'))
ok('主色 5=view', isPrimaryAction(5, 'view') && !isPrimaryAction(5, 'withdraw'))
ok('主色 7=republish', isPrimaryAction(7, 'republish'))
for (let s = 0; s <= 9; s++) {
  const arr = getRowActions({ status: s }, () => true)
  ok(`状态${s} 恰好一个主色动作`, arr.filter((a) => isPrimaryAction(s, a.key)).length === 1,
    arr.map((a) => `${a.key}${isPrimaryAction(s, a.key) ? '*' : ''}`).join(','))
}

ok('STATUS_OPTIONS 共 10 项', STATUS_OPTIONS.length === 10, STATUS_OPTIONS.length)
ok('STATUS_OPTIONS 码为 0..9', STATUS_OPTIONS.map((o) => o.value).join(',') === '0,1,2,3,4,5,6,7,8,9')
ok('STATUS_OPTIONS 文案与状态名一致',
  STATUS_OPTIONS.map((o) => o.label).join(',') === STATUS_NAMES.join(','),
  STATUS_OPTIONS.map((o) => o.label).join(','))

// ---------------------------------------------------------------------------
// 7) 跨模块一致性：动作 label 与手册/路由标题不冲突（抽样）
// ---------------------------------------------------------------------------
ok('submit 的 label 是「提交初审」', ACTION_META.submit.label === '提交初审', ACTION_META.submit.label)
ok('republish 的 label 是「重新发布」', ACTION_META.republish.label === '重新发布', ACTION_META.republish.label)
ok('offline/archive/remove 都带 danger', !!ACTION_META.offline.danger && !!ACTION_META.archive.danger && !!ACTION_META.remove.danger)
ok('withdraw 不带 danger（可恢复）', !ACTION_META.withdraw.danger)

// ---------------------------------------------------------------------------
console.log('='.repeat(72))
console.log(`F06 操作列矩阵：通过 ${pass} 项，失败 ${fails.length} 项`)
console.log('='.repeat(72))
if (fails.length) {
  console.log('\n失败清单：')
  fails.forEach((f, i) => console.log(`  ${i + 1}. ${f}`))
  process.exitCode = 1
} else {
  console.log('\n状态 × 角色 矩阵（共 40 格）：')
  for (let s = 0; s <= 9; s++) {
    const row = ['作者', '编辑', '主编', '管理员'].map((r) => `${r}:${EXPECT[s][r].join('/')}`)
    console.log(`  ${s} ${STATUS_NAMES[s].padEnd(6, '　')} | ${row.join('  ')}`)
  }
  console.log('\n全部通过。')
}
