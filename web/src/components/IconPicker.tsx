import { useMemo, useState } from 'react'
import type { IconType } from '@/types/api'
import { AppIcon } from './AppIcon'

export interface IconValue {
  icon: string
  iconType: IconType
}

interface Props {
  value: IconValue
  onChange: (v: IconValue) => void
  /** Hosts known on the form — used to populate favicon suggestions. */
  hosts?: string[]
}

type Mode = 'emoji' | 'url' | 'favicon'

function initialMode(v: IconValue): Mode {
  if (v.iconType === 'url' || v.iconType === 'png_base64') return 'url'
  return 'emoji'
}

function apexOf(host: string): string {
  const h = host.trim().toLowerCase().replace(/^\*\./, '').replace(/^https?:\/\//, '')
  return h.split('/')[0]
}

function faviconUrl(host: string): string {
  return `https://icons.duckduckgo.com/ip3/${apexOf(host)}.ico`
}

export function IconPicker({ value, onChange, hosts = [] }: Props) {
  const [mode, setMode] = useState<Mode>(initialMode(value))

  const apexes = useMemo(() => {
    const seen = new Set<string>()
    const out: string[] = []
    for (const h of hosts) {
      const a = apexOf(h)
      if (a && !seen.has(a)) {
        seen.add(a)
        out.push(a)
      }
    }
    return out
  }, [hosts])

  function setEmoji(s: string) {
    onChange({ icon: s, iconType: 'emoji' })
  }
  function setUrl(s: string) {
    onChange({ icon: s, iconType: 'url' })
  }

  return (
    <div className="space-y-3">
      <div role="tablist" className="inline-flex rounded-xl bg-gray-950 border border-gray-800 p-1">
        {(['emoji', 'url', 'favicon'] as const).map(m => (
          <button
            key={m}
            type="button"
            role="tab"
            aria-selected={mode === m}
            data-testid={`icon-picker-tab-${m}`}
            onClick={() => setMode(m)}
            className={`px-3 py-1 text-xs font-medium rounded-lg transition-colors ${
              mode === m ? 'bg-emerald-500 text-black' : 'text-gray-400 hover:text-gray-200'
            }`}
          >
            {m === 'emoji' ? 'Emoji' : m === 'url' ? 'URL' : 'Favicon'}
          </button>
        ))}
      </div>

      <div className="flex items-center gap-3">
        <div className="w-12 h-12 flex items-center justify-center bg-gray-950 border border-gray-800 rounded-xl shrink-0">
          <AppIcon
            icon={value.icon || null}
            iconType={value.iconType}
            size="lg"
          />
        </div>

        <div className="flex-1 min-w-0">
          {mode === 'emoji' && (
            <input
              type="text"
              value={value.iconType === 'emoji' ? value.icon : ''}
              onChange={e => setEmoji(e.target.value)}
              placeholder="📺"
              maxLength={4}
              data-testid="icon-picker-emoji-input"
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-2 text-white focus:outline-none focus:border-emerald-500"
            />
          )}
          {mode === 'url' && (
            <input
              type="url"
              value={value.iconType === 'url' ? value.icon : ''}
              onChange={e => setUrl(e.target.value)}
              placeholder="https://example.com/icon.png"
              data-testid="icon-picker-url-input"
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-2 text-white text-sm focus:outline-none focus:border-emerald-500"
            />
          )}
          {mode === 'favicon' && (
            <div className="text-xs text-gray-500">
              {apexes.length === 0
                ? 'Add a host first, then pick its favicon.'
                : 'Pick a host below to use its favicon.'}
            </div>
          )}
        </div>
      </div>

      {mode === 'favicon' && apexes.length > 0 && (
        <div className="flex flex-wrap gap-2" data-testid="icon-picker-favicon-list">
          {apexes.map(a => {
            const url = faviconUrl(a)
            const selected = value.iconType === 'url' && value.icon === url
            return (
              <button
                key={a}
                type="button"
                onClick={() => setUrl(url)}
                data-testid={`icon-picker-favicon-${a}`}
                className={`inline-flex items-center gap-2 px-2.5 py-1.5 rounded-lg border text-xs font-mono transition-colors ${
                  selected
                    ? 'bg-emerald-500/15 border-emerald-500 text-emerald-200'
                    : 'bg-gray-950 border-gray-700 text-gray-300 hover:border-gray-500'
                }`}
              >
                <img
                  src={url}
                  alt=""
                  width={16}
                  height={16}
                  className="inline-block object-contain"
                />
                {a}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
