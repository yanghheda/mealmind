import { apiFetch } from './client'
import type { SearchResponse } from './types'

/** GET /api/search?keyword=&page=&pageSize= —— 关键字搜索（商家 + 菜品分组）。 */
export function fetchSearch(keyword: string, page = 1, pageSize = 10): Promise<SearchResponse> {
  return apiFetch('/search', { query: { keyword, page, pageSize } })
}