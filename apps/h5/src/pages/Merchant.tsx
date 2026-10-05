import { useRef, useState } from 'react'
import type { MouseEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StatusBar } from '../components/StatusBar'
import { Icon } from '../components/Icon'
import { SafeImg } from '../components/SafeImg'
import { SpecSheet } from '../components/SpecSheet'
import { Loading } from '../components/Loading'
import { fetchMerchant, fetchMerchantMenu } from '../api/merchants'
import { addCartItem } from '../api/cart'
import { CART_QUERY_KEY, useCart } from '../hooks/useCart'
import { flyToCart } from '../lib/flyToCart'
import {
  deliveryFeeText,
  distanceText,
  formatMoney,
  formatRating,
  minutesText,
  salesText,
} from '../lib/format'
import { showToast } from '../store/ui'
import type { AddCartItemRequest, DishView, MenuCategoryView } from '../api/types'
import './Merchant.css'

/** 商家详情 / 菜单（Shell 变体 B）：头部信息 + 菜单双栏 + 规格弹层 + 底部购物车条。 */
export function Merchant() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [activeCat, setActiveCat] = useState(0)
  const [specDish, setSpecDish] = useState<DishView | null>(null)
  const sourceRef = useRef<HTMLElement | null>(null)

  const merchantQuery = useQuery({
    queryKey: ['merchant', id],
    queryFn: () => fetchMerchant(id),
    enabled: Boolean(id),
  })
  const menuQuery = useQuery({
    queryKey: ['merchant', id, 'menu'],
    queryFn: () => fetchMerchantMenu(id),
    enabled: Boolean(id),
  })
  const cartQuery = useCart()
  const addMutation = useMutation({
    mutationFn: addCartItem,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CART_QUERY_KEY }),
  })

  const merchant = merchantQuery.data
  const categories: MenuCategoryView[] = menuQuery.data?.categories ?? []
  const closed = merchant ? !merchant.isOpen : false
  const cartGroup = cartQuery.data?.groups.find((group) => group.merchantId === id)
  const cartCount = cartGroup?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0
  const cartSubtotal = cartGroup?.subtotal ?? 0
  const minOrder = merchant?.minOrderAmount ?? 0

  const back = () => {
    if (window.history.length > 1) navigate(-1)
    else navigate('/home')
  }

  const submitAdd = (vars: AddCartItemRequest, source: HTMLElement | null, imageUrl?: string) => {
    addMutation.mutate(vars, {
      onSuccess: () => {
        flyToCart(source, imageUrl)
        showToast('已加入购物车')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '加购失败，请重试'),
    })
  }

  const handleAdd = (dish: DishView, event: MouseEvent<HTMLButtonElement>) => {
    const source =
      event.currentTarget.closest('.dish')?.querySelector<HTMLElement>('.dish-img') ?? null
    if (dish.specGroups.length > 0) {
      sourceRef.current = source
      setSpecDish(dish)
      return
    }
    submitAdd({ dishId: dish.id, quantity: 1 }, source, dish.imageUrl)
  }

  const handleSpecSubmit = (optionIds: string[], quantity: number) => {
    if (!specDish) return
    submitAdd(
      { dishId: specDish.id, specOptionIds: optionIds, quantity },
      sourceRef.current,
      specDish.imageUrl,
    )
    setSpecDish(null)
  }

  if (merchantQuery.isPending || menuQuery.isPending) return <Loading />
  if (!merchant || merchantQuery.isError || menuQuery.isError) {
    return (
      <div className="app app--merchant">
        <StatusBar />
        <main className="screen screen--flush">
          <div className="page-head">
            <button className="back" type="button" aria-label="返回" onClick={back}>
              <Icon name="arrow-left" size={21} />
            </button>
            <span className="title">商家</span>
          </div>
          <div className="empty">
            <p>商家信息加载失败</p>
            <button
              className="btn btn-outline btn-sm"
              type="button"
              onClick={() => {
                merchantQuery.refetch()
                menuQuery.refetch()
              }}
            >
              重试
            </button>
          </div>
        </main>
      </div>
    )
  }

  const activeCategory = categories[activeCat] ?? categories[0]
  const couponVisible = merchant.featureTags.includes('明厨亮灶')

  return (
    <div className="app app--merchant">
      <StatusBar />

      <main className="screen screen--flush m-screen">
        <section className="m-hero">
          <SafeImg className="m-hero-img" src={merchant.logoUrl} alt={merchant.name} />
          <div className="m-top">
            <button className="m-top-btn" type="button" aria-label="返回" onClick={back}>
              <Icon name="arrow-left" size={18} />
            </button>
            <div className="m-top-right">
              <button
                className="m-top-btn"
                type="button"
                aria-label="搜索"
                onClick={() => showToast('演示版本暂未开放搜索功能')}
              >
                <Icon name="search" size={18} />
              </button>
              <button
                className="m-top-btn"
                type="button"
                aria-label="分享"
                onClick={() => showToast('演示版本暂未开放分享功能')}
              >
                <Icon name="share" size={18} />
              </button>
              <button
                className="m-top-btn"
                type="button"
                aria-label="更多"
                onClick={() => showToast('演示版本暂未开放更多操作')}
              >
                <Icon name="more-horizontal" size={18} />
              </button>
            </div>
          </div>
        </section>

        <section className="m-hcard">
          <div className="mi-row">
            <SafeImg className="mi-logo" src={merchant.logoUrl} alt={merchant.name} />
            <div className="mi-main">
              <div className="mi-name-row">
                <h1 className="mi-name">{merchant.name}</h1>
                <button
                  className="btn btn-outline btn-sm"
                  type="button"
                  onClick={() => showToast('演示版本暂未开放预订功能')}
                >
                  预订
                </button>
              </div>
              <div className="mi-meta">
                <b className="rate">{formatRating(merchant.rating)}</b>
                <span className="mi-sep">·</span>
                <span>{salesText(merchant.monthlySales)}</span>
                {closed && (
                  <>
                    <span className="mi-sep">·</span>
                    <i className="tag tag-gray">休息中</i>
                  </>
                )}
              </div>
              <div className="mi-tags">
                {merchant.featureTags.map((tag) => (
                  <i className="tag tag-gray" key={tag}>
                    {tag}
                  </i>
                ))}
              </div>
            </div>
          </div>
          <div className="mi-subline">
            约 {minutesText(merchant.deliveryMinutes)} · 距您 {distanceText(merchant.distanceMeters)} · 起送 ¥
            {formatMoney(merchant.minOrderAmount)} · {deliveryFeeText(merchant.deliveryFee)}
          </div>
          {merchant.notice && merchant.notice.trim().length > 0 && (
            <div className="m-notice">
              <span className="nlabel">公告</span>
              <p>{merchant.notice}</p>
            </div>
          )}
        </section>

        <div className="m-tabs">
          <span className="m-tab on">点餐</span>
          <button
            className="m-tab"
            type="button"
            onClick={() => showToast('演示版本暂未开放评价')}
          >
            评价
          </button>
          <button
            className="m-tab"
            type="button"
            onClick={() => showToast('演示版本暂未开放品牌页')}
          >
            品牌
          </button>
          {couponVisible && <i className="tag tag-orange m-tab-tag">明厨亮灶</i>}
        </div>

        <button
          className="ai-strip"
          type="button"
          onClick={() => navigate(`/agent?merchantId=${encodeURIComponent(id)}`)}
        >
          <Icon name="sparkles" size={18} />
          <span>让 AI 帮我选这家</span>
          <span className="ai-go">
            <Icon name="chevron-right" size={16} />
          </span>
        </button>

        {closed && <div className="m-closed-tip">商家休息中，暂时无法加购</div>}

        <section className="menu">
          <aside className="rail">
            {categories.map((category, index) => (
              <button
                className={index === activeCat ? 'rail-item on' : 'rail-item'}
                type="button"
                key={category.categoryId}
                onClick={() => setActiveCat(index)}
              >
                {category.name}
              </button>
            ))}
          </aside>
          <div className="mlist">
            {activeCategory ? (
              <>
                <div className="ml-cat">
                  <h2>{activeCategory.name}</h2>
                  <span className="ml-count">共 {activeCategory.dishes.length} 件</span>
                </div>
                {activeCategory.dishes.map((dish) => (
                  <DishRow dish={dish} closed={closed} key={dish.id} onAdd={handleAdd} />
                ))}
              </>
            ) : (
              <div className="empty">
                <p>该商家暂无菜品</p>
              </div>
            )}
          </div>
        </section>

        <p className="app-footer-note">模拟数据 · 仅用于产品演示</p>
      </main>

      <div className="cartbar" role="region" aria-label="购物车">
        <button
          className="cb-left"
          type="button"
          aria-label="查看购物车"
          onClick={() => navigate('/cart')}
        >
          <span className="cb-ico">
            <Icon name="shopping-cart" size={22} />
            {cartCount > 0 && <i className="cb-badge">{cartCount > 99 ? '99+' : cartCount}</i>}
          </span>
          <span className="cb-info">
            <span className="cb-amount">¥{formatMoney(cartSubtotal)}</span>
            <span className="cb-sub">
              {minOrder > 0 && cartSubtotal < minOrder
                ? `差 ¥${formatMoney(minOrder - cartSubtotal)} 起送`
                : cartCount > 0
                  ? '已选商品'
                  : '还没选东西'}
            </span>
          </span>
        </button>
        <Link className="cb-go" to="/cart">
          去购物车
        </Link>
      </div>

      {specDish && (
        <SpecSheet
          dish={specDish}
          submitting={addMutation.isPending}
          onClose={() => setSpecDish(null)}
          onSubmit={handleSpecSubmit}
        />
      )}
    </div>
  )
}

interface DishRowProps {
  dish: DishView
  closed: boolean
  onAdd: (dish: DishView, event: MouseEvent<HTMLButtonElement>) => void
}

/** 菜品标签展示优先级：榜单 / 招牌最靠前，其后辣度、口味、食材、健康（后端按 tag_type 字母序返回）。 */
const TAG_PRIORITY = ['RANK', 'SELLING', 'SPICY', 'FLAVOR', 'INGREDIENT', 'HEALTH']

function tagRank(tagType: string): number {
  const index = TAG_PRIORITY.indexOf(tagType)
  return index === -1 ? TAG_PRIORITY.length : index
}

function DishRow({ dish, closed, onAdd }: DishRowProps) {
  const tags = [...dish.tags].sort((a, b) => tagRank(a.tagType) - tagRank(b.tagType)).slice(0, 3)

  return (
    <div className="dish">
      <SafeImg className="dish-img" src={dish.imageUrl} alt={dish.name} />
      <div className="dish-main">
        <div className="dish-name">{dish.name}</div>
        {dish.description && <div className="dish-desc">{dish.description}</div>}
        <div className="dish-meta">
          <span>{salesText(dish.monthlySales)}</span>
        </div>
        {tags.length > 0 && (
          <div className="dish-tags">
            {tags.map((item, index) => (
              <i className="tag tag-red" key={`${item.tag}-${index}`}>
                {item.tag}
              </i>
            ))}
          </div>
        )}
        <div className="dish-price-row">
          <span className="dish-price">
            <b className="price">¥{formatMoney(dish.price)}</b>
            {dish.priceFrom && <small>起</small>}
          </span>
          {dish.originPrice != null && dish.originPrice > dish.price && (
            <span className="dish-orig">¥{formatMoney(dish.originPrice)}</span>
          )}
          <span className="dish-act">
            {!dish.isAvailable ? (
              <span className="dish-soldout">售罄</span>
            ) : closed ? (
              <button className="add-btn" type="button" disabled aria-label="休息中不可加购">
                <Icon name="plus" size={16} />
              </button>
            ) : (
              <button
                className="add-btn"
                type="button"
                aria-label="加入购物车"
                onClick={(event) => onAdd(dish, event)}
              >
                <Icon name="plus" size={16} />
              </button>
            )}
          </span>
        </div>
      </div>
    </div>
  )
}
