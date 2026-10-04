/** 首页分类宫格（与 prototype/mock.js 的 DB.categories 一致）。仅「美食外卖」为真实入口。 */
export interface HomeCategory {
  id: string
  name: string
  tint: string
  ink: string
  icon: string
  real: boolean
}

export const HOME_CATEGORIES: HomeCategory[] = [
  { id: 'food', name: '美食外卖', tint: '#FFEAE0', ink: '#FF5000', icon: 'utensils', real: true },
  { id: 'market', name: '超市便利', tint: '#EAF3FF', ink: '#2E7CF6', icon: 'shopping-bag', real: false },
  { id: 'drug', name: '买药看病', tint: '#EAF7EE', ink: '#1E9E57', icon: 'pill', real: false },
  { id: 'fresh', name: '生鲜到家', tint: '#EAF9F3', ink: '#0FA678', icon: 'sprout', real: false },
  { id: 'flower', name: '鲜花礼品', tint: '#FFF0F3', ink: '#F0436E', icon: 'flower', real: false },
  { id: 'dessert', name: '甜品饮品', tint: '#FFF6E5', ink: '#E8900C', icon: 'coffee', real: false },
  { id: 'coupon', name: '天天红包', tint: '#FFF0EC', ink: '#FA2C19', icon: 'percent', real: false },
  { id: 'fruit', name: '0 元领水果', tint: '#FFF3E6', ink: '#F07B0C', icon: 'citrus', real: false },
  { id: 'beans', name: '赚吃货豆', tint: '#FFF8E6', ink: '#C98A00', icon: 'coins', real: false },
  { id: 'group', name: '爆品团', tint: '#FFEDE6', ink: '#FF4A1F', icon: 'flame', real: false },
]

/* 演示期固定文案（后端未提供定位/搜索热词/公告等字段，按原型常量兜底）。 */
export const LOCATION_NAME = '星海中心 · B 座'
export const SEARCH_PLACEHOLDER = '大家都在搜：麻辣烫'

/** 未开放入口的统一提示文案（设计契约 §7）。 */
export const PLACEHOLDER_TOAST = '演示版本仅开放「美食外卖」'

/** 图片加载失败时的兜底图（与后端 ImageUrlResolver 的 default 一致）。 */
export const DEFAULT_IMAGE = '/images/cat-default.svg'
