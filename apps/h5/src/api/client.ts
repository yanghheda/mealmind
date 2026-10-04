import { clearSessionLocal, getSessionId } from '../store/session'
import type { ApiEnvelope } from './types'

const SESSION_HEADER = 'X-Session-Id'

/** 业务错误：携带后端 code（成功为 0，失败为 ErrorCode 名或 HTTP 状态码）。 */
export class ApiError extends Error {
  readonly code: number | string
  readonly status: number

  constructor(code: number | string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  query?: Record<string, string | number | undefined>
  body?: unknown
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const search = new URLSearchParams()
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
    }
  }
  const suffix = search.toString()
  return `/api${path}${suffix ? `?${suffix}` : ''}`
}

/**
 * 统一请求封装：注入 X-Session-Id，解包 {code,message,data}；
 * HTTP 401 清除本地会话并回到封面。
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  const sessionId = getSessionId()
  if (sessionId) headers.set(SESSION_HEADER, sessionId)

  let body: string | undefined
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(options.body)
  }

  const response = await fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body,
  })

  if (response.status === 401) {
    clearSessionLocal()
    if (window.location.pathname !== '/') window.location.assign('/')
    throw new ApiError('UNAUTHORIZED', '登录态已失效，请重新进入', 401)
  }

  let payload: ApiEnvelope<T> | null = null
  try {
    payload = (await response.json()) as ApiEnvelope<T>
  } catch {
    payload = null
  }

  if (!payload) {
    throw new ApiError(response.status || 'INTERNAL_ERROR', '服务响应异常', response.status)
  }
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message || '请求失败', response.status)
  }
  return payload.data
}
