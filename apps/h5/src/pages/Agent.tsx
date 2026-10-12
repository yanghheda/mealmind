import { useCallback, useEffect, useRef, useState } from 'react'
import type { MouseEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StatusBar } from '../components/StatusBar'
import { Icon } from '../components/Icon'
import { SafeImg } from '../components/SafeImg'
import { SpecSheet } from '../components/SpecSheet'
import { fetchMerchant } from '../api/merchants'
import { addCartItem } from '../api/cart'
import {
  PREFERENCE_QUERY_KEY,
  deletePreference,
  fetchPreferences,
  preferenceLabel,
} from '../api/preferences'
import { CARD_PAGE_SIZE, useAgentSession } from '../hooks/useAgentSession'
import type { RunBlock } from '../hooks/useAgentSession'
import { CART_QUERY_KEY, useCart } from '../hooks/useCart'
import { flyToCart } from '../lib/flyToCart'
import { formatMoney } from '../lib/format'
import { showToast } from '../store/ui'
import type {
  ClarifyAnswer,
  ClarifyEvent,
  DecisionCard,
  DimScore,
  PreferenceSavedEvent,
  RunProfile,
} from '../api/agent'
import type { AddCartItemRequest, DishView } from '../api/types'
import './Agent.css'

/** 欢迎态示例需求（措辞对齐种子数据的标签词表）。 */
const SAMPLES = [
  '30 元内来点微辣的，不要香菜，半小时内送到',
  '减脂期想吃清淡的，40 分钟内送到',
  '加班夜宵，来点快的，50 元以内',
]

/** 底部快捷标签（整句需求，一点即发起）。 */
const QUICK_CHIPS = ['微辣 30 元内', '清淡低卡', '不要香菜', '40 分钟内送到']

/** done 块的「继续调整」chips（接口 18 REFINE：基于原需求追加一句修正）。 */
const REFINE_CHIPS = ['再便宜点', '换清淡的', '更快送达']

const DIM_LABELS: Record<string, string> = {
  taste: '口味',
  budget: '预算',
  timeliness: '时效',
  reputation: '口碑',
  scene: '场景',
}

/** AI 决策会话页（Shell 变体 C）：一句话需求 → 进度步骤 → 带分推荐卡 → 加购。 */
export function Agent() {
  const [params] = useSearchParams()
  const merchantId = params.get('merchantId') ?? undefined
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [input, setInput] = useState('')
  /** 被用户点 x 移除的长期偏好：仅影响下一轮（MVP 不重发当前 run）。 */
  const [excludedPrefIds, setExcludedPrefIds] = useState<string[]>([])
  const [savedNotice, setSavedNotice] = useState<PreferenceSavedEvent | null>(null)
  const [specDish, setSpecDish] = useState<DishView | null>(null)
  const sourceRef = useRef<HTMLElement | null>(null)
  const chatRef = useRef<HTMLDivElement | null>(null)

  const handlePreferenceSaved = useCallback(
    (event: PreferenceSavedEvent) => {
      setSavedNotice(event)
      void queryClient.invalidateQueries({ queryKey: PREFERENCE_QUERY_KEY })
    },
    [queryClient],
  )

  const { blocks, busy, submit, answer, nextBatch, refine, reset } = useAgentSession(
    merchantId ? 'MERCHANT' : 'GLOBAL',
    merchantId,
    { onPreferenceSaved: handlePreferenceSaved },
  )

  const prefsQuery = useQuery({ queryKey: PREFERENCE_QUERY_KEY, queryFn: fetchPreferences })
  const memoryItems =
    prefsQuery.data?.items.filter((item) => !excludedPrefIds.includes(item.id)) ?? []

  const merchantQuery = useQuery({
    queryKey: ['merchant', merchantId],
    queryFn: () => fetchMerchant(merchantId as string),
    enabled: Boolean(merchantId),
  })
  const cartQuery = useCart()
  const cartCount = cartQuery.data?.totalQuantity ?? 0
  const addMutation = useMutation({
    mutationFn: addCartItem,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CART_QUERY_KEY }),
  })

  useEffect(() => {
    const node = chatRef.current
    if (!node) return
    window.requestAnimationFrame(() => {
      window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
    })
  }, [blocks])

  useEffect(() => {
    if (!savedNotice) return
    const timer = window.setTimeout(() => setSavedNotice(null), 6000)
    return () => window.clearTimeout(timer)
  }, [savedNotice])

  const back = () => {
    if (window.history.length > 1) navigate(-1)
    else navigate('/home')
  }

  const send = (text: string) => {
    const trimmed = text.trim()
    if (!trimmed || busy) return
    // 偏好列表就绪时显式传当前未排除的 id（空数组 = 本轮全部不带入）；未就绪则不传，由后端默认全选 ACTIVE
    submit(trimmed, prefsQuery.isSuccess ? memoryItems.map((item) => item.id) : undefined)
    setInput('')
  }

  const undoSaved = () => {
    const items = savedNotice?.items ?? []
    setSavedNotice(null)
    void (async () => {
      for (const item of items) {
        try {
          await deletePreference(item.id)
        } catch {
          // 单条撤销失败不阻塞其余条目
        }
      }
      void queryClient.invalidateQueries({ queryKey: PREFERENCE_QUERY_KEY })
      showToast('已撤销记忆')
    })()
  }

  /** 换一批（接口 18 NEXT_BATCH）：服务端按已展示集合取下一组并替换当前批次，秒回。 */
  const handleNextBatch = (block: RunBlock) => {
    void nextBatch(block.id).then((result) => {
      if (!result) showToast('换一批失败，请重试')
      else if (!result.hasMore) showToast('已看完所有候选')
    })
  }

  const handleAdd = (card: DecisionCard, event: MouseEvent<HTMLButtonElement>) => {
    const source =
      event.currentTarget.closest('.rec-card')?.querySelector<HTMLElement>('.rec-img') ?? null
    if (card.specGroups.length > 0) {
      sourceRef.current = source
      setSpecDish(cardToDish(card))
      return
    }
    addToCart({ dishId: card.dishId, quantity: 1 }, source, card)
  }

  const addToCart = (vars: AddCartItemRequest, source: HTMLElement | null, card: DecisionCard) => {
    addMutation.mutate(vars, {
      onSuccess: () => {
        flyToCart(source, card.imageUrl ?? undefined)
        showToast('已加入购物车')
        setSpecDish(null)
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '加购失败，请重试'),
    })
  }

  return (
    <div className="app app--agent">
      <StatusBar />

      <main className="screen screen--flush">
        <div className="page-head agent-head">
          <button className="back" type="button" aria-label="返回" onClick={back}>
            <Icon name="arrow-left" size={21} />
          </button>
          <span className="title">AI 点外卖</span>
          <span className="head-right">
            <Link className="cart-pill" to="/cart" aria-label="购物车">
              <Icon name="shopping-cart" size={16} />
              <span>购物车</span>
              {cartCount > 0 && <i className="cp-badge">{cartCount > 99 ? '99+' : cartCount}</i>}
            </Link>
            <button
              className="icon-btn"
              type="button"
              aria-label="重新开始"
              onClick={reset}
              disabled={blocks.length === 0}
            >
              <Icon name="refresh" size={19} />
            </button>
          </span>
        </div>

        <div className="chat" ref={chatRef}>
          {blocks.length === 0 && (
            <Welcome
              merchantName={merchantQuery.data?.name}
              merchantMode={Boolean(merchantId)}
              onPick={send}
            />
          )}
          {blocks.map((block) => (
            <RunBlockView
              key={block.id}
              block={block}
              busy={busy}
              onNextBatch={() => handleNextBatch(block)}
              onRefine={(text) => refine(block.id, text)}
              onRetry={() => send(block.text)}
              onRelax={send}
              onAdd={handleAdd}
              onAnswer={(answers, skipped) => answer(block.id, answers, skipped)}
            />
          ))}
        </div>
      </main>

      <div className="agent-inputbar">
        {savedNotice && savedNotice.items.length > 0 && (
          <div className="pref-undo">
            <Icon name="sparkles" size={13} />
            <span className="txt">
              {savedNotice.promoted ? '已记住为长期偏好：' : '已记住：'}
              {savedNotice.items.map((item) => preferenceLabel(item)).join('、')}
            </span>
            <button className="undo-btn" type="button" onClick={undoSaved}>
              撤销
            </button>
          </div>
        )}
        {memoryItems.length > 0 && (
          <div className="chips-scroll memory-bar">
            <span className="memory-label">
              <Icon name="sparkles" size={12} />
              长期偏好
            </span>
            {memoryItems.map((item) => (
              <button
                className="chip memory-chip"
                type="button"
                key={item.id}
                aria-label={`下一轮不带入 ${preferenceLabel(item)}`}
                onClick={() => setExcludedPrefIds((prev) => [...prev, item.id])}
              >
                <span>{preferenceLabel(item)}</span>
                <Icon name="x" size={12} />
              </button>
            ))}
          </div>
        )}
        <div className="chips-scroll">
          {QUICK_CHIPS.map((chip) => (
            <button className="chip" type="button" key={chip} onClick={() => send(chip)}>
              {chip}
            </button>
          ))}
        </div>
        <div className="input-row">
          <span className="input-pill">
            <input
              value={input}
              placeholder="说说你想吃什么…"
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') send(input)
              }}
            />
          </span>
          <button
            className="send-btn"
            type="button"
            aria-label="发送"
            disabled={busy || !input.trim()}
            onClick={() => send(input)}
          >
            <Icon name="send" size={19} />
          </button>
        </div>
      </div>

      {specDish && (
        <SpecSheet
          dish={specDish}
          submitting={addMutation.isPending}
          onClose={() => setSpecDish(null)}
          onSubmit={(optionIds, quantity) => {
            const card = specDish
              ? blocks
                  .flatMap((block) => block.cards)
                  .find((item) => item.dishId === specDish.id)
              : undefined
            if (!card) return
            addToCart(
              { dishId: card.dishId, specOptionIds: optionIds, quantity },
              sourceRef.current,
              card,
            )
          }}
        />
      )}
    </div>
  )
}

/* ---------------- 欢迎态 ---------------- */

function Welcome({
  merchantName,
  merchantMode,
  onPick,
}: {
  merchantName?: string
  merchantMode: boolean
  onPick: (text: string) => void
}) {
  return (
    <div className="welcome">
      {merchantMode && (
        <div className="ctx-chip">
          <Icon name="sparkles" size={13} />
          <span>本店模式：{merchantName ?? '当前商家'}</span>
        </div>
      )}
      <div className="welcome-hero">
        <span className="welcome-ico">
          <Icon name="sparkles" size={26} />
        </span>
        <h2>想吃什么，说一句就行</h2>
        <p className="sub">口味、预算、忌口，交给 AI 帮你挑</p>
      </div>
      <div className="welcome-note">试试下面的例子，或直接输入你的需求</div>
      <div className="sample-list">
        {SAMPLES.map((sample) => (
          <button className="sample" type="button" key={sample} onClick={() => onPick(sample)}>
            <span className="txt">{sample}</span>
            <Icon name="chevron-right" size={16} />
          </button>
        ))}
      </div>
    </div>
  )
}

/* ---------------- 单次决策块 ---------------- */

function RunBlockView({
  block,
  busy,
  onNextBatch,
  onRefine,
  onRetry,
  onRelax,
  onAdd,
  onAnswer,
}: {
  block: RunBlock
  busy: boolean
  onNextBatch: () => void
  onRefine: (text: string) => void
  onRetry: () => void
  onRelax: (text: string) => void
  onAdd: (card: DecisionCard, event: MouseEvent<HTMLButtonElement>) => void
  onAnswer: (answers: ClarifyAnswer[], skipped: boolean) => void
}) {
  // 打分中只露首屏；done 后由「换一批」按页替换展示（走服务端接口 18）
  const visible = block.phase === 'running' ? block.cards.slice(0, CARD_PAGE_SIZE) : block.cards
  const hasMore = block.phase === 'done' && block.hasMore

  return (
    <>
      <div className="bubble-user">
        {block.refined && <span className="refine-tag">基于上一条需求</span>}
        <span>{block.text}</span>
      </div>

      {block.degradeLevel === 'FULL' && (
        <div className="degrade-banner">
          <Icon name="circle-alert" size={14} />
          <span>当前为规则推荐模式，结果可能不如智能推荐精准</span>
        </div>
      )}

      {block.profile && (
        <div className="ai-card">
          <div className="ai-label">
            <Icon name="sparkles" size={14} />
            <span>我理解到的需求</span>
            {block.fromFallback && <i className="mini-tag">规则解析</i>}
          </div>
          <div className="u-chips">
            {profileChips(block.profile, Boolean(block.clarify)).map((chip) => (
              <span className="u-chip" key={chip}>
                {chip}
              </span>
            ))}
          </div>
          {block.appliedPreferences.length > 0 && (
            <div className="memory-note">
              <Icon name="sparkles" size={12} />
              <span>
                本次已带入长期偏好：
                {block.appliedPreferences.map((item) => preferenceLabel(item)).join('、')}
              </span>
            </div>
          )}
          {block.filtered && (
            <div className="note-line">
              <Icon name="circle-check" size={13} />
              <span>{filteredText(block.filtered)}</span>
            </div>
          )}
        </div>
      )}

      {block.clarify && <ClarifyCard clarify={block.clarify} onAnswer={onAnswer} />}

      {block.phase === 'running' && !block.clarify && (
        <div className="ai-card">
          <div className="ai-label">
            <Icon name="sparkles" size={14} />
            <span>正在为你分析{block.scoring && block.scoring.total > 0 ? `（${Math.min(block.scoring.done, block.scoring.total)}/${block.scoring.total} 道）` : ''}</span>
          </div>
          <div className="steps">
            {block.steps.map((step) => (
              <div className={`step ${step.status}`} key={step.key}>
                <span className="dot" />
                <span className="txt">{step.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {block.phase === 'error' && block.error && (
        <div className="ai-card ai-card--error">
          <div className="ai-label">
            <Icon name="circle-alert" size={14} />
            <span>{block.error.message}</span>
          </div>
          {block.error.retryable && (
            <button className="btn btn-outline btn-sm" type="button" onClick={onRetry}>
              重试
            </button>
          )}
        </div>
      )}

      {block.phase === 'done' && block.cards.length === 0 && (
        <div className="ai-card">
          <div className="ai-label">
            <Icon name="circle-alert" size={14} />
            <span>没有完全符合条件的候选，试试放宽条件</span>
          </div>
          <div className="u-chips relax-chips">
            {block.relaxOptions.map((option) => (
              <button
                className="chip"
                type="button"
                key={option.type}
                onClick={() => onRelax(option.text)}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {visible.length > 0 && (
        <div className="results">
          <div className="results-head">
            <h3>
              为你推荐
              <span className="results-sub">
                {block.phase === 'done'
                  ? `共 ${block.totalCount} 道候选`
                  : '打分中…'}
              </span>
            </h3>
            {hasMore && (
              <button className="refresh-btn" type="button" onClick={onNextBatch}>
                <Icon name="refresh" size={13} />
                <span>换一批</span>
              </button>
            )}
          </div>
          {visible.map((card) => (
            <RecCard
              key={card.dishId}
              card={card}
              reason={card.reason ?? block.reasons[card.dishId]}
              onAdd={onAdd}
            />
          ))}
        </div>
      )}

      {block.phase === 'done' && block.cards.length > 0 && (
        <div className="refine-bar">
          <span className="refine-label">继续调整</span>
          <div className="u-chips">
            {REFINE_CHIPS.map((chip) => (
              <button
                className="chip"
                type="button"
                key={chip}
                disabled={busy}
                onClick={() => onRefine(chip)}
              >
                {chip}
              </button>
            ))}
          </div>
        </div>
      )}
    </>
  )
}

/* ---------------- 追问卡（W2） ---------------- */

/** 方向追问：确定性选项 chips，选中至少一项可「继续推荐」，未选题目按跳过该问处理。 */
function ClarifyCard({
  clarify,
  onAnswer,
}: {
  clarify: ClarifyEvent
  onAnswer: (answers: ClarifyAnswer[], skipped: boolean) => void
}) {
  const [picked, setPicked] = useState<Record<string, string | null>>({})
  const hasPicked = clarify.questions.some((question) => picked[question.id] != null)

  const submitPicked = () => {
    onAnswer(
      clarify.questions.map((question) => ({
        questionId: question.id,
        optionId: picked[question.id] ?? null,
      })),
      false,
    )
  }

  return (
    <div className="ai-card clarify-card">
      <div className="ai-label">
        <Icon name="sparkles" size={14} />
        <span>再确认一下，帮你挑得更准</span>
      </div>
      {clarify.questions.map((question) => (
        <div className="clarify-q" key={question.id}>
          <p className="clarify-title">{question.title}</p>
          <div className="u-chips">
            {question.options.map((option) => (
              <button
                className={`chip${picked[question.id] === option.id ? ' on' : ''}`}
                type="button"
                key={option.id}
                onClick={() =>
                  setPicked((prev) => ({
                    ...prev,
                    [question.id]: prev[question.id] === option.id ? null : option.id,
                  }))
                }
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="clarify-actions">
        <button
          className="btn btn-grad btn-sm"
          type="button"
          disabled={!hasPicked}
          onClick={submitPicked}
        >
          继续推荐
        </button>
        {clarify.allowSkip && (
          <button
            className="btn btn-outline btn-sm"
            type="button"
            onClick={() => onAnswer([], true)}
          >
            跳过，直接推荐
          </button>
        )}
      </div>
    </div>
  )
}

/* ---------------- 推荐卡 ---------------- */

function RecCard({
  card,
  reason,
  onAdd,
}: {
  card: DecisionCard
  reason?: string
  onAdd: (card: DecisionCard, event: MouseEvent<HTMLButtonElement>) => void
}) {
  const confidence = card.confidence
  const confClass = confidence >= 0.75 ? 'high' : confidence >= 0.6 ? 'mid' : 'low'
  return (
    <article className="ai-card rec-card">
      <div className="rec-head">
        <SafeImg className="rec-img" src={card.imageUrl ?? ''} alt={card.name} />
        <div className="rec-title">
          <h4>{card.name}</h4>
          <p>
            {card.merchantName} · ¥{formatMoney(card.price)}
          </p>
        </div>
        <div className="rec-score">
          <b>{Math.round(card.compositeScore)}</b>
          <span>综合分</span>
        </div>
      </div>

      <div className="rec-dims">
        {card.dims.map((dim: DimScore) => (
          <div className="dim" key={dim.dim}>
            <span className="dim-k">{DIM_LABELS[dim.dim] ?? dim.dim}</span>
            <span className="dim-bar">
              <i style={{ width: `${Math.max(0, Math.min(10, dim.value)) * 10}%` }} />
            </span>
            <b>{dim.value.toFixed(1)}</b>
          </div>
        ))}
      </div>

      <div className="rec-meta">
        <span className={`conf ${confClass}`}>置信度 {confidence.toFixed(2)}</span>
        {reason && <span className="rec-reason">{reason}</span>}
      </div>

      <div className="rec-tags">
        {card.mode === 'RULE' && <i className="tag tag-gray">规则分</i>}
        {card.near && <i className="tag tag-orange">接近</i>}
        {card.overBudget && <i className="tag tag-orange">略超预算</i>}
        {confidence < 0.6 && <i className="tag tag-orange">依据有限</i>}
      </div>

      <div className="rec-actions">
        <button className="btn btn-grad add-btn" type="button" onClick={(event) => onAdd(card, event)}>
          加入购物车
        </button>
        <Link className="rec-link" to={`/merchant/${card.merchantId}`}>
          看店铺
        </Link>
      </div>
    </article>
  )
}

/* ---------------- 文案工具 ---------------- */

function profileChips(profile: RunProfile, pendingClarify = false): string[] {
  const chips: string[] = []
  if (profile.taste_tags.length) chips.push(profile.taste_tags.join(' / '))
  if (profile.budget_max != null) chips.push(`${profile.budget_max} 元内`)
  if (profile.max_delivery_min != null) chips.push(`${profile.max_delivery_min} 分钟内送到`)
  if (profile.avoid_ingredients.length) chips.push(`不吃${profile.avoid_ingredients.join('、')}`)
  if (profile.scene) chips.push(profile.scene)
  if (profile.meal_period) chips.push(profile.meal_period)
  if (chips.length > 0) return chips
  // 待答追问时不再说「按热门推荐」：此时还没收口，方向由用户下一步选择决定
  return pendingClarify ? ['还没定方向，等你选一个'] : ['没有识别到明确偏好，按热门推荐']
}

function filteredText(filtered: {
  candidateCount: number
  poolSize: number
  filteredDetail: {
    closed: number
    unavailable: number
    budget: number
    avoid: number
    timeliness: number
    capped: number
  }
}): string {
  const detail = filtered.filteredDetail
  const parts: string[] = []
  if (detail.closed > 0) parts.push(`打烊 ${detail.closed}`)
  if (detail.unavailable > 0) parts.push(`售罄 ${detail.unavailable}`)
  if (detail.budget > 0) parts.push(`超预算 ${detail.budget}`)
  if (detail.avoid > 0) parts.push(`忌口 ${detail.avoid}`)
  if (detail.timeliness > 0) parts.push(`时效不符 ${detail.timeliness}`)
  if (detail.capped > 0) parts.push(`候选截断 ${detail.capped}`)
  const excluded = parts.length > 0 ? `（${parts.join(' · ')}）` : ''
  if (filtered.candidateCount === 0) {
    return `从 ${filtered.poolSize} 道菜中已过滤全部候选，可放宽条件重试`
  }
  return `已从 ${filtered.poolSize} 道菜中过滤出 ${filtered.candidateCount} 道候选${excluded}`
}

function cardToDish(card: DecisionCard): DishView {
  return {
    id: card.dishId,
    name: card.name,
    description: null,
    imageUrl: card.imageUrl ?? '',
    price: card.price,
    originPrice: null,
    monthlySales: 0,
    isAvailable: true,
    isCombo: false,
    priceFrom: card.specGroups.length > 0,
    tags: [],
    specGroups: card.specGroups,
  }
}