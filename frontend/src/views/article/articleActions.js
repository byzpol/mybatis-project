/**
 * 文章行的"可执行动作"计算（F06）
 * ===========================================================================
 * 列表页操作列该显示哪几个按钮，完全由「文章当前状态 + 当前用户权限」两个输入决定。
 * 这两个输入之外没有任何其它因素，所以这里把它写成一个**纯函数**：
 *
 *     getRowActions(article, hasPermission) -> [{ key, label, perm, danger }]
 *
 * 为什么单独抽一个文件、而不是写在 ArticleList.vue 里：
 *  · 它是纯函数，**不需要后端、不需要浏览器环境就能逐条验证**。
 *    这一步（F06）后端 ArticleController 还没写（S09 才建），列表接口是 404，
 *    页面上根本不会有真实数据行 —— 如果逻辑埋在 .vue 里，这条最关键的核查项
 *    （"操作列按钮随状态与权限正确变化"）就只能靠肉眼看代码。
 *    抽出来之后可以用 10 个状态 × 4 个角色 = 40 种组合逐个断言（见 docs/F06-*.md）。
 *  · 规则改动只需要改一张表，不用在模板里找散落的 v-if。
 *
 * ---------------------------------------------------------------------------
 * 1) 动作清单是**从状态机反推出来的**，不是拍脑袋
 * ---------------------------------------------------------------------------
 * 依据 `src/main/java/com/lab/cms/common/ArticleStateMachine.java` 的 14 条合法迁移：
 *
 *   DRAFT(0)            + SUBMIT                 -> PENDING_EDITOR(1)
 *   REJECTED(4)         + SUBMIT                 -> PENDING_EDITOR(1)
 *   PENDING_EDITOR(1)   + AUDIT_PASS / REJECT    -> PENDING_CHIEF(2) / REJECTED(4)
 *   PENDING_CHIEF(2)    + AUDIT_PASS / REJECT    -> APPROVED(3)      / REJECTED(4)
 *   APPROVED(3)         + PUBLISH_NOW            -> PUBLISHED(5)
 *   APPROVED(3)         + PUBLISH_SCHEDULE       -> SCHEDULED(6)
 *   SCHEDULED(6)        + PUBLISH_SCHEDULED_TRIGGER -> PUBLISHED(5)   ← **系统触发，不是按钮**
 *   PUBLISHED(5)        + WITHDRAW               -> WITHDRAWN(7)
 *   PUBLISHED(5)        + OFFLINE                -> OFFLINE(8)
 *   WITHDRAWN(7)        + PUBLISH_NOW            -> PUBLISHED(5)
 *   WITHDRAWN(7)        + ARCHIVE                -> ARCHIVED(9)
 *   OFFLINE(8)          + ARCHIVE                -> ARCHIVED(9)
 *
 * ⚠️ 由此得到三个**容易被漏掉**的结论（都逐条核对过状态机源码，不是从注释抄的）：
 *   · `SCHEDULED(6)` 只有一条出边，而且是**定时任务触发**的。
 *     所以"定时发布中"的文章**没有人工可做的动作**（不能撤回、不能下架）——
 *     界面上只给「查看」。这不是漏写，是状态机就这么定的。
 *     如果将来要支持"取消排期"，得先给状态机加一条迁移，而不是只在前端加个按钮。
 *   · `ARCHIVED(9)` 是终态（没有任何出边）→ 只读查看。
 *   · ⚠️ **`OFFLINE(8)` 也只有 `ARCHIVE` 一条出边，没有 `PUBLISH_NOW`**。
 *     《成员A-开发提示词手册》F06 写的是"已撤回/已下架 → 查看、重新发布、归档"，
 *     其中**「已下架→重新发布」在状态机里不存在**（`ArticleStateMachine` 第 13~14 条
 *     只有 `WITHDRAWN+ARCHIVE→ARCHIVED` 和 `OFFLINE+ARCHIVE→ARCHIVED`）。
 *     按项目口径「代码 > 手册」，本文件对 status 8 只给「查看 + 归档」。
 *     真这么下发按钮，后端 `assertTransfer` 会抛 11003（状态非法），
 *     也就是"界面给了个必然失败的按钮"—— 比少一个按钮糟糕得多。
 *     若产品上确实要"下架后重新上架"，正确做法是**先给状态机加第 15 条迁移**
 *     （并同步 `ArticleStateMachineTest` 的"合法 14 条"断言与 `docs/接口契约.md`），
 *     而不是只在前端把按钮加上。已记入 docs/F06-*.md「发现的不一致」。
 *
 * 另外「删除」不在状态机里（它不是状态迁移，是逻辑删除），
 * 按需求只在 DRAFT / REJECTED 两态开放（与后端校验一致）。
 *
 * ---------------------------------------------------------------------------
 * 2) 权限码一律取**数据库 `sys_permission` 里真实存在的那一套**
 * ---------------------------------------------------------------------------
 * 实测（F06 期间重查过库，纠正了此前记错的一版）：
 *   ROLE_AUTHOR(7)  article:create/delete/submit/update/view/withdraw + category:view
 *   ROLE_EDITOR(9)  = AUTHOR 的 7 条 + article:audit + category:manage
 *   ROLE_CHIEF(12)  = EDITOR 的 9 条 + article:manage + article:publish + report:view
 *   ROLE_ADMIN(20)  全部
 *
 * 于是"谁能看见什么"可以直接读出来：
 *   · article:audit    → 编辑 / 主编 / 管理员（**作者没有**）→ 作者看不到初审/终审按钮
 *   · article:publish  → 主编 / 管理员（**编辑没有**）→ 编辑看不到立即发布/排期发布
 *   · article:manage   → 主编 / 管理员 → 下架/归档只有他们能做
 *
 * ⚠️ 前端只按**权限码**判断显示与否。至于"这篇是不是我写的"这类**归属**判断（水平越权），
 *    属于后端 S12 的 `checkOwnership` 职责，前端不做也不该做——
 *    前端做了也拦不住人直接调接口。所以这里不引入"是否本人"这个维度。
 *
 * ⚠️ 顺带记一笔：`docs/接口契约.md` §1.3 用的 `article:list / article:edit /
 *    article:offline / article:archive` 这四个码**库里一个都没有**。
 *    本文件一律跟随库表（理由见 docs/F03-后台布局与主题实测记录.md「权限码口径不一致」）。
 */

/** 权限码常量：集中一处，避免字符串在各文件里手写拼错 */
export const PERM = {
  VIEW: 'article:view',
  CREATE: 'article:create',
  UPDATE: 'article:update',
  SUBMIT: 'article:submit',
  WITHDRAW: 'article:withdraw',
  DELETE: 'article:delete',
  AUDIT: 'article:audit',
  PUBLISH: 'article:publish',
  // 下架与归档共用这一个码（库里只有 article:manage，没有 offline/archive 两个码）
  MANAGE: 'article:manage'
}

/** 动作 key → 展示信息。label 用动词开头，和弹窗标题对得上 */
export const ACTION_META = {
  view: { key: 'view', label: '查看', perm: PERM.VIEW },
  edit: { key: 'edit', label: '编辑', perm: PERM.UPDATE },
  submit: { key: 'submit', label: '提交初审', perm: PERM.SUBMIT },
  audit: { key: 'audit', label: '审核', perm: PERM.AUDIT },
  publishNow: { key: 'publishNow', label: '立即发布', perm: PERM.PUBLISH },
  publishSchedule: { key: 'publishSchedule', label: '排期发布', perm: PERM.PUBLISH },
  republish: { key: 'republish', label: '重新发布', perm: PERM.PUBLISH },
  withdraw: { key: 'withdraw', label: '撤回', perm: PERM.WITHDRAW },
  offline: { key: 'offline', label: '下架', perm: PERM.MANAGE, danger: true },
  archive: { key: 'archive', label: '归档', perm: PERM.MANAGE, danger: true },
  remove: { key: 'remove', label: '删除', perm: PERM.DELETE, danger: true }
}

/**
 * 状态 → 动作顺序表。
 *
 * 顺序约定（手册里各状态的顺序不统一，这里统一一次）：
 *   查看最先（最常用、最安全），破坏性动作最后（删除/归档/下架）。
 *   这样一排按钮的"危险程度"从左到右递增，误点的概率最低。
 *
 * 各状态是否显示，还会被 `ACTION_META.perm` 再过一道权限过滤。
 */
const RULES = {
  0: ['edit', 'submit', 'remove'], // 草稿：编辑、提交初审、删除（手册未给"查看"，与手册一致）
  1: ['view', 'audit'], // 待编辑初审：查看 + 审核（标题在页面上按状态显示"编辑初审"）
  2: ['view', 'audit'], // 待主编终审：查看 + 审核（显示"主编终审"）
  3: ['view', 'publishNow', 'publishSchedule'], // 审核通过：查看、立即发布、排期发布
  4: ['edit', 'submit', 'remove'], // 已驳回：同草稿（可改后重投）
  5: ['view', 'withdraw', 'offline'], // 已发布：查看、撤回、下架
  6: ['view'], // 定时发布中：状态机只有系统触发那条出边 → 无人工动作
  7: ['view', 'republish', 'archive'], // 已撤回：查看、重新发布、归档
  8: ['view', 'archive'], // 已下架：⚠️ 状态机无 OFFLINE→PUBLISH_NOW，**不给「重新发布」**（见文件头注释）
  9: ['view'] // 已归档：终态，只读
}

/**
 * 计算某一行当前该显示哪些动作。
 *
 * @param {{status:number}} article 文章行（至少要有 status；其它字段用不到）
 * @param {(perm:string)=>boolean} hasPermission 权限判断函数，
 *        实际传 pinia 的 `userStore.hasPermission` —— 传函数进来而不是直接 import store，
 *        是为了这个文件保持纯函数、能在没有 pinia 的环境里被断言。
 * @returns {Array<{key:string,label:string,perm:string,danger?:boolean}>}
 */
export function getRowActions(article, hasPermission) {
  const status = article == null ? null : Number(article.status)
  const keys = RULES[status]
  if (!keys) return []

  const check = typeof hasPermission === 'function' ? hasPermission : () => false
  return keys.map((k) => ACTION_META[k]).filter((meta) => meta && check(meta.perm))
}

/**
 * 审核按钮的文案要按**当前状态**区分环节（初审 / 终审）。
 * 这纯粹是展示文案，请求体里**不带 stage** —— 阶段由后端按文章状态判定。
 */
export function auditActionLabel(status) {
  return Number(status) === 2 ? '主编终审' : '编辑初审'
}

/**
 * 状态 → 该状态的"主色"动作 key。用来给最重要的那个按钮加 type="primary"。
 * 列表里按钮都是 link 型，靠颜色区分主次即可，不需要实心。
 */
const PRIMARY = {
  0: 'submit',
  1: 'audit',
  2: 'audit',
  3: 'publishNow',
  4: 'submit',
  5: 'view',
  6: 'view',
  7: 'republish',
  8: 'view', // 已下架只有「查看 + 归档」，主色给查看
  9: 'view'
}

export function isPrimaryAction(status, key) {
  return PRIMARY[Number(status)] === key
}

/** 供页面的"状态下拉"使用；与 StatusTag 的映射表同源，但不引组件以免耦合 */
export const STATUS_OPTIONS = [
  { value: 0, label: '草稿' },
  { value: 1, label: '待编辑初审' },
  { value: 2, label: '待主编终审' },
  { value: 3, label: '审核通过' },
  { value: 4, label: '已驳回' },
  { value: 5, label: '已发布' },
  { value: 6, label: '定时发布中' },
  { value: 7, label: '已撤回' },
  { value: 8, label: '已下架' },
  { value: 9, label: '已归档' }
]
