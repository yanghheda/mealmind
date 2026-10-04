import { apiFetch } from './client'
import type {
  AddCartItemRequest,
  AddCartItemResponse,
  CartResponse,
  DeleteCartItemResponse,
  UpdateCartItemResponse,
} from './types'

/** GET /api/cart —— 查询购物车（按商家分组）。 */
export function fetchCart(): Promise<CartResponse> {
  return apiFetch('/cart')
}

/** POST /api/cart/items —— 加入购物车（同菜品 + 同规格合并数量）。 */
export function addCartItem(request: AddCartItemRequest): Promise<AddCartItemResponse> {
  return apiFetch('/cart/items', { method: 'POST', body: request })
}

/** PATCH /api/cart/items —— 修改数量（quantity = 0 表示删除）。 */
export function updateCartItem(itemId: string, quantity: number): Promise<UpdateCartItemResponse> {
  return apiFetch('/cart/items', { method: 'PATCH', body: { itemId, quantity } })
}

/** DELETE /api/cart/items —— 删除单条（itemId）或清空一组（merchantId）。 */
export function deleteCartItem(
  params: { itemId: string } | { merchantId: string },
): Promise<DeleteCartItemResponse> {
  return apiFetch('/cart/items', { method: 'DELETE', query: params })
}
