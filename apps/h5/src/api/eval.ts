import { apiFetch } from './client'

export interface EvalBatch {
  id: string
  rubricVersion: string
  caseCount: number
  triggeredBy: string
  startedAt: string
  finishedAt: string | null
  status: 'RUNNING' | 'DONE' | 'FAILED'
}

export interface EvalMetric {
  id: number
  batchId: string
  metricKey: string
  metricValue: number
  sampleSize: number
  computedAt: string
}

export interface EvalResult {
  id: number
  batchId: string
  caseId: string
  passed: number
  failDetailJson: string | null
}

export interface Badcase {
  id: number
  runId: string | null
  caseId: string | null
  category: string
  detailJson: string | null
  status: string
  createdAt: string
}

export interface GoldenCase {
  id: string
  caseType: string
  inputText: string
  expectedConstraints: string | null
  expectedPoints: string | null
  rubricVersion: string
  annotatedBy: string | null
  createdAt: string
}

export interface BatchDetail {
  batch: EvalBatch
  metrics: EvalMetric[]
}

export const runEvalBatch = (triggeredBy?: string) =>
  apiFetch<EvalBatch>('/eval/batches', { method: 'POST', query: { triggeredBy } })

export const listEvalBatches = () =>
  apiFetch<EvalBatch[]>('/eval/batches')

export const getEvalBatch = (batchId: string) =>
  apiFetch<BatchDetail>(`/eval/batches/${batchId}`)

export const listEvalResults = (batchId: string) =>
  apiFetch<EvalResult[]>(`/eval/batches/${batchId}/results`)

export const listBadcases = (status?: string) =>
  apiFetch<Badcase[]>('/eval/badcases', { query: { status } })

export const listGoldenCases = () =>
  apiFetch<GoldenCase[]>('/eval/golden-cases')

export const METRIC_LABELS: Record<string, string> = {
  hard_constraint_pass_rate: '硬约束零违规率',
  understanding_accuracy: '理解准确率',
  candidate_sufficiency: '候选充足率',
  empty_correctness: '空结果准确率',
  top1_taste_match: 'Top1 口味命中率',
  latency_p95_ms: 'P95 延迟(ms)',
}
