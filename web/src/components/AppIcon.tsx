import type { IconType } from '@/types/api'

interface Props {
  icon: string | null | undefined
  iconType?: IconType
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

const SIZE_PX: Record<NonNullable<Props['size']>, number> = {
  sm: 20,
  md: 24,
  lg: 28,
}

const SIZE_TEXT: Record<NonNullable<Props['size']>, string> = {
  sm: 'text-base',
  md: 'text-xl',
  lg: 'text-2xl',
}

export function AppIcon({ icon, iconType = 'emoji', size = 'md', className }: Props) {
  const px = SIZE_PX[size]
  if (icon && iconType === 'url') {
    return (
      <img
        src={icon}
        alt=""
        width={px}
        height={px}
        className={`inline-block object-contain ${className ?? ''}`}
        style={{ width: px, height: px }}
        loading="lazy"
      />
    )
  }
  if (icon && iconType === 'png_base64') {
    const src = icon.startsWith('data:') ? icon : `data:image/png;base64,${icon}`
    return (
      <img
        src={src}
        alt=""
        width={px}
        height={px}
        className={`inline-block object-contain ${className ?? ''}`}
        style={{ width: px, height: px }}
      />
    )
  }
  return (
    <span aria-hidden className={`${SIZE_TEXT[size]} ${className ?? ''}`}>
      {icon || '◳'}
    </span>
  )
}
