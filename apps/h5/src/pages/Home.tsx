import { useRef } from 'react'
import type { CSSProperties } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { StatusBar } from '../components/StatusBar'
import { TabBar } from '../components/TabBar'
import { Icon } from '../components/Icon'
import { MerchantCard } from '../components/MerchantCard'
import { fetchHomeMerchants } from '../api/merchants'
import { HOME_CATEGORIES, LOCATION_NAME, PLACEHOLDER_TOAST, SEARCH_PLACEHOLDER } from '../lib/constants'
import { showToast } from '../store/ui'
import './Home.css'

/** 首页（Shell 变体 A）：定位 / 搜索 / 分类宫格 / 推广条 / 推荐商家流。 */
export function Home() {
  const listRef = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const query = useInfiniteQuery({
    queryKey: ['home', 'merchants'],
    queryFn: ({ pageParam }) => fetchHomeMerchants(pageParam, 10),
    initialPageParam: 1,
    getNextPageParam: (lastPage, pages) => (lastPage.hasMore ? pages.length + 1 : undefined),
  })
  const merchants = query.data?.pages.flatMap((page) => page.list) ?? []

  const openFoods = () => listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })

  return (
    <div className="app">
      <StatusBar warm />

      <main className="screen">
        <div className="home-top">
          <div className="hero stagger">
            <div className="hero-loc">
              <Icon name="map-pin" size={15} />
              <span>{LOCATION_NAME}</span>
              <Icon name="chevron-down" size={13} className="chev" />
            </div>
            <div className="hero-search searchbar">
              <span className="scan">
                <Icon name="scan" size={18} />
              </span>
              <input type="text" placeholder={SEARCH_PLACEHOLDER} aria-label="搜索商家、菜品" />
              <button className="btn-search" type="button" onClick={() => navigate('/search')}>
                搜索
              </button>
            </div>
          </div>

          <div className="cat-grid stagger">
            {HOME_CATEGORIES.map((category) => {
              const tile = (
                <>
                  <span className="cat-tile" style={tint(category.tint, category.ink)}>
                    <Icon name={category.icon} size={23} />
                  </span>
                  <span>{category.name}</span>
                </>
              )
              return category.real ? (
                <button className="cat" type="button" key={category.id} onClick={openFoods}>
                  {tile}
                </button>
              ) : (
                <button
                  className="cat"
                  type="button"
                  key={category.id}
                  onClick={() => showToast(PLACEHOLDER_TOAST)}
                >
                  {tile}
                </button>
              )
            })}
          </div>
        </div>

        <div className="promo-strip">
          <span className="promo-cell">
            <Icon name="percent" size={13} />
            满 78 减 25
          </span>
          <span className="promo-cell">
            <Icon name="utensils" size={13} />
            堂食店
          </span>
          <span className="promo-cell">
            <Icon name="zap" size={13} />
            15 分钟
          </span>
          <span className="promo-cell">
            <Icon name="bike" size={13} />
            免配送费
          </span>
        </div>

        <div className="chips-row hscroll">
          <button className="chip on" type="button">
            全部
          </button>
          {['预订早餐', '龙虾烧烤', '超市便利'].map((label) => (
            <button
              className="chip"
              type="button"
              key={label}
              onClick={() => showToast(PLACEHOLDER_TOAST)}
            >
              {label}
            </button>
          ))}
          <button className="chip" type="button" onClick={() => showToast(PLACEHOLDER_TOAST)}>
            <Icon name="sliders-horizontal" size={13} />
            筛选
          </button>
        </div>

        <div className="sec-head">
          <h2 className="sec-title">为你推荐</h2>
          <button className="sec-more" type="button" onClick={() => showToast(PLACEHOLDER_TOAST)}>
            更多外卖
            <Icon name="chevron-right" size={14} />
          </button>
        </div>

        <div ref={listRef}>
          {query.isPending &&
            [0, 1, 2].map((index) => (
              <div className="sk-card" key={index}>
                <div className="sk sk-img" />
                <div className="sk-lines">
                  <div className="sk sk-line w60" />
                  <div className="sk sk-line w40" />
                  <div className="sk sk-line w80" />
                </div>
              </div>
            ))}

          {query.isError && (
            <div className="empty">
              <p>商家列表加载失败</p>
              <button className="btn btn-outline btn-sm" type="button" onClick={() => query.refetch()}>
                重试
              </button>
            </div>
          )}

          {merchants.map((merchant) => (
            <MerchantCard merchant={merchant} key={merchant.id} />
          ))}

          {query.hasNextPage && (
            <div className="load-more">
              <button
                className="btn btn-outline btn-sm"
                type="button"
                disabled={query.isFetchingNextPage}
                onClick={() => query.fetchNextPage()}
              >
                {query.isFetchingNextPage ? '加载中…' : '加载更多'}
              </button>
            </div>
          )}
        </div>

        <p className="app-footer-note">模拟数据 · 仅用于产品演示</p>
      </main>

      <TabBar active="home" />
    </div>
  )
}

function tint(background: string, color: string): CSSProperties {
  return { '--tint': background, '--ink': color } as CSSProperties
}
