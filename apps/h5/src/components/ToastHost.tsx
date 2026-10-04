import { useUiStore } from '../store/ui'

/** 全局 Toast 容器（设计契约 §4 .toast）。 */
export function ToastHost() {
  const toast = useUiStore((state) => state.toast)
  return <div className={toast ? 'toast show' : 'toast'}>{toast}</div>
}
