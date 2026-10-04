import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { StatusBar } from '../components/StatusBar'
import { TabBar } from '../components/TabBar'
import { Icon } from '../components/Icon'
import { SafeImg } from '../components/SafeImg'
import { deleteCartItem, updateCartItem } from '../api/cart'
import { CART_QUERY_KEY, useCart } from '../hooks/useCart'
import { formatMoney } from '../lib/format'
import { LOCATION_NAME, PLACEHOLDER_TOAST } from '../lib/constants'
import { showToast } from '../store/ui'
import type { CartGroupView, CartItemView } from '../api/types'
import './Cart.css'

/**
 * 购物车（Shell 变体 A）：按商家分组。
 * 条目默认勾选、可单条取消或全选；合计 = Σ(选中条目单价 × 数量)；
 * 底部 Tab 角标仍由服务端 totalQuantity 驱动，勾选不影响角标；「管理」切换删除态（PRD §4.6）。
 */
export function Cart() {
  const queryClient = useQueryClient()
  const cartQuery = useCart()
  // 仅记录用户显式取消/勾选的结果，未记录的条目按默认勾选处理（无需 effect 同步）
  const [overrides, setOverrides] = useState<Record<string, boolean>>({})
  const [manage, setManage] = useState(false)
  const invalidate = () => queryClient.invalidateQueries({ queryKey: CART_QUERY_KEY })

  const onError = (error: unknown) => showToast(error instanceof Error ? error.message : '操作失败')

  const updateMutation = useMutation({
    mutationFn: ({ itemId, quantity }: { itemId: string; quantity: number }) =>
      updateCartItem(itemId, quantity),
    onSuccess: invalidate,
    onError,
  })
  const deleteItemMutation = useMutation({
    mutationFn: (itemId: string) => deleteCartItem({ itemId }),
    onSuccess: () => {
      invalidate()
      showToast('已移除该商品')
    },
    onError,
  })
  const deleteGroupMutation = useMutation({
    mutationFn: (merchantId: string) => deleteCartItem({ merchantId }),
    onSuccess: () => {
      invalidate()
      showToast('已清空该商家商品')
    },
    onError,
  })

  const groups = cartQuery.data?.groups ?? []
  const items = groups.flatMap((group) => group.items)

  const isChecked = (id: string) => overrides[id] ?? true
  const allChecked = items.length > 0 && items.every((item) => isChecked(item.id))
  const totalAmount = items.reduce(
    (sum, item) => (isChecked(item.id) ? sum + item.unitPrice * item.quantity : sum),
    0,
  )

  const toggleItem = (id: string) =>
    setOverrides((prev) => ({ ...prev, [id]: !(prev[id] ?? true) }))
  const toggleAll = () =>
    setOverrides((prev) => {
      const next: Record<string, boolean> = { ...prev }
      for (const item of items) next[item.id] = !allChecked
      return next
    })

  // 数量减到 0 即删除（PATCH quantity=0，后端同事务删除）
  const step = (item: CartItemView, delta: number) => {
    const next = item.quantity + delta
    if (next <= 0) {
      deleteItemMutation.mutate(item.id)
      return
    }
    updateMutation.mutate({ itemId: item.id, quantity: next })
  }

  return (
    <div className="app app--cart">
      <StatusBar />

      <main className="screen cart-screen">
        <div className="page-head">
          <span className="title">购物车</span>
          <button
            className="cart-loc"
            type="button"
            onClick={() => showToast('演示版本暂不支持切换收货地址')}
          >
            <Icon name="map-pin" size={13} />
            <span>{LOCATION_NAME}</span>
            <Icon name="chevron-down" size={12} className="chev" />
          </button>
          <div className="head-actions">
            <button className="cart-manage" type="button" onClick={() => setManage((value) => !value)}>
              {manage ? '完成' : '管理'}
            </button>
          </div>
        </div>

        <div className="cart-list">
          {cartQuery.isPending && (
            <div className="empty">
              <p>加载中…</p>
            </div>
          )}

          {cartQuery.isError && (
            <div className="empty">
              <p>购物车加载失败</p>
              <button className="btn btn-outline btn-sm" type="button" onClick={() => cartQuery.refetch()}>
                重试
              </button>
            </div>
          )}

          {!cartQuery.isPending && !cartQuery.isError && groups.length === 0 && (
            <div className="empty">
              <Icon name="shopping-cart" size={40} />
              <p className="empty-title">购物车还是空的</p>
              <div className="empty-acts">
                <button
                  className="btn btn-outline btn-sm"
                  type="button"
                  onClick={() => showToast(PLACEHOLDER_TOAST)}
                >
                  去 AI 决策
                </button>
              </div>
            </div>
          )}

          {groups.map((group) => (
            <CartGroupCard
              group={group}
              key={group.merchantId}
              manage={manage}
              isChecked={isChecked}
              onToggleItem={toggleItem}
              onStep={step}
              onDeleteItem={(item) => deleteItemMutation.mutate(item.id)}
              onDeleteGroup={(merchantId) => deleteGroupMutation.mutate(merchantId)}
            />
          ))}
        </div>

        <p className="app-footer-note">模拟数据 · 仅用于产品演示</p>
      </main>

      {groups.length > 0 && (
        <div className="cartbar">
          <button className="cartbar-all" type="button" onClick={toggleAll}>
            <span className={allChecked ? 'pick on' : 'pick'}>
              <PickIcon on={allChecked} />
            </span>
            <span>全选</span>
          </button>
          <div className="cartbar-sum">
            <span className="cartbar-slabel">合计</span>
            <b className="price cartbar-total">¥{formatMoney(totalAmount)}</b>
          </div>
          <button
            className="btn btn-grad cartbar-cta"
            type="button"
            onClick={() => showToast('演示版本至加购为止，不含结算')}
          >
            一键结算
          </button>
        </div>
      )}

      <TabBar active="cart" />
    </div>
  )
}

interface CartGroupCardProps {
  group: CartGroupView
  manage: boolean
  isChecked: (itemId: string) => boolean
  onToggleItem: (itemId: string) => void
  onStep: (item: CartItemView, delta: number) => void
  onDeleteItem: (item: CartItemView) => void
  onDeleteGroup: (merchantId: string) => void
}

function CartGroupCard({
  group,
  manage,
  isChecked,
  onToggleItem,
  onStep,
  onDeleteItem,
  onDeleteGroup,
}: CartGroupCardProps) {
  return (
    <section className="card">
      <div className="cart-ghead">
        <Link className="cart-gname" to={`/merchant/${group.merchantId}`}>
          <span className="cart-gtext">{group.merchantName}</span>
          <Icon name="chevron-right" size={13} />
        </Link>
        <span className="cart-gsubtotal">小计 ¥{formatMoney(group.subtotal)}</span>
        <button
          className="cart-gdel"
          type="button"
          aria-label="删除该商家全部商品"
          onClick={() => onDeleteGroup(group.merchantId)}
        >
          <Icon name="trash-2" size={16} />
        </button>
      </div>
      <div className="cart-gitems">
        {group.items.map((item) => (
          <div className="cart-item" key={item.id}>
            <button
              className={isChecked(item.id) ? 'pick on' : 'pick'}
              type="button"
              aria-label="选择商品"
              onClick={() => onToggleItem(item.id)}
            >
              <PickIcon on={isChecked(item.id)} />
            </button>
            <SafeImg className="cart-thumb" src={item.imageUrl} alt={item.name} />
            <div className="cart-info">
              <div className="cart-iname">
                {item.name}
                {!item.isAvailable && <i className="tag tag-gray cart-iunavailable">已下架</i>}
              </div>
              {item.specText && <div className="cart-ispec">{item.specText}</div>}
              <div className="cart-ibottom">
                <span className="price cart-iprice">¥{formatMoney(item.unitPrice)}</span>
                <span className="stepper">
                  <button
                    className="minus"
                    type="button"
                    aria-label="减少"
                    disabled={!item.isAvailable}
                    onClick={() => onStep(item, -1)}
                  >
                    <Icon name="minus" size={14} />
                  </button>
                  <span className="num">{item.quantity}</span>
                  <button
                    className="plus"
                    type="button"
                    aria-label="增加"
                    disabled={!item.isAvailable}
                    onClick={() => onStep(item, 1)}
                  >
                    <Icon name="plus" size={14} />
                  </button>
                </span>
              </div>
            </div>
            {manage && (
              <button
                className="cart-idel"
                type="button"
                aria-label="删除商品"
                onClick={() => onDeleteItem(item)}
              >
                <Icon name="trash-2" size={18} />
              </button>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}

/** 勾选圈（原型 cart.html 的 pickSvg）：选中填充+对勾，未选中描边圈。 */
function PickIcon({ on }: { on: boolean }) {
  if (on) {
    return (
      <svg width="20" height="20" viewBox="0 0 24 24">
        <circle cx="12" cy="12" r="10" fill="currentColor" />
        <path
          d="m7.4 12.4 3.1 3.1 6.1-6.6"
          fill="none"
          stroke="#fff"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    )
  }
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="9.4" />
    </svg>
  )
}
