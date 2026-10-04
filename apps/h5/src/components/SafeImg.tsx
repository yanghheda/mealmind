import type { ImgHTMLAttributes } from 'react'
import { DEFAULT_IMAGE } from '../lib/constants'

/** 图片加载失败兜底：回退到 cat-default.svg（与后端 ImageUrlResolver 的兜底一致）。 */
export function SafeImg({ src, alt = '', ...rest }: ImgHTMLAttributes<HTMLImageElement>) {
  return (
    <img
      {...rest}
      src={src || DEFAULT_IMAGE}
      alt={alt}
      onError={(event) => {
        const el = event.currentTarget
        el.onerror = null
        if (!el.src.endsWith(DEFAULT_IMAGE)) el.src = DEFAULT_IMAGE
      }}
    />
  )
}
