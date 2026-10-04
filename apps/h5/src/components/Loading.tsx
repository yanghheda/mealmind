import type { CSSProperties } from 'react'

const style: CSSProperties = {
  padding: '120px 0',
  textAlign: 'center',
  color: 'var(--c-text-ter)',
  fontSize: 13,
}

/** 鉴权校验 / 路由切换时的轻量占位。 */
export function Loading({ text = '加载中…' }: { text?: string }) {
  return (
    <div className="app">
      <div className="screen" style={style}>
        {text}
      </div>
    </div>
  )
}
