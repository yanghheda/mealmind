import { create } from 'zustand'

interface UiState {
  toast: string | null
  showToast: (message: string) => void
}

let toastTimer: ReturnType<typeof setTimeout> | undefined

export const useUiStore = create<UiState>((set) => ({
  toast: null,
  showToast: (message) => {
    set({ toast: message })
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => set({ toast: null }), 1800)
  },
}))

/** 非组件上下文触发 Toast（如事件回调）。 */
export function showToast(message: string): void {
  useUiStore.getState().showToast(message)
}
