import { Link } from 'react-router-dom'
import { Icon } from './Icon'
import { useCart } from '../hooks/useCart'
import { showToast } from '../store/ui'
import { PLACEHOLDER_TOAST } from '../lib/constants'

interface TabBarProps {
  /** 当前激活项；home / cart / mine 有真实页面。 */
  active: 'home' | 'cart' | 'mine'
}

/**
 * 冻结导航（设计契约 §5 变体 A）。
 * 消息未实现，走 §7 占位提示；AI 点外卖进会话页；购物车角标由真实购物车数量驱动。
 */
export function TabBar({ active }: TabBarProps) {
  const { data } = useCart()
  const count = data?.totalQuantity ?? 0
  const placeholder = () => showToast(PLACEHOLDER_TOAST)

  return (
    <nav className="tabbar" aria-label="主导航">
      <Link className={active === 'home' ? 'tab is-active' : 'tab'} data-nav="home" to="/home">
        <Icon name="house" />
        <span>推荐</span>
      </Link>
      <button className="tab" type="button" onClick={placeholder}>
        <Icon name="message-circle" />
        <span>消息</span>
        <i className="tab-dot" />
      </button>
      <Link className="tab tab-ai" data-nav="agent" to="/agent">
        <span className="tab-ai-btn">
          <Icon name="sparkles" size={24} />
        </span>
        <span>AI 点外卖</span>
      </Link>
      <Link className={active === 'cart' ? 'tab is-active' : 'tab'} data-nav="cart" to="/cart">
        <span className="tab-ico">
          <Icon name="shopping-cart" />
          {count > 0 && <i className="tab-badge">{count > 99 ? '99+' : count}</i>}
        </span>
        <span>购物车</span>
      </Link>
      <Link className={active === 'mine' ? 'tab is-active' : 'tab'} data-nav="mine" to="/mine">
        <Icon name="user" />
        <span>我的</span>
      </Link>
    </nav>
  )
}
