import { create } from 'zustand'

/** localStorage 键名与原型保持一致，便于演示数据衔接。 */
const STORAGE_KEY = 'mealmind.session'

export interface StoredSession {
  sessionId: string
  nickname: string
  loginAt: string
}

function readStored(): StoredSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredSession>
    if (!parsed || typeof parsed.sessionId !== 'string' || parsed.sessionId.length === 0) return null
    return {
      sessionId: parsed.sessionId,
      nickname: parsed.nickname ?? '',
      loginAt: parsed.loginAt ?? '',
    }
  } catch {
    return null
  }
}

interface SessionState {
  sessionId: string | null
  nickname: string | null
  loginAt: string | null
  setSession: (session: StoredSession) => void
  clear: () => void
}

const stored = readStored()

export const useSessionStore = create<SessionState>((set) => ({
  sessionId: stored?.sessionId ?? null,
  nickname: stored?.nickname ?? null,
  loginAt: stored?.loginAt ?? null,
  setSession: (session) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    } catch {
      /* 忽略持久化失败，内存态仍可用 */
    }
    set({ sessionId: session.sessionId, nickname: session.nickname, loginAt: session.loginAt })
  },
  clear: () => {
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      /* 忽略 */
    }
    set({ sessionId: null, nickname: null, loginAt: null })
  },
}))

/** 供 API 客户端在非 React 上下文读取当前会话。 */
export function getSessionId(): string | null {
  return useSessionStore.getState().sessionId
}

/** 供 API 客户端在收到 401 时清除本地登录态。 */
export function clearSessionLocal(): void {
  useSessionStore.getState().clear()
}
