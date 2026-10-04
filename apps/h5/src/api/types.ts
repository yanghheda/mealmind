/**
 * 后端 DTO 的 TypeScript 映射（字段名与 services/biz 的 record 完全一致，camelCase）。
 * BigDecimal 由 Jackson 序列化为 number，时间由 Spring Boot 序列化为 ISO-8601 字符串。
 */

/** 统一响应体 {code, message, data}；成功 code = 0，失败 code 为 ErrorCode 名。 */
export interface ApiEnvelope<T> {
  code: number | string
  message: string
  data: T
}

/* ---------------- 会话 ---------------- */

export interface SessionGuestResponse {
  sessionId: string
  nickname: string
  loginAt: string
}

export interface SessionMeResponse {
  sessionId: string
  nickname: string
  createdAt: string
  lastActiveAt: string
  status: string
}

/* ---------------- 商家与菜单 ---------------- */

export interface PreviewDish {
  id: string
  name: string
  imageUrl: string
  price: number
}

export interface MerchantView {
  id: string
  name: string
  logoUrl: string
  rating: number
  monthlySales: number
  deliveryMinutes: number
  distanceMeters: number
  minOrderAmount: number
  deliveryFee: number
  isOpen: boolean
  rankLabel: string | null
  notice: string | null
  promoTags: string[]
  featureTags: string[]
  previewDishes: PreviewDish[]
}

export interface MerchantListResponse {
  list: MerchantView[]
  total: number
  hasMore: boolean
}

export interface DishTagView {
  tag: string
  tagType: string
}

export interface SpecOptionView {
  optionId: string
  optionName: string
  extraPrice: number
  isDefault: boolean
}

export interface SpecGroupView {
  groupId: string
  groupName: string
  isRequired: boolean
  options: SpecOptionView[]
}

export interface DishView {
  id: string
  name: string
  description: string | null
  imageUrl: string
  price: number
  originPrice: number | null
  monthlySales: number
  isAvailable: boolean
  isCombo: boolean
  priceFrom: boolean
  tags: DishTagView[]
  specGroups: SpecGroupView[]
}

export interface MenuCategoryView {
  categoryId: string
  name: string
  dishes: DishView[]
}

export interface MenuResponse {
  merchantId: string
  categories: MenuCategoryView[]
}

/* ---------------- 购物车 ---------------- */

export interface CartItemView {
  id: string
  dishId: string
  name: string
  imageUrl: string
  specText: string | null
  unitPrice: number
  quantity: number
  isAvailable: boolean
  isCombo: boolean
}

export interface CartGroupView {
  merchantId: string
  merchantName: string
  logoUrl: string
  items: CartItemView[]
  subtotal: number
}

export interface CartResponse {
  groups: CartGroupView[]
  totalQuantity: number
  totalAmount: number
}

export interface AddCartItemRequest {
  dishId: string
  specOptionIds?: string[]
  quantity: number
}

export interface AddCartItemResponse {
  itemId: string
  quantity: number
}

export interface UpdateCartItemRequest {
  itemId: string
  quantity: number
}

export interface UpdateCartItemResponse {
  itemId: string
  quantity: number
  deleted: boolean
}

export interface DeleteCartItemResponse {
  deleted: number
}
