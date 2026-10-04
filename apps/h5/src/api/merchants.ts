import { apiFetch } from './client'
import type { MenuResponse, MerchantListResponse, MerchantView } from './types'

/** GET /api/home/merchants?page=&pageSize= —— 首页推荐商家流（分页）。 */
export function fetchHomeMerchants(page: number, pageSize = 10): Promise<MerchantListResponse> {
  return apiFetch('/home/merchants', { query: { page, pageSize } })
}

/** GET /api/merchants/{id} —— 商家详情。 */
export function fetchMerchant(merchantId: string): Promise<MerchantView> {
  return apiFetch(`/merchants/${encodeURIComponent(merchantId)}`)
}

/** GET /api/merchants/{id}/menu —— 商家菜单（分类与菜品）。 */
export function fetchMerchantMenu(merchantId: string): Promise<MenuResponse> {
  return apiFetch(`/merchants/${encodeURIComponent(merchantId)}/menu`)
}
