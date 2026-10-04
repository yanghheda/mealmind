import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchSessionMe } from '../api/session'
import { useSessionStore } from '../store/session'
import { Loading } from './Loading'

/**
 * 受保护路由：本地存在 sessionId 时，先用 GET /api/session/me 校验登录态，
 * 校验通过才渲染子路由；无会话或校验失败一律回到封面。
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const sessionId = useSessionStore((state) => state.sessionId)
  const clear = useSessionStore((state) => state.clear)

  const me = useQuery({
    queryKey: ['session', 'me'],
    queryFn: fetchSessionMe,
    enabled: Boolean(sessionId),
    retry: false,
    staleTime: Infinity,
  })

  useEffect(() => {
    if (me.isError) clear()
  }, [me.isError, clear])

  if (!sessionId) return <Navigate to="/" replace />
  if (me.isPending) return <Loading />
  if (me.isError) return <Navigate to="/" replace />
  return <>{children}</>
}
