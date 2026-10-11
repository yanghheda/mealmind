import { apiFetch } from './client'

export type PreferenceType = 'TASTE' | 'BUDGET' | 'AVOID' | 'SCENE'
export type PreferenceStatus = 'ACTIVE' | 'PENDING' | 'IGNORED'

/** 接口 12/13 返回的偏好视图（后端 PreferenceView）。 */
export interface PreferenceView {
  id: string
  type: PreferenceType
  content: string
  source: 'AUTO' | 'MANUAL'
  status: PreferenceStatus
  seenCount: number
}

/** understand 事件的 appliedPreferences 载荷（接口 16 向后兼容新增字段，仅 id/type/content）。 */
export interface AppliedPreference {
  id: string
  type: PreferenceType
  content: string
}

export interface PreferenceListResponse {
  items: PreferenceView[]
}

export interface PreferenceDeleteResponse {
  id: string
  status: string
}

/** 记忆卡 react-query 缓存键（我的页与会话页共用，写入/撤销后统一失效）。 */
export const PREFERENCE_QUERY_KEY = ['preferences'] as const

/** GET /api/preferences —— 接口 12：记忆卡列表（仅 ACTIVE）。 */
export function fetchPreferences(): Promise<PreferenceListResponse> {
  return apiFetch('/preferences')
}

/** POST /api/preferences —— 接口 13：新增 / 复活偏好（同内容幂等）。 */
export function createPreference(type: PreferenceType, content: string): Promise<PreferenceView> {
  return apiFetch('/preferences', { method: 'POST', body: { type, content } })
}

/** DELETE /api/preferences/{id} —— 接口 14：删除（幂等墓碑，同内容不再自动写入）。 */
export function deletePreference(id: string): Promise<PreferenceDeleteResponse> {
  return apiFetch(`/preferences/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/** 记忆 chip 展示文案：忌口加「不吃」前缀、预算加「≤ ¥N」前缀，与 prototype/mine.html 一致。 */
export function preferenceLabel(item: { type: PreferenceType; content: string }): string {
  switch (item.type) {
    case 'AVOID':
      return `不吃${item.content}`
    case 'BUDGET':
      return `预算 ≤ ¥${item.content}`
    default:
      return item.content
  }
}