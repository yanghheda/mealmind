interface StatusBarProps {
  /** 暖色底：首页头部使用 statusbar--warm。 */
  warm?: boolean
}

/** 仿真状态栏（设计契约 §5，随 Shell 复制）。 */
export function StatusBar({ warm = false }: StatusBarProps) {
  return (
    <div className={warm ? 'statusbar statusbar--warm' : 'statusbar'} aria-hidden="true">
      <span>9:41</span>
      <span className="statusbar-icons">
        <svg width="17" height="11" viewBox="0 0 17 11" fill="currentColor">
          <rect x="0" y="7" width="3" height="4" rx="0.8" />
          <rect x="4.5" y="5" width="3" height="6" rx="0.8" />
          <rect x="9" y="2.6" width="3" height="8.4" rx="0.8" />
          <rect x="13.5" y="0" width="3" height="11" rx="0.8" />
        </svg>
        <svg width="16" height="11" viewBox="0 0 16 11" fill="currentColor">
          <path d="M8 10.6 6.2 8.7a2.6 2.6 0 0 1 3.6 0L8 10.6z" />
          <path d="M8 6.6c1.2 0 2.3.45 3.1 1.2l1.2-1.25A6.4 6.4 0 0 0 8 4.8a6.4 6.4 0 0 0-4.3 1.75L4.9 7.8A4.5 4.5 0 0 1 8 6.6z" />
          <path d="M8 2.8c2.1 0 4 .8 5.5 2.1l1.2-1.25A9.3 9.3 0 0 0 8 1 9.3 9.3 0 0 0 1.3 3.65L2.5 4.9A7.4 7.4 0 0 1 8 2.8z" />
        </svg>
        <svg width="25" height="12" viewBox="0 0 25 12">
          <rect x="0.5" y="0.5" width="21" height="11" rx="3" fill="none" stroke="currentColor" opacity="0.4" />
          <rect x="2" y="2" width="18" height="8" rx="1.8" fill="currentColor" />
          <path d="M23 4v4a2.2 2.2 0 0 0 0-4z" fill="currentColor" opacity="0.4" />
        </svg>
      </span>
    </div>
  )
}
