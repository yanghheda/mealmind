import { apiFetch } from './client'
import type { SessionGuestResponse, SessionMeResponse } from './types'

/** POST /api/session/guest —— 创建游客身份（无需鉴权）。 */
export function createGuestSession(): Promise<SessionGuestResponse> {
  return apiFetch('/session/guest', { method: 'POST' })
}

/** GET /api/session/me —— 查询当前登录态。 */
export function fetchSessionMe(): Promise<SessionMeResponse> {
  return apiFetch('/session/me')
}

/** DELETE /api/session —— 退出登录并清空该游客数据。 */
export function logoutSession(): Promise<null> {
  return apiFetch('/session', { method: 'DELETE' })
}
