/**
 * 加购飞入动效：图片从来源卡片抛物线飞向购物车（Tab 或商家页底部购物车条）。
 * 移植自 prototype/app.js 的 flyToCart / flyFrom，供商家页与首页共用。
 */
export function flyToCart(fromEl: HTMLElement | null, imageUrl?: string): void {
  if (!fromEl) return
  const target =
    document.querySelector('.tabbar [data-nav="cart"]') ||
    document.querySelector('.cartbar .cb-ico')
  if (!target) return

  const start = fromEl.getBoundingClientRect()
  const end = target.getBoundingClientRect()
  const size = Math.max(start.width, 44)

  const fly = document.createElement('img')
  const sourceImg = fromEl.querySelector('img')
  const src = imageUrl || (sourceImg ? sourceImg.currentSrc || sourceImg.src : '')
  if (src) {
    fly.src = src
  } else {
    fly.style.background = 'linear-gradient(135deg,#FF7A00,#FF3D6E)'
  }
  fly.alt = ''
  fly.style.cssText =
    `position:fixed;left:${start.left}px;top:${start.top}px;width:${size}px;height:${size}px;` +
    'border-radius:50%;object-fit:cover;z-index:300;pointer-events:none;' +
    'box-shadow:0 6px 16px rgba(0,0,0,.22);' +
    'transition:transform .55s cubic-bezier(.3,-.2,.7,1),opacity .55s ease;'
  document.body.appendChild(fly)

  const dx = end.left + end.width / 2 - (start.left + size / 2)
  const dy = end.top + end.height / 2 - (start.top + size / 2)
  requestAnimationFrame(() => {
    fly.style.transform = `translate(${dx}px,${dy}px) scale(.35)`
    fly.style.opacity = '.55'
  })

  window.setTimeout(() => {
    fly.remove()
    const badge = document.querySelector<HTMLElement>('.tab-badge, .cb-badge')
    if (badge) {
      badge.classList.remove('pop')
      void badge.offsetWidth
      badge.classList.add('pop')
    }
  }, 560)
}
