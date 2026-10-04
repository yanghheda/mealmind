import { ICONS } from './icons'

interface IconProps {
  name: string
  size?: number
  className?: string
}

/** 内联 SVG 图标；未知名称渲染为空，避免抛错。 */
export function Icon({ name, size = 22, className }: IconProps) {
  const definition = ICONS[name]
  if (!definition) return null
  const filled = typeof definition === 'object'
  const inner = typeof definition === 'string' ? definition : definition.svg

  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? 'currentColor' : 'none'}
      stroke={filled ? 'none' : 'currentColor'}
      strokeWidth={filled ? undefined : 1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: inner }}
    />
  )
}
