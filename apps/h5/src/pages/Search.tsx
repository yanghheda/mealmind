import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StatusBar } from '../components/StatusBar'
import { Icon } from '../components/Icon'
import { SafeImg } from '../components/SafeImg'
import { MerchantCard } from '../components/MerchantCard'
import { fetchSearch } from '../api/search'
import { formatMoney, minutesText } from '../lib/format'
import { SEARCH_PLACEHOLDER } from '../lib/constants'
import type { SearchDishView } from '../api/types'
import './Search.css'

/** 输入防抖窗口（W4）：停止输入 300ms 后才发请求。 */
const DEBOUNCE_MS = 300

/**
 * 搜索页（Shell 变体 C）：顶栏返回 + 输入自动聚焦 + 300ms 防抖；
 * 结果按「商家 / 菜品」分组（商家复用 MerchantCard，菜品为搜索行样式）；
 * 四态：未输入 / 加载（.sk-* 骨架）/ 空态 / 错误。
 */
export function Search() {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const [keyword, setKeyword] = useState('')
  const [debounced, setDebounced] = useState('')
  const trimmed = keyword.trim()

  useEffect(() => {
    if (!trimmed) return
    const timer = window.setTimeout(() => setDebounced(trimmed), DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [trimmed])

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  // 清空输入即视为无关键词（deferred 值在渲染期派生，避免 effect 里同步 setState）
  const queryKeyword = trimmed === '' ? '' : debounced
  const query = useQuery({
    queryKey: ['search', queryKeyword],
    queryFn: () => fetchSearch(queryKeyword),
    enabled: queryKeyword !== '',
  })

  const back = () => {
    if (window.history.length > 1) navigate(-1)
    else navigate('/home')
  }

  /** 点「搜索」/ 回车立即执行，不必等防抖计时。 */
  const submitNow = () => {
    if (trimmed) setDebounced(trimmed)
  }

  const data = query.data
  // 防抖等待期或新关键词首载时展示骨架，避免闪现上一关键词的结果
  const loading = trimmed !== '' && (queryKeyword === '' || query.isPending)
  const noResult = Boolean(data) && data!.merchants.length === 0 && data!.dishes.length === 0

  return (
    <div className="app app--search">
      <StatusBar />

      <main className="screen screen--flush">
        <div className="page-head search-head">
          <button className="back" type="button" aria-label="返回" onClick={back}>
            <Icon name="arrow-left" size={21} />
          </button>
          <div className="searchbar search-bar">
            <span className="scan">
              <Icon name="search" size={18} />
            </span>
            <input
              ref={inputRef}
              type="text"
              value={keyword}
              placeholder={SEARCH_PLACEHOLDER}
              aria-label="搜索商家、菜品"
              onChange={(event) => setKeyword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') submitNow()
              }}
            />
            {keyword !== '' && (
              <button
                className="search-clear"
                type="button"
                aria-label="清空"
                onClick={() => {
                  setKeyword('')
                  inputRef.current?.focus()
                }}
              >
                <Icon name="x" size={15} />
              </button>
            )}
            <button className="btn-search" type="button" onClick={submitNow}>
              搜索
            </button>
          </div>
        </div>

        {trimmed === '' && (
          <div className="empty">
            <Icon name="search" size={40} />
            <p>搜商家、搜菜品，一句话直达</p>
            <p className="search-hint">试试「香辣」「奶茶」「火锅」</p>
          </div>
        )}

        {loading && (
          <>
            {[0, 1].map((index) => (
              <div className="sk-card" key={index}>
                <div className="sk sk-img" />
                <div className="sk-lines">
                  <div className="sk sk-line w60" />
                  <div className="sk sk-line w40" />
                  <div className="sk sk-line w80" />
                </div>
              </div>
            ))}
            <div className="card search-sk-rows">
              {[0, 1, 2].map((index) => (
                <div className="sk sk-line" key={index} />
              ))}
            </div>
          </>
        )}

        {trimmed !== '' && !loading && query.isError && (
          <div className="empty">
            <p>搜索失败，请稍后重试</p>
            <button className="btn btn-outline btn-sm" type="button" onClick={() => query.refetch()}>
              重试
            </button>
          </div>
        )}

        {data && !loading && (
          <>
            {noResult ? (
              <div className="empty">
                <Icon name="search" size={40} />
                <p>没有找到「{data.keyword}」相关的商家或菜品</p>
                <p className="search-hint">换个关键词试试</p>
              </div>
            ) : (
              <>
                {data.merchants.length > 0 && (
                  <section>
                    <div className="sec-head">
                      <h2 className="sec-title">商家</h2>
                      <span className="sec-count">共 {data.merchantTotal} 家</span>
                    </div>
                    {data.merchants.map((merchant) => (
                      <MerchantCard merchant={merchant} key={merchant.id} />
                    ))}
                  </section>
                )}
                {data.dishes.length > 0 && (
                  <section>
                    <div className="sec-head">
                      <h2 className="sec-title">菜品</h2>
                      <span className="sec-count">共 {data.dishTotal} 道</span>
                    </div>
                    {data.dishes.map((dish) => (
                      <DishRow dish={dish} key={dish.dishId} />
                    ))}
                  </section>
                )}
              </>
            )}
          </>
        )}

        <p className="app-footer-note">模拟数据 · 仅用于产品演示</p>
      </main>
    </div>
  )
}

/** 菜品搜索结果行：图 + 菜名 + 商家/时长 + 标签 + 价格，点击进入商家详情。 */
function DishRow({ dish }: { dish: SearchDishView }) {
  return (
    <Link className="sdish" to={`/merchant/${dish.merchantId}`}>
      <SafeImg className="sdish-img" src={dish.imageUrl} alt={dish.name} />
      <span className="sdish-main">
        <span className="sdish-name">{dish.name}</span>
        <span className="sdish-meta">
          {dish.merchantName} · {minutesText(dish.deliveryMinutes)}
        </span>
        {dish.tags.length > 0 && (
          <span className="sdish-tags">
            {dish.tags.map((tag) => (
              <i className="tag tag-red" key={tag}>
                {tag}
              </i>
            ))}
          </span>
        )}
      </span>
      <span className="sdish-price price">¥{formatMoney(dish.price)}</span>
    </Link>
  )
}