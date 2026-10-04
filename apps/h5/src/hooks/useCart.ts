import { fetchCart } from '../api/cart'
import { useQuery } from '@tanstack/react-query'

export const CART_QUERY_KEY = ['cart'] as const

/** 购物车查询：Tab 角标、商家页底部条、购物车页共用同一份缓存。 */
export function useCart() {
  return useQuery({
    queryKey: CART_QUERY_KEY,
    queryFn: fetchCart,
  })
}
