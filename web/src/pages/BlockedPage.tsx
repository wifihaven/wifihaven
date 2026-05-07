import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'

function reasonText(reason: string, until?: string | null): string {
  if (reason === 'paused') return 'Parental controls have paused internet access.'
  if (reason === 'schedule') return until ? `Blocked by schedule until ${until}.` : 'Blocked by schedule.'
  if (reason === 'time_limit') return 'Daily screen time limit reached.'
  if (reason.startsWith('site_time_limit')) return 'Daily time limit for this site reached.'
  if (reason.startsWith('category:')) return `Blocked category: ${reason.slice('category:'.length)}.`
  if (reason === 'extra_blocked') return 'This site is blocked.'
  return 'This site is blocked.'
}

type Step = 'idle' | 'login' | 'grant' | 'done'

export function BlockedPage() {
  const [params] = useSearchParams()
  const mac    = params.get('mac') ?? ''
  const host   = params.get('host') ?? ''
  const reason = params.get('reason') ?? ''
  const until  = params.get('until')

  const [step, setStep]         = useState<Step>('idle')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [extMins, setExtMins]   = useState(30)
  const [loginErr, setLoginErr] = useState<string | null>(null)
  const [granting, setGranting] = useState(false)

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault()
    setLoginErr(null)
    try {
      const res = await api.auth.login(username, password)
      localStorage.setItem('token', res.token)
      setStep('grant')
    } catch (err) {
      setLoginErr(err instanceof Error ? err.message : 'Login failed')
    }
  }

  async function handleGrant(e: React.FormEvent) {
    e.preventDefault()
    setGranting(true)
    try {
      await api.time.grantExtension({ deviceMac: mac, extraMinutes: extMins, note: null })
      setStep('done')
    } finally {
      setGranting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-sm space-y-6 text-center">
        <div className="space-y-2">
          <div className="text-4xl font-bold text-red-500">Blocked</div>
          <div className="text-lg font-mono text-white">{host}</div>
          <p className="text-gray-400 text-sm">{reasonText(reason, until)}</p>
        </div>

        {step === 'idle' && (
          <button
            className="w-full py-2 px-4 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-sm font-medium"
            onClick={() => setStep('login')}
          >
            Request extension
          </button>
        )}

        {step === 'done' && (
          <p className="text-green-400 font-medium">Extension granted</p>
        )}
      </div>

      {(step === 'login' || step === 'grant') && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 bg-black/70 flex items-center justify-center p-4"
        >
          <div className="bg-gray-900 rounded-xl p-6 w-full max-w-sm space-y-4">
            {step === 'login' && (
              <>
                <h2 className="text-white font-semibold text-lg">Parent login</h2>
                <form onSubmit={handleLogin} className="space-y-3">
                  <div className="space-y-1 text-left">
                    <label htmlFor="ext-username" className="text-sm text-gray-400">
                      Username
                    </label>
                    <input
                      id="ext-username"
                      type="text"
                      value={username}
                      onChange={e => setUsername(e.target.value)}
                      className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm"
                      autoComplete="username"
                    />
                  </div>
                  <div className="space-y-1 text-left">
                    <label htmlFor="ext-password" className="text-sm text-gray-400">
                      Password
                    </label>
                    <input
                      id="ext-password"
                      type="password"
                      value={password}
                      onChange={e => setPassword(e.target.value)}
                      className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm"
                      autoComplete="current-password"
                    />
                  </div>
                  {loginErr && (
                    <p className="text-red-400 text-sm">{loginErr}</p>
                  )}
                  <div className="flex gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => setStep('idle')}
                      className="flex-1 py-2 text-sm text-gray-400 hover:text-white border border-gray-700 rounded-lg"
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      className="flex-1 py-2 text-sm font-medium bg-blue-600 hover:bg-blue-500 text-white rounded-lg"
                    >
                      Log in
                    </button>
                  </div>
                </form>
              </>
            )}

            {step === 'grant' && (
              <>
                <h2 className="text-white font-semibold text-lg">Grant extra time</h2>
                <form onSubmit={handleGrant} className="space-y-3">
                  <div className="space-y-1 text-left">
                    <label htmlFor="ext-mins" className="text-sm text-gray-400">
                      Extra minutes
                    </label>
                    <input
                      id="ext-mins"
                      type="number"
                      min={5}
                      max={240}
                      value={extMins}
                      onChange={e => setExtMins(Number(e.target.value))}
                      className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm"
                    />
                  </div>
                  <div className="flex gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => setStep('idle')}
                      className="flex-1 py-2 text-sm text-gray-400 hover:text-white border border-gray-700 rounded-lg"
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      disabled={granting}
                      className="flex-1 py-2 text-sm font-medium bg-green-600 hover:bg-green-500 text-white rounded-lg disabled:opacity-50"
                    >
                      Grant
                    </button>
                  </div>
                </form>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
