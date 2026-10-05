import { useCallback, useRef, useState } from 'react'
import { createRun, fetchRunSnapshot, openRunStream } from '../api/agent'
import type {
  DecisionCard,
  DoneEvent,
  ErrorEvent,
  FilteredEvent,
  RunProfile,
  RelaxOption,
  ScoringEvent,
} from '../api/agent'

export const CARD_PAGE_SIZE = 4

export type StepKey = 'understand' | 'filter' | 'score' | 'phrase'

export interface ProgressStep {
  key: StepKey
  label: string
  status: 'pending' | 'doing' | 'done'
}

export interface RunBlock {
  id: string
  runId: string | null
  text: string
  phase: 'running' | 'done' | 'error'
  steps: ProgressStep[]
  profile: RunProfile | null
  fromFallback: boolean
  filtered: FilteredEvent | null
  scoring: ScoringEvent | null
  /** 打分阶段逐步到达；done 后替换为最终排序结果（带并列标记）。 */
  cards: DecisionCard[]
  reasons: Record<string, string>
  error: ErrorEvent | null
  relaxOptions: RelaxOption[]
  batchIndex: number
  latencyMs: number | null
}

const STEP_LABELS: Record<StepKey, string> = {
  understand: '理解需求',
  filter: '硬约束过滤',
  score: '逐菜打分',
  phrase: '生成理由',
}

function initialSteps(): ProgressStep[] {
  return (Object.keys(STEP_LABELS) as StepKey[]).map((key, index) => ({
    key,
    label: STEP_LABELS[key],
    status: index === 0 ? 'doing' : 'pending',
  }))
}

function advance(steps: ProgressStep[], doneKey: StepKey, nextKey?: StepKey): ProgressStep[] {
  return steps.map((step) => {
    if (step.key === doneKey) return { ...step, status: 'done' }
    if (nextKey && step.key === nextKey) return { ...step, status: 'doing' }
    return step
  })
}

/**
 * AI 决策会话状态机：一句话 → 进度步骤 → 带分卡片。
 * 事件驱动：SSE 事件与 LangGraph 节点边界一一对应；
 * 流异常结束时以快照兜底对齐（断线恢复口径）。
 */
export function useAgentSession(mode?: 'GLOBAL' | 'MERCHANT', merchantId?: string) {
  const [blocks, setBlocks] = useState<RunBlock[]>([])
  const aborts = useRef(new Map<string, () => void>())
  const phases = useRef(new Map<string, RunBlock['phase']>())

  const patch = useCallback((blockId: string, update: (block: RunBlock) => RunBlock) => {
    setBlocks((prev) =>
      prev.map((block) => {
        if (block.id !== blockId) return block
        const next = update(block)
        phases.current.set(blockId, next.phase)
        return next
      }),
    )
  }, [])

  const handleEvent = useCallback(
    (blockId: string, event: string, data: unknown) => {
      switch (event) {
        case 'understand': {
          const payload = data as { profile: RunProfile; fromFallback: boolean }
          patch(blockId, (block) => ({
            ...block,
            profile: payload.profile,
            fromFallback: payload.fromFallback,
            steps: advance(block.steps, 'understand', 'filter'),
          }))
          break
        }
        case 'filtered': {
          const payload = data as FilteredEvent
          patch(blockId, (block) => ({
            ...block,
            filtered: payload,
            steps: advance(block.steps, 'filter', 'score'),
          }))
          break
        }
        case 'scoring': {
          const payload = data as ScoringEvent
          patch(blockId, (block) => ({
            ...block,
            scoring: payload,
            steps:
              payload.done >= payload.total
                ? advance(block.steps, 'score', 'phrase')
                : block.steps,
          }))
          break
        }
        case 'card': {
          const card = data as DecisionCard
          patch(blockId, (block) =>
            block.cards.some((item) => item.dishId === card.dishId)
              ? block
              : { ...block, cards: [...block.cards, card] },
          )
          break
        }
        case 'reason.delta': {
          const payload = data as { dishId: string; text: string }
          patch(blockId, (block) => ({
            ...block,
            reasons: { ...block.reasons, [payload.dishId]: payload.text },
          }))
          break
        }
        case 'done': {
          const payload = data as DoneEvent
          patch(blockId, (block) => ({
            ...block,
            phase: 'done',
            cards: payload.cards,
            relaxOptions: payload.relaxOptions ?? [],
            latencyMs: payload.latencyMs,
            steps: block.steps.map((step) => ({ ...step, status: 'done' as const })),
          }))
          break
        }
        case 'error': {
          const payload = data as ErrorEvent
          patch(blockId, (block) => ({
            ...block,
            phase: 'error',
            error: payload,
            steps: block.steps.map((step) =>
              step.status === 'doing' ? { ...step, status: 'pending' as const } : step,
            ),
          }))
          break
        }
        default:
          break
      }
    },
    [patch],
  )

  /** 流结束时若仍处于运行态：拉快照对齐；快照未完成则提示重试。 */
  const recover = useCallback(
    async (blockId: string, runId: string) => {
      if (phases.current.get(blockId) !== 'running') return
      try {
        const snapshot = await fetchRunSnapshot(runId)
        if (snapshot.status === 'DONE') {
          patch(blockId, (block) => ({
            ...block,
            phase: 'done',
            profile: block.profile ?? snapshot.profile,
            cards: snapshot.cards,
            latencyMs: snapshot.latencyMs,
            steps: block.steps.map((step) => ({ ...step, status: 'done' as const })),
          }))
        } else {
          patch(blockId, (block) => ({
            ...block,
            phase: 'error',
            error: { code: 'RUN_INTERRUPTED', message: '连接中断，请重试', retryable: true },
          }))
        }
      } catch {
        patch(blockId, (block) => ({
          ...block,
          phase: 'error',
          error: { code: 'RUN_INTERRUPTED', message: '连接中断，请重试', retryable: true },
        }))
      }
    },
    [patch],
  )

  const submit = useCallback(
    (text: string) => {
      const trimmed = text.trim()
      if (!trimmed) return
      const blockId = `b-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
      const block: RunBlock = {
        id: blockId,
        runId: null,
        text: trimmed,
        phase: 'running',
        steps: initialSteps(),
        profile: null,
        fromFallback: false,
        filtered: null,
        scoring: null,
        cards: [],
        reasons: {},
        error: null,
        relaxOptions: [],
        batchIndex: 0,
        latencyMs: null,
      }
      phases.current.set(blockId, 'running')
      setBlocks((prev) => [...prev, block])

      createRun({ text: trimmed, mode, merchantId })
        .then(({ runId }) => {
          patch(blockId, (current) => ({ ...current, runId }))
          const abort = openRunStream(runId, {
            onEvent: (event, data) => handleEvent(blockId, event, data),
            onClose: () => {
              aborts.current.delete(runId)
              void recover(blockId, runId)
            },
            onError: (error) => {
              aborts.current.delete(runId)
              patch(blockId, (current) =>
                current.phase === 'running'
                  ? {
                      ...current,
                      phase: 'error',
                      error: {
                        code: 'STREAM_ERROR',
                        message: error instanceof Error ? error.message : '连接中断，请重试',
                        retryable: true,
                      },
                    }
                  : current,
              )
            },
          })
          aborts.current.set(runId, abort)
        })
        .catch((error) => {
          patch(blockId, (current) => ({
            ...current,
            phase: 'error',
            error: {
              code: 'CREATE_FAILED',
              message: error instanceof Error ? error.message : '发起决策失败',
              retryable: true,
            },
          }))
        })
    },
    [handleEvent, merchantId, mode, patch, recover],
  )

  const nextBatch = useCallback(
    (blockId: string) => patch(blockId, (block) => ({ ...block, batchIndex: block.batchIndex + 1 })),
    [patch],
  )

  const reset = useCallback(() => {
    aborts.current.forEach((abort) => abort())
    aborts.current.clear()
    phases.current.clear()
    setBlocks([])
  }, [])

  const busy = blocks.some((block) => block.phase === 'running')
  return { blocks, busy, submit, nextBatch, reset }
}