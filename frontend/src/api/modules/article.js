import { get, post, put, del } from '@/api/request'

/**
 * 文章域接口（成员A 主责）—— 入户线模板
 *
 * 契约来源：docs/接口契约.md §1.3「文章域完整接口清单」。
 * 该表标题写"15 个"，实际列了 16 行 —— 以表体为准，这里按 16 行实现。
 *
 * ⚠️ 与《成员A-开发提示词手册》F02 的 4 处差异（以契约为准，不照抄手册）：
 *   ① 手册：审计入参 {stage, result, opinion}
 *      契约：ArticleAuditDTO = { auditAction(1通过/2驳回), auditOpinion }
 *            —— 审核阶段不传！由文章当前状态决定（status=1 走初审、status=2 走终审），
 *               传 stage 反而会和后端真实状态打架。
 *   ② 手册：POST /articles/{id}/versions/{versionNo}/rollback
 *      契约：POST /articles/{id}/rollback
 *   ③ 手册未列 DELETE /articles/{id}（删除，仅草稿/驳回态）—— 契约第 5 行有，本文件补上。
 *   ④ 手册未列 GET /articles/{id}/audit-logs（审核流水）—— 契约第 9 行有，本文件补上。
 *
 * 本文件只负责"怎么调"，不含任何业务判断（不做权限判断、不做状态判断），
 * 权限由后端 @RequirePermission 兜底，状态合法性由后端 ArticleStateMachine 兜底。
 */

// ---------------------------------------------------------------------------
// 查询
// ---------------------------------------------------------------------------

/**
 * 分页检索文章
 * GET /api/v1/articles        权限 article:list
 * @param {Object} params 查询条件，继承 PageQuery：
 *        page(从1开始) / size(默认10，上限100) / sortField / sortOrder(asc|desc)
 *        + keyword / status / categoryId / tagId / authorId
 *          createTimeFrom / createTimeTo / onlyTop
 * @returns {Promise<{records:Array,total:number,current:number,size:number,pages:number}>}
 *          —— 契约的 PageResult（§2.2），不是 MP 的 IPage，字段固定五个
 * 注意：sortField 必须落在后端白名单内（createTime / updateTime / publishTime /
 *       viewCount / title），传别的会被后端以 400 + PARAM_ERROR 拒掉。
 */
export function pageArticles(params) {
  return get('/articles', params)
}

/**
 * 文章详情
 * GET /api/v1/articles/{id}    权限 article:view
 * @returns ArticleDetailVO：含 content 正文、rejectReason 最近驳回意见、
 *          auditLogs 审核流水、versions 版本列表
 */
export function getArticleDetail(id) {
  return get(`/articles/${id}`)
}

/**
 * 审核流水查询
 * GET /api/v1/articles/{id}/audit-logs    权限 article:view
 * @returns Array<AuditLogVO>，按时间正序
 * 说明：详情接口也带 auditLogs，单独开这个接口是为了"审核记录页"能单独刷新/分页，
 *       不必为了看几条流水把整篇正文再拉一遍。
 */
export function listAuditLogs(id) {
  return get(`/articles/${id}/audit-logs`)
}

/**
 * 版本列表
 * GET /api/v1/articles/{id}/versions      权限 article:view
 * @returns Array<ArticleVersionVO>，只有版本号与时间，快照正文要回滚前单独取
 */
export function listVersions(id) {
  return get(`/articles/${id}/versions`)
}

// ---------------------------------------------------------------------------
// 草稿（新建 / 编辑 / 删除）
// ---------------------------------------------------------------------------

/**
 * 新建草稿
 * POST /api/v1/articles/draft    权限 article:create     记日志
 * @param {Object} data ArticleSaveDTO：title / summary / content /
 *        coverImage / categoryId / tagIds / isTop（id 不传，新建）
 * @returns 新建文章的 id（Number）。新建草稿不做重复提交保护 → 后端非幂等，
 *          前端按钮要自己防连点（F06 表单里用 loading 锁按钮）。
 */
export function saveDraft(data) {
  return post('/articles/draft', data)
}

/**
 * 编辑草稿
 * PUT /api/v1/articles/{id}/draft   权限 article:edit     记日志
 * @param {number} id 文章 id
 * @param {Object} data ArticleSaveDTO
 * ⚠️ 乐观锁：后端 cms_article 有 version 列（MP @Version），
 *    并发编辑同一篇时后提交者会失败，前端要能把"版本冲突"的错误提示出来。
 */
export function updateDraft(id, data) {
  return put(`/articles/${id}/draft`, data)
}

/**
 * 删除文章
 * DELETE /api/v1/articles/{id}      权限 article:delete   记日志
 * 业务限制：仅「草稿」「已驳回」态可删（后端校验），其余状态返回业务错误码。
 * 实现为逻辑删除（deleted=1），不是物理删除。
 */
export function deleteArticle(id) {
  return del(`/articles/${id}`)
}

// ---------------------------------------------------------------------------
// 投稿 / 审核
// ---------------------------------------------------------------------------

/**
 * 提交初审
 * POST /api/v1/articles/{id}/submit    权限 article:submit   记日志
 * 状态迁移：草稿(0) / 已驳回(4) → 待编辑初审(1)
 */
export function submitForAudit(id) {
  return post(`/articles/${id}/submit`)
}

/**
 * 批量提交初审
 * POST /api/v1/articles/batch-submit   权限 article:submit（或 article:edit）  记日志
 * @param {number[]} ids 文章 id 数组
 * @returns {Promise<Array>} 逐条结果，形如 [{ id, success, message }]
 *          —— 需求 A-F04 明确"不因单条失败整批回滚"，所以前端要按条渲染成功/失败，
 *             不能当成"要么全成要么全败"。
 */
export function batchSubmit(ids) {
  return post('/articles/batch-submit', { ids })
}

/**
 * 审核（初审 / 终审共用）
 * POST /api/v1/articles/{id}/audit     权限 article:audit    记日志
 * @param {number} id 文章 id
 * @param {{auditAction:number, auditOpinion?:string}} data
 *        auditAction：1 通过 / 2 驳回（ArticleAuditDTO，必填）
 *        auditOpinion：审核意见。通过时可空；**驳回时后端必填**，
 *                      否则抛 ARTICLE_AUDIT_OPINION_REQUIRED。
 * ⚠️ 不传 stage！审核阶段由文章当前状态决定（status=1 即初审、status=2 即终审）。
 *    前端 UI 上仍可以按 status 显示"初审"/"终审"标题，但不要往请求体里塞 stage。
 * 状态迁移：待编辑初审(1) 通过→待主编终审(2)；待主编终审(2) 通过→审核通过(3)；
 *          任一阶段驳回→已驳回(4)
 */
export function audit(id, data) {
  return post(`/articles/${id}/audit`, data)
}

// ---------------------------------------------------------------------------
// 发布 / 撤回 / 下架 / 归档
// ---------------------------------------------------------------------------

/**
 * 立即发布
 * POST /api/v1/articles/{id}/publish   权限 article:publish  记日志
 * 前置状态：审核通过(3)；迁移到 已发布(5)
 */
export function publishNow(id) {
  return post(`/articles/${id}/publish`)
}

/**
 * 排期发布
 * POST /api/v1/articles/{id}/schedule  权限 article:publish  记日志
 * @param {number} id 文章 id
 * @param {string} scheduleTime 计划发布时间，ISO 8601 字符串（如 '2026-10-25T09:00:00'）
 *        后端校验必须晚于当前时间，否则抛 ARTICLE_SCHEDULE_TIME_INVALID(11007)
 * 前置状态：审核通过(3)；迁移到 定时发布中(6)，到点由后端定时任务转 已发布(5)
 */
export function publishSchedule(id, scheduleTime) {
  return post(`/articles/${id}/schedule`, { scheduleTime })
}

/**
 * 撤回
 * POST /api/v1/articles/{id}/withdraw  权限 article:withdraw 记日志
 * 把已发布/定时发布中的文章收回，进入 已撤回(7)
 */
export function withdraw(id) {
  return post(`/articles/${id}/withdraw`)
}

/**
 * 下架
 * POST /api/v1/articles/{id}/offline   权限 article:offline  记日志
 * 已发布(5) / 已撤回(7) → 已下架(8)
 */
export function offline(id) {
  return post(`/articles/${id}/offline`)
}

/**
 * 归档
 * POST /api/v1/articles/{id}/archive   权限 article:archive  记日志
 * → 已归档(9)，**终态**，之后不能再到任何其他状态（状态机只有单向入边）
 */
export function archive(id) {
  return post(`/articles/${id}/archive`)
}

// ---------------------------------------------------------------------------
// 版本回滚
// ---------------------------------------------------------------------------

/**
 * 回滚到指定版本
 * POST /api/v1/articles/{id}/rollback  权限 article:edit     记日志
 *
 * ⚠️ URL 以契约为准：契约是 /articles/{id}/rollback，
 *    手册写的 /articles/{id}/versions/{versionNo}/rollback 已废弃。
 *    versionNo 改为放在请求体里（对应 cms_article_version.version_no 列）。
 *
 * 业务约束（见 docs/数据库设计.md / 需求分析 A-F15）：
 *   · 已发布文章不建议直接回滚，须先撤回再回滚；
 *   · 回滚本身也会产生一个新版本（版本号递增），不是"回到过去"而是"以旧内容新建一版"。
 *
 * @param {number} id 文章 id
 * @param {number} versionNo 目标版本号（从 1 递增）
 */
export function rollbackVersion(id, versionNo) {
  return post(`/articles/${id}/rollback`, { versionNo })
}
