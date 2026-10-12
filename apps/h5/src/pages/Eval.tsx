import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { StatusBar } from '../components/StatusBar'
import { Icon } from '../components/Icon'
import {
  getCostSummary,
  getEvalBatch,
  listBadcases,
  listEvalBatches,
  listGoldenCases,
  runEvalBatch,
  METRIC_LABELS,
  type Badcase,
} from '../api/eval'
import { showToast } from '../store/ui'
import './Eval.css'

const METRIC_KEYS = [
  'hard_constraint_pass_rate',
  'understanding_accuracy',
  'candidate_sufficiency',
  'empty_correctness',
  'top1_taste_match',
  'latency_p95_ms',
  'stability_top1_consistency',
  'stability_dim_range_max',
  'stability_pass_rate',
]

/** 评测看板页（PRD 4.9）：触发批次、看指标、查 badcase。 */
export function Eval() {
  const queryClient = useQueryClient()
  const [selectedBatchId, setSelectedBatchId] = useState<string | null>(null)

  const { data: batches } = useQuery({
    queryKey: ['eval-batches'],
    queryFn: listEvalBatches,
    refetchInterval: 5000,
  })
  const { data: badcases } = useQuery({
    queryKey: ['eval-badcases'],
    queryFn: () => listBadcases('OPEN'),
  })
  const { data: goldenCases } = useQuery({
    queryKey: ['eval-golden'],
    queryFn: listGoldenCases,
  })
  const { data: detail } = useQuery({
    queryKey: ['eval-batch-detail', selectedBatchId],
    queryFn: () => getEvalBatch(selectedBatchId!),
    enabled: !!selectedBatchId,
  })
  const { data: costSummary } = useQuery({
    queryKey: ['eval-cost-summary'],
    queryFn: () => getCostSummary(),
  })

  const runMutation = useMutation({
    mutationFn: () => runEvalBatch('h5'),
    onSuccess: (batch) => {
      showToast('评测已触发')
      setSelectedBatchId(batch.id)
      queryClient.invalidateQueries({ queryKey: ['eval-batches'] })
    },
    onError: () => showToast('触发失败，请检查服务'),
  })

  const latestBatch = batches?.[0]
  const metrics = detail?.metrics ?? []
  const metricMap = new Map(metrics.map((m) => [m.metricKey, m]))

  return (
    <div className="eval-page">
      <StatusBar />

      <section className="eval-hero">
        <button className="eval-run-btn" disabled={runMutation.isPending} onClick={() => runMutation.mutate()}>
          <Icon name="sparkles" />
          {runMutation.isPending ? '评测中…' : '跑一次全量评测'}
        </button>
        <p className="eval-hero-sub">
          当前模式：<span className="tag">AUTO</span>
          {goldenCases && <span className="eval-count">{goldenCases.length} 条 Golden 用例</span>}
        </p>
      </section>

      {costSummary && (
        <section className="eval-cost">
          <h2>单次决策成本</h2>
          <div className="metric-grid">
            <div className="metric-card">
              <div className="metric-label">平均成本（元/次）</div>
              <div className="metric-value">{costSummary.avgCostYuan.toFixed(5)}</div>
            </div>
            <div className="metric-card">
              <div className="metric-label">门限（元/次）</div>
              <div className={`metric-value ${costSummary.overGate ? 'cost-over' : 'cost-ok'}`}>
                {costSummary.gateYuan.toFixed(2)}
                <span className="cost-flag">{costSummary.overGate ? '超门限' : '达标'}</span>
              </div>
            </div>
          </div>
          <p className="eval-batch-meta">
            样本 {costSummary.sampleSize} 次 · P95 延迟 {Math.round(costSummary.p95LatencyMs)}ms ·
            标价口径（免费额度内实付 0）
          </p>
        </section>
      )}

      {latestBatch && (
        <section className="eval-metrics">
          <h2>最新批次指标</h2>
          <div className="metric-grid">
            {METRIC_KEYS.map((key) => {
              const m = metricMap.get(key)
              const value = !m
                ? '—'
                : key === 'latency_p95_ms'
                  ? Math.round(m.metricValue)
                  : key === 'stability_dim_range_max'
                    ? m.metricValue.toFixed(2)
                    : `${m.metricValue}%`
              return (
                <div key={key} className="metric-card">
                  <div className="metric-label">{METRIC_LABELS[key] ?? key}</div>
                  <div className="metric-value">{value}</div>
                </div>
              )
            })}
          </div>
          <p className="eval-batch-meta">
            批次 {latestBatch.id.slice(0, 8)} · {latestBatch.caseCount} 用例 · {latestBatch.status}
          </p>
        </section>
      )}

      <section className="eval-batches">
        <h2>历史批次</h2>
        {batches?.length === 0 && <p className="empty">暂无批次</p>}
        <ul className="batch-list">
          {batches?.map((b) => (
            <li
              key={b.id}
              className={`batch-item ${selectedBatchId === b.id ? 'active' : ''}`}
              onClick={() => setSelectedBatchId(b.id)}
            >
              <span className="batch-id">{b.id.slice(0, 8)}</span>
              <span className="batch-cases">{b.caseCount} 用例</span>
              <span className={`batch-status status-${b.status}`}>{b.status}</span>
              <span className="batch-time">{new Date(b.startedAt).toLocaleString()}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="eval-badcases">
        <h2>Badcase（OPEN）</h2>
        {badcases?.length === 0 && <p className="empty">暂无 badcase 🎉</p>}
        <ul className="badcase-list">
          {badcases?.slice(0, 20).map((bc) => (
            <BadcaseItem key={bc.id} bc={bc} />
          ))}
        </ul>
      </section>
    </div>
  )
}

function BadcaseItem({ bc }: { bc: Badcase }) {
  let detail: { input?: string; category?: string; cardCount?: number } = {}
  try {
    detail = bc.detailJson ? JSON.parse(bc.detailJson) : {}
  } catch {
    /* ignore */
  }
  return (
    <li className="badcase-item">
      <div className="badcase-head">
        <span className="badcase-cat">{bc.category}</span>
        <span className="badcase-time">{new Date(bc.createdAt).toLocaleString()}</span>
      </div>
      <p className="badcase-input">{detail.input ?? '（无输入）'}</p>
    </li>
  )
}
