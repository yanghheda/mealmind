import { Link } from 'react-router-dom'
import { SafeImg } from './SafeImg'
import { deliveryFeeText, distanceText, formatMoney, formatRating, minutesText, salesText } from '../lib/format'
import type { MerchantView } from '../api/types'

/** 首页推荐商家卡：图 + 名称/评分/月售/时长/距离/配送费 + 排名 + 标签 + 菜品预览条。 */
export function MerchantCard({ merchant }: { merchant: MerchantView }) {
  const closed = !merchant.isOpen
  const previews = merchant.previewDishes.slice(0, 3)

  return (
    <Link
      className={closed ? 'mcard mcard--dishes mcard--closed' : 'mcard mcard--dishes'}
      to={`/merchant/${merchant.id}`}
    >
      <span className="mcard-imgwrap">
        <SafeImg className="mcard-img" src={merchant.logoUrl} alt={merchant.name} />
        {closed && <i className="mcard-badge">休息中</i>}
      </span>
      <span className="mcard-main">
        <span className="mcard-name">{merchant.name}</span>
        <span className="mcard-meta">
          <b className="rate">{formatRating(merchant.rating)}</b>
          <span className="dot">·</span>
          <span>{salesText(merchant.monthlySales)}</span>
          <span className="dot">·</span>
          <span>{minutesText(merchant.deliveryMinutes)}</span>
          <span className="dot">·</span>
          <span>{distanceText(merchant.distanceMeters)}</span>
          <span className="dot">·</span>
          <span>{deliveryFeeText(merchant.deliveryFee)}</span>
        </span>
        {merchant.rankLabel && <span className="mcard-rank">{merchant.rankLabel}</span>}
        <span className="mcard-tags">
          {merchant.promoTags.slice(0, 2).map((tag) => (
            <i className="tag tag-red" key={tag}>
              {tag}
            </i>
          ))}
          {merchant.featureTags.slice(0, 2).map((tag) => (
            <i className="tag tag-gray" key={tag}>
              {tag}
            </i>
          ))}
        </span>
        {previews.length > 0 && (
          <span className="disc-strip">
            {previews.map((dish) => (
              <span className="disc" key={dish.id}>
                <SafeImg src={dish.imageUrl} alt={dish.name} />
                <span className="dn">{dish.name}</span>
                <span className="dp">¥{formatMoney(dish.price)}</span>
              </span>
            ))}
          </span>
        )}
      </span>
    </Link>
  )
}
