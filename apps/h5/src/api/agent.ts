import { apiFetch } from './client'
import { clearSessionLocal, getSessionId } from '../store/session'
import type { AppliedPreference } from './preferences'
import type { SpecGroupView } from './types'

/* ---------------- 决策：请求与响应 ---------------- */

export interface RunProfile {
  taste_tags: string[]
  budget_max: number | null
  avoid_ingredients: string[]
  max_delivery_min: number | null
  scene: string | null
  meal_period: string | null
  notes: string | null
  open_questions: string[]
}

export interface DimScore {
  dim: string
  value: number
  confidence: number
}

export interface DecisionCard {
  dishId: string
  name: string
  merchantId: string
  merchantName: string
  price: number
  imageUrl: string | null
  compositeScore: number
  confidence: number
  dims: DimScore[]
  mode: string
  near: boolean
  overBudget: boolean
  specGroups: SpecGroupView[]
  /** 推荐理由（W3 起：换一批与快照恢复的卡片带理由；实时流仍以 reason.delta 事件为准）。 */
  reason?: string | null
}

export interface FilteredDetail {
  closed: number
  unavailable: number
  budget: number
  avoid: number
  timeliness: number
  capped: number
  overBudget: number
}

export interface FilteredEvent {
  candidateCount: number
  poolSize: number
  filteredDetail: FilteredDetail
}

export interface ScoringEvent {
  done: number
  total: number
  mode: string
}

export interface ReasonDeltaEvent {
  dishId: string
  text: string
}

/** 追问卡选项（W2）：value 为该选项要写入 profile 的值（null = 不填，等同跳过该问）。 */
export interface ClarifyOption {
  id: string
  label: string
  value: string | null
}

export interface ClarifyQuestion {
  id: string
  type: 'TASTE' | 'BUDGET'
  title: string
  options: ClarifyOption[]
  skippable: boolean
}

/** clarify 事件载荷（W2 追问）：一次最多 2 问。 */
export interface ClarifyEvent {
  questions: ClarifyQuestion[]
  allowSkip: boolean
  maxQuestions: number
}

/** clarify.resolved 事件载荷：answered / user_skip / timeout 三条路径都会下发，用于收起追问卡。 */
export interface ClarifyResolvedEvent {
  skipped: boolean
  reason: 'answered' | 'user_skip' | 'timeout'
}

/** 单题回答：optionId 为 null 表示跳过该问题。 */
export interface ClarifyAnswer {
  questionId: string
  optionId: string | null
}

/** understand 事件载荷：profile 之外向后兼容新增 appliedPreferences（本次带入的长期偏好）。 */
export interface UnderstandEvent {
  profile: RunProfile
  fromFallback: boolean
  appliedPreferences?: AppliedPreference[]
  /** 本轮确定性澄清问题（W2）：非空表示业务服务门控过滤、等待用户回答。 */
  questions?: ClarifyQuestion[]
  /** 澄清收口后的二次理解（W2，向后兼容新增字段）。 */
  clarifyResolved?: boolean
}

export interface RelaxOption {
  type: string
  label: string
  text: string
}

export interface DoneEvent {
  cards: DecisionCard[]
  latencyMs: number
  costYuan: number
  rubricVersion: string
  relaxOptions: RelaxOption[]
  degradeLevel: DegradeLevel
}

/** 降级档位（技术方案 §6.1）：NORMAL 无差异 / PARTIAL 部分规则分 / FULL 全规则分。 */
export type DegradeLevel = 'NORMAL' | 'PARTIAL' | 'FULL'

/** degraded 事件：档位发生迁移（NORMAL → PARTIAL / FULL，或恢复），或本轮维持降级档位时的状态同步。 */
export interface DegradedEvent {
  level: DegradeLevel
  reason: string
}

export interface ErrorEvent {
  code: string
  message: string
  retryable: boolean
}

export interface RunSnapshot {
  runId: string
  status: string
  profile: RunProfile
  filteredDetail: FilteredDetail | null
  cards: DecisionCard[]
  lastSeq: number
  latencyMs: number | null
  costYuan: number | null
  rubricVersion: string
  degradeLevel: DegradeLevel
  /** 待答澄清问题（W2）：status=RUNNING 且 clarifyState=PENDING 时用于恢复追问卡。 */
  clarify: ClarifyEvent | null
  clarifyState: 'PENDING' | 'ANSWERED' | 'SKIPPED' | null
}

/** 会话历史单条（接口 23）：重进会话页时水合消息流的整轮还原视图。 */
export interface RunHistoryItem {
  runId: string
  inputText: string
  mode: 'GLOBAL' | 'MERCHANT'
  merchantId: string | null
  status: string
  degradeLevel: DegradeLevel
  latencyMs: number | null
  /** REFINE 继承来源 run；非空表示该轮由「继续调整」发起。 */
  parentRunId: string | null
  clarifyState: 'PENDING' | 'ANSWERED' | 'SKIPPED' | null
  /** 待答澄清问题；未追问为 null。 */
  clarify: ClarifyEvent | null
  /** 结构化理解结果；未产出理解时为 null。 */
  profile: RunProfile | null
  /** filtered 事件视图；未过滤时为 null。 */
  filtered: FilteredEvent | null
  appliedPreferences: AppliedPreference[]
  cards: DecisionCard[]
}

export interface CreateRunRequest {
  text: string
  mode?: 'GLOBAL' | 'MERCHANT'
  merchantId?: string
  /** 长期偏好带入（PRD 4.8）：undefined → 默认全选 ACTIVE；[] → 全部不带入。 */
  preferenceIds?: string[] | null
}

/** POST /api/agent/runs —— 创建一次决策（幂等窗口由后端保证）。 */
export function createRun(request: CreateRunRequest): Promise<{ runId: string; streamUrl: string }> {
  return apiFetch('/agent/runs', { method: 'POST', body: request })
}

/** GET /api/agent/runs/{runId} —— 拉取快照（断线恢复对齐）。 */
export function fetchRunSnapshot(runId: string): Promise<RunSnapshot> {
  return apiFetch(`/agent/runs/${encodeURIComponent(runId)}`)
}

/** GET /api/agent/runs —— 接口 23：会话历史（时间升序，最近 N 轮），重进水合消息流。 */
export function fetchRunHistory(limit = 3): Promise<{ items: RunHistoryItem[] }> {
  return apiFetch(`/agent/runs?limit=${limit}`)
}

/** POST /api/agent/runs/{runId}/answers —— 提交澄清回答或整轮跳过（W2；问题失效返回 409）。 */
export function answerClarify(
  runId: string,
  request: { answers: ClarifyAnswer[]; skipped: boolean },
): Promise<{ runId: string; accepted: boolean }> {
  return apiFetch(`/agent/runs/${encodeURIComponent(runId)}/answers`, {
    method: 'POST',
    body: request,
  })
}

/* ---------------- 接口 18：修正 / 换一批 ---------------- */

export type RefineRequest =
  /** 修正：基于原 run 新建继承 run，走完整理解链路（返回新 runId + streamUrl）。 */
  | { type: 'REFINE'; text: string }
  /** 换一批：同 run 从已落库候选里取下一组（零模型成本，秒回）。 */
  | { type: 'NEXT_BATCH'; excludeDishIds: string[]; pageSize?: number }

export interface RefineResponse {
  runId: string
  streamUrl: string | null
  cards: DecisionCard[] | null
  hasMore: boolean | null
}

/** POST /api/agent/runs/{runId}/refine —— 接口 18：修正 / 换一批（未完成的 run 返回 409）。 */
export function refineRun(runId: string, request: RefineRequest): Promise<RefineResponse> {
  return apiFetch(`/agent/runs/${encodeURIComponent(runId)}/refine`, {
    method: 'POST',
    body: request,
  })
}

/* ---------------- 决策：SSE 订阅 ---------------- */

export interface RunStreamHandlers {
  /** 收到一个协议事件（heartbeat 已过滤）；seq 为服务端自增序号。 */
  onEvent: (event: string, data: unknown, seq: number) => void
  /** 流正常结束（done / error 后服务端关闭）。 */
  onClose: () => void
  /** 网络或鉴权异常。 */
  onError: (error: unknown) => void
}

/**
 * GET /api/agent/runs/{runId}/stream 的 SSE 订阅。
 * 用 fetch + ReadableStream 而不是 EventSource：需要带 X-Session-Id 与 Last-Event-ID 头。
 * 返回 abort 函数，用于页面卸载或重新开始时主动断开。
 */
export function openRunStream(
  runId: string,
  handlers: RunStreamHandlers,
  lastEventId?: number,
): () => void {
  const controller = new AbortController()

  const run = async () => {
    const headers = new Headers({ Accept: 'text/event-stream' })
    const sessionId = getSessionId()
    if (sessionId) headers.set('X-Session-Id', sessionId)
    if (lastEventId && lastEventId > 0) headers.set('Last-Event-ID', String(lastEventId))

    const response = await fetch(`/api/agent/runs/${encodeURIComponent(runId)}/stream`, {
      headers,
      signal: controller.signal,
    })

    if (response.status === 401) {
      clearSessionLocal()
      if (window.location.pathname !== '/') window.location.assign('/')
      throw new Error('登录态已失效，请重新进入')
    }
    if (!response.ok || !response.body) {
      throw new Error(`决策流连接失败（${response.status}）`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        dispatchFrame(frame, handlers)
        boundary = buffer.indexOf('\n\n')
      }
    }
    handlers.onClose()
  }

  run().catch((error) => {
    if (!controller.signal.aborted) handlers.onError(error)
  })

  return () => controller.abort()
}

function dispatchFrame(frame: string, handlers: RunStreamHandlers): void {
  let event = ''
  let seq = 0
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    else if (line.startsWith('id:')) seq = Number(line.slice(3).trim()) || 0
  }
  if (!event || event === 'heartbeat' || dataLines.length === 0) return
  try {
    handlers.onEvent(event, JSON.parse(dataLines.join('\n')), seq)
  } catch {
    // 忽略无法解析的帧，保持流可用
  }
}