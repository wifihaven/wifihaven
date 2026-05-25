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

// #1004 — only HTTPS image URLs are renderable. Anything else (http:, data:,
// javascript:, weird schemes, junk) falls back to the placeholder so a
// malicious icon string can never reach the DOM as an attacker-controlled
// `src`. Base64 PNGs are wrapped here so callers never construct the data:
// URI from raw input.
function safeImageSrc(icon: string, iconType: IconType): string | null {
  if (iconType === 'url') {
    try {
      const u = new URL(icon)
      return u.protocol === 'https:' ? u.toString() : null
    } catch {
      return null
    }
  }
  if (iconType === 'png_base64') {
    // Strict base64 alphabet only — no scheme prefix accepted from the wire.
    if (!/^[A-Za-z0-9+/]+=*$/.test(icon)) return null
    return `data:image/png;base64,${icon}`
  }
  return null
}

export function AppIcon({ icon, iconType = 'emoji', size = 'md', className }: Props) {
  const px = SIZE_PX[size]
  const src = icon && iconType !== 'emoji' ? safeImageSrc(icon, iconType) : null
  if (src) {
    return (
      <img
        src={src}
        alt=""
        width={px}
        height={px}
        className={`inline-block object-contain ${className ?? ''}`}
        style={{ width: px, height: px }}
        loading="lazy"
      />
    )
  }
  return (
    <span aria-hidden className={`${SIZE_TEXT[size]} ${className ?? ''}`}>
      {iconType === 'emoji' ? (icon || '◳') : '◳'}
    </span>
  )
}
