import { apiFetch } from './client'
import { clearSessionLocal, getSessionId } from '../store/session'
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

/** degraded 事件：档位发生迁移（NORMAL → PARTIAL / FULL，或恢复）。 */
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
}

export interface CreateRunRequest {
  text: string
  mode?: 'GLOBAL' | 'MERCHANT'
  merchantId?: string
}

/** POST /api/agent/runs —— 创建一次决策（幂等窗口由后端保证）。 */
export function createRun(request: CreateRunRequest): Promise<{ runId: string; streamUrl: string }> {
  return apiFetch('/agent/runs', { method: 'POST', body: request })
}

/** GET /api/agent/runs/{runId} —— 拉取快照（断线恢复对齐）。 */
export function fetchRunSnapshot(runId: string): Promise<RunSnapshot> {
  return apiFetch(`/agent/runs/${encodeURIComponent(runId)}`)
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