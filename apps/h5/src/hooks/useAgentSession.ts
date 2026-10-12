import { useCallback, useEffect, useRef, useState } from 'react'
import { answerClarify, createRun, fetchRunSnapshot, openRunStream, refineRun } from '../api/agent'
import type {
  ClarifyAnswer,
  ClarifyEvent,
  DecisionCard,
  DegradeLevel,
  DoneEvent,
  ErrorEvent,
  FilteredEvent,
  PreferenceSavedEvent,
  RunProfile,
  RelaxOption,
  ScoringEvent,
  UnderstandEvent,
} from '../api/agent'
import type { AppliedPreference } from '../api/preferences'

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
  /** 本次 run 实际带入的长期偏好（understand 事件新增字段），用于会话内展示。 */
  appliedPreferences: AppliedPreference[]
  /** 待答追问卡（W2）：clarify 事件到达后显示，回答 / 跳过 / 超时后清空。 */
  clarify: ClarifyEvent | null
  /** 降级档位：NORMAL 无差异 / PARTIAL 部分卡片走规则分 / FULL 全部走规则分。 */
  degradeLevel: DegradeLevel
  filtered: FilteredEvent | null
  scoring: ScoringEvent | null
  /** 打分阶段逐步到达；done 后全量展示（结果区横向滚动查看，不再分页）。 */
  cards: DecisionCard[]
  reasons: Record<string, string>
  error: ErrorEvent | null
  relaxOptions: RelaxOption[]
  /** 是否由「修正」发起（UI 标注「基于上一条需求」）。 */
  refined: boolean
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

/** 新决策块初始状态（refined = 由「修正」发起，runId 拿到后再回填）。 */
function initialBlock(id: string, text: string, refined = false): RunBlock {
  return {
    id,
    runId: null,
    text,
    phase: 'running',
    steps: initialSteps(),
    profile: null,
    fromFallback: false,
    appliedPreferences: [],
    clarify: null,
    degradeLevel: 'NORMAL',
    filtered: null,
    scoring: null,
    cards: [],
    reasons: {},
    error: null,
    relaxOptions: [],
    refined,
    latencyMs: null,
  }
}

/**
 * AI 决策会话状态机：一句话 → 进度步骤 → 带分卡片。
 * 事件驱动：SSE 事件与 LangGraph 节点边界一一对应；
 * 流异常结束时以快照兜底对齐（断线恢复口径）。
 */
export function useAgentSession(
  mode?: 'GLOBAL' | 'MERCHANT',
  merchantId?: string,
  options?: { onPreferenceSaved?: (event: PreferenceSavedEvent) => void },
) {
  const [blocks, setBlocks] = useState<RunBlock[]>([])
  const aborts = useRef(new Map<string, () => void>())
  const phases = useRef(new Map<string, RunBlock['phase']>())
  /** 块 → runId（提交澄清回答时定位 run）。 */
  const runIds = useRef(new Map<string, string>())
  /** 已自动续订过流的块：澄清期间断流只自动恢复一次，避免断流循环（W2）。 */
  const resumed = useRef(new Set<string>())
  /** 指向最新的 recover（subscribe 的关闭/异常回调需要它，避免与 recover 循环依赖）。 */
  const recoverRef = useRef<(blockId: string, runId: string) => void>(() => {})
  const savedHandler = useRef(options?.onPreferenceSaved)

  useEffect(() => {
    savedHandler.current = options?.onPreferenceSaved
  }, [options?.onPreferenceSaved])

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
          const payload = data as UnderstandEvent
          patch(blockId, (block) => ({
            ...block,
            profile: payload.profile,
            fromFallback: payload.fromFallback,
            appliedPreferences: payload.appliedPreferences ?? [],
            clarify: payload.clarifyResolved ? null : block.clarify,
            // 首答含疑问时停在「理解需求」：等待用户回答，不推进到过滤（W2 门控同口径）
            steps: payload.questions?.length
              ? block.steps.map((step) =>
                  step.key === 'understand' ? { ...step, status: 'done' as const } : step,
                )
              : advance(block.steps, 'understand', 'filter'),
          }))
          break
        }
        case 'clarify': {
          patch(blockId, (block) => ({ ...block, clarify: data as ClarifyEvent }))
          break
        }
        case 'clarify.resolved': {
          patch(blockId, (block) => ({ ...block, clarify: null }))
          break
        }
        case 'preference.saved': {
          savedHandler.current?.(data as PreferenceSavedEvent)
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
        case 'degraded': {
          const payload = data as { level: DegradeLevel; reason: string }
          patch(blockId, (block) => ({ ...block, degradeLevel: payload.level }))
          break
        }
        case 'done': {
          const payload = data as DoneEvent
          patch(blockId, (block) => ({
            ...block,
            phase: 'done',
            cards: payload.cards ?? [],
            relaxOptions: payload.relaxOptions ?? [],
            latencyMs: payload.latencyMs,
            degradeLevel: payload.degradeLevel ?? block.degradeLevel,
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

  /** 订阅一条决策流：正常关闭与网络异常都走 recover 兜底对齐（澄清等待中断流由 recover 续订一次）。 */
  const subscribe = useCallback(
    (blockId: string, runId: string, lastEventId?: number) => {
      const abort = openRunStream(
        runId,
        {
          onEvent: (event, data) => handleEvent(blockId, event, data),
          onClose: () => {
            aborts.current.delete(runId)
            recoverRef.current(blockId, runId)
          },
          onError: () => {
            aborts.current.delete(runId)
            recoverRef.current(blockId, runId)
          },
        },
        lastEventId,
      )
      aborts.current.set(runId, abort)
    },
    [handleEvent],
  )

  /** 流结束时若仍处于运行态：拉快照对齐；澄清等待中则恢复追问卡并按 seq 续订（W2）。 */
  const recover = useCallback(
    async (blockId: string, runId: string) => {
      if (phases.current.get(blockId) !== 'running') return
      try {
        const snapshot = await fetchRunSnapshot(runId)
        if (snapshot.status === 'DONE' || snapshot.status === 'DEGRADED') {
          patch(blockId, (block) => ({
            ...block,
            phase: 'done',
            profile: block.profile ?? snapshot.profile,
            cards: snapshot.cards ?? [],
            latencyMs: snapshot.latencyMs,
            degradeLevel: snapshot.degradeLevel ?? block.degradeLevel,
            steps: block.steps.map((step) => ({ ...step, status: 'done' as const })),
          }))
          return
        }
        if (snapshot.status === 'RUNNING' && snapshot.clarifyState === 'PENDING' && snapshot.clarify) {
          // 追问等待中的断流：恢复追问卡并续订流；每个块最多自动续订一次，避免断流循环
          patch(blockId, (block) => ({
            ...block,
            profile: block.profile ?? snapshot.profile,
            clarify: block.clarify ?? snapshot.clarify,
          }))
          if (!resumed.current.has(blockId)) {
            resumed.current.add(blockId)
            subscribe(blockId, runId, snapshot.lastSeq)
          }
          return
        }
        patch(blockId, (block) => ({
          ...block,
          phase: 'error',
          error: { code: 'RUN_INTERRUPTED', message: '连接中断，请重试', retryable: true },
        }))
      } catch {
        patch(blockId, (block) => ({
          ...block,
          phase: 'error',
          error: { code: 'RUN_INTERRUPTED', message: '连接中断，请重试', retryable: true },
        }))
      }
    },
    [patch, subscribe],
  )

  useEffect(() => {
    recoverRef.current = (blockId, runId) => {
      void recover(blockId, runId)
    }
  }, [recover])

  const submit = useCallback(
    (text: string, preferenceIds?: string[]) => {
      const trimmed = text.trim()
      if (!trimmed) return
      const blockId = `b-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
      phases.current.set(blockId, 'running')
      setBlocks((prev) => [...prev, initialBlock(blockId, trimmed)])

      createRun({ text: trimmed, mode, merchantId, preferenceIds })
        .then(({ runId }) => {
          runIds.current.set(blockId, runId)
          patch(blockId, (current) => ({ ...current, runId }))
          subscribe(blockId, runId)
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
    [merchantId, mode, patch, subscribe],
  )

  /** 提交澄清回答 / 整轮跳过（W2）：乐观收起追问卡，失败时用快照兜底恢复。 */
  const answer = useCallback(
    async (blockId: string, answers: ClarifyAnswer[], skipped: boolean) => {
      const runId = runIds.current.get(blockId)
      if (!runId) return
      patch(blockId, (block) => ({ ...block, clarify: null }))
      try {
        await answerClarify(runId, { answers, skipped })
      } catch {
        try {
          const snapshot = await fetchRunSnapshot(runId)
          if (
            snapshot.status === 'RUNNING' &&
            snapshot.clarifyState === 'PENDING' &&
            snapshot.clarify
          ) {
            patch(blockId, (block) => ({ ...block, clarify: snapshot.clarify }))
            return
          }
        } catch {
          // 快照也拿不到：交给后续断流恢复 / 超时兜底
        }
        void recover(blockId, runId)
      }
    },
    [patch, recover],
  )

  /**
   * 修正（接口 18 REFINE）：基于原 block 的 run 新建继承 run；块内展示本次修正，
   * 提交文本由服务端拼接「原话 + 修正」，模式与带入偏好一并继承。
   */
  const refine = useCallback(
    (blockId: string, text: string) => {
      const trimmed = text.trim()
      const parentRunId = runIds.current.get(blockId)
      if (!trimmed || !parentRunId) return
      const newBlockId = `b-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
      phases.current.set(newBlockId, 'running')
      setBlocks((prev) => [...prev, initialBlock(newBlockId, trimmed, true)])

      refineRun(parentRunId, { type: 'REFINE', text: trimmed })
        .then(({ runId }) => {
          runIds.current.set(newBlockId, runId)
          patch(newBlockId, (current) => ({ ...current, runId }))
          subscribe(newBlockId, runId)
        })
        .catch((error) => {
          patch(newBlockId, (current) => ({
            ...current,
            phase: 'error',
            error: {
              code: 'REFINE_FAILED',
              message: error instanceof Error ? error.message : '调整失败，请重试',
              retryable: false,
            },
          }))
        })
    },
    [patch, subscribe],
  )

  const reset = useCallback(() => {
    aborts.current.forEach((abort) => abort())
    aborts.current.clear()
    phases.current.clear()
    runIds.current.clear()
    resumed.current.clear()
    setBlocks([])
  }, [])

  const busy = blocks.some((block) => block.phase === 'running')
  return { blocks, busy, submit, answer, refine, reset }
}