/** 金额格式化：最多两位小数，去掉无意义的尾随 0（18 → "18"，9.90 → "9.9"）。 */
export function formatMoney(value: number | string | null | undefined): string {
  const n = Number(value)
  if (!Number.isFinite(n)) return '0'
  return n.toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
}

/** 评分：后端 0–5，保留一位小数。 */
export function formatRating(value: number | null | undefined): string {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(1) : '—'
}

/** 月售文案：1000 起以整千加「+」呈现（对齐原型「月售 1000+」）。 */
export function salesText(value: number | null | undefined): string {
  const n = value ?? 0
  if (n >= 10000) return `月售 ${Math.floor(n / 10000)}万+`
  if (n >= 1000) return `月售 ${Math.floor(n / 1000) * 1000}+`
  return `月售 ${n}`
}

/** 距离：不足 1km 用米，否则保留一位小数。 */
export function distanceText(meters: number | null | undefined): string {
  const n = meters ?? 0
  if (n < 1000) return `${n}m`
  return `${(n / 1000).toFixed(1)}km`
}

/** 配送费：0 视为免配送费。 */
export function deliveryFeeText(fee: number | null | undefined): string {
  const n = Number(fee)
  if (!Number.isFinite(n) || n <= 0) return '免配送费'
  return `配送约 ¥${formatMoney(n)}`
}

/** 时长：分钟。 */
export function minutesText(minutes: number | null | undefined): string {
  return `${minutes ?? 0} 分钟`
}
